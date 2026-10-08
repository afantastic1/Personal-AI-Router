/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportAdapterTest {
    @Test
    fun localImportRemainsIndependentOfRemoteChecksumMetadata() = runBlocking {
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
    fun rejectsUnsafeModelAndFilePathsWithoutWritingOutsideTheRoot() = runBlocking {
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

    @Test
    fun cancellationDuringCopyDoesNotPublishTheModel() = runBlocking {
        val root = Files.createTempDirectory("pair-model-import-cancel").toFile()
        lateinit var importJob: Job
        try {
            importJob = launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                LocalImportAdapter(root).importMnnModel(
                    "cancelled-model",
                    listOf(
                        LocalModelFile("config.json") {
                            bytes("""{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","tokenizer_file":"tokenizer.txt"}""")
                        },
                        LocalModelFile("llm_config.json") { bytes("{}") },
                        LocalModelFile("llm.mnn") { bytes("graph") },
                        LocalModelFile("llm.mnn.weight") { bytes("weights") },
                        LocalModelFile("tokenizer.txt") {
                            CancellingInputStream("tokens".toByteArray()) { importJob.cancel() }
                        },
                    ),
                )
            }

            importJob.start()
            importJob.join()

            assertTrue("the import coroutine should observe cancellation", importJob.isCancelled)
            assertTrue("cancelled imports must not become installed", File(root, "cancelled-model").exists().not())
            assertTrue(
                "cancelled imports must remove their unique staging directory",
                root.listFiles().orEmpty().none { it.name.startsWith(".cancelled-model.importing-") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importRemovesOnlyOldOrphanedStagingDirectories() = runBlocking {
        val root = Files.createTempDirectory("pair-model-import-orphans").toFile()
        val stale = File(root, ".safe-model.importing-crashed")
        val unrelated = File(root, ".other-model.importing-crashed")
        try {
            assertTrue(stale.mkdir())
            assertTrue(unrelated.mkdir())
            assertTrue(stale.setLastModified(System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L))

            LocalImportAdapter(root).importMnnModel("safe-model", validModelFiles())

            assertTrue("a crashed import orphan should be removed", stale.exists().not())
            assertTrue("another model's staging must remain untouched", unrelated.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationAfterAtomicPublishKeepsTheCommittedModel() = runBlocking {
        val root = Files.createTempDirectory("pair-model-import-commit").toFile()
        lateinit var importJob: Job
        var publishedBeforeCancellation = false
        try {
            importJob = launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                LocalImportAdapter(root).importMnnModel(
                    "committed-model",
                    validModelFiles(),
                ) { _ ->
                    publishedBeforeCancellation = File(root, "committed-model/config.json").isFile
                    importJob.cancel()
                }
            }

            importJob.start()
            importJob.join()

            assertTrue("cancellation callback should run after commit", importJob.isCancelled)
            assertTrue("commit notification must follow the atomic move", publishedBeforeCancellation)
            assertTrue("a committed import must remain installed", File(root, "committed-model/config.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validModelFiles() = listOf(
        LocalModelFile("config.json") {
            bytes("""{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","tokenizer_file":"tokenizer.txt"}""")
        },
        LocalModelFile("llm_config.json") { bytes("{}") },
        LocalModelFile("llm.mnn") { bytes("graph") },
        LocalModelFile("llm.mnn.weight") { bytes("weights") },
        LocalModelFile("tokenizer.txt") { bytes("tokens") },
    )

    private class CancellingInputStream(
        private val content: ByteArray,
        private val cancel: () -> Unit,
    ) : java.io.InputStream() {
        private var read = false

        override fun read(): Int = -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (read) return -1
            read = true
            val count = minOf(length, content.size)
            content.copyInto(buffer, offset, 0, count)
            cancel()
            return count
        }
    }

    private fun bytes(value: String) = ByteArrayInputStream(value.toByteArray())
}
