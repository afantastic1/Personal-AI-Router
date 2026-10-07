/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MnnRuntimeInstrumentedTest {
    @Test
    fun fixtureIsRequiredAndValidBeforeAcceptanceBegins() {
        requiredModelDescriptor()
    }

    @Test
    fun cpuLoadsGeneratesStreamsReportsMetricsAndReloadsAcrossFiveCycles() {
        val runtime = NativeMnn.create()
        val model = requiredModelDescriptor()
        val postUnloadPss = mutableListOf<Long>()
        val loadedPss = mutableListOf<Long>()
        val postUnloadNativeHeap = mutableListOf<Long>()
        val baselinePss = readPssKilobytes()
        val baselineNativeHeap = Debug.getNativeHeapAllocatedSize() / 1024L

        try {
            repeat(5) { cycle ->
                assertSuccess(runtime.loadModel(model, MnnBackend.CPU), "CPU load cycle ${cycle + 1}")
                val chunks = mutableListOf<String>()
                val result = runtime.generate(
                    requestId = 100L + cycle,
                    request = MnnChatRequest("qwen-device-test", listOf(MnnChatMessage(MnnChatRole.USER, "Reply with one short word.")), maxTokens = 16)
                ) { chunk -> chunks.add(chunk) }

                assertTrue("CPU generation cycle ${cycle + 1} failed.", result is MnnResult.Success)
                assertFalse("CPU generation returned no stream chunks.", chunks.isEmpty())
                assertTrue("CPU generation returned no output.", chunks.joinToString("").isNotBlank())
                assertTrue("CPU generation metrics were empty.", runtime.getMetrics().generatedTokens > 0)
                loadedPss.add(readPssKilobytes())
                assertSuccess(runtime.unloadModel(), "CPU unload cycle ${cycle + 1}")
                postUnloadPss.add(readPssKilobytes())
                postUnloadNativeHeap.add(Debug.getNativeHeapAllocatedSize() / 1024L)
            }

            assertNoProgressiveMemoryGrowth(
                baselinePss,
                baselineNativeHeap,
                loadedPss,
                postUnloadPss,
                postUnloadNativeHeap
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun nativeTerminalStatusMapsToStopAndLengthFinishReasons() {
        val runtime = NativeMnn.create()
        try {
            assertSuccess(runtime.loadModel(requiredModelDescriptor(), MnnBackend.CPU), "CPU load")

            val tokenLimited = runtime.generate(
                requestId = 190,
                request = MnnChatRequest(
                    "qwen-device-test",
                    listOf(MnnChatMessage(MnnChatRole.USER, "Reply with one short word.")),
                    maxTokens = 1,
                    temperature = 0f,
                ),
            ) { }
            assertEquals(MnnFinishReason.LENGTH, generationFinishReason(tokenLimited))

            val naturallyStopped = runtime.generate(
                requestId = 191,
                request = MnnChatRequest(
                    "qwen-device-test",
                    listOf(MnnChatMessage(MnnChatRole.USER, "Reply with exactly the word yes, then stop. /no_think")),
                    maxTokens = 32,
                    temperature = 0f,
                ),
            ) { }
            assertEquals(MnnFinishReason.STOP, generationFinishReason(naturallyStopped))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun cancellationQuiescesGenerationBeforeUnloadAndReload() {
        val runtime = NativeMnn.create()
        try {
            assertSuccess(runtime.loadModel(requiredModelDescriptor(), MnnBackend.CPU), "CPU load")
            val firstChunk = CountDownLatch(1)
            val generationFinished = CountDownLatch(1)
            val generationResult = arrayOfNulls<MnnResult<MnnGenerationResult>>(1)
            val generationThread = Thread {
                generationResult[0] = runtime.generate(
                    requestId = 206,
                    request = MnnChatRequest("qwen-device-test", listOf(MnnChatMessage(MnnChatRole.USER, "Count slowly.")), maxTokens = 512)
                ) { firstChunk.countDown() }
                generationFinished.countDown()
            }
            generationThread.start()

            assertTrue("Generation did not start emitting before timeout.", firstChunk.await(60, TimeUnit.SECONDS))
            runtime.cancel(206)
            assertTrue("Generation did not quiesce after cancellation.", generationFinished.await(60, TimeUnit.SECONDS))
            assertEquals(MnnErrorCode.CANCELLED, failureCode(requireNotNull(generationResult[0])))
            assertSuccess(runtime.unloadModel(), "CPU unload after cancellation")
            assertSuccess(runtime.loadModel(requiredModelDescriptor(), MnnBackend.CPU), "CPU reload after cancellation")
            assertSuccess(runtime.unloadModel(), "CPU final unload")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun identicalSeedProducesIdenticalSampledResponse() {
        val runtime = NativeMnn.create()
        try {
            assertSuccess(runtime.loadModel(requiredModelDescriptor(), MnnBackend.CPU), "CPU load")
            val request = MnnChatRequest(
                modelId = "qwen-device-test",
                messages = listOf(MnnChatMessage(MnnChatRole.USER, "Write one short greeting. /no_think")),
                maxTokens = 24,
                temperature = 0.8f,
                topP = 0.9f,
                seed = 1701,
            )
            val first = runtime.generate(251, request) { }
            val second = runtime.generate(252, request) { }

            assertTrue("First seeded generation failed.", first is MnnResult.Success)
            assertTrue("Second seeded generation failed.", second is MnnResult.Success)
            assertEquals(
                "The same seed and sampling parameters produced different responses.",
                generationText(first),
                generationText(second),
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun openClProbeReportsTypedUnsupportedOrGeneratesAndCpuRemainsUsable() {
        val runtime = NativeMnn.create()
        val model = requiredModelDescriptor()
        try {
            val openClLoad = runtime.loadModel(model, MnnBackend.OPENCL)
            when (openClLoad) {
                is MnnResult.Success -> {
                    val chunks = mutableListOf<String>()
                    val result = runtime.generate(
                        requestId = 307,
                        request = MnnChatRequest("qwen-device-test", listOf(MnnChatMessage(MnnChatRole.USER, "Reply with one short word.")), maxTokens = 8)
                    ) { chunk -> chunks.add(chunk) }
                    if (result is MnnResult.Failure) {
                        assertEquals(MnnErrorCode.BACKEND_UNSUPPORTED, result.error.code)
                    } else {
                        assertFalse("OpenCL generation returned no stream chunks.", chunks.isEmpty())
                    }
                    assertSuccess(runtime.unloadModel(), "OpenCL unload")
                }
                is MnnResult.Failure -> assertEquals(MnnErrorCode.BACKEND_UNSUPPORTED, openClLoad.error.code)
            }

            assertSuccess(runtime.loadModel(model, MnnBackend.CPU), "CPU load after OpenCL probe")
            val cpuResult = runtime.generate(
                requestId = 308,
                request = MnnChatRequest("qwen-device-test", listOf(MnnChatMessage(MnnChatRole.USER, "Reply with one short word.")), maxTokens = 4)
            ) {}
            assertTrue("CPU failed after OpenCL probe.", cpuResult is MnnResult.Success)
            assertSuccess(runtime.unloadModel(), "CPU unload after OpenCL probe")
        } finally {
            runtime.close()
        }
    }

    private fun requiredModelDescriptor(): MnnModelDescriptor {
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("pairMnnModelDir")
        assertFalse("Missing required MNN fixture argument: pairMnnModelDir", path.isNullOrBlank())
        val resolvedPath = requireNotNull(path)
        val directory = File(resolvedPath)
        assertTrue("Required MNN fixture directory is missing on device.", directory.isDirectory)
        assertTrue("Required MNN fixture config is missing on device.", File(directory, "config.json").isFile)
        assertTrue("Required MNN model graph is missing on device.", File(directory, "llm.mnn").isFile)
        assertTrue("Required MNN model weights are missing on device.", File(directory, "llm.mnn.weight").isFile)
        assertTrue(
            "Required MNN tokenizer is missing on device.",
            File(directory, "tokenizer.mtok").isFile || File(directory, "tokenizer.txt").isFile
        )
        return MnnModelDescriptor("qwen-device-test", File(directory, "config.json").absolutePath, "Qwen device test")
    }

    private fun readPssKilobytes(): Long {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss.toLong()
    }

    private fun assertNoProgressiveMemoryGrowth(
        baselinePss: Long,
        baselineNativeHeap: Long,
        loadedPss: List<Long>,
        postUnloadPss: List<Long>,
        postUnloadNativeHeap: List<Long>
    ) {
        assertEquals("Expected five post-unload PSS samples.", 5, postUnloadPss.size)
        assertEquals("Expected five loaded PSS samples.", 5, loadedPss.size)
        assertEquals("Expected five post-unload native-heap samples.", 5, postUnloadNativeHeap.size)
        val pssToleranceKb = maxOf(64L * 1024L, baselinePss / 10L)
        val nativeHeapToleranceKb = maxOf(16L * 1024L, baselineNativeHeap / 4L)
        assertTrue(
            "Post-unload PSS exceeded its pre-load baseline: baseline=$baselinePss KB, loaded=$loadedPss KB, " +
                "postUnload=$postUnloadPss KB, tolerance=$pssToleranceKb KB",
            postUnloadPss.maxOrNull()!! <= baselinePss + pssToleranceKb
        )
        assertTrue(
            "Post-unload native heap grew beyond tolerance: baseline=$baselineNativeHeap KB, " +
                "postUnload=$postUnloadNativeHeap KB, tolerance=$nativeHeapToleranceKb KB",
            postUnloadNativeHeap.maxOrNull()!! <= baselineNativeHeap + nativeHeapToleranceKb
        )
    }

    private fun assertSuccess(result: MnnResult<Unit>, operation: String) {
        assertTrue("$operation did not succeed.", result is MnnResult.Success)
    }

    private fun failureCode(result: MnnResult<*>): MnnErrorCode = when (result) {
        is MnnResult.Failure -> result.error.code
        is MnnResult.Success -> error("Expected operation to fail")
    }

    private fun generationText(result: MnnResult<MnnGenerationResult>): String = when (result) {
        is MnnResult.Success -> result.value.text
        is MnnResult.Failure -> error("Expected generation to succeed: ${result.error.code}")
    }

    private fun generationFinishReason(result: MnnResult<MnnGenerationResult>): MnnFinishReason = when (result) {
        is MnnResult.Success -> result.value.finishReason
        is MnnResult.Failure -> error("Expected generation to succeed: ${result.error.code}")
    }
}
