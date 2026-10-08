/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class MnnModelCatalogTest {
    @Test
    fun ignoresHiddenInstallerStagingDirectories() {
        val root = Files.createTempDirectory("pair-mnn-catalog").toFile()
        val installed = root.resolve("installed-model")
        val staging = root.resolve(".partial-model.downloading")
        createValidModel(installed)
        createValidModel(staging)
        try {
            assertEquals(listOf("installed-model"), MnnModelCatalog(root).listModels().map { it.modelId })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createValidModel(directory: java.io.File) {
        directory.mkdirs()
        directory.resolve("config.json").writeText("""{"llm_model":"llm.mnn"}""")
        directory.resolve("llm_config.json").writeText("{}")
        directory.resolve("llm.mnn").writeText("graph")
        directory.resolve("llm.mnn.weight").writeText("weights")
        directory.resolve("tokenizer.txt").writeText("tokenizer")
    }
}
