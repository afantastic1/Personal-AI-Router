/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nv.pair.data.PairNode
import com.nv.pair.mnn.MnnModelCatalog
import com.nv.pair.models.ModelCapability.CHAT
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelHubScreen(nodes: List<PairNode>) {
    val context = LocalContext.current
    val modelRoot = remember { File(context.filesDir, "mnn/models") }
    val scope = rememberCoroutineScope()
    val catalog = remember { ModelCatalogRepository(listOf(ModelScopeAdapter(), HuggingFaceAdapter())) }
    val installed = remember { mutableStateListOf<ModelDescriptor>() }
    val searchResults = remember { mutableStateListOf<ModelDescriptor>() }
    var source by remember { mutableStateOf(ModelSourceKind.MODELSCOPE) }
    var query by remember { mutableStateOf("") }
    var importModelId by remember { mutableStateOf("") }
    var busyModel by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var failureMessage by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ModelDescriptor?>(null) }
    var pendingImportId by remember { mutableStateOf("") }

    fun reloadInstalled() {
        installed.clear()
        installed.addAll(installedModels(modelRoot))
    }

    LaunchedEffect(modelRoot) { reloadInstalled() }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val modelId = pendingImportId
        if (modelId.isNotBlank() && uris.isNotEmpty()) {
            busyModel = modelId
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val files = uris.map { uri ->
                            val name = context.displayName(uri)
                            LocalModelFile(name) {
                                context.contentResolver.openInputStream(uri)
                                    ?: throw IllegalStateException("The selected model file could not be opened.")
                            }
                        }
                        LocalImportAdapter(modelRoot).importMnnModel(modelId, files)
                    }
                }.onSuccess { descriptor ->
                    catalog.addLocal(descriptor)
                    statusMessage = "Imported ${descriptor.displayName}."
                    failureMessage = ""
                    reloadInstalled()
                }.onFailure { failure ->
                    failureMessage = failure.message ?: "Model import failed."
                    statusMessage = ""
                }
                busyModel = ""
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Model Hub", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Search model catalogs, install verified MNN models to this device, or import files from local storage.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Catalog", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceChoice("ModelScope", source == ModelSourceKind.MODELSCOPE) { source = ModelSourceKind.MODELSCOPE }
                    SourceChoice("Hugging Face", source == ModelSourceKind.HUGGING_FACE) { source = ModelSourceKind.HUGGING_FACE }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Model name or family") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = busyModel.isEmpty() && query.isNotBlank(),
                    onClick = {
                        busyModel = "search"
                        failureMessage = ""
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { catalog.search(query.trim(), source) }
                            }.onSuccess { results ->
                                searchResults.clear()
                                searchResults.addAll(results)
                                statusMessage = if (results.isEmpty()) "No matching models were found." else "Found ${results.size} catalog entries."
                            }.onFailure { failure ->
                                failureMessage = failure.message ?: "Model search failed."
                            }
                            busyModel = ""
                        }
                    },
                ) {
                    Text(if (busyModel == "search") "Searching…" else "Search models")
                }
                searchResults.forEach { descriptor ->
                    ModelCatalogCard(
                        descriptor = descriptor,
                        busy = busyModel == descriptor.logicalId,
                        onInstall = {
                            busyModel = descriptor.logicalId
                            failureMessage = ""
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val adapter = when (descriptor.source.kind) {
                                            ModelSourceKind.MODELSCOPE -> ModelScopeAdapter()
                                            ModelSourceKind.HUGGING_FACE -> HuggingFaceAdapter()
                                            else -> throw IllegalArgumentException("This source cannot download catalog models.")
                                        }
                                        ModelHubInstaller(modelRoot).installMnnModel(descriptor, adapter)
                                    }
                                }.onSuccess { installedModel ->
                                    statusMessage = "Installed ${installedModel.displayName}."
                                    reloadInstalled()
                                }.onFailure { failure ->
                                    failureMessage = failure.message ?: "Model installation failed."
                                }
                                busyModel = ""
                            }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = importModelId,
                        onValueChange = { importModelId = it },
                        label = { Text("Local model ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = busyModel.isEmpty() && importModelId.isNotBlank(),
                        onClick = {
                            pendingImportId = importModelId.trim()
                            importPicker.launch(arrayOf("*/*"))
                        },
                    ) {
                        Text("Import MNN files")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Installed on this device", style = MaterialTheme.typography.titleLarge)
                if (installed.isEmpty()) {
                    Text("No MNN models are installed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    installed.forEach { model ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(model.engineModelId, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatBytes(model.estimatedMemoryBytes), style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { pendingDelete = model }) { Text("Delete") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PAIR runtime inventory", style = MaterialTheme.typography.titleLarge)
                Text("Only these advertised models are eligible for Auto selection.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val inventory = remember(nodes) { ModelInventoryRepository().fromNetwork(nodes) }
                if (inventory.isEmpty()) {
                    Text("No models are currently available in the PAIR network.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    inventory.forEach { item ->
                        Text("${item.model.engineModelId} · ${item.engine} · ${item.nodeId}${if (item.loaded) " · loaded" else ""}")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Automatic model policies", style = MaterialTheme.typography.titleLarge)
                Text("auto → auto-balanced")
                Text("auto-fast · prefers loaded and smaller models")
                Text("auto-balanced · weighs quality and loaded state")
                Text("auto-best · prefers model capability and size")
                Text("The existing PAIR scheduler still chooses the node.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (statusMessage.isNotBlank()) Text(statusMessage, color = MaterialTheme.colorScheme.primary)
        if (failureMessage.isNotBlank()) Text(failureMessage, color = MaterialTheme.colorScheme.error)
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${model.displayName}?") },
            text = { Text("This removes the installed model files from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { ModelHubInstaller(modelRoot).deleteInstalledModel(model.engineModelId) } }
                            .onSuccess {
                                statusMessage = "Deleted ${model.displayName}."
                                reloadInstalled()
                            }
                            .onFailure { failure -> failureMessage = failure.message ?: "Model deletion failed." }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun ModelCatalogCard(descriptor: ModelDescriptor, busy: Boolean, onInstall: () -> Unit) {
    val requiredArtifacts = setOf("config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt")
    val filesByPath = descriptor.files.associateBy(ModelFile::path)
    val hasChecksums = requiredArtifacts.all { filesByPath[it]?.sha256 != null }
    val supported = descriptor.format == ModelFormat.MNN && hasChecksums
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(descriptor.displayName, style = MaterialTheme.typography.titleMedium)
            Text(descriptor.source.repository, style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(descriptor.family, descriptor.parameterCount?.let { "${it / 1_000_000}M" }, descriptor.quantization, descriptor.format.name)
                    .joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (supported) "SHA-256 verified MNN files available" else "MNN files with SHA-256 checksums required",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(enabled = supported && !busy, onClick = onInstall) {
                    Text(if (busy) "Installing…" else "Install")
                }
            }
        }
    }
}

private fun installedModels(modelRoot: File): List<ModelDescriptor> = MnnModelCatalog(modelRoot).listModels().map { model ->
    ModelDescriptor(
        logicalId = "local:${model.modelId}",
        engineModelId = model.modelId,
        displayName = model.displayName ?: model.modelId,
        family = inferFamilyName(model.modelId),
        parameterCount = inferParameters(model.modelId),
        quantization = null,
        contextLength = null,
        capabilities = setOf(CHAT),
        source = ModelSource(ModelSourceKind.LOCAL, model.configPath),
        format = ModelFormat.MNN,
        estimatedMemoryBytes = File(model.configPath).parentFile?.walkTopDown()?.filter(File::isFile)?.sumOf(File::length),
        compatibility = ModelCompatibility.COMPATIBLE,
    )
}

private fun inferFamilyName(modelId: String): String = listOf("qwen", "llama", "gemma", "mistral", "deepseek", "phi")
    .firstOrNull { modelId.contains(it, ignoreCase = true) } ?: "unknown"

private fun inferParameters(modelId: String): Long? = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*[- ]?B(?:\\b|$)")
    .find(modelId)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1_000_000_000.0).toLong() }

private fun formatBytes(bytes: Long?): String = bytes?.let { "Estimated memory: ${it / (1024 * 1024)} MiB" } ?: "Memory estimate unavailable"

private fun Context.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "model-file"
}
