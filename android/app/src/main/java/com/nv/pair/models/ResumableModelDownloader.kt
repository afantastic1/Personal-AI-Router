/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom

class ResumableModelDownloader(private val allowLoopbackHttpForTests: Boolean = false) {
    fun download(
        url: String,
        destination: File,
        expectedSha256: String?,
        bearerToken: String? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
        expectedSizeBytes: Long? = null,
    ): File {
        require(expectedSha256 == null || isTrustedSha256(expectedSha256)) { "A valid SHA-256 checksum is required when supplied." }
        require(expectedSizeBytes == null || expectedSizeBytes >= 0L) { "Expected artifact size cannot be negative." }
        val parent = destination.parentFile ?: throw IOException("Model artifact destination has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create model download directory.")
        val partial = File(parent, "${destination.name}.part")
        var attempt = 0
        var transientRetries = 0
        var expectedTotalBytes = expectedSizeBytes
        while (true) {
            val offset = partial.length()
            val connection = openDownloadConnection(URL(url), bearerToken, offset)
            try {
                val status = connection.responseCode
                if (status == HTTP_REQUEST_TIMEOUT || status == HTTP_TOO_MANY_REQUESTS || status in HTTP_SERVER_ERRORS) {
                    if (transientRetries >= MAX_TRANSIENT_RETRIES) {
                        throw IOException("Model download failed after bounded retries with HTTP $status.")
                    }
                    val delayMillis = retryDelayMillis(connection.getHeaderField("Retry-After"), transientRetries++)
                    connection.disconnect()
                    try {
                        Thread.sleep(delayMillis)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Model download retry was interrupted.", interrupted)
                    }
                    continue
                }
                if (status == HTTP_RANGE_NOT_SATISFIABLE && offset > 0L) {
                    val total = UNSATISFIED_RANGE.matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                        ?.groupValues?.get(1)?.toLongOrNull()
                    val expectedLengthMatches = total == offset &&
                        (expectedTotalBytes == null || expectedTotalBytes == total)
                    val expectedHashMatches = expectedSha256 == null || sha256(partial) == expectedSha256.lowercase()
                    if (expectedLengthMatches && expectedHashMatches) {
                        expectedTotalBytes = total
                        break
                    }
                    if (attempt++ >= MAX_RESTARTS) throw IOException("Model source repeatedly rejected the resume range.")
                    partial.delete()
                    continue
                }
                var segmentEndExclusive: Long? = null
                val append = when (status) {
                    HTTP_PARTIAL -> {
                        val range = validateContentRange(
                            connection.getHeaderField("Content-Range"),
                            offset,
                            connection.contentLengthLong,
                            expectedTotalBytes,
                        )
                        if (range.totalBytes != null) expectedTotalBytes = range.totalBytes
                        else if (expectedTotalBytes == null) {
                            throw IOException("Model source did not provide a complete artifact length.")
                        }
                        segmentEndExclusive = range.endInclusive + 1L
                        true
                    }
                    HTTP_OK -> {
                        val responseLength = connection.contentLengthLong.takeIf { it >= 0L }
                        if (responseLength != null && expectedTotalBytes != null && responseLength != expectedTotalBytes) {
                            throw IOException("Model source response length does not match the inspected artifact size.")
                        }
                        if (expectedTotalBytes == null) expectedTotalBytes = responseLength
                        false
                    }
                    else -> throw IOException("Model download failed with HTTP $status.")
                }
                val responseBytesExpected = segmentEndExclusive?.minus(offset)
                    ?: connection.contentLengthLong.takeIf { it >= 0L }
                var receivedBytes = if (append) offset else 0L
                var responseBytesReceived = 0L
                onProgress(receivedBytes, expectedTotalBytes)
                FileOutputStream(partial, append).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedIOException("Model download was cancelled.")
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            responseBytesReceived += count
                            if (responseBytesExpected != null && responseBytesReceived > responseBytesExpected) {
                                throw IOException("Model source sent more bytes than its response range declared.")
                            }
                            output.write(buffer, 0, count)
                            receivedBytes += count
                            onProgress(receivedBytes, expectedTotalBytes)
                        }
                    }
                    output.fd.sync()
                }
                if (responseBytesExpected != null && responseBytesReceived != responseBytesExpected) {
                    throw IOException("Model source ended before its response range was complete.")
                }
                val actualBytes = partial.length()
                if (expectedTotalBytes != null && actualBytes > expectedTotalBytes) {
                    throw IOException("Downloaded model artifact exceeds its expected size.")
                }
                if (status != HTTP_PARTIAL || actualBytes == expectedTotalBytes) break
            } finally {
                connection.disconnect()
            }
        }

        if (!partial.isFile || (expectedTotalBytes != null && partial.length() != expectedTotalBytes) ||
            (expectedSha256 == null && expectedTotalBytes == null) ||
            (expectedSha256 != null && sha256(partial) != expectedSha256.lowercase())
        ) {
            partial.delete()
            throw IOException("Downloaded model artifact failed size or SHA-256 verification.")
        }
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return destination
    }

    private fun openDownloadConnection(initialUrl: URL, bearerToken: String?, offset: Long): HttpURLConnection {
        var target = initialUrl
        val credentialOrigin = origin(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val loopbackTestUrl = allowLoopbackHttpForTests && target.protocol == "http" &&
                target.host in LOOPBACK_TEST_HOSTS
            if ((!loopbackTestUrl && target.protocol != "https") ||
                (!loopbackTestUrl && !isAllowedDownloadHost(target.host))
            ) {
                throw IOException("Model download URL is outside the approved HTTPS hosts.")
            }
            val opened = target.openConnection()
            if (opened !is HttpURLConnection) throw IOException("Model source must use HTTPS.")
            val connection = opened
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MILLIS
            connection.readTimeout = DOWNLOAD_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
            if (origin(target) == credentialOrigin) {
                bearerToken?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            }
            val status = connection.responseCode
            if (status !in HTTP_REDIRECTS) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank() || redirectCount == MAX_REDIRECTS) {
                throw IOException("Model source returned an invalid or excessive redirect chain.")
            }
            target = URL(target, location)
        }
        throw IOException("Model source returned an excessive redirect chain.")
    }

    private fun validateContentRange(
        value: String?,
        offset: Long,
        contentLength: Long,
        expectedTotalBytes: Long?,
    ): ContentRange {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty())
            ?: throw IOException("Model source returned an invalid resume range.")
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        val totalBytes = total ?: expectedTotalBytes
        if (start != offset || end == null || end < offset ||
            (contentLength >= 0L && end - offset + 1L != contentLength) ||
            (totalBytes != null && (totalBytes <= end || (expectedTotalBytes != null && totalBytes != expectedTotalBytes)))
        ) {
            throw IOException("Model source returned a resume range at the wrong offset.")
        }
        return ContentRange(end, total)
    }

    private fun retryDelayMillis(retryAfter: String?, retry: Int): Long {
        val seconds = retryAfter?.toLongOrNull()
        if (seconds != null) return seconds.coerceIn(0L, MAX_RETRY_AFTER_SECONDS) * 1_000L
        val serverDateDelay = retryAfter?.let { value ->
            runCatching {
                val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                java.time.Duration.between(ZonedDateTime.now(retryAt.zone), retryAt).toMillis()
                    .coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
            }.getOrNull()
        }
        if (serverDateDelay != null) return serverDateDelay
        val ceiling = (BASE_RETRY_DELAY_MILLIS shl retry).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        return ThreadLocalRandom.current().nextLong(ceiling / 2L, ceiling + 1L)
    }

    private fun origin(url: URL): String = "${url.protocol.lowercase()}://${url.host.lowercase()}:${url.port.takeIf { it >= 0 } ?: url.defaultPort}"

    private data class ContentRange(val endInclusive: Long, val totalBytes: Long?)

    private fun isAllowedDownloadHost(host: String): Boolean = APPROVED_DOWNLOAD_HOSTS.any { allowed ->
        host.equals(allowed, ignoreCase = true) || host.endsWith(".$allowed", ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val DOWNLOAD_TIMEOUT_MILLIS = 60_000
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_RESTARTS = 1
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_TRANSIENT_RETRIES = 3
        const val BASE_RETRY_DELAY_MILLIS = 500L
        const val MAX_RETRY_DELAY_MILLIS = 8_000L
        const val MAX_RETRY_AFTER_SECONDS = 30L
        const val MAX_RETRY_AFTER_MILLIS = 30_000L
        const val MAX_REDIRECTS = 5
        val HTTP_REDIRECTS = 300..399
        val HTTP_SERVER_ERRORS = 500..599
        val CONTENT_RANGE = Regex("(?i)bytes (\\d+)-(\\d+)/(\\d+|\\*)")
        val UNSATISFIED_RANGE = Regex("(?i)bytes \\*/(\\d+)")
        val APPROVED_DOWNLOAD_HOSTS = setOf("huggingface.co", "hf.co", "modelscope.cn")
        val LOOPBACK_TEST_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
