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
import java.util.concurrent.atomic.AtomicInteger
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
            val progress = mutableListOf<Pair<Long, Long?>>()

            val downloaded = ResumableModelDownloader(allowLoopbackHttpForTests = true).download(
                "http://127.0.0.1:${server.port}/model.bin",
                destination,
                sha256(content),
                onProgress = { received, total -> progress += received to total },
            )

            assertTrue(downloaded.isFile)
            assertTrue(content.contentEquals(downloaded.readBytes()))
            assertEquals(partialLength.toLong() to content.size.toLong(), progress.first())
            assertEquals(content.size.toLong() to content.size.toLong(), progress.last())
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
                ResumableModelDownloader(allowLoopbackHttpForTests = true).download(
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

    @Test
    fun serverIgnoringRangeRestartsFromZeroInsteadOfAppending() {
        val content = "fresh complete artifact".toByteArray()
        val server = ModelDownloadServer { DownloadResponse(200, content) }
        val directory = Files.createTempDirectory("pair-model-download").toFile()
        try {
            val destination = directory.resolve("model.bin")
            destination.resolveSibling("model.bin.part").writeBytes("old partial".toByteArray())

            val downloaded = ResumableModelDownloader(allowLoopbackHttpForTests = true).download(
                "http://127.0.0.1:${server.port}/model.bin",
                destination,
                sha256(content),
            )

            assertTrue(content.contentEquals(downloaded.readBytes()))
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidResumeRangeIsRejectedWithoutPublishingFile() {
        val content = "verified model shard".toByteArray()
        val offset = 4
        val server = ModelDownloadServer { DownloadResponse(206, content, "bytes 0-${content.lastIndex}/${content.size}") }
        val directory = Files.createTempDirectory("pair-model-download").toFile()
        try {
            val destination = directory.resolve("model.bin")
            destination.resolveSibling("model.bin.part").writeBytes(content.copyOfRange(0, offset))

            val failure = runCatching {
                ResumableModelDownloader(allowLoopbackHttpForTests = true).download(
                    "http://127.0.0.1:${server.port}/model.bin",
                    destination,
                    sha256(content),
                )
            }.exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertTrue(destination.exists().not())
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun followsValidPartialContentSegmentsUntilTheDeclaredTotalIsComplete() {
        val content = "segmented model".toByteArray()
        val requestedRanges = mutableListOf<String?>()
        val server = ModelDownloadServer(expectedRequests = 3) { range ->
            requestedRanges += range
            when (range) {
                null -> DownloadResponse(206, content.copyOfRange(0, 4), "bytes 0-3/${content.size}")
                "bytes=4-" -> DownloadResponse(206, content.copyOfRange(4, 9), "bytes 4-8/${content.size}")
                "bytes=9-" -> DownloadResponse(206, content.copyOfRange(9, content.size), "bytes 9-${content.lastIndex}/${content.size}")
                else -> error("Unexpected range: $range")
            }
        }
        val directory = Files.createTempDirectory("pair-model-download").toFile()
        try {
            val destination = directory.resolve("model.bin")

            val downloaded = ResumableModelDownloader(allowLoopbackHttpForTests = true).download(
                "http://127.0.0.1:${server.port}/model.bin",
                destination,
                sha256(content),
            )

            assertTrue(content.contentEquals(downloaded.readBytes()))
            assertEquals(listOf(null, "bytes=4-", "bytes=9-"), requestedRanges)
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsFullResponseWhoseLengthDiffersFromInspectedArtifactSize() {
        val content = "truncated".toByteArray()
        val server = ModelDownloadServer { DownloadResponse(200, content) }
        val directory = Files.createTempDirectory("pair-model-download").toFile()
        try {
            val destination = directory.resolve("model.bin")

            val failure = runCatching {
                ResumableModelDownloader(allowLoopbackHttpForTests = true).download(
                    "http://127.0.0.1:${server.port}/model.bin",
                    destination,
                    expectedSha256 = null,
                    expectedSizeBytes = content.size + 1L,
                )
            }.exceptionOrNull()

            assertTrue("response must match the inspected size", failure is java.io.IOException)
            assertTrue(destination.exists().not())
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun retriesTransientServerFailuresWithRetryAfterDelay() {
        val content = "retried artifact".toByteArray()
        val requests = AtomicInteger()
        val server = ModelDownloadServer(expectedRequests = 2) {
            if (requests.getAndIncrement() == 0) {
                DownloadResponse(503, ByteArray(0), retryAfter = "0")
            } else {
                DownloadResponse(200, content)
            }
        }
        val directory = Files.createTempDirectory("pair-model-download").toFile()
        try {
            val destination = directory.resolve("model.bin")

            ResumableModelDownloader(allowLoopbackHttpForTests = true).download(
                "http://127.0.0.1:${server.port}/model.bin",
                destination,
                sha256(content),
            )

            assertEquals(2, requests.get())
            assertTrue(content.contentEquals(destination.readBytes()))
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun incompletePartialRejectedWith416IsClearedAndRestartedOnce() {
        val content = "complete replacement".toByteArray()
        val requestedRanges = mutableListOf<String?>()
        val server = ModelDownloadServer(expectedRequests = 2) { range ->
            requestedRanges += range
            if (requestedRanges.size == 1) {
                DownloadResponse(416, ByteArray(0), contentRange = "bytes */7")
            } else {
                DownloadResponse(200, content)
            }
        }
        val directory = Files.createTempDirectory("pair-model-download").toFile()
        try {
            val destination = directory.resolve("model.bin")
            destination.resolveSibling("model.bin.part").writeBytes("partial".toByteArray())

            ResumableModelDownloader(allowLoopbackHttpForTests = true).download(
                "http://127.0.0.1:${server.port}/model.bin",
                destination,
                sha256(content),
            )

            assertEquals(listOf("bytes=7-", null), requestedRanges)
            assertTrue(content.contentEquals(destination.readBytes()))
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

    private data class DownloadResponse(
        val status: Int,
        val body: ByteArray,
        val contentRange: String? = null,
        val retryAfter: String? = null,
    )

    private class ModelDownloadServer(
        private val expectedRequests: Int = 1,
        private val respond: (String?) -> DownloadResponse,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetSocketAddress("127.0.0.1", 0).address)
        private val worker = Thread {
            repeat(expectedRequests) { server.accept().use(::serve) }
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
            val reason = when (response.status) {
                206 -> "Partial Content"
                416 -> "Range Not Satisfiable"
                503 -> "Service Unavailable"
                else -> "OK"
            }
            val headers = buildString {
                append("HTTP/1.1 ${response.status} $reason\r\n")
                append("Content-Length: ${response.body.size}\r\n")
                response.contentRange?.let { append("Content-Range: $it\r\n") }
                response.retryAfter?.let { append("Retry-After: $it\r\n") }
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
