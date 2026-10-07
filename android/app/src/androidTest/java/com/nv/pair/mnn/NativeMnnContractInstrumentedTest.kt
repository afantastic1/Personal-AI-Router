/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

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
class NativeMnnContractInstrumentedTest {
    @Test
    fun reportsPinnedNativeVersion() {
        val runtime = NativeMnn.create()

        assertEquals("3.6.1", runtime.version())
        runtime.close()
    }

    @Test
    fun translatesMissingNativeLibraryToTypedError() {
        val runtime = NativeMnn.create { throw UnsatisfiedLinkError("synthetic missing library") }
        val result = runtime.loadModel(modelDescriptor("/missing/config.json"), MnnBackend.CPU)

        assertEquals(MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE, failureCode(result))
        runtime.close()
    }

    @Test
    fun rejectsInvalidModelConfigPathWithoutNativeCrash() {
        val runtime = NativeMnn.create()

        val result = runtime.loadModel(modelDescriptor("/missing/config.json"), MnnBackend.CPU)

        assertEquals(MnnErrorCode.MODEL_DIRECTORY_MISSING, failureCode(result))
        runtime.close()
    }

    @Test
    fun deliversVisibleStreamCallbackDuringIncrementalGeneration() {
        val runtime = NativeMnn.create()
        try {
            val model = requiredModelDescriptor()
            assertTrue(runtime.loadModel(model, MnnBackend.CPU) is MnnResult.Success)
            val chunks = mutableListOf<String>()

            val result = runtime.generate(
                requestId = 21,
                request = MnnChatRequest("qwen-device-test", listOf(MnnChatMessage(MnnChatRole.USER, "Reply with one short word.")), maxTokens = 8)
            ) { chunk -> chunks.add(chunk) }

            assertTrue(result is MnnResult.Success)
            assertFalse(chunks.isEmpty())
        } finally {
            runtime.unloadModel()
            runtime.close()
        }
    }

    @Test
    fun cancellationStopsActiveGenerationAndUnloadIsIdempotent() {
        val runtime = NativeMnn.create()
        assertTrue(runtime.loadModel(requiredModelDescriptor(), MnnBackend.CPU) is MnnResult.Success)
        val firstChunk = CountDownLatch(1)
        val generationFinished = CountDownLatch(1)
        val resultHolder = arrayOfNulls<MnnResult<MnnGenerationResult>>(1)
        val generationThread = Thread {
            resultHolder[0] = runtime.generate(
                requestId = 22,
                request = MnnChatRequest("qwen-device-test", listOf(MnnChatMessage(MnnChatRole.USER, "Count slowly.")), maxTokens = 512)
            ) { firstChunk.countDown() }
            generationFinished.countDown()
        }
        generationThread.start()

        assertTrue("generation should emit before cancellation", firstChunk.await(30, TimeUnit.SECONDS))
        runtime.cancel(22)
        assertTrue("generation should stop after cancellation", generationFinished.await(30, TimeUnit.SECONDS))
        assertEquals(MnnErrorCode.CANCELLED, failureCode(requireNotNull(resultHolder[0])))
        assertTrue(runtime.unloadModel() is MnnResult.Success)
        assertTrue(runtime.unloadModel() is MnnResult.Success)
        runtime.close()
    }

    private fun requiredModelDescriptor(): MnnModelDescriptor {
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("pairMnnModelDir")
        assertFalse(
            "Missing required MNN fixture argument: pairMnnModelDir",
            path.isNullOrBlank()
        )
        val config = File(requireNotNull(path), "config.json")
        assertTrue("Required MNN fixture config is missing on device.", config.isFile)
        return MnnModelDescriptor("qwen-device-test", config.absolutePath, "Qwen device test")
    }

    private fun modelDescriptor(configPath: String) =
        MnnModelDescriptor("contract-test", configPath, "Contract test")

    private fun failureCode(result: MnnResult<*>): MnnErrorCode = when (result) {
        is MnnResult.Failure -> result.error.code
        is MnnResult.Success -> error("Expected operation to fail")
    }
}
