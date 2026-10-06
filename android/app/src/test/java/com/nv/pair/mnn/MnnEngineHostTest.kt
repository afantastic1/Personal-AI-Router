/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnEngineHostTest {
    @Test
    fun successfulLoadReachesReady() {
        val host = MnnEngineHost(FakeMnnRuntime())

        val result = host.loadModel(model(), MnnBackend.CPU)

        assertTrue(result is MnnResult.Success)
        assertEquals(MnnEngineState.READY, host.getStatus().state)
        host.close()
    }

    @Test
    fun failedLoadReachesErrorAndLeavesNoLoadedModel() {
        val host = MnnEngineHost(FakeMnnRuntime(loadError = MnnErrorCode.MODEL_LOAD_FAILED))

        val result = host.loadModel(model(), MnnBackend.CPU)

        val errorCode = when (result) {
            is MnnResult.Failure -> result.error.code
            is MnnResult.Success -> error("Expected the runtime load to fail")
        }
        assertEquals(MnnErrorCode.MODEL_LOAD_FAILED, errorCode)
        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertEquals(null, host.getLoadedModel())
        host.close()
    }

    @Test
    fun runtimeLoadExceptionBecomesTypedFailureAndErrorState() {
        val host = MnnEngineHost(FakeMnnRuntime(throwOnLoad = true))

        val result = host.loadModel(model(), MnnBackend.CPU)

        val errorCode = when (result) {
            is MnnResult.Failure -> result.error.code
            is MnnResult.Success -> error("Expected runtime load exception to be translated")
        }
        assertEquals(MnnErrorCode.MODEL_LOAD_FAILED, errorCode)
        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertEquals(null, host.getLoadedModel())
        host.close()
    }

    @Test
    fun generationStreamsTokensAndReturnsMetrics() {
        val host = MnnEngineHost(FakeMnnRuntime())
        host.loadModel(model(), MnnBackend.CPU)
        val tokens = mutableListOf<String>()

        val result = host.generate(7, MnnGenerationRequest("synthetic prompt")) { tokens.add(it) }

        assertEquals(listOf("synthetic", " response"), tokens)
        assertTrue(result is MnnResult.Success)
        val generatedTokens = when (result) {
            is MnnResult.Success -> result.value.metrics.generatedTokens
            is MnnResult.Failure -> error("Expected generation to complete")
        }
        assertEquals(2, generatedTokens)
        assertEquals(MnnEngineState.READY, host.getStatus().state)
        host.close()
    }

    @Test
    fun cancellationReturnsGenerationToReadyState() {
        val runtime = FakeMnnRuntime(blockGeneration = true)
        val host = MnnEngineHost(runtime)
        host.loadModel(model(), MnnBackend.CPU)
        val generationFinished = CountDownLatch(1)
        val generationResult = arrayOfNulls<MnnResult<MnnGenerationResult>>(1)

        Thread {
            generationResult[0] = host.generate(9, MnnGenerationRequest("synthetic prompt")) { }
            generationFinished.countDown()
        }.start()

        assertTrue(runtime.generationStarted.await(2, TimeUnit.SECONDS))
        host.cancel(9)

        assertTrue(generationFinished.await(2, TimeUnit.SECONDS))
        val errorCode = when (val result = generationResult[0]) {
            is MnnResult.Failure -> result.error.code
            is MnnResult.Success -> error("Expected cancellation to stop generation")
            null -> error("Expected generation to return a result")
        }
        assertEquals(MnnErrorCode.CANCELLED, errorCode)
        assertEquals(MnnEngineState.READY, host.getStatus().state)
        host.close()
    }

    @Test
    fun unloadWaitsForGenerationBeforeReleasingRuntime() {
        val runtime = FakeMnnRuntime(blockGeneration = true)
        val host = MnnEngineHost(runtime)
        host.loadModel(model(), MnnBackend.CPU)
        val generationFinished = CountDownLatch(1)
        val unloadFinished = CountDownLatch(1)

        Thread {
            host.generate(11, MnnGenerationRequest("synthetic prompt")) { }
            generationFinished.countDown()
        }.start()
        assertTrue(runtime.generationStarted.await(2, TimeUnit.SECONDS))
        Thread {
            host.unloadModel()
            unloadFinished.countDown()
        }.start()

        assertFalse(unloadFinished.await(100, TimeUnit.MILLISECONDS))
        runtime.releaseGeneration.countDown()
        assertTrue(generationFinished.await(2, TimeUnit.SECONDS))
        assertTrue(unloadFinished.await(2, TimeUnit.SECONDS))
        assertEquals(1, runtime.unloadCount.get())
        assertEquals(MnnEngineState.UNLOADED, host.getStatus().state)
        host.close()
    }

    @Test
    fun unloadAndReloadAllowsAnotherGeneration() {
        val runtime = FakeMnnRuntime()
        val host = MnnEngineHost(runtime)

        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        assertTrue(host.unloadModel() is MnnResult.Success)
        assertTrue(host.loadModel(model("qwen-reloaded"), MnnBackend.CPU) is MnnResult.Success)
        assertTrue(host.generate(13, MnnGenerationRequest("synthetic prompt")) { } is MnnResult.Success)

        assertEquals(2, runtime.loadCount.get())
        host.close()
    }

    @Test
    fun unsupportedOpenClDoesNotDisableCpu() {
        val host = MnnEngineHost(FakeMnnRuntime(supportedBackends = setOf(MnnBackend.CPU)))

        val openClResult = host.loadModel(model(), MnnBackend.OPENCL)
        val cpuResult = host.loadModel(model(), MnnBackend.CPU)

        val errorCode = when (openClResult) {
            is MnnResult.Failure -> openClResult.error.code
            is MnnResult.Success -> error("Expected OpenCL backend to be unsupported")
        }
        assertEquals(MnnErrorCode.BACKEND_UNSUPPORTED, errorCode)
        assertTrue(cpuResult is MnnResult.Success)
        assertEquals(MnnEngineState.READY, host.getStatus().state)
        host.close()
    }

    private fun model(modelId: String = "qwen-test"): MnnModelDescriptor {
        val config = Files.createTempFile("pair-mnn-config", ".json").toFile()
        config.writeText("{}")
        return MnnModelDescriptor(modelId, config.absolutePath, modelId)
    }

    private class FakeMnnRuntime(
        private val loadError: MnnErrorCode? = null,
        private val throwOnLoad: Boolean = false,
        private val blockGeneration: Boolean = false,
        private val supportedBackends: Set<MnnBackend> = setOf(MnnBackend.CPU, MnnBackend.OPENCL)
    ) : MnnRuntime {
        val generationStarted = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val loadCount = AtomicInteger()
        val unloadCount = AtomicInteger()
        private var loadedModel: MnnLoadedModel? = null
        private var activeRequestId: Long? = null
        @Volatile private var cancelledRequestId: Long? = null

        override fun loadModel(model: MnnModelDescriptor, backend: MnnBackend): MnnResult<Unit> {
            loadCount.incrementAndGet()
            if (throwOnLoad) {
                error("Synthetic runtime failure")
            }
            if (backend !in supportedBackends) {
                return MnnResult.failure(MnnErrorCode.BACKEND_UNSUPPORTED, "Backend unavailable")
            }
            if (loadError != null) {
                return MnnResult.failure(loadError, "Model load failed")
            }
            loadedModel = MnnLoadedModel(model, backend)
            return MnnResult.success(Unit)
        }

        override fun generate(
            requestId: Long,
            request: MnnGenerationRequest,
            onToken: (String) -> Unit
        ): MnnResult<MnnGenerationResult> {
            activeRequestId = requestId
            generationStarted.countDown()
            if (blockGeneration) {
                releaseGeneration.await(2, TimeUnit.SECONDS)
            } else {
                onToken("synthetic")
                onToken(" response")
            }
            activeRequestId = null
            if (cancelledRequestId == requestId) {
                return MnnResult.failure(MnnErrorCode.CANCELLED, "Generation cancelled")
            }
            return MnnResult.success(
                MnnGenerationResult(
                    text = "synthetic response",
                    metrics = MnnRuntimeMetrics(generatedTokens = 2)
                )
            )
        }

        override fun cancel(requestId: Long) {
            if (activeRequestId == requestId) {
                cancelledRequestId = requestId
                releaseGeneration.countDown()
            }
        }

        override fun unloadModel(): MnnResult<Unit> {
            unloadCount.incrementAndGet()
            loadedModel = null
            return MnnResult.success(Unit)
        }

        override fun getStatus(): MnnRuntimeStatus = MnnRuntimeStatus(
            state = if (loadedModel == null) MnnEngineState.UNLOADED else MnnEngineState.READY,
            backend = loadedModel?.backend
        )

        override fun getLoadedModel(): MnnLoadedModel? = loadedModel

        override fun getMetrics(): MnnRuntimeMetrics = MnnRuntimeMetrics()
    }
}
