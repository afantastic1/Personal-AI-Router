/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.net.InetAddress
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelHubInstallerTest {
    @Test
    fun installRecoversWhenOnlyTheInitialTaskManifestTemporaryFileRemains() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-manifest-recovery").toFile()
        val staging = root.resolve(".Qwen3-0.6B-MNN.downloading")
        assertTrue(staging.mkdir())
        staging.resolve(".pair-download.json.tmp").writeText("incomplete")
        try {
            val installed = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(catalogDescriptor(artifacts), localHttpAdapter(server.port))

            assertEquals("Qwen3-0.6B-MNN", installed.engineModelId)
            assertTrue(root.resolve("Qwen3-0.6B-MNN/.pair-model.json").isFile)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun installationResumesPartialArtifactsAfterInstallerReconstruction() {
        val artifacts = mnnArtifacts().toMutableMap()
        artifacts["llm.mnn.weight"] = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        val server = ArtifactServer(artifacts, expectedRequests = 6)
        val root = Files.createTempDirectory("pair-model-resume").toFile()
        val descriptor = catalogDescriptor(artifacts)
        try {
            val firstFailure = runCatching {
                ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                    .installMnnModel(descriptor, localHttpAdapter(server.port)) { progress ->
                        if (progress.artifactPath == "llm.mnn.weight" && progress.artifactBytesReceived > 0L) {
                            throw InterruptedIOException("Simulated process interruption.")
                        }
                    }
            }.exceptionOrNull()

            assertTrue(firstFailure is InterruptedIOException)
            val staging = root.resolve(".Qwen3-0.6B-MNN.downloading")
            val partialWeight = staging.resolve("llm.mnn.weight.part")
            assertTrue(staging.isDirectory)
            assertTrue(partialWeight.length() in 1 until artifacts.getValue("llm.mnn.weight").size.toLong())

            val installed = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(descriptor, localHttpAdapter(server.port))

            assertEquals("Qwen3-0.6B-MNN", installed.engineModelId)
            assertTrue(artifacts.getValue("llm.mnn.weight").contentEquals(root.resolve("Qwen3-0.6B-MNN/llm.mnn.weight").readBytes()))
            assertEquals(listOf(null, null, null, null, "bytes=65536-", null), server.requestedRanges.toList())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun successfulInstallPublishesAtomicallyUnderThePrivateModelRoot() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-hub").toFile()
        try {
            val descriptor = catalogDescriptor(artifacts)
            val installed = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(descriptor, localHttpAdapter(server.port))

            assertEquals("Qwen3-0.6B-MNN", installed.engineModelId)
            assertEquals(ModelSourceKind.LOCAL, installed.source.kind)
            assertTrue(root.resolve("Qwen3-0.6B-MNN/config.json").isFile)
            assertTrue(root.resolve("Qwen3-0.6B-MNN/llm.mnn.weight").isFile)
            assertTrue(root.resolve("Qwen3-0.6B-MNN/.pair-model.json").isFile)
            assertTrue(root.resolve(".Qwen3-0.6B-MNN.downloading").exists().not())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failedInstallLeavesNoFinalModelDirectory() {
        val artifacts = mnnArtifacts().toMutableMap()
        artifacts["llm.mnn.weight"] = "tampered".toByteArray()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-hub").toFile()
        try {
            val descriptor = catalogDescriptor(mnnArtifacts())
            val failure = runCatching {
                ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                    .installMnnModel(descriptor, localHttpAdapter(server.port))
            }.exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertTrue(root.resolve("Qwen3-0.6B-MNN").exists().not())
            assertTrue(root.resolve(".Qwen3-0.6B-MNN.downloading").isDirectory)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun missingRequiredArtifactHashRequiresExplicitOptInAndRecordsLocalDigest() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts, expectedRequests = 5)
        val root = Files.createTempDirectory("pair-model-hub").toFile()
        try {
            val descriptor = catalogDescriptor(artifacts).copy(
                files = catalogDescriptor(artifacts).files.map { file ->
                    if (file.path == "llm.mnn.weight") file.copy(sha256 = null) else file
                },
            )
            val installer = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
            val failure = runCatching { installer.installMnnModel(descriptor, localHttpAdapter(server.port)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(root.listFiles().orEmpty().isEmpty())

            installer.installMnnModel(descriptor, localHttpAdapter(server.port), allowUnverifiedSource = true)
            val manifest = org.json.JSONObject(root.resolve("Qwen3-0.6B-MNN/.pair-model.json").readText())
            assertEquals("UNVERIFIED_SOURCE_DIGEST", manifest.getString("digestStatus"))
            assertEquals(5, server.requestedPaths.size)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    private fun catalogDescriptor(artifacts: Map<String, ByteArray>) = ModelDescriptor(
        logicalId = "hugging_face:owner/Qwen3-0.6B-MNN",
        engineModelId = "owner/Qwen3-0.6B-MNN",
        displayName = "Qwen3 0.6B MNN",
        family = "qwen",
        parameterCount = 600_000_000,
        quantization = null,
        contextLength = null,
        source = ModelSource(ModelSourceKind.HUGGING_FACE, "owner/Qwen3-0.6B-MNN", "a".repeat(40)),
        format = ModelFormat.MNN,
        estimatedMemoryBytes = 1_440_000_000,
        compatibility = ModelCompatibility.COMPATIBLE,
        files = artifacts.map { (path, bytes) -> ModelFile(path, bytes.size.toLong(), sha256(bytes)) },
        requiredArtifactPaths = listOf("llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt"),
    )

    private fun localHttpAdapter(port: Int) = object : ModelSourceAdapter {
        override val kind = ModelSourceKind.HUGGING_FACE
        override fun parseSearchResponse(response: String): List<ModelDescriptor> = emptyList()
        override fun search(query: String, limit: Int): List<ModelDescriptor> = emptyList()
        override fun searchPage(query: String, page: Int, pageSize: Int): ModelSearchPage = ModelSearchPage(
            descriptors = emptyList(),
            providerPage = RemoteModelPage(emptyList(), page, pageSize, hasMore = false),
        )
        override fun inspect(descriptor: ModelDescriptor): ModelDescriptor = descriptor
        override fun fileUrl(repository: String, revision: String, path: String): URL = URL("http://127.0.0.1:$port/$path")
        override fun accessToken(): String? = null
    }

    private fun mnnArtifacts(): Map<String, ByteArray> = mapOf(
        "config.json" to """{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","tokenizer_file":"tokenizer.txt"}""".toByteArray(),
        "llm_config.json" to "{}".toByteArray(),
        "llm.mnn" to "graph".toByteArray(),
        "llm.mnn.weight" to "weights".toByteArray(),
        "tokenizer.txt" to "tokens".toByteArray(),
    )

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

    private class ArtifactServer(
        private val artifacts: Map<String, ByteArray>,
        expectedRequests: Int = artifacts.size,
    ) : AutoCloseable {
        private val server = ServerSocket(0, expectedRequests, InetAddress.getByName("127.0.0.1"))
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val requestedRanges = Collections.synchronizedList(mutableListOf<String?>())
        val port: Int = server.localPort
        private val worker = Thread {
            repeat(expectedRequests) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@Thread
                try {
                    socket.use(::serve)
                } catch (_: java.net.SocketException) {
                    // The simulated process interruption closes the download connection early.
                }
            }
        }.apply { start() }

        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            val path = reader.readLine()?.split(' ')?.getOrNull(1)?.removePrefix("/")
            var range: String? = null
            path?.let(requestedPaths::add)
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
            }
            requestedRanges += range
            val body = artifacts[path] ?: ByteArray(0)
            val offset = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
            val responseBody = body.drop(offset).toByteArray()
            val status = if (offset > 0) "206 Partial Content" else "200 OK"
            val contentRange = if (offset > 0) "Content-Range: bytes $offset-${body.lastIndex}/${body.size}\r\n" else ""
            val header = "HTTP/1.1 $status\r\nContent-Length: ${responseBody.size}\r\n${contentRange}Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(header.toByteArray(StandardCharsets.US_ASCII))
                write(responseBody)
                flush()
            }
        }

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }
}
