/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelHubInstallerTest {
    @Test
    fun downloadsVerifiesAndInstallsMnnArtifactsUnderThePrivateModelRoot() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-hub").toFile()
        try {
            val descriptor = catalogDescriptor(artifacts)
            val installed = ModelHubInstaller(root).installMnnModel(descriptor, localHttpAdapter(server.port))

            assertEquals("Qwen3-0.6B-MNN", installed.engineModelId)
            assertEquals(ModelSourceKind.LOCAL, installed.source.kind)
            assertTrue(root.resolve("Qwen3-0.6B-MNN/config.json").isFile)
            assertTrue(root.resolve("Qwen3-0.6B-MNN/llm.mnn.weight").isFile)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsChecksumMismatchAndRemovesTheIncompleteStagingDirectory() {
        val artifacts = mnnArtifacts().toMutableMap()
        artifacts["llm.mnn.weight"] = "tampered".toByteArray()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-hub").toFile()
        try {
            val descriptor = catalogDescriptor(mnnArtifacts())
            val failure = runCatching {
                ModelHubInstaller(root).installMnnModel(descriptor, localHttpAdapter(server.port))
            }.exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertTrue(root.resolve("Qwen3-0.6B-MNN").exists().not())
            assertTrue(root.listFiles().orEmpty().isEmpty())
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
        capabilities = setOf(ModelCapability.CHAT),
        source = ModelSource(ModelSourceKind.HUGGING_FACE, "owner/Qwen3-0.6B-MNN"),
        format = ModelFormat.MNN,
        estimatedMemoryBytes = 1_440_000_000,
        compatibility = ModelCompatibility.COMPATIBLE,
        files = artifacts.map { (path, bytes) -> ModelFile(path, bytes.size.toLong(), sha256(bytes)) },
    )

    private fun localHttpAdapter(port: Int) = object : ModelSourceAdapter {
        override val kind = ModelSourceKind.HUGGING_FACE
        override fun parseSearchResponse(response: String): List<ModelDescriptor> = emptyList()
        override fun search(query: String, limit: Int): List<ModelDescriptor> = emptyList()
        override fun fileUrl(repository: String, revision: String, path: String): URL = URL("http://127.0.0.1:$port/$path")
        override fun accessToken(): String? = null
    }

    private fun mnnArtifacts(): Map<String, ByteArray> = mapOf(
        "config.json" to """{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","tokenizer_file":"tokenizer.txt"}""".toByteArray(),
        "llm.mnn" to "graph".toByteArray(),
        "llm.mnn.weight" to "weights".toByteArray(),
        "tokenizer.txt" to "tokens".toByteArray(),
    )

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

    private class ArtifactServer(private val artifacts: Map<String, ByteArray>) : AutoCloseable {
        private val server = ServerSocket(0, artifacts.size, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        private val worker = Thread {
            repeat(artifacts.size) {
                server.accept().use(::serve)
            }
        }.apply { start() }

        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            val path = reader.readLine()?.split(' ')?.getOrNull(1)?.removePrefix("/")
            while (reader.readLine()?.isNotEmpty() == true) Unit
            val body = artifacts[path] ?: ByteArray(0)
            val header = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(header.toByteArray(StandardCharsets.US_ASCII))
                write(body)
                flush()
            }
        }

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }
}
