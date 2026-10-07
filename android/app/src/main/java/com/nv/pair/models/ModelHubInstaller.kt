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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject

class ResumableModelDownloader {
    fun download(url: String, destination: File, expectedSha256: String, bearerToken: String? = null): File {
        require(expectedSha256.matches(SHA256_PATTERN)) { "A valid SHA-256 checksum is required." }
        val parent = destination.parentFile ?: throw IOException("Model artifact destination has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create model download directory.")
        val partial = File(parent, "${destination.name}.part")
        val offset = partial.length()
        val opened = URL(url).openConnection()
        if (opened !is HttpURLConnection) throw IOException("Model source must use HTTP or HTTPS.")
        val connection = opened
        connection.connectTimeout = DOWNLOAD_TIMEOUT_MILLIS
        connection.readTimeout = DOWNLOAD_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
        bearerToken?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        try {
            val status = connection.responseCode
            val append = when {
                offset > 0L && status == HTTP_PARTIAL -> {
                    val range = connection.getHeaderField("Content-Range")
                    if (range?.startsWith("bytes $offset-", ignoreCase = true) != true) {
                        throw IOException("Model source returned an invalid resume range.")
                    }
                    true
                }
                status == HTTP_OK -> false
                else -> throw IOException("Model download failed with HTTP $status.")
            }
            FileOutputStream(partial, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
        } finally {
            connection.disconnect()
        }

        if (!partial.isFile || sha256(partial) != expectedSha256.lowercase()) {
            partial.delete()
            throw IOException("Downloaded model artifact failed SHA-256 verification.")
        }
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
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
        const val DOWNLOAD_TIMEOUT_MILLIS = 60_000
        const val COPY_BUFFER_BYTES = 64 * 1024
        val SHA256_PATTERN = Regex("(?i)[0-9a-f]{64}")
    }
}

class ModelHubInstaller(
    private val modelRoot: File,
    private val downloader: ResumableModelDownloader = ResumableModelDownloader(),
    private val modelManager: MnnModelManager = MnnModelManager(),
) {
    fun installMnnModel(descriptor: ModelDescriptor, adapter: ModelSourceAdapter): ModelDescriptor {
        require(descriptor.format == ModelFormat.MNN) { "Only MNN models can be installed on this device." }
        require(descriptor.source.kind == adapter.kind) { "The model source adapter does not match the catalog entry." }
        require(MODEL_ID.matches(installedModelId(descriptor))) { "The model repository must end in a safe model ID." }
        if (!modelRoot.exists() && !modelRoot.mkdirs()) throw IOException("Could not create the private model directory.")
        val modelId = installedModelId(descriptor)
        val target = File(modelRoot, modelId)
        require(target.canonicalFile.parentFile == modelRoot.canonicalFile) { "Model target must stay inside the private model root." }
        require(!target.exists()) { "A model with this ID is already installed." }
        val staging = File(modelRoot, ".$modelId.downloading")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdir()) throw IOException("Could not create private model staging directory.")

        try {
            val config = descriptor.files.firstOrNull { it.path == CONFIG_FILE }
                ?: throw IOException("The catalog entry does not include config.json.")
            downloadArtifact(descriptor, adapter, staging, config)
            val configJson = try {
                JSONObject(File(staging, CONFIG_FILE).readText())
            } catch (_: JSONException) {
                throw IOException("The model config is not valid JSON.")
            }
            requiredArtifactNames(configJson).forEach { name ->
                val file = descriptor.files.firstOrNull { it.path == name }
                    ?: throw IOException("The catalog entry is missing a required MNN artifact: $name.")
                downloadArtifact(descriptor, adapter, staging, file)
            }
            when (val resolved = modelManager.resolve(staging, modelId, descriptor.displayName)) {
                is MnnResult.Success -> Unit
                is MnnResult.Failure -> throw IOException("Downloaded files are not a valid MNN model: ${resolved.error.message}")
            }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return descriptor.copy(
                logicalId = "local:$modelId",
                engineModelId = modelId,
                source = ModelSource(ModelSourceKind.LOCAL, target.absolutePath),
                estimatedMemoryBytes = descriptor.estimatedMemoryBytes
                    ?: target.walkTopDown().filter(File::isFile).sumOf(File::length),
                compatibility = ModelCompatibility.COMPATIBLE,
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
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
        artifact: ModelFile,
    ) {
        require(isSafeRelativePath(artifact.path)) { "Model artifact path must stay inside the staging directory." }
        val checksum = artifact.sha256 ?: throw IOException("The catalog entry has no SHA-256 checksum for ${artifact.path}.")
        val destination = File(staging, artifact.path)
        require(destination.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
            "Model artifact path must stay inside the staging directory."
        }
        downloader.download(
            adapter.fileUrl(descriptor.source.repository, descriptor.source.revision, artifact.path).toString(),
            destination,
            checksum,
            adapter.accessToken(),
        )
    }

    private fun requiredArtifactNames(config: JSONObject): List<String> = listOf(
        config.optString("llm_model").ifBlank { DEFAULT_MODEL_FILE },
        config.optString("llm_weight").ifBlank { DEFAULT_WEIGHT_FILE },
        config.optString("tokenizer_file").ifBlank { DEFAULT_TOKENIZER_FILE },
    ).onEach { name -> require(isSafeRelativePath(name)) { "MNN config artifact paths must stay inside the model directory." } }
        .distinct()

    private fun installedModelId(descriptor: ModelDescriptor): String = descriptor.engineModelId.substringAfterLast('/')

    private fun isSafeRelativePath(value: String): Boolean = value.isNotBlank() &&
        !value.startsWith('/') &&
        value.split('/').all { it.isNotBlank() && it != "." && it != ".." && SAFE_PATH_SEGMENT.matches(it) }

    private companion object {
        const val CONFIG_FILE = "config.json"
        const val DEFAULT_MODEL_FILE = "llm.mnn"
        const val DEFAULT_WEIGHT_FILE = "llm.mnn.weight"
        const val DEFAULT_TOKENIZER_FILE = "tokenizer.txt"
        val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
    }
}
