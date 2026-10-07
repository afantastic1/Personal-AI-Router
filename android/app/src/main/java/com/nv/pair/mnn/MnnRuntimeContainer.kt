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
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val inference = LocalMnnInferenceService(MnnModelCatalog(modelRoot), MnnEngineHost(runtime))
    private val httpServer = MnnHttpServer(inference, port)

    val localPort: Int
        get() = httpServer.localPort

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
