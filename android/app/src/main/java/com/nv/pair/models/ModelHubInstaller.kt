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
)

class ResumableModelDownloader(private val allowLoopbackHttpForTests: Boolean = false) {
    fun download(
        url: String,
        destination: File,
        expectedSha256: String?,
        bearerToken: String? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): File {
        require(expectedSha256 == null || isTrustedSha256(expectedSha256)) { "A valid SHA-256 checksum is required when supplied." }
        val parent = destination.parentFile ?: throw IOException("Model artifact destination has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create model download directory.")
        val partial = File(parent, "${destination.name}.part")
        var attempt = 0
        var transientRetries = 0
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
                    if (total == offset && (expectedSha256 == null || sha256(partial) == expectedSha256.lowercase())) break
                    if (attempt++ >= MAX_RESTARTS) throw IOException("Model source repeatedly rejected the resume range.")
                    partial.delete()
                    continue
                }
                val append = when {
                    offset > 0L && status == HTTP_PARTIAL -> {
                        validateContentRange(
                            connection.getHeaderField("Content-Range"),
                            offset,
                            connection.contentLengthLong,
                        )
                        true
                    }
                    status == HTTP_OK -> false
                    else -> throw IOException("Model download failed with HTTP $status.")
                }
                val artifactTotal = connection.contentLengthLong.takeIf { it >= 0L }
                    ?.let { contentLength -> if (append) offset + contentLength else contentLength }
                var receivedBytes = if (append) offset else 0L
                onProgress(receivedBytes, artifactTotal)
                FileOutputStream(partial, append).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedIOException("Model download was cancelled.")
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            receivedBytes += count
                            onProgress(receivedBytes, artifactTotal)
                        }
                    }
                    output.fd.sync()
                }
                break
            } finally {
                connection.disconnect()
            }
        }

        if (!partial.isFile || (expectedSha256 != null && sha256(partial) != expectedSha256.lowercase())) {
            partial.delete()
            throw IOException("Downloaded model artifact failed SHA-256 verification.")
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

    private fun validateContentRange(value: String?, offset: Long, contentLength: Long) {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty())
            ?: throw IOException("Model source returned an invalid resume range.")
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        if (start != offset || end == null || end < offset ||
            (contentLength >= 0L && end - offset + 1L != contentLength) ||
            (total != null && total <= end)
        ) {
            throw IOException("Model source returned a resume range at the wrong offset.")
        }
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
        require(!target.exists()) { "A model with this ID is already installed." }
        val staging = File(modelRoot, ".$modelId.downloading")
        require(staging.canonicalFile.parentFile == modelRoot.canonicalFile) { "Model staging directory must stay inside the private model root." }
        val task = loadOrCreateDownloadTask(staging, descriptor, modelId)

        val config = descriptor.files.firstOrNull { it.path == CONFIG_FILE }
            ?: throw IOException("The catalog entry does not include config.json.")
        downloadArtifact(descriptor, adapter, staging, task, config, 0L, null, onProgress)
        val configOnDisk = File(staging, CONFIG_FILE)
        if (configOnDisk.length() > MAX_CONFIG_BYTES) throw IOException("The model config exceeds the 1 MiB size limit.")
        val configJson = try {
            JSONObject(configOnDisk.readText())
        } catch (_: JSONException) {
            throw IOException("The model config is not valid JSON.")
        }
        val requiredArtifacts = requiredArtifactNames(configJson).map { name ->
            val file = descriptor.files.firstOrNull { it.path == name }
                ?: throw IOException("The catalog entry is missing a required MNN artifact: $name.")
            file
        }
        if (requiredArtifacts.map(ModelFile::path) != descriptor.requiredArtifactPaths) {
            throw IOException("The inspected artifact set no longer matches config.json.")
        }
        val allArtifacts = listOf(config) + requiredArtifacts
        val totalBytes = allArtifacts.map(ModelFile::sizeBytes).takeIf { sizes -> sizes.all { it != null } }
            ?.sumOf { it ?: 0L }
        var completedBytes = configOnDisk.length()
        requiredArtifacts.forEach { artifact ->
            downloadArtifact(descriptor, adapter, staging, task, artifact, completedBytes, totalBytes, onProgress)
            completedBytes += File(staging, artifact.path).length()
        }
        when (val resolved = modelManager.resolve(staging, modelId, descriptor.displayName)) {
            is MnnResult.Success -> Unit
            is MnnResult.Failure -> throw IOException("Downloaded files are not a valid MNN model: ${resolved.error.message}")
        }
        writeProvenanceManifest(staging, descriptor, allArtifacts)
        if (!File(staging, DOWNLOAD_TASK_FILE).delete()) throw IOException("Could not finalize the model download task.")
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return descriptor.copy(
            logicalId = "local:$modelId",
            engineModelId = modelId,
            source = ModelSource(ModelSourceKind.LOCAL, target.absolutePath),
            estimatedMemoryBytes = descriptor.estimatedMemoryBytes
                ?: target.walkTopDown().filter(File::isFile).sumOf(File::length),
            compatibility = ModelCompatibility.COMPATIBLE,
        )
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
        ) { received, artifactTotal ->
            onProgress(
                ModelDownloadProgress(
                    artifactPath = artifact.path,
                    artifactBytesReceived = received,
                    artifactTotalBytes = artifactTotal ?: artifact.sizeBytes,
                    overallBytesReceived = completedBytes + received,
                    overallTotalBytes = overallTotalBytes,
                ),
            )
        }
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
            receipt.sourceSha256 == expectedSha256?.lowercase()
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
        if (manifest.optInt("schemaVersion") != DOWNLOAD_TASK_SCHEMA_VERSION ||
            manifest.optString("provider") != descriptor.source.kind.name ||
            manifest.optString("repository") != descriptor.source.repository ||
            manifest.optString("revision") != descriptor.source.revision ||
            manifest.optString("modelId") != modelId ||
            requiredArtifactPaths != descriptor.requiredArtifactPaths ||
            storedSourceHashes != expectedSources
        ) {
            throw IOException("The existing download task belongs to a different model source or revision.")
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
        )
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
            .put("requiredArtifactPaths", org.json.JSONArray(task.requiredArtifactPaths))
            .put("sourceSha256ByPath", sources)
            .put("completedArtifacts", completed)
        val destination = File(staging, DOWNLOAD_TASK_FILE)
        val temporary = File(staging, "$DOWNLOAD_TASK_FILE.tmp")
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
            .put("schemaVersion", 1)
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
        const val DOWNLOAD_TASK_SCHEMA_VERSION = 1
        const val MAX_CONFIG_BYTES = 1024 * 1024L
        val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
    }
}
