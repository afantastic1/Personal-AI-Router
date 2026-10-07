/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MnnModelCatalogTest {
    @Test
    fun listsOnlyValidatedModelDirectoriesInStableOrder() {
        val root = Files.createTempDirectory("pair-mnn-catalog").toFile()
        createModel(root, "qwen3-1.7b")
        createModel(root, "qwen3-0.6b")
        root.resolve("broken").mkdirs()

        val catalog = MnnModelCatalog(root)

        assertEquals(listOf("qwen3-0.6b", "qwen3-1.7b"), catalog.listModels().map { it.modelId })
        assertNotNull(catalog.find("qwen3-0.6b"))
        assertNull(catalog.find("../qwen3-0.6b"))
        assertNull(catalog.find("broken"))
    }

    private fun createModel(root: java.io.File, modelId: String) {
        val directory = root.resolve(modelId)
        directory.mkdirs()
        directory.resolve("config.json").writeText("""{"llm_model":"llm.mnn"}""")
        directory.resolve("llm.mnn").writeText("model")
        directory.resolve("llm.mnn.weight").writeText("weights")
        directory.resolve("tokenizer.txt").writeText("tokenizer")
    }
}
