/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nv.pair.mnn.MnnModelCatalog
import com.nv.pair.runtime.PairRuntimeController
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

sealed interface CatalogPhase {
    data object Idle : CatalogPhase
    data object Searching : CatalogPhase
    data class Failed(val failure: CatalogFailure) : CatalogPhase
}

data class CatalogFailure(
    val provider: ProviderId?,
    val operation: String?,
    val category: ProviderErrorCategory?,
    val httpStatus: Int?,
    val retryable: Boolean,
    val message: String,
)

enum class DownloadPhase { IDLE, INSPECTING, INSTALLING, DELETING, FAILED, CANCELLED }

data class ModelHubUiState(
    val catalogPhase: CatalogPhase = CatalogPhase.Idle,
    val downloadPhase: DownloadPhase = DownloadPhase.IDLE,
    val activeModelId: String? = null,
    val searchResults: List<ModelDescriptor> = emptyList(),
    val catalogPage: Int = 1,
    val hasMoreCatalogPages: Boolean = false,
    val installedModels: List<ModelDescriptor> = emptyList(),
    val downloadProgress: ModelDownloadProgress? = null,
    val statusMessage: String = "",
    val failureMessage: String = "",
) {
    val isBusy: Boolean get() = catalogPhase == CatalogPhase.Searching || when (downloadPhase) {
        DownloadPhase.INSPECTING, DownloadPhase.INSTALLING, DownloadPhase.DELETING -> true
        DownloadPhase.IDLE, DownloadPhase.FAILED, DownloadPhase.CANCELLED -> false
    }
}

