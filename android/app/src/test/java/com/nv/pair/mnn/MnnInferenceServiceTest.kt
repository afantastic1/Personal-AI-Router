/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnInferenceServiceTest {
    @Test
    fun defaultBackendIsCpu() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        val runtime = FakeRuntime()
        val service = LocalMnnInferenceService(MnnModelCatalog(root), MnnEngineHost(runtime))
        try {
            assertTrue(service.generate(1, request()) { } is MnnResult.Success)
            assertEquals(MnnBackend.CPU, runtime.getLoadedModel()?.backend)
        } finally {
            service.close()
        }
    }

    @Test
    fun firstGenerationUsesSelectedOpenCl() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        val runtime = FakeRuntime()
        val selection = MnnBackendSelection(MnnBackend.OPENCL)
        val service = LocalMnnInferenceService(MnnModelCatalog(root), MnnEngineHost(runtime), selection)
        try {
            assertTrue(service.generate(2, request()) { } is MnnResult.Success)
            assertEquals(MnnBackend.OPENCL, runtime.getLoadedModel()?.backend)
        } finally {
            service.close()
        }
    }

    @Test
    fun modelSwitchKeepsSelectedOpenCl() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        createModel(root, "qwen3-1.7b")
        val runtime = FakeRuntime()
        val service = LocalMnnInferenceService(
            MnnModelCatalog(root),
            MnnEngineHost(runtime),
            MnnBackendSelection(MnnBackend.OPENCL),
        )
        try {
            assertTrue(service.generate(3, request("qwen3-0.6b")) { } is MnnResult.Success)
            assertTrue(service.generate(4, request("qwen3-1.7b")) { } is MnnResult.Success)
            assertEquals(listOf(MnnBackend.OPENCL, MnnBackend.OPENCL), runtime.loadedBackends)
        } finally {
            service.close()
        }
    }

    @Test
    fun sameModelReloadsWhenBackendPreferenceChanges() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        val runtime = FakeRuntime()
        val selection = MnnBackendSelection(MnnBackend.CPU)
        val service = LocalMnnInferenceService(MnnModelCatalog(root), MnnEngineHost(runtime), selection)
        try {
            assertTrue(service.generate(5, request()) { } is MnnResult.Success)
            selection.update(MnnBackend.OPENCL)
            assertTrue(service.generate(6, request()) { } is MnnResult.Success)
            assertEquals(listOf(MnnBackend.CPU, MnnBackend.OPENCL), runtime.loadedBackends)
        } finally {
            service.close()
        }
    }

    @Test
    fun explicitLoadSelectsBackendForFollowingImplicitModelLoad() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        createModel(root, "qwen3-1.7b")
        val runtime = FakeRuntime()
        val service = LocalMnnInferenceService(MnnModelCatalog(root), MnnEngineHost(runtime))
        try {
            assertTrue(service.ensureLoaded("qwen3-0.6b", MnnBackend.OPENCL) is MnnResult.Success)
            assertTrue(service.generate(9, request("qwen3-1.7b")) { } is MnnResult.Success)
            assertEquals(listOf(MnnBackend.OPENCL, MnnBackend.OPENCL), runtime.loadedBackends)
        } finally {
            service.close()
        }
    }

    @Test
    fun unsupportedOpenClDoesNotSilentlyFallbackAndCpuWorksAfterward() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        val runtime = FakeRuntime(supportedBackends = setOf(MnnBackend.CPU))
        val selection = MnnBackendSelection(MnnBackend.OPENCL)
        val service = LocalMnnInferenceService(MnnModelCatalog(root), MnnEngineHost(runtime), selection)
        try {
            val unsupported = service.generate(7, request()) { }
            assertEquals(MnnErrorCode.BACKEND_UNSUPPORTED, failureCode(unsupported))
            assertEquals(listOf(MnnBackend.OPENCL), runtime.loadedBackends)

            selection.update(MnnBackend.CPU)
            assertTrue(service.generate(8, request()) { } is MnnResult.Success)
            assertEquals(listOf(MnnBackend.OPENCL, MnnBackend.CPU), runtime.loadedBackends)
        } finally {
            service.close()
        }
    }

    @Test
    fun loadsOneCatalogModelAndReturnsTypedMissingModelFailure() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        val runtime = FakeRuntime()
        val service = LocalMnnInferenceService(MnnModelCatalog(root), MnnEngineHost(runtime))
        try {
            val loaded = service.ensureLoaded("qwen3-0.6b", MnnBackend.CPU)
            val missing = service.ensureLoaded("unknown", MnnBackend.CPU)

            assertTrue(loaded is MnnResult.Success)
            assertEquals("qwen3-0.6b", service.status().modelId)
            assertEquals(MnnErrorCode.MODEL_NOT_FOUND, failureCode(missing))
        } finally {
            service.close()
        }
    }

    @Test
    fun rejectsConcurrentGenerationAndAcceptsNextRequestAfterCancellation() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        val runtime = FakeRuntime(blockGeneration = true)
        val service = LocalMnnInferenceService(MnnModelCatalog(root), MnnEngineHost(runtime))
        val result = arrayOfNulls<MnnResult<MnnGenerationResult>>(1)
        val first = Thread {
            result[0] = service.generate(31, request()) { }
        }
        first.start()
        try {
            assertTrue(runtime.generationStarted.await(2, TimeUnit.SECONDS))
            val second = service.generate(32, request()) { }
            assertEquals(MnnErrorCode.ENGINE_BUSY, failureCode(second))
            service.cancel(31)
            first.join(2_000)
            assertEquals(MnnErrorCode.CANCELLED, failureCode(requireNotNull(result[0])))
            assertTrue(service.generate(33, request()) { } is MnnResult.Success)
        } finally {
            service.close()
        }
    }

    @Test
    fun generationKeepsExplicitlyLoadedBackend() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        val runtime = FakeRuntime()
        val service = LocalMnnInferenceService(MnnModelCatalog(root), MnnEngineHost(runtime))
        try {
            assertTrue(service.ensureLoaded("qwen3-0.6b", MnnBackend.OPENCL) is MnnResult.Success)
            assertTrue(service.generate(41, request()) { } is MnnResult.Success)
            assertEquals(MnnBackend.OPENCL, runtime.getLoadedModel()?.backend)
        } finally {
            service.close()
        }
    }

    @Test
    fun reloadsModelBeforeRetryingAfterGenerationFailure() {
        val root = Files.createTempDirectory("pair-mnn-service").toFile()
        createModel(root, "qwen3-0.6b")
        val runtime = FakeRuntime(generationFailures = 1)
        val service = LocalMnnInferenceService(MnnModelCatalog(root), MnnEngineHost(runtime))
        try {
            val first = service.generate(51, request()) { }
            val retry = service.generate(52, request()) { }

            assertEquals(MnnErrorCode.GENERATION_FAILED, failureCode(first))
            assertTrue(retry is MnnResult.Success)
            assertEquals(2, runtime.loadCount.get())
        } finally {
            service.close()
        }
    }

    private fun createModel(root: File, modelId: String) {
        val directory = root.resolve(modelId)
        directory.mkdirs()
        directory.resolve("config.json").writeText("""{"llm_model":"llm.mnn"}""")
        directory.resolve("llm.mnn").writeText("model")
        directory.resolve("llm.mnn.weight").writeText("weights")
        directory.resolve("tokenizer.txt").writeText("tokenizer")
    }

    private fun request(modelId: String = "qwen3-0.6b") =
        MnnChatRequest(modelId, listOf(MnnChatMessage(MnnChatRole.USER, "hello")))

    private fun failureCode(result: MnnResult<*>): MnnErrorCode = when (result) {
        is MnnResult.Failure -> result.error.code
        is MnnResult.Success -> error("Expected the operation to fail")
    }

    private class FakeRuntime(
        private val blockGeneration: Boolean = false,
        generationFailures: Int = 0,
        private val supportedBackends: Set<MnnBackend> = setOf(MnnBackend.CPU, MnnBackend.OPENCL),
    ) : MnnRuntime {
        val generationStarted = CountDownLatch(1)
        val loadCount = AtomicInteger()
        val loadedBackends = CopyOnWriteArrayList<MnnBackend>()
        private val releaseGeneration = CountDownLatch(1)
        private val remainingGenerationFailures = AtomicInteger(generationFailures)
        @Volatile private var loaded: MnnLoadedModel? = null
        @Volatile private var cancelled: Long? = null

        override fun loadModel(model: MnnModelDescriptor, backend: MnnBackend): MnnResult<Unit> {
            loadCount.incrementAndGet()
            loadedBackends.add(backend)
            if (backend !in supportedBackends) {
                return MnnResult.failure(MnnErrorCode.BACKEND_UNSUPPORTED, "Backend unavailable")
            }
            loaded = MnnLoadedModel(model, backend)
            return MnnResult.success(Unit)
        }

        override fun generate(
            requestId: Long,
            request: MnnChatRequest,
            onToken: (String) -> Unit,
        ): MnnResult<MnnGenerationResult> {
            generationStarted.countDown()
            if (blockGeneration) releaseGeneration.await(2, TimeUnit.SECONDS)
            if (cancelled == requestId) return MnnResult.failure(MnnErrorCode.CANCELLED, "cancelled")
            if (remainingGenerationFailures.getAndUpdate { failures -> maxOf(0, failures - 1) } > 0) {
                return MnnResult.failure(MnnErrorCode.GENERATION_FAILED, "generation failed")
            }
            onToken("response")
            return MnnResult.success(MnnGenerationResult("response", MnnRuntimeMetrics(generatedTokens = 1), MnnFinishReason.STOP))
        }

        override fun cancel(requestId: Long) {
            cancelled = requestId
            releaseGeneration.countDown()
        }

        override fun unloadModel(): MnnResult<Unit> {
            loaded = null
            return MnnResult.success(Unit)
        }

        override fun getStatus() = MnnRuntimeStatus(
            if (loaded == null) MnnEngineState.UNLOADED else MnnEngineState.READY,
            loaded?.backend,
            loaded?.model?.modelId,
        )

        override fun getLoadedModel(): MnnLoadedModel? = loaded

        override fun getMetrics() = MnnRuntimeMetrics()

        override fun close() {
            unloadModel()
        }
    }
}
