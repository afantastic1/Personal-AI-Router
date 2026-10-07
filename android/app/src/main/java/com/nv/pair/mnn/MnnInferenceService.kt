/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface MnnInferenceService {
    fun listModels(): List<MnnModelDescriptor>

    fun status(): MnnRuntimeStatus

    fun ensureLoaded(modelId: String, backend: MnnBackend): MnnResult<MnnLoadedModel>

    fun generate(requestId: Long, request: MnnChatRequest, onToken: (String) -> Unit): MnnResult<MnnGenerationResult>

    fun cancel(requestId: Long)

    fun unload(): MnnResult<Unit>
}

class LocalMnnInferenceService(
    private val catalog: MnnModelCatalog,
    private val host: MnnEngineHost,
) : MnnInferenceService, AutoCloseable {
    private val operationLock = ReentrantLock()
    private val activeRequestId = AtomicReference<Long?>(null)

    override fun listModels(): List<MnnModelDescriptor> = catalog.listModels()

    override fun status(): MnnRuntimeStatus = host.getStatus()

    override fun ensureLoaded(modelId: String, backend: MnnBackend): MnnResult<MnnLoadedModel> =
        operationLock.withLock {
            if (activeRequestId.get() != null) {
                return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is already active.")
            }
            ensureLoadedLocked(modelId, backend)
        }

    override fun generate(
        requestId: Long,
        request: MnnChatRequest,
        onToken: (String) -> Unit,
    ): MnnResult<MnnGenerationResult> {
        val validation = request.validate()
        if (validation is MnnResult.Failure) return validation
        if (!activeRequestId.compareAndSet(null, requestId)) {
            return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is already active.")
        }
        return try {
            val backend = host.getLoadedModel()
                ?.takeIf { it.model.modelId == request.modelId }
                ?.backend
                ?: MnnBackend.CPU
            when (val loaded = operationLock.withLock { ensureLoadedLocked(request.modelId, backend) }) {
                is MnnResult.Failure -> loaded
                is MnnResult.Success -> host.generate(requestId, request, onToken)
            }
        } finally {
            activeRequestId.compareAndSet(requestId, null)
        }
    }

    override fun cancel(requestId: Long) {
        if (activeRequestId.get() == requestId) host.cancel(requestId)
    }

    override fun unload(): MnnResult<Unit> = operationLock.withLock {
        if (activeRequestId.get() != null) {
            return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is already active.")
        }
        host.unloadModel()
    }

    override fun close() {
        activeRequestId.get()?.let(::cancel)
        host.close()
    }

    private fun ensureLoadedLocked(modelId: String, backend: MnnBackend): MnnResult<MnnLoadedModel> {
        val model = catalog.find(modelId)
            ?: return MnnResult.failure(MnnErrorCode.MODEL_NOT_FOUND, "The requested MNN model is not installed.")
        val loaded = host.getLoadedModel()
        if (
            loaded?.model?.modelId == modelId &&
            loaded.backend == backend &&
            host.getStatus().state == MnnEngineState.READY
        ) {
            return MnnResult.success(loaded)
        }
        return when (val result = host.loadModel(model, backend)) {
            is MnnResult.Success -> host.getLoadedModel()?.let { MnnResult.success(it) }
                ?: MnnResult.failure(MnnErrorCode.INVALID_STATE, "MNN did not report a loaded model.")
            is MnnResult.Failure -> result
        }
    }
}
