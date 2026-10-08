/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import com.nv.pair.mnn.MnnModelManager
import com.nv.pair.mnn.MnnResult
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
import org.json.JSONException
import org.json.JSONObject

data class ModelDownloadProgress(
    val artifactPath: String,
    val artifactBytesReceived: Long,
    val artifactTotalBytes: Long?,
    val overallBytesReceived: Long,
    val overallTotalBytes: Long?,
)

private data class DownloadedArtifactReceipt(
    val sizeBytes: Long,
    val installedSha256: String,
    val sourceSha256: String?,
)

private data class ModelDownloadTaskState(
    val provider: String,
    val repository: String,
    val revision: String,
    val modelId: String,
    val requiredArtifactPaths: List<String>,
    val sourceSha256ByPath: Map<String, String>,
    val completedArtifacts: MutableMap<String, DownloadedArtifactReceipt>,
    var phase: ModelInstallPhase,
)

private enum class ModelInstallPhase {
    DOWNLOADING,
    VERIFIED,
    PUBLISHING,
    INSTALLED,
}

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

class ModelHubInstaller(
    private val modelRoot: File,
    private val downloader: ResumableModelDownloader = ResumableModelDownloader(),
    private val modelManager: MnnModelManager = MnnModelManager(),
) {
    fun installMnnModel(
        descriptor: ModelDescriptor,
        adapter: ModelSourceAdapter,
        allowUnverifiedSource: Boolean = false,
        onProgress: (ModelDownloadProgress) -> Unit = {},
    ): ModelDescriptor {
        require(descriptor.format == ModelFormat.MNN) { "Only MNN models can be installed on this device." }
        require(descriptor.source.kind == adapter.kind) { "The model source adapter does not match the catalog entry." }
        val installability = descriptor.installability()
        require(installability == ModelInstallability.VERIFIED_INSTALLABLE ||
            (installability == ModelInstallability.LOCAL_DIGEST_ONLY && allowUnverifiedSource)
        ) { "The model source is not eligible for installation." }
        require(MODEL_ID.matches(installedModelId(descriptor))) { "The model repository must end in a safe model ID." }
        if (!modelRoot.exists() && !modelRoot.mkdirs()) throw IOException("Could not create the private model directory.")
        val modelId = installedModelId(descriptor)
        val target = File(modelRoot, modelId)
        require(target.canonicalFile.parentFile == modelRoot.canonicalFile) { "Model target must stay inside the private model root." }
        if (target.exists()) return recoverPublishedInstall(target, descriptor, modelId)
        val staging = File(modelRoot, ".$modelId.downloading")
        require(staging.canonicalFile.parentFile == modelRoot.canonicalFile) { "Model staging directory must stay inside the private model root." }
        val task = loadOrCreateDownloadTask(staging, descriptor, modelId)

        if (task.phase == ModelInstallPhase.DOWNLOADING) {
            val allArtifacts = resolveRequiredArtifacts(staging, descriptor, requireConfig = false)
            val config = allArtifacts.first()
            downloadArtifact(descriptor, adapter, staging, task, config, 0L, null, onProgress)
            val resolvedArtifacts = resolveRequiredArtifacts(staging, descriptor)
            val totalBytes = resolvedArtifacts.map(ModelFile::sizeBytes).takeIf { sizes -> sizes.all { it != null } }
                ?.sumOf { it ?: 0L }
            var completedBytes = File(staging, CONFIG_FILE).length()
            resolvedArtifacts.drop(1).forEach { artifact ->
                downloadArtifact(descriptor, adapter, staging, task, artifact, completedBytes, totalBytes, onProgress)
                completedBytes += File(staging, artifact.path).length()
            }
            validateMnnPackage(staging, descriptor, modelId)
            writeProvenanceManifest(staging, descriptor, resolvedArtifacts)
            task.phase = ModelInstallPhase.VERIFIED
            writeDownloadTask(staging, task)
        }

        if (task.phase != ModelInstallPhase.VERIFIED && task.phase != ModelInstallPhase.PUBLISHING) {
            throw IOException("The model download task has an invalid install phase.")
        }
        validateMnnPackage(staging, descriptor, modelId)
        validateProvenanceManifest(staging, descriptor)
        if (task.phase == ModelInstallPhase.VERIFIED) {
            task.phase = ModelInstallPhase.PUBLISHING
            writeDownloadTask(staging, task)
        }
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (publishFailure: IOException) {
            if (target.exists()) return recoverPublishedInstall(target, descriptor, modelId)
            throw publishFailure
        }
        task.phase = ModelInstallPhase.INSTALLED
        runCatching { writeDownloadTask(target, task) }
        runCatching { File(target, DOWNLOAD_TASK_FILE).delete() }
        return installedDescriptor(target, descriptor, modelId)
    }

    private fun installedDescriptor(target: File, descriptor: ModelDescriptor, modelId: String): ModelDescriptor =
        descriptor.copy(
            logicalId = "local:$modelId",
            engineModelId = modelId,
            source = ModelSource(ModelSourceKind.LOCAL, target.absolutePath),
            estimatedMemoryBytes = descriptor.estimatedMemoryBytes
                ?: target.walkTopDown().filter(File::isFile).sumOf(File::length),
            compatibility = ModelCompatibility.COMPATIBLE,
        )

    private fun recoverPublishedInstall(target: File, descriptor: ModelDescriptor, modelId: String): ModelDescriptor {
        validateMnnPackage(target, descriptor, modelId)
        validateProvenanceManifest(target, descriptor)
        val taskFile = File(target, DOWNLOAD_TASK_FILE)
        if (taskFile.isFile) {
            val manifest = try {
                JSONObject(taskFile.readText())
            } catch (_: JSONException) {
                throw IOException("The published model has invalid recovery metadata.")
            }
            if (manifest.optInt("schemaVersion") != DOWNLOAD_TASK_SCHEMA_VERSION ||
                manifest.optString("provider") != descriptor.source.kind.name ||
                manifest.optString("repository") != descriptor.source.repository ||
                manifest.optString("revision") != descriptor.source.revision ||
                manifest.optString("modelId") != modelId ||
                manifest.optString("phase") !in setOf(ModelInstallPhase.PUBLISHING.name, ModelInstallPhase.INSTALLED.name)
            ) {
                throw IOException("The published model recovery metadata does not match this source revision.")
            }
            manifest.put("phase", ModelInstallPhase.INSTALLED.name)
            runCatching { writeJsonAtomically(taskFile, manifest) }
            runCatching { taskFile.delete() }
        }
        return installedDescriptor(target, descriptor, modelId)
    }

    private fun resolveRequiredArtifacts(
        root: File,
        descriptor: ModelDescriptor,
        requireConfig: Boolean = true,
    ): List<ModelFile> {
        val config = descriptor.files.firstOrNull { it.path == CONFIG_FILE }
            ?: throw IOException("The catalog entry does not include config.json.")
        val configFile = File(root, CONFIG_FILE)
        if (!configFile.isFile) {
            if (requireConfig) throw IOException("The model staging directory is missing config.json.")
            return listOf(config) + descriptor.requiredArtifactPaths.map { path ->
                descriptor.files.firstOrNull { it.path == path }
                    ?: throw IOException("The catalog entry is missing a required MNN artifact: $path.")
            }
        }
        if (configFile.length() > MAX_CONFIG_BYTES) throw IOException("The model config exceeds the 1 MiB size limit.")
        val configJson = try {
            JSONObject(configFile.readText())
        } catch (_: JSONException) {
            throw IOException("The model config is not valid JSON.")
        }
        val requiredArtifacts = requiredArtifactNames(configJson).map { name ->
            descriptor.files.firstOrNull { it.path == name }
                ?: throw IOException("The catalog entry is missing a required MNN artifact: $name.")
        }
        if (requiredArtifacts.map(ModelFile::path) != descriptor.requiredArtifactPaths) {
            throw IOException("The inspected artifact set no longer matches config.json.")
        }
        return listOf(config) + requiredArtifacts
    }

    private fun validateMnnPackage(root: File, descriptor: ModelDescriptor, modelId: String) {
        val artifacts = resolveRequiredArtifacts(root, descriptor)
        artifacts.forEach { artifact ->
            val file = File(root, artifact.path)
            if (!file.isFile || (artifact.sizeBytes != null && file.length() != artifact.sizeBytes)) {
                throw IOException("Model artifact ${artifact.path} does not match the inspected size.")
            }
            val expectedHash = artifact.sha256?.takeIf(::isTrustedSha256)?.lowercase()
            if (expectedHash != null && installedSha256(file) != expectedHash) {
                throw IOException("Model artifact ${artifact.path} failed SHA-256 verification.")
            }
        }
        when (val resolved = modelManager.resolve(root, modelId, descriptor.displayName)) {
            is MnnResult.Success -> Unit
            is MnnResult.Failure -> throw IOException("Downloaded files are not a valid MNN model: ${resolved.error.message}")
        }
    }

    private fun validateProvenanceManifest(root: File, descriptor: ModelDescriptor) {
        val manifestFile = File(root, PROVENANCE_FILE)
        if (!manifestFile.isFile) throw IOException("The model provenance manifest is missing.")
        val manifest = try {
            JSONObject(manifestFile.readText())
        } catch (_: JSONException) {
            throw IOException("The model provenance manifest is invalid.")
        }
        val artifacts = resolveRequiredArtifacts(root, descriptor)
        if (manifest.optInt("schemaVersion") != PROVENANCE_SCHEMA_VERSION ||
            manifest.optString("provider") != descriptor.source.kind.name ||
            manifest.optString("repository") != descriptor.source.repository ||
            manifest.optString("revision") != descriptor.source.revision
        ) {
            throw IOException("The installed model belongs to a different source revision.")
        }
        val entries = manifest.optJSONArray("files") ?: throw IOException("The model provenance manifest has no files.")
        val recorded = (0 until entries.length()).mapNotNull { index ->
            entries.optJSONObject(index)?.let { entry -> entry.optString("path") to entry }
        }.toMap()
        val expectedDigestStatus = if (artifacts.all { it.sha256?.let(::isTrustedSha256) == true }) {
            "VERIFIED_SOURCE_SHA256"
        } else {
            "UNVERIFIED_SOURCE_DIGEST"
        }
        if (recorded.size != entries.length() || recorded.keys != artifacts.map(ModelFile::path).toSet() ||
            manifest.optString("digestStatus") != expectedDigestStatus
        ) {
            throw IOException("The model provenance manifest does not match the required artifact set.")
        }
        artifacts.forEach { artifact ->
            val file = File(root, artifact.path)
            val entry = recorded.getValue(artifact.path)
            val recordedSize = entry.optLong("sizeBytes", -1L)
            val recordedInstalledHash = entry.optString("installedSha256")
            val recordedSourceHash = entry.optString("sourceSha256").takeIf(String::isNotBlank)?.lowercase()
            val expectedSourceHash = artifact.sha256?.takeIf(::isTrustedSha256)?.lowercase()
            if (!file.isFile || file.length() != recordedSize ||
                (artifact.sizeBytes != null && file.length() != artifact.sizeBytes) ||
                !isTrustedSha256(recordedInstalledHash) || installedSha256(file) != recordedInstalledHash.lowercase() ||
                recordedSourceHash != expectedSourceHash ||
                (expectedSourceHash != null && recordedInstalledHash.lowercase() != expectedSourceHash)
            ) {
                throw IOException("Installed model artifact ${artifact.path} failed provenance verification.")
            }
        }
    }

    fun deleteInstalledModel(modelId: String) {
        require(MODEL_ID.matches(modelId)) { "Model ID must be a safe local identifier." }
        val target = File(modelRoot, modelId)
        require(target.canonicalFile.parentFile == modelRoot.canonicalFile) { "Model target must stay inside the private model root." }
        if (target.exists() && !target.deleteRecursively()) throw IOException("Could not delete the installed model.")
    }

    private fun downloadArtifact(
        descriptor: ModelDescriptor,
        adapter: ModelSourceAdapter,
        staging: File,
        task: ModelDownloadTaskState,
        artifact: ModelFile,
        completedBytes: Long,
        overallTotalBytes: Long?,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        require(isSafeRelativePath(artifact.path)) { "Model artifact path must stay inside the staging directory." }
        require(artifact.sizeBytes == null || artifact.sizeBytes >= 0L) { "Model artifact size cannot be negative." }
        val checksum = artifact.sha256?.takeIf(::isTrustedSha256)
        val destination = File(staging, artifact.path)
        require(destination.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
            "Model artifact path must stay inside the staging directory."
        }
        val parent = destination.parentFile ?: throw IOException("Model artifact destination has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create model artifact directory.")
        if (reuseCompletedArtifact(staging, task, artifact, checksum, destination)) {
            val size = destination.length()
            onProgress(
                ModelDownloadProgress(
                    artifactPath = artifact.path,
                    artifactBytesReceived = size,
                    artifactTotalBytes = size,
                    overallBytesReceived = completedBytes + size,
                    overallTotalBytes = overallTotalBytes,
                ),
            )
            return
        }
        downloader.download(
            adapter.fileUrl(descriptor.source.repository, descriptor.source.revision, artifact.path).toString(),
            destination,
            checksum,
            adapter.accessToken(),
            onProgress = { received, artifactTotal ->
                onProgress(
                    ModelDownloadProgress(
                        artifactPath = artifact.path,
                        artifactBytesReceived = received,
                        artifactTotalBytes = artifactTotal ?: artifact.sizeBytes,
                        overallBytesReceived = completedBytes + received,
                        overallTotalBytes = overallTotalBytes,
                    ),
                )
            },
            expectedSizeBytes = artifact.sizeBytes,
        )
        task.completedArtifacts[artifact.path] = DownloadedArtifactReceipt(
            sizeBytes = destination.length(),
            installedSha256 = installedSha256(destination),
            sourceSha256 = checksum?.lowercase(),
        )
        writeDownloadTask(staging, task)
    }

    private fun reuseCompletedArtifact(
        staging: File,
        task: ModelDownloadTaskState,
        artifact: ModelFile,
        expectedSha256: String?,
        destination: File,
    ): Boolean {
        val receipt = task.completedArtifacts[artifact.path]
        val fileMatchesReceipt = receipt != null && destination.isFile &&
            destination.length() == receipt.sizeBytes && installedSha256(destination) == receipt.installedSha256 &&
            receipt.sourceSha256 == expectedSha256?.lowercase() &&
            (artifact.sizeBytes == null || destination.length() == artifact.sizeBytes)
        if (fileMatchesReceipt && (expectedSha256 == null || receipt.installedSha256 == expectedSha256.lowercase())) {
            return true
        }

        task.completedArtifacts.remove(artifact.path)
        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not replace an incomplete staged model artifact.")
        }
        val partial = File(destination.parentFile, "${destination.name}.part")
        if (receipt != null && expectedSha256 != receipt.sourceSha256 && partial.exists() && !partial.delete()) {
            throw IOException("Could not discard a partial artifact from a different source checksum.")
        }
        if (receipt != null) writeDownloadTask(staging, task)
        return false
    }

    private fun loadOrCreateDownloadTask(
        staging: File,
        descriptor: ModelDescriptor,
        modelId: String,
    ): ModelDownloadTaskState {
        val expectedSources = sourceSha256ByPath(descriptor)
        if (!staging.exists() && !staging.mkdir()) throw IOException("Could not create private model staging directory.")
        if (!staging.isDirectory) throw IOException("The model staging path is not a directory.")

        val file = File(staging, DOWNLOAD_TASK_FILE)
        if (!file.isFile) {
            val temporary = File(staging, "$DOWNLOAD_TASK_FILE.tmp")
            val unexpectedEntries = staging.listFiles().orEmpty().filterNot { entry ->
                entry == temporary && entry.isFile
            }
            if (unexpectedEntries.isNotEmpty()) {
                if (File(staging, PROVENANCE_FILE).isFile) {
                    return recoverVerifiedStagingWithoutTask(staging, descriptor, modelId, expectedSources)
                }
                throw IOException("An incomplete model download has no resumable task metadata.")
            }
            if (temporary.exists() && !temporary.delete()) {
                throw IOException("Could not discard an incomplete model download task file.")
            }
            return newDownloadTask(descriptor, modelId, expectedSources).also { task ->
                writeDownloadTask(staging, task)
            }
        }
        val manifest = try {
            JSONObject(file.readText())
        } catch (_: JSONException) {
            throw IOException("The model download task metadata is invalid.")
        }
        val requiredArtifactPaths = manifest.optJSONArray("requiredArtifactPaths")
            ?.let { paths -> (0 until paths.length()).map(paths::optString) }
            ?: throw IOException("The model download task metadata has no artifact list.")
        val storedSources = manifest.optJSONObject("sourceSha256ByPath")
            ?: throw IOException("The model download task metadata has no source fingerprints.")
        val storedSourceHashes = expectedSources.keys.associateWith { path -> storedSources.optString(path) }
        val schemaVersion = manifest.optInt("schemaVersion")
        if (schemaVersion !in LEGACY_DOWNLOAD_TASK_SCHEMA_VERSION..DOWNLOAD_TASK_SCHEMA_VERSION ||
            manifest.optString("provider") != descriptor.source.kind.name ||
            manifest.optString("repository") != descriptor.source.repository ||
            manifest.optString("revision") != descriptor.source.revision ||
            manifest.optString("modelId") != modelId ||
            requiredArtifactPaths != descriptor.requiredArtifactPaths ||
            storedSourceHashes != expectedSources
        ) {
            throw IOException("The existing download task belongs to a different model source or revision.")
        }
        val phase = if (schemaVersion == LEGACY_DOWNLOAD_TASK_SCHEMA_VERSION) {
            ModelInstallPhase.DOWNLOADING
        } else {
            runCatching { ModelInstallPhase.valueOf(manifest.optString("phase")) }
                .getOrElse { throw IOException("The model download task has an invalid install phase.", it) }
        }

        val completed = mutableMapOf<String, DownloadedArtifactReceipt>()
        val completedFiles = manifest.optJSONArray("completedArtifacts")
            ?: throw IOException("The model download task metadata has no completed artifact list.")
        for (index in 0 until completedFiles.length()) {
            val entry = completedFiles.optJSONObject(index) ?: continue
            val path = entry.optString("path")
            val size = entry.optLong("sizeBytes", -1L)
            val installedHash = entry.optString("installedSha256")
            val sourceHash = entry.optString("sourceSha256").takeIf(String::isNotEmpty)
            if (path in expectedSources && size >= 0L && isTrustedSha256(installedHash) &&
                (sourceHash == null || isTrustedSha256(sourceHash))
            ) {
                completed[path] = DownloadedArtifactReceipt(size, installedHash.lowercase(), sourceHash?.lowercase())
            }
        }
        return ModelDownloadTaskState(
            provider = descriptor.source.kind.name,
            repository = descriptor.source.repository,
            revision = descriptor.source.revision,
            modelId = modelId,
            requiredArtifactPaths = requiredArtifactPaths,
            sourceSha256ByPath = expectedSources,
            completedArtifacts = completed,
            phase = phase,
        )
    }

    private fun recoverVerifiedStagingWithoutTask(
        staging: File,
        descriptor: ModelDescriptor,
        modelId: String,
        expectedSources: Map<String, String>,
    ): ModelDownloadTaskState {
        validateMnnPackage(staging, descriptor, modelId)
        validateProvenanceManifest(staging, descriptor)
        val artifacts = resolveRequiredArtifacts(staging, descriptor)
        val task = newDownloadTask(descriptor, modelId, expectedSources).apply {
            phase = ModelInstallPhase.VERIFIED
            artifacts.forEach { artifact ->
                val file = File(staging, artifact.path)
                completedArtifacts[artifact.path] = DownloadedArtifactReceipt(
                    sizeBytes = file.length(),
                    installedSha256 = installedSha256(file),
                    sourceSha256 = artifact.sha256?.takeIf(::isTrustedSha256)?.lowercase(),
                )
            }
        }
        writeDownloadTask(staging, task)
        return task
    }

    private fun sourceSha256ByPath(descriptor: ModelDescriptor): Map<String, String> =
        (listOf(CONFIG_FILE) + descriptor.requiredArtifactPaths).associateWith { path ->
            descriptor.files.firstOrNull { it.path == path }?.sha256
                ?.takeIf(::isTrustedSha256)?.lowercase().orEmpty()
        }

    private fun newDownloadTask(
        descriptor: ModelDescriptor,
        modelId: String,
        sourceSha256ByPath: Map<String, String>,
    ): ModelDownloadTaskState = ModelDownloadTaskState(
        provider = descriptor.source.kind.name,
        repository = descriptor.source.repository,
        revision = descriptor.source.revision,
        modelId = modelId,
        requiredArtifactPaths = descriptor.requiredArtifactPaths,
        sourceSha256ByPath = sourceSha256ByPath,
        completedArtifacts = mutableMapOf(),
        phase = ModelInstallPhase.DOWNLOADING,
    )

    private fun writeDownloadTask(staging: File, task: ModelDownloadTaskState) {
        val sources = JSONObject()
        task.sourceSha256ByPath.forEach { (path, checksum) -> sources.put(path, checksum) }
        val completed = org.json.JSONArray()
        task.completedArtifacts.toSortedMap().forEach { (path, receipt) ->
            completed.put(JSONObject()
                .put("path", path)
                .put("sizeBytes", receipt.sizeBytes)
                .put("installedSha256", receipt.installedSha256)
                .put("sourceSha256", receipt.sourceSha256.orEmpty()))
        }
        val manifest = JSONObject()
            .put("schemaVersion", DOWNLOAD_TASK_SCHEMA_VERSION)
            .put("provider", task.provider)
            .put("repository", task.repository)
            .put("revision", task.revision)
            .put("modelId", task.modelId)
            .put("phase", task.phase.name)
            .put("requiredArtifactPaths", org.json.JSONArray(task.requiredArtifactPaths))
            .put("sourceSha256ByPath", sources)
            .put("completedArtifacts", completed)
        writeJsonAtomically(File(staging, DOWNLOAD_TASK_FILE), manifest)
    }

    private fun writeJsonAtomically(destination: File, manifest: JSONObject) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun writeProvenanceManifest(
        staging: File,
        descriptor: ModelDescriptor,
        artifacts: List<ModelFile>,
    ) {
        val files = org.json.JSONArray()
        artifacts.forEach { artifact ->
            files.put(JSONObject()
                .put("path", artifact.path)
                .put("sizeBytes", File(staging, artifact.path).length())
                .put("sourceSha256", artifact.sha256?.lowercase())
                .put("installedSha256", installedSha256(File(staging, artifact.path))))
        }
        val manifest = JSONObject()
            .put("schemaVersion", PROVENANCE_SCHEMA_VERSION)
            .put("provider", descriptor.source.kind.name)
            .put("repository", descriptor.source.repository)
            .put("revision", descriptor.source.revision)
            .put("digestStatus", if (artifacts.all { it.sha256?.let(::isTrustedSha256) == true }) "VERIFIED_SOURCE_SHA256" else "UNVERIFIED_SOURCE_DIGEST")
            .put("files", files)
        val file = File(staging, PROVENANCE_FILE)
        FileOutputStream(file).use { output ->
            output.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun requiredArtifactNames(config: JSONObject): List<String> = listOf(
        config.optString("llm_config").ifBlank { DEFAULT_LLM_CONFIG_FILE },
        config.optString("llm_model").ifBlank { DEFAULT_MODEL_FILE },
        config.optString("llm_weight").ifBlank { DEFAULT_WEIGHT_FILE },
        config.optString("tokenizer_file").ifBlank { DEFAULT_TOKENIZER_FILE },
    ).onEach { name -> require(isSafeRelativePath(name)) { "MNN config artifact paths must stay inside the model directory." } }
        .distinct()

    private fun installedSha256(file: File): String {
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

    private fun installedModelId(descriptor: ModelDescriptor): String = descriptor.engineModelId.substringAfterLast('/')

    private fun isSafeRelativePath(value: String): Boolean = value.isNotBlank() &&
        !value.startsWith('/') &&
        value.split('/').all { it.isNotBlank() && it != "." && it != ".." && SAFE_PATH_SEGMENT.matches(it) }

    private companion object {
        const val CONFIG_FILE = "config.json"
        const val DEFAULT_LLM_CONFIG_FILE = "llm_config.json"
        const val DEFAULT_MODEL_FILE = "llm.mnn"
        const val DEFAULT_WEIGHT_FILE = "llm.mnn.weight"
        const val DEFAULT_TOKENIZER_FILE = "tokenizer.txt"
        const val PROVENANCE_FILE = ".pair-model.json"
        const val DOWNLOAD_TASK_FILE = ".pair-download.json"
        const val LEGACY_DOWNLOAD_TASK_SCHEMA_VERSION = 1
        const val DOWNLOAD_TASK_SCHEMA_VERSION = 2
        const val PROVENANCE_SCHEMA_VERSION = 1
        const val MAX_CONFIG_BYTES = 1024 * 1024L
        val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
    }
}
