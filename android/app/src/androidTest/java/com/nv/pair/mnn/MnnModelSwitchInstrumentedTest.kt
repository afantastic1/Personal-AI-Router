/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MnnModelSwitchInstrumentedTest {
    @Test
    fun generatesAfterSwitchingBetweenTwoExternalModelFixtures() {
        val arguments = InstrumentationRegistry.getArguments()
        val primaryDirectory = requiredDirectory(arguments.getString("pairMnnModelDir"), "pairMnnModelDir")
        val secondaryDirectory = requiredDirectory(
            arguments.getString("pairMnnModelSwitchDir"),
            "pairMnnModelSwitchDir"
        )
        assertFalse(
            "Model-switch acceptance requires two separate model directories.",
            primaryDirectory.canonicalPath == secondaryDirectory.canonicalPath
        )
        val primary = descriptor(primaryDirectory, "qwen-primary")
        val secondary = descriptor(secondaryDirectory, "qwen-secondary")
        val runtime = NativeMnn.create()

        try {
            assertTrue("Primary fixture failed to load.", runtime.loadModel(primary, MnnBackend.CPU) is MnnResult.Success)
            assertGenerated(runtime, 501)
            assertTrue("Secondary fixture failed to replace the primary model.", runtime.loadModel(secondary, MnnBackend.CPU) is MnnResult.Success)
            assertGenerated(runtime, 502)
            assertTrue("Primary fixture failed to reload after model switching.", runtime.loadModel(primary, MnnBackend.CPU) is MnnResult.Success)
            assertGenerated(runtime, 503)
            assertTrue("Final model unload failed.", runtime.unloadModel() is MnnResult.Success)
        } finally {
            runtime.close()
        }
    }

    private fun requiredDirectory(path: String?, argumentName: String): File {
        assertFalse("Missing required model-switch fixture argument: $argumentName", path.isNullOrBlank())
        val directory = File(requireNotNull(path))
        assertTrue("Required model-switch fixture directory is missing.", directory.isDirectory)
        listOf("config.json", "llm.mnn", "llm.mnn.weight").forEach { filename ->
            assertTrue("Required model-switch fixture file is missing: $filename", File(directory, filename).isFile)
        }
        assertTrue(
            "Required model-switch fixture tokenizer is missing.",
            File(directory, "tokenizer.mtok").isFile || File(directory, "tokenizer.txt").isFile
        )
        return directory
    }

    private fun descriptor(directory: File, modelId: String) = MnnModelDescriptor(
        modelId = modelId,
        configPath = File(directory, "config.json").absolutePath,
        displayName = modelId
    )

    private fun assertGenerated(runtime: NativeMnn, requestId: Long) {
        val chunks = mutableListOf<String>()
        val result = runtime.generate(
            requestId = requestId,
            request = MnnGenerationRequest("Reply with one short word.", maxTokens = 4)
        ) { chunk -> chunks.add(chunk) }
        assertTrue("Generation failed after model switch.", result is MnnResult.Success)
        assertTrue("Generation emitted no tokens after model switch.", chunks.isNotEmpty())
    }
}
