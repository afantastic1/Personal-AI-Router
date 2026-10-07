/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableModelDownloaderTest {
    @Test
    fun resumesPartialDownloadAndVerifiesSha256BeforePublishingFile() {
        val content = "verified model shard".toByteArray()
        val partialLength = 8
        val server = ModelDownloadServer { requestedRange ->
            assertEquals("bytes=$partialLength-", requestedRange)
            val remaining = content.copyOfRange(partialLength, content.size)
            DownloadResponse(206, remaining, "bytes $partialLength-${content.lastIndex}/${content.size}")
        }
        val directory = Files.createTempDirectory("pair-model-download").toFile()
        try {
            val destination = directory.resolve("model.bin")
            destination.resolveSibling("model.bin.part").writeBytes(content.copyOfRange(0, partialLength))

            val downloaded = ResumableModelDownloader().download(
                "http://127.0.0.1:${server.port}/model.bin",
                destination,
                sha256(content),
            )

            assertTrue(downloaded.isFile)
            assertTrue(content.contentEquals(downloaded.readBytes()))
            assertTrue(destination.resolveSibling("model.bin.part").exists().not())
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun hashMismatchDeletesPartFileAndDoesNotPublishDestination() {
        val content = "complete artifact".toByteArray()
        val server = ModelDownloadServer { DownloadResponse(200, content) }
        val directory = Files.createTempDirectory("pair-model-download").toFile()
        try {
            val destination = directory.resolve("model.bin")
            destination.resolveSibling("model.bin.part").writeBytes("stale".toByteArray())

            val failure = runCatching {
                ResumableModelDownloader().download(
                    "http://127.0.0.1:${server.port}/model.bin",
                    destination,
                    "0".repeat(64),
                )
            }.exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertTrue(destination.exists().not())
            assertTrue(destination.resolveSibling("model.bin.part").exists().not())
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

    private data class DownloadResponse(val status: Int, val body: ByteArray, val contentRange: String? = null)

    private class ModelDownloadServer(
        private val respond: (String?) -> DownloadResponse,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetSocketAddress("127.0.0.1", 0).address)
        private val worker = Thread {
            server.accept().use(::serve)
        }.apply(Thread::start)
        val port: Int = server.localPort

        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            reader.readLine()
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            val response = respond(range)
            val reason = if (response.status == 206) "Partial Content" else "OK"
            val headers = buildString {
                append("HTTP/1.1 ${response.status} $reason\r\n")
                append("Content-Length: ${response.body.size}\r\n")
                response.contentRange?.let { append("Content-Range: $it\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.US_ASCII))
                write(response.body)
                flush()
            }
        }

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }
}
