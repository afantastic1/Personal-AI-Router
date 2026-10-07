/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MnnEngineHost(
    private val runtime: MnnRuntime,
    private val shutdownTimeoutMillis: Long = SHUTDOWN_TIMEOUT_MILLIS,
    private val logWarning: (String) -> Unit = { message -> android.util.Log.w(TAG, message) },
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pair-mnn-runtime").apply { isDaemon = true }
    }
    private val stateLock = ReentrantLock()
    private val isClosed = AtomicBoolean(false)
    private val generationAdmission = AtomicReference<Long?>(null)
    private var state = MnnEngineState.UNLOADED
    private var loadedModel: MnnLoadedModel? = null
    private var activeRequestId: Long? = null
    private var lastError: MnnError? = null
    private var metrics = MnnRuntimeMetrics()
    private var runtimeAvailable = true

    init {
        try {
            val runtimeStatus = runtime.getStatus()
            state = runtimeStatus.state
            loadedModel = runtime.getLoadedModel()
            lastError = runtimeStatus.error
            metrics = runtime.getMetrics()
            runtimeAvailable = runtimeStatus.error?.code != MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE
        } catch (_: Exception) {
            state = MnnEngineState.ERROR
            lastError = MnnError(MnnErrorCode.INTERNAL_ERROR, "MNN runtime initialization failed.")
            runtimeAvailable = false
        }
    }

    fun loadModel(model: MnnModelDescriptor, backend: MnnBackend): MnnResult<Unit> =
        dispatch {
            val previousModel = stateLock.withLock {
                state = MnnEngineState.LOADING
                lastError = null
                loadedModel
            }
            if (previousModel != null) {
                val unloadResult = runtime.unloadModel()
                if (unloadResult is MnnResult.Failure) {
                    return@dispatch failStateAfterRuntimeSync(unloadResult.error)
                }
                stateLock.withLock { loadedModel = null }
            }

            val loadResult = try {
                runtime.loadModel(model, backend)
            } catch (_: Exception) {
                MnnResult.failure(MnnErrorCode.MODEL_LOAD_FAILED, "MNN could not load the requested model.")
            }
            when (val result = loadResult) {
                is MnnResult.Success -> {
                    val runtimeModel = runCatching { runtime.getLoadedModel() }.getOrNull()
                    val runtimeMetrics = runCatching { runtime.getMetrics() }.getOrDefault(MnnRuntimeMetrics())
                    stateLock.withLock {
                        loadedModel = runtimeModel ?: MnnLoadedModel(model, backend)
                        state = MnnEngineState.READY
                        lastError = null
                        metrics = runtimeMetrics
                    }
                    MnnResult.success(Unit)
                }
                is MnnResult.Failure -> {
                    runCatching { runtime.unloadModel() }
                    stateLock.withLock { loadedModel = null }
                    failState(result.error)
                }
            }
        }

    fun generate(
        requestId: Long,
        request: MnnChatRequest,
        onToken: (String) -> Unit
    ): MnnResult<MnnGenerationResult> {
        val validation = request.validate()
        if (validation is MnnResult.Failure) {
            return validation
        }
        if (!generationAdmission.compareAndSet(null, requestId)) {
            return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is already active.")
        }
        return try {
            dispatch {
                val canGenerate = stateLock.withLock {
                    if (state != MnnEngineState.READY || loadedModel == null || activeRequestId != null) {
                        false
                    } else if (loadedModel?.model?.modelId != request.modelId) {
                        false
                    } else {
                        activeRequestId = requestId
                        state = MnnEngineState.GENERATING
                        lastError = null
                        true
                    }
                }
                if (!canGenerate) {
                    return@dispatch MnnResult.failure(
                        if (getStatus().modelId == null) MnnErrorCode.INVALID_STATE else MnnErrorCode.MODEL_NOT_LOADED,
                        "The requested MNN model must be loaded before generation."
                    )
                }

                val result = try {
                    runtime.generate(requestId, request, onToken)
                } catch (_: Exception) {
                    MnnResult.failure(MnnErrorCode.GENERATION_FAILED, "MNN generation failed.")
                }
                val generationMetrics = when (result) {
                    is MnnResult.Success -> result.value.metrics
                    is MnnResult.Failure -> runCatching { runtime.getMetrics() }
                        .getOrDefault(MnnRuntimeMetrics())
                }
                stateLock.withLock {
                    activeRequestId = null
                    when (result) {
                        is MnnResult.Success -> {
                            metrics = generationMetrics
                            state = MnnEngineState.READY
                            lastError = null
                        }
                        is MnnResult.Failure -> {
                            metrics = generationMetrics
                            if (result.error.code == MnnErrorCode.CANCELLED) {
                                state = MnnEngineState.READY
                                lastError = null
                            } else {
                                state = MnnEngineState.ERROR
                                lastError = result.error
                                if (result.error.code == MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE) {
                                    runtimeAvailable = false
                                }
                            }
                        }
                    }
                }
                result
            }
        } finally {
            generationAdmission.compareAndSet(requestId, null)
        }
    }

    fun cancel(requestId: Long) {
        stateLock.withLock {
            if (activeRequestId == requestId) {
                runtime.cancel(requestId)
            }
        }
    }

    fun unloadModel(): MnnResult<Unit> = dispatch {
        val isAlreadyUnloaded = stateLock.withLock {
            loadedModel == null && state == MnnEngineState.UNLOADED
        }
        if (isAlreadyUnloaded) {
            return@dispatch MnnResult.success(Unit)
        }
        when (val result = runtime.unloadModel()) {
            is MnnResult.Success -> {
                stateLock.withLock {
                    loadedModel = null
                    activeRequestId = null
                    metrics = MnnRuntimeMetrics()
                    lastError = null
                    state = MnnEngineState.UNLOADED
                }
                MnnResult.success(Unit)
            }
            is MnnResult.Failure -> failStateAfterRuntimeSync(result.error)
        }
    }

    fun getStatus(): MnnRuntimeStatus = stateLock.withLock {
        MnnRuntimeStatus(
            state = state,
            backend = loadedModel?.backend,
            modelId = loadedModel?.model?.modelId,
            error = lastError
        )
    }

    fun getLoadedModel(): MnnLoadedModel? = stateLock.withLock { loadedModel }

    fun getMetrics(): MnnRuntimeMetrics = stateLock.withLock { metrics }

    fun getHealth(): MnnHealthStatus = stateLock.withLock {
        val available = runtimeAvailable && !isClosed.get()
        MnnHealthStatus(
            available = available,
            state = state,
            error = if (available) null else lastError
                ?: MnnError(MnnErrorCode.INVALID_STATE, "MNN runtime is unavailable."),
        )
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            val requestToCancel = stateLock.withLock { activeRequestId }
            requestToCancel?.let { requestId ->
                runCatching { runtime.cancel(requestId) }
                    .onFailure { warnShutdown("Could not cancel active MNN generation during shutdown.") }
            }
            val closeFuture: Future<*> = try {
                executor.submit {
                    runCatching { runtime.close() }
                    stateLock.withLock {
                        loadedModel = null
                        activeRequestId = null
                        state = MnnEngineState.UNLOADED
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                executor.shutdownNow()
                return
            }
            try {
                closeFuture.get(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)
                executor.shutdown()
            } catch (_: TimeoutException) {
                warnShutdown("MNN shutdown exceeded ${shutdownTimeoutMillis}ms; native runtime may remain allocated until process exit.")
                executor.shutdownNow()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                warnShutdown("MNN shutdown was interrupted; native runtime may remain allocated until process exit.")
                executor.shutdownNow()
            } catch (_: ExecutionException) {
                warnShutdown("MNN runtime close failed; native runtime may remain allocated until process exit.")
                executor.shutdownNow()
            }
        }
    }

    private fun warnShutdown(message: String) {
        runCatching { logWarning(message) }
    }

    private fun <T> dispatch(action: () -> MnnResult<T>): MnnResult<T> {
        if (isClosed.get()) {
            return MnnResult.failure(MnnErrorCode.INVALID_STATE, "MNN engine host is closed.")
        }
        return try {
            executor.submit<MnnResult<T>> {
                try {
                    action()
                } catch (_: Exception) {
                    MnnResult.failure(MnnErrorCode.INTERNAL_ERROR, "MNN runtime operation failed.")
                }
            }.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            MnnResult.failure(MnnErrorCode.INTERNAL_ERROR, "MNN runtime operation was interrupted.")
        } catch (_: ExecutionException) {
            MnnResult.failure(MnnErrorCode.INTERNAL_ERROR, "MNN runtime operation failed.")
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            MnnResult.failure(MnnErrorCode.INVALID_STATE, "MNN engine host is closed.")
        }
    }

    private fun failState(error: MnnError): MnnResult.Failure {
        stateLock.withLock {
            state = MnnEngineState.ERROR
            lastError = error
            if (error.code == MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE) {
                runtimeAvailable = false
            }
        }
        return MnnResult.Failure(error)
    }

    private fun failStateAfterRuntimeSync(error: MnnError): MnnResult.Failure {
        val runtimeModel = runCatching { runtime.getLoadedModel() }.getOrNull()
        stateLock.withLock { loadedModel = runtimeModel }
        return failState(error)
    }

    private companion object {
        const val TAG = "PAIR-MNN"
        const val SHUTDOWN_TIMEOUT_MILLIS = 10_000L
    }
}
