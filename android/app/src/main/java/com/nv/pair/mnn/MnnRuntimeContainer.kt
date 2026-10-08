/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import com.nv.pair.mnn.http.MnnHttpServer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MnnRuntimeContainer(
    modelRoot: File,
    port: Int = MnnHttpServer.DEFAULT_PORT,
    runtime: MnnRuntime = NativeMnn.create(),
    preferredBackend: MnnBackend = MnnBackend.CPU,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val backendSelection = MnnBackendSelection(preferredBackend)
    private val inference = LocalMnnInferenceService(
        MnnModelCatalog(modelRoot),
        MnnEngineHost(runtime),
        backendSelection,
    )
    private val httpServer = MnnHttpServer(inference, port)

    val localPort: Int
        get() = httpServer.localPort

    fun health(): MnnHealthStatus = inference.health()

    fun runtimeStatus(): MnnRuntimeStatus = inference.status()

    fun preferredBackend(): MnnBackend = inference.preferredBackend()

    fun setPreferredBackend(backend: MnnBackend) = inference.setPreferredBackend(backend)

    fun deleteModel(modelId: String, deleteFiles: () -> Unit): MnnResult<Unit> =
        inference.deleteModel(modelId, deleteFiles)

    @Synchronized
    fun start() {
        check(!closeRequested.get() && !closed.get()) { "MNN runtime container is closing or closed." }
        httpServer.start()
    }

    @Synchronized
    override fun close() {
        if (closed.get()) return
        closeRequested.set(true)
        var failure: Exception? = null
        try {
            httpServer.close()
        } catch (closeFailure: Exception) {
            failure = closeFailure
        }
        try {
            inference.close()
        } catch (closeFailure: Exception) {
            if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
        closed.set(true)
    }
}
