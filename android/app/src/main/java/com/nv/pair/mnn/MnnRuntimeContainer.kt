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

    fun start() {
        check(!closed.get()) { "MNN runtime container is closed." }
        httpServer.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            httpServer.close()
        } finally {
            inference.close()
        }
    }
}