class ModelHubViewModel @JvmOverloads constructor(
    application: Application,
    private val catalog: ModelCatalogRepository = ModelCatalogRepository(
        listOf(ModelScopeAdapter(), HuggingFaceAdapter()),
    ),
) : AndroidViewModel(application) {
    private val applicationContext = application.applicationContext
    private val modelRoot = File(application.filesDir, "mnn/models")
    private val installer = ModelHubInstaller(modelRoot)
    private val runtimeController = PairRuntimeController(application)
    private val mutableState = MutableStateFlow(ModelHubUiState())
    private var searchGeneration = 0L
    private var searchJob: Job? = null
    private var activeOperation: Job? = null
    private var activeSearchQuery = ""
    private var activeSearchSource = ModelSourceKind.MODELSCOPE
    private var activeSearchPage = 1

    val state: StateFlow<ModelHubUiState> = mutableState.asStateFlow()

    init {
        refreshInstalledModels()
    }

    fun search(query: String, source: ModelSourceKind) {
        activeSearchQuery = query
        activeSearchSource = source
        loadCatalogPage(page = 1)
    }

    fun nextCatalogPage() {
        val current = mutableState.value
        if (current.hasMoreCatalogPages && current.catalogPhase != CatalogPhase.Searching) {
            loadCatalogPage(current.catalogPage + 1)
        }
    }

    fun previousCatalogPage() {
        val current = mutableState.value
        if (current.catalogPage > 1 && current.catalogPhase != CatalogPhase.Searching) {
            loadCatalogPage(current.catalogPage - 1)
        }
    }

    fun retryCatalogSearch() {
        val failure = when (val phase = mutableState.value.catalogPhase) {
            is CatalogPhase.Failed -> phase.failure
            CatalogPhase.Idle, CatalogPhase.Searching -> return
        }
        if (failure.retryable) loadCatalogPage(activeSearchPage)
    }

    private fun loadCatalogPage(page: Int) {
        if (mutableState.value.downloadPhase in setOf(
                DownloadPhase.INSPECTING,
                DownloadPhase.INSTALLING,
                DownloadPhase.DELETING,
            )
        ) return
        searchGeneration++
        activeSearchPage = page
        val generation = searchGeneration
        val query = activeSearchQuery
        val source = activeSearchSource
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                catalogPhase = CatalogPhase.Searching,
                searchResults = if (page == 1) emptyList() else mutableState.value.searchResults,
                catalogPage = if (page == 1) 1 else mutableState.value.catalogPage,
                hasMoreCatalogPages = if (page == 1) false else mutableState.value.hasMoreCatalogPages,
                failureMessage = "",
                statusMessage = "",
            )
            try {
                val result = withContext(Dispatchers.IO) {
                    catalog.searchPage(query, source, page)
                }
                if (generation != searchGeneration) return@launch
                val results = result.descriptors
                mutableState.value = mutableState.value.copy(
                    catalogPhase = CatalogPhase.Idle,
                    searchResults = results,
                    catalogPage = result.providerPage.page,
                    hasMoreCatalogPages = result.providerPage.hasMore,
                    statusMessage = if (results.isEmpty()) "No matching models were found."
                        else "Found ${results.size} catalog entries on page ${result.providerPage.page}.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProviderError) {
                recordCatalogFailure(
                    generation,
                    CatalogFailure(
                        provider = failure.provider,
                        operation = failure.operation,
                        category = failure.category,
                        httpStatus = failure.httpStatus,
                        retryable = failure.retryable,
                        message = failure.message ?: "Model search failed.",
                    ),
                )
            } catch (failure: Exception) {
                recordCatalogFailure(
                    generation,
                    CatalogFailure(
                        provider = null,
                        operation = null,
                        category = null,
                        httpStatus = null,
                        retryable = false,
                        message = failure.message ?: "Model search failed.",
                    ),
                )
            }
        }
    }

    private fun recordCatalogFailure(generation: Long, failure: CatalogFailure) {
        if (generation != searchGeneration) return
        mutableState.value = mutableState.value.copy(
            catalogPhase = CatalogPhase.Failed(failure),
            failureMessage = failure.message,
        )
    }

    fun changeSource() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        mutableState.value = mutableState.value.copy(
            catalogPhase = CatalogPhase.Idle,
            searchResults = emptyList(),
            catalogPage = 1,
            hasMoreCatalogPages = false,
            failureMessage = "",
            statusMessage = "",
        )
    }

    fun inspect(descriptor: ModelDescriptor) {
        runModelOperation(descriptor.logicalId, DownloadPhase.INSPECTING, "Model inspection failed.") {
            val inspected = withContext(Dispatchers.IO) {
                catalog.adapterFor(descriptor.source.kind).inspect(descriptor)
            }
            mutableState.value = mutableState.value.copy(
                searchResults = mutableState.value.searchResults.map { current ->
                    if (current.logicalId == inspected.logicalId) inspected else current
                },
                statusMessage = "Inspected ${inspected.displayName}.",
            )
        }
    }

    fun install(descriptor: ModelDescriptor, allowUnverifiedSource: Boolean = false) {
        runModelOperation(descriptor.logicalId, DownloadPhase.INSTALLING, "Model installation failed.") {
            val installed = runInterruptible(Dispatchers.IO) {
                installer.installMnnModel(descriptor, catalog.adapterFor(descriptor.source.kind), allowUnverifiedSource) { progress ->
                    mutableState.update { it.copy(downloadProgress = progress) }
                }
            }
            refreshInstalledModels()
            mutableState.value = mutableState.value.copy(statusMessage = "Installed ${installed.displayName}.")
        }
    }

    fun cancelActiveOperation() {
        activeOperation?.cancel()
    }

    fun delete(model: ModelDescriptor) {
        runModelOperation(model.engineModelId, DownloadPhase.DELETING, "Model deletion failed.") {
            runtimeController.deleteInstalledModel(model.engineModelId)
            refreshInstalledModels()
            mutableState.value = mutableState.value.copy(statusMessage = "Deleted ${model.displayName}.")
        }
    }

    fun import(modelId: String, uris: List<Uri>) {
        runModelOperation(modelId, DownloadPhase.INSTALLING, "Model import failed.") {
            val files = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val displayName = applicationContext.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else {
                            null
                        }
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model-file"
                    LocalModelFile(displayName) {
                        applicationContext.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("The selected model file could not be opened.")
                    }
                }
            }
            val importer = LocalImportAdapter(modelRoot)
            val committedImport = AtomicReference<ModelDescriptor?>()
            val imported = try {
                withContext(Dispatchers.IO) {
                    importer.importMnnModel(modelId, files, committedImport::set)
                }
            } catch (cancelled: CancellationException) {
                committedImport.get() ?: throw cancelled
            }
            catalog.addLocal(imported)
            refreshInstalledModels()
            mutableState.value = mutableState.value.copy(statusMessage = "Imported ${imported.displayName}.")
        }
    }

    private fun runModelOperation(
        modelId: String,
        phase: DownloadPhase,
        fallbackMessage: String,
        action: suspend () -> Unit,
    ) {
        if (mutableState.value.isBusy) return
        val operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            mutableState.value = mutableState.value.copy(
                downloadPhase = phase,
                activeModelId = modelId,
                downloadProgress = null,
                failureMessage = "",
                statusMessage = "",
            )
            try {
                action()
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(
                    downloadPhase = DownloadPhase.CANCELLED,
                    statusMessage = "Model operation cancelled.",
                )
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(
                    downloadPhase = DownloadPhase.FAILED,
                    failureMessage = failure.message ?: fallbackMessage,
                )
            } finally {
                val terminalPhase = mutableState.value.downloadPhase.takeIf { phase ->
                    phase == DownloadPhase.FAILED || phase == DownloadPhase.CANCELLED
                }
                mutableState.value = mutableState.value.copy(
                    activeModelId = null,
                    downloadPhase = terminalPhase ?: DownloadPhase.IDLE,
                    downloadProgress = null,
                )
                activeOperation = null
            }
        }
        activeOperation = operation
        operation.start()
    }

    private fun refreshInstalledModels() {
        viewModelScope.launch {
            val models = withContext(Dispatchers.IO) { installedModels(modelRoot) }
            mutableState.value = mutableState.value.copy(installedModels = models)
        }
    }
}

private fun installedModels(modelRoot: File): List<ModelDescriptor> = MnnModelCatalog(modelRoot).listModels().map { model ->
    val modelId = model.modelId
    ModelDescriptor(
        logicalId = "local:$modelId",
        engineModelId = modelId,
        displayName = model.displayName ?: modelId,
        family = listOf("qwen", "llama", "gemma", "mistral", "deepseek", "phi")
            .firstOrNull { modelId.contains(it, ignoreCase = true) } ?: "unknown",
        parameterCount = PARAMETER_PATTERN.find(modelId)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { (it * 1_000_000_000.0).toLong() },
        quantization = null,
        contextLength = null,
        source = ModelSource(ModelSourceKind.LOCAL, model.configPath),
        format = ModelFormat.MNN,
        estimatedMemoryBytes = File(model.configPath).parentFile?.walkTopDown()?.filter(File::isFile)?.sumOf(File::length),
        compatibility = ModelCompatibility.COMPATIBLE,
    )
}

private val PARAMETER_PATTERN = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*[- ]?B(?:\\b|$)")
