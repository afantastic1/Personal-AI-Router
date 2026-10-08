/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportAdapterTest {
    @Test
    fun localImportRemainsIndependentOfRemoteChecksumMetadata() {
        val root = Files.createTempDirectory("pair-model-import").toFile()
        try {
            val descriptor = LocalImportAdapter(root).importMnnModel(
                "qwen3-0.6b",
                listOf(
                    LocalModelFile("config.json") { bytes("""{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","tokenizer_file":"tokenizer.txt"}""") },
                    LocalModelFile("llm_config.json") { bytes("{}") },
                    LocalModelFile("llm.mnn") { bytes("graph") },
                    LocalModelFile("llm.mnn.weight") { bytes("weights") },
                    LocalModelFile("tokenizer.txt") { bytes("tokens") },
                ),
            )

            assertEquals("qwen3-0.6b", descriptor.engineModelId)
            assertTrue(File(root, "qwen3-0.6b/config.json").isFile)
            assertTrue(File(root, "qwen3-0.6b/llm.mnn.weight").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafeModelAndFilePathsWithoutWritingOutsideTheRoot() {
        val root = Files.createTempDirectory("pair-model-import").toFile()
        try {
            val unsafeModel = runCatching {
                LocalImportAdapter(root).importMnnModel("../outside", emptyList())
            }.exceptionOrNull()
            val unsafeFile = runCatching {
                LocalImportAdapter(root).importMnnModel("safe-model", listOf(LocalModelFile("../outside") { bytes("x") }))
            }.exceptionOrNull()

            assertTrue(unsafeModel is IllegalArgumentException)
            assertTrue(unsafeFile is IllegalArgumentException)
            assertTrue(File(root.parentFile, "outside").exists().not())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun bytes(value: String) = ByteArrayInputStream(value.toByteArray())
}
