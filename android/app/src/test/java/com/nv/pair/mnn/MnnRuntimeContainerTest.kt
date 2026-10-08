/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnRuntimeContainerTest {
    @Test
    fun failedCloseCanBeRetriedThroughContainer() {
        val runtime = CloseFailingRuntime(failures = 1)
        val modelRoot = Files.createTempDirectory("pair-container-close").toFile()
        val container = MnnRuntimeContainer(modelRoot, port = 0, runtime = runtime)

        try {
            val firstFailure = runCatching { container.close() }.exceptionOrNull()

            assertTrue("container must surface runtime cleanup failure", firstFailure is IllegalStateException)
            assertEquals(1, runtime.closeCount.get())
            assertTrue("container must not restart after close begins", runCatching { container.start() }.isFailure)

            container.close()

            assertEquals(2, runtime.closeCount.get())
            assertEquals(MnnEngineState.UNLOADED, container.runtimeStatus().state)
        } finally {
            modelRoot.deleteRecursively()
        }
    }

    private class CloseFailingRuntime(failures: Int) : MnnRuntime {
        val closeCount = AtomicInteger()
        private val failuresRemaining = AtomicInteger(failures)

        override fun loadModel(model: MnnModelDescriptor, backend: MnnBackend): MnnResult<Unit> =
            MnnResult.success(Unit)

        override fun generate(
            requestId: Long,
            request: MnnChatRequest,
            onToken: (String) -> Unit,
        ): MnnResult<MnnGenerationResult> = MnnResult.failure(MnnErrorCode.INVALID_STATE, "not loaded")

        override fun cancel(requestId: Long) = Unit

        override fun unloadModel(): MnnResult<Unit> = MnnResult.success(Unit)

        override fun getStatus(): MnnRuntimeStatus = MnnRuntimeStatus(MnnEngineState.UNLOADED)

        override fun getLoadedModel(): MnnLoadedModel? = null

        override fun getMetrics(): MnnRuntimeMetrics = MnnRuntimeMetrics()

        override fun close() {
            closeCount.incrementAndGet()
            if (failuresRemaining.getAndUpdate { remaining -> if (remaining > 0) remaining - 1 else remaining } > 0) {
                error("Synthetic native close failure")
            }
        }
    }
}
