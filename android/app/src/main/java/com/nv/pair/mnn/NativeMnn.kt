/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class NativeMnn private constructor(
    private var nativeHandle: Long,
    private val initializationError: MnnError?
) : MnnRuntime, AutoCloseable {
    private val lock = ReentrantLock(true)
    private val requestFinished = lock.newCondition()
    private var loadedModel: MnnLoadedModel? = null
    private var activeRequestId: Long? = null
    private var unloadRequested = false
    private var closed = false
    private var lastError: MnnError? = initializationError
    private var metrics = MnnRuntimeMetrics()

    fun version(): String {
        if (initializationError != null) {
            return "unavailable"
        }
        return try {
            nativeVersion()
        } catch (_: UnsatisfiedLinkError) {
            "unavailable"
        }
    }

    override fun loadModel(model: MnnModelDescriptor, backend: MnnBackend): MnnResult<Unit> = lock.withLock {
        availabilityError()?.let { return MnnResult.Failure(it) }
        if (unloadRequested || activeRequestId != null) {
            return MnnResult.failure(MnnErrorCode.INVALID_STATE, "MNN is busy with another operation.")
        }

        val configPath = File(model.configPath)
        val directory = configPath.parentFile
            ?: return MnnResult.failure(MnnErrorCode.MODEL_DIRECTORY_MISSING, "Model config directory is missing.")
        val resolvedModel = when (val validation = MnnModelManager().resolve(directory, model.modelId, model.displayName)) {
            is MnnResult.Failure -> {
                lastError = validation.error
                return validation
            }
            is MnnResult.Success -> validation.value
        }
        if (runCatching { File(resolvedModel.configPath).canonicalPath != configPath.canonicalPath }.getOrDefault(true)) {
            return fail(MnnErrorCode.MODEL_CONFIG_INVALID, "Model config path does not match the validated model directory.")
        }
        if (loadedModel != null) {
            val unloadCode = runCatching { nativeUnloadModel(nativeHandle) }.getOrDefault(NATIVE_ERROR_LOAD)
            if (unloadCode != NATIVE_SUCCESS) {
                return fail(MnnErrorCode.MODEL_LOAD_FAILED, "The previously loaded model could not be released.")
            }
            loadedModel = null
        }

        val handle = nativeHandle
        val resultCode = try {
            nativeLoadModel(handle, configPath.absolutePath, backend.ordinal)
        } catch (_: UnsatisfiedLinkError) {
            return unavailable()
        } catch (_: Exception) {
            return fail(MnnErrorCode.MODEL_LOAD_FAILED, "MNN could not load the requested model.")
        }
        val mappedError = loadError(resultCode)
        if (mappedError != null) {
            loadedModel = null
            lastError = mappedError
            return MnnResult.Failure(mappedError)
        }
        loadedModel = MnnLoadedModel(model, backend)
        lastError = null
        metrics = MnnRuntimeMetrics()
        MnnResult.success(Unit)
    }

    override fun generate(
        requestId: Long,
        request: MnnGenerationRequest,
        onToken: (String) -> Unit
    ): MnnResult<MnnGenerationResult> {
        when (val validation = request.validate()) {
            is MnnResult.Failure -> return validation
            is MnnResult.Success -> Unit
        }

        val handle = lock.withLock {
            availabilityError()?.let { return MnnResult.Failure(it) }
            if (unloadRequested || loadedModel == null || activeRequestId != null) {
                return MnnResult.failure(MnnErrorCode.INVALID_STATE, "A model must be ready before generation.")
            }
            activeRequestId = requestId
            nativeHandle
        }

        val output = StringBuilder()
        val callback = TokenCallback { token ->
            output.append(token)
            onToken(token)
        }
        val resultCode = try {
            nativeGenerate(
                handle,
                requestId,
                request.prompt,
                request.maxTokens,
                request.temperature,
                request.topP,
                request.seed ?: -1,
                callback
            )
        } catch (_: UnsatisfiedLinkError) {
            NATIVE_ERROR_UNAVAILABLE
        } catch (_: Exception) {
            NATIVE_ERROR_GENERATION
        }

        val nativeMetrics = try {
            nativeGetMetrics(handle)
        } catch (_: Exception) {
            longArrayOf(0L, 0L, 0L)
        }
        val completedMetrics = MnnRuntimeMetrics(
            promptTokens = nativeMetrics.getOrElse(0) { 0L }.toInt(),
            generatedTokens = nativeMetrics.getOrElse(1) { 0L }.toInt(),
            durationMillis = nativeMetrics.getOrElse(2) { 0L },
            tokensPerSecond = if (nativeMetrics.getOrElse(2) { 0L } > 0L) {
                nativeMetrics.getOrElse(1) { 0L }.toDouble() * 1_000.0 /
                    nativeMetrics.getOrElse(2) { 1L }.toDouble()
            } else {
                0.0
            }
        )

        lock.withLock {
            metrics = completedMetrics
            activeRequestId = null
            requestFinished.signalAll()
        }

        return when (resultCode) {
            NATIVE_SUCCESS -> MnnResult.success(MnnGenerationResult(output.toString(), completedMetrics))
            NATIVE_CANCELLED -> MnnResult.failure(MnnErrorCode.CANCELLED, "Generation was cancelled.")
            NATIVE_ERROR_UNSUPPORTED -> MnnResult.failure(
                MnnErrorCode.BACKEND_UNSUPPORTED,
                "The selected MNN backend is not supported by this runtime."
            )
            NATIVE_ERROR_UNAVAILABLE -> unavailable()
            else -> fail(MnnErrorCode.GENERATION_FAILED, "MNN generation failed.")
        }
    }

    override fun cancel(requestId: Long) {
        lock.withLock {
            if (activeRequestId == requestId && nativeHandle != 0L) {
                runCatching { nativeCancel(nativeHandle, requestId) }
            }
        }
    }

    override fun unloadModel(): MnnResult<Unit> = lock.withLock {
        availabilityError()?.let {
            if (nativeHandle == 0L) {
                loadedModel = null
                return MnnResult.success(Unit)
            }
            return MnnResult.Failure(it)
        }
        unloadRequested = true
        try {
            while (activeRequestId != null) {
                requestFinished.await()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            unloadRequested = false
            return MnnResult.failure(MnnErrorCode.INTERNAL_ERROR, "MNN unload was interrupted.")
        }

        val resultCode = try {
            nativeUnloadModel(nativeHandle)
        } catch (_: UnsatisfiedLinkError) {
            NATIVE_ERROR_UNAVAILABLE
        } catch (_: Exception) {
            NATIVE_ERROR_LOAD
        }
        unloadRequested = false
        loadedModel = null
        metrics = MnnRuntimeMetrics()
        when (resultCode) {
            NATIVE_SUCCESS -> MnnResult.success(Unit)
            NATIVE_ERROR_UNAVAILABLE -> unavailable()
            else -> fail(MnnErrorCode.MODEL_LOAD_FAILED, "MNN could not unload the model cleanly.")
        }
    }

    override fun getStatus(): MnnRuntimeStatus = lock.withLock {
        MnnRuntimeStatus(
            state = when {
                closed -> MnnEngineState.UNLOADED
                activeRequestId != null -> MnnEngineState.GENERATING
                loadedModel != null -> MnnEngineState.READY
                lastError != null -> MnnEngineState.ERROR
                else -> MnnEngineState.UNLOADED
            },
            backend = loadedModel?.backend,
            modelId = loadedModel?.model?.modelId,
            error = lastError
        )
    }

    override fun getLoadedModel(): MnnLoadedModel? = lock.withLock { loadedModel }

    override fun getMetrics(): MnnRuntimeMetrics = lock.withLock { metrics }

    override fun close() {
        lock.withLock {
            if (closed) {
                return
            }
            unloadRequested = true
            try {
                while (activeRequestId != null) {
                    requestFinished.await()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (nativeHandle != 0L && initializationError == null) {
                runCatching { nativeUnloadModel(nativeHandle) }
                runCatching { nativeDestroySession(nativeHandle) }
                nativeHandle = 0L
            }
            loadedModel = null
            closed = true
        }
    }

    private fun availabilityError(): MnnError? = when {
        initializationError != null -> initializationError
        closed || nativeHandle == 0L -> MnnError(MnnErrorCode.INVALID_STATE, "MNN runtime is closed.")
        else -> null
    }

    private fun unavailable(): MnnResult.Failure = fail(
        MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE,
        "The PAIR MNN native library could not be loaded. Rebuild the arm64-v8a runtime."
    )

    private fun fail(code: MnnErrorCode, message: String): MnnResult.Failure {
        val error = MnnError(code, message)
        lastError = error
        return MnnResult.Failure(error)
    }

    private fun loadError(resultCode: Int): MnnError? = when (resultCode) {
        NATIVE_SUCCESS -> null
        NATIVE_ERROR_INVALID_CONFIG -> MnnError(
            MnnErrorCode.MODEL_CONFIG_INVALID,
            "The MNN model config is invalid or incomplete."
        )
        NATIVE_ERROR_UNSUPPORTED -> MnnError(
            MnnErrorCode.BACKEND_UNSUPPORTED,
            "The selected MNN backend is not supported by this runtime or device."
        )
        NATIVE_ERROR_UNAVAILABLE -> MnnError(
            MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE,
            "The PAIR MNN native library could not be loaded. Rebuild the arm64-v8a runtime."
        )
        else -> MnnError(MnnErrorCode.MODEL_LOAD_FAILED, "MNN could not load the requested model.")
    }

    private external fun nativeVersion(): String
    private external fun nativeCreateSession(): Long
    private external fun nativeLoadModel(handle: Long, configPath: String, backend: Int): Int
    private external fun nativeGenerate(
        handle: Long,
        requestId: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        seed: Int,
        callback: TokenCallback
    ): Int
    private external fun nativeCancel(handle: Long, requestId: Long)
    private external fun nativeUnloadModel(handle: Long): Int
    private external fun nativeGetMetrics(handle: Long): LongArray
    private external fun nativeDestroySession(handle: Long)

    private class TokenCallback(private val callback: (String) -> Unit) {
        fun onToken(token: String) = callback(token)
    }

    companion object {
        private const val NATIVE_SUCCESS = 0
        private const val NATIVE_CANCELLED = 1
        private const val NATIVE_ERROR_UNSUPPORTED = 2
        private const val NATIVE_ERROR_INVALID_CONFIG = 3
        private const val NATIVE_ERROR_LOAD = 4
        private const val NATIVE_ERROR_GENERATION = 5
        private const val NATIVE_ERROR_UNAVAILABLE = 6

        fun create(libraryLoader: () -> Unit = { System.loadLibrary("pair_mnn") }): NativeMnn {
            return try {
                libraryLoader()
                val runtime = NativeMnn(0L, null)
                runtime.nativeHandle = runtime.nativeCreateSession()
                if (runtime.nativeHandle == 0L) {
                    NativeMnn(0L, MnnError(MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE, "MNN session could not be created."))
                } else {
                    runtime
                }
            } catch (_: UnsatisfiedLinkError) {
                NativeMnn(
                    0L,
                    MnnError(
                        MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE,
                        "The PAIR MNN native library could not be loaded. Rebuild the arm64-v8a runtime."
                    )
                )
            }
        }
    }
}
