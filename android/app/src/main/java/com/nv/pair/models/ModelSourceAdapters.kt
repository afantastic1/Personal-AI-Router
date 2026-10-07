/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

interface ModelSourceAdapter {
    val kind: ModelSourceKind
    fun parseSearchResponse(response: String): List<ModelDescriptor>
    fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<ModelDescriptor>
    fun fileUrl(repository: String, revision: String, path: String): URL
    fun accessToken(): String?

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 20
    }
}

class HuggingFaceAdapter(
    private val apiBase: String = HUGGING_FACE_API,
    private val bearerToken: String? = null,
) : ModelSourceAdapter {
    override val kind = ModelSourceKind.HUGGING_FACE
    override fun accessToken(): String? = bearerToken

    override fun search(query: String, limit: Int): List<ModelDescriptor> {
        require(limit in 1..MAX_SEARCH_LIMIT)
        val url = URL("$apiBase/models?search=${encode(query)}&limit=$limit&full=true")
        return fetch(url, bearerToken, ::parseSearchResponse)
    }

    override fun fileUrl(repository: String, revision: String, path: String): URL = URL(
        "https://huggingface.co/${repository.split('/').joinToString("/") { encode(it) }}/resolve/${encode(revision)}/${encodePath(path)}",
    )

    override fun parseSearchResponse(response: String): List<ModelDescriptor> {
        val rows = JSONArray(response)
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let(::descriptor)
        }
    }

    private fun descriptor(row: JSONObject): ModelDescriptor? {
        val repository = row.optString("id").trim().takeIf(String::isNotEmpty) ?: return null
        val tags = strings(row.optJSONArray("tags"))
        val files = row.optJSONArray("siblings")?.let(::huggingFaceFiles).orEmpty()
        return modelDescriptor(
            kind = kind,
            repository = repository,
            displayName = repository.substringAfterLast('/'),
            description = row.optString("pipeline_tag"),
            tags = tags,
            files = files,
        )
    }

    private fun huggingFaceFiles(rows: JSONArray): List<ModelFile> = (0 until rows.length()).mapNotNull { index ->
        val file = rows.optJSONObject(index) ?: return@mapNotNull null
        val path = file.optString("rfilename").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val size = file.optLong("size", -1L).takeIf { it >= 0L }
        val lfs = file.optJSONObject("lfs")
        val hash = (lfs?.optString("oid") ?: file.optString("oid")).removePrefix("sha256:").takeIf(::isSha256)
        ModelFile(path, size, hash)
    }

    companion object {
        private const val HUGGING_FACE_API = "https://huggingface.co/api"
        private const val MAX_SEARCH_LIMIT = 100
    }
}

class ModelScopeAdapter(
    private val apiBase: String = MODELSCOPE_API,
    private val bearerToken: String? = null,
) : ModelSourceAdapter {
    override val kind = ModelSourceKind.MODELSCOPE
    override fun accessToken(): String? = bearerToken

    override fun search(query: String, limit: Int): List<ModelDescriptor> {
        require(limit in 1..MAX_SEARCH_LIMIT)
        val url = URL("$apiBase/models?PageNumber=1&PageSize=$limit&Name=${encode(query)}")
        return fetch(url, bearerToken, ::parseSearchResponse).map { model ->
            if (model.files.isNotEmpty()) model else model.copy(files = listFiles(model.source.repository, model.source.revision))
        }
    }

    override fun fileUrl(repository: String, revision: String, path: String): URL = URL(
        "https://modelscope.cn/models/${repository.split('/').joinToString("/") { encode(it) }}/resolve/${encode(revision)}/${encodePath(path)}",
    )

    private fun listFiles(repository: String, revision: String): List<ModelFile> {
        val repoPath = repository.split('/').joinToString("/") { encode(it) }
        val url = URL("$apiBase/models/$repoPath/repo/files?Revision=${encode(revision)}&Recursive=true")
        return fetch(url, bearerToken) { response ->
            val root = JSONObject(response)
            val data = root.optJSONObject("Data") ?: root.optJSONObject("data") ?: JSONObject()
            val rows = data.optJSONArray("Files") ?: data.optJSONArray("files")
                ?: data.optJSONArray("FileList") ?: data.optJSONArray("file_list") ?: JSONArray()
            modelScopeFiles(rows)
        }
    }

    override fun parseSearchResponse(response: String): List<ModelDescriptor> {
        val root = JSONObject(response)
        val data = root.optJSONObject("Data") ?: root.optJSONObject("data") ?: JSONObject()
        val rows = data.optJSONArray("Models") ?: data.optJSONArray("models") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let(::descriptor)
        }
    }

    private fun descriptor(row: JSONObject): ModelDescriptor? {
        val repository = (row.optString("Path").ifBlank { row.optString("path") })
            .trim().takeIf(String::isNotEmpty) ?: return null
        val tags = strings(row.optJSONArray("Tags") ?: row.optJSONArray("tags"))
        val files = modelScopeFiles(row.optJSONArray("Files") ?: row.optJSONArray("files"))
        return modelDescriptor(
            kind = kind,
            repository = repository,
            displayName = row.optString("Name").ifBlank { row.optString("name") }.ifBlank { repository.substringAfterLast('/') },
            description = row.optString("Description").ifBlank { row.optString("description") },
            tags = tags,
            files = files,
        )
    }

    private fun modelScopeFiles(rows: JSONArray?): List<ModelFile> = rows?.let { files ->
        (0 until files.length()).mapNotNull { index ->
            val file = files.optJSONObject(index) ?: return@mapNotNull null
            val path = (file.optString("Name").ifBlank { file.optString("name") }
                .ifBlank { file.optString("Path").ifBlank { file.optString("path") } }
                .ifBlank { file.optString("FileName").ifBlank { file.optString("file_name") } })
                .trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val size = file.optLong("Size", file.optLong("size", -1L)).takeIf { it >= 0L }
            val hash = file.optString("Sha256").ifBlank { file.optString("sha256") }
                .removePrefix("sha256:").lowercase().takeIf(::isSha256)
            ModelFile(path, size, hash)
        }
    }.orEmpty()

    companion object {
        private const val MODELSCOPE_API = "https://modelscope.cn/api/v1"
        private const val MAX_SEARCH_LIMIT = 100
    }
}

private fun <T> fetch(
    url: URL,
    bearerToken: String?,
    parse: (String) -> T,
): T {
    val opened = url.openConnection()
    if (opened !is HttpURLConnection) throw IOException("Model catalog endpoint must use HTTP or HTTPS.")
    val connection = opened
    connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
    connection.readTimeout = NETWORK_TIMEOUT_MILLIS
    connection.setRequestProperty("Accept", "application/json")
    bearerToken?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
    try {
        if (connection.responseCode !in 200..299) {
            throw IOException("Model catalog request failed with HTTP ${connection.responseCode}.")
        }
        return connection.inputStream.bufferedReader().use { parse(it.readText()) }
    } finally {
        connection.disconnect()
    }
}

private fun modelDescriptor(
    kind: ModelSourceKind,
    repository: String,
    displayName: String,
    description: String,
    tags: List<String>,
    files: List<ModelFile>,
): ModelDescriptor {
    val searchable = "$repository $displayName $description ${tags.joinToString(" ")}"
    val format = when {
        files.any { it.path.endsWith(".mnn", ignoreCase = true) } -> ModelFormat.MNN
        files.any { it.path.endsWith(".gguf", ignoreCase = true) } -> ModelFormat.GGUF
        files.any { it.path.endsWith(".onnx", ignoreCase = true) } -> ModelFormat.ONNX
        else -> ModelFormat.UNKNOWN
    }
    val parameters = PARAMETER_PATTERN.find(searchable)?.groupValues?.get(1)?.toDoubleOrNull()
        ?.let { (it * PARAMETERS_PER_BILLION).toLong() }
    val quantization = QUANTIZATION_PATTERN.find(searchable)?.value
    val context = CONTEXT_PATTERN.find(searchable)?.groupValues?.get(1)?.toIntOrNull()
    val memory = parameters?.let { (it * BYTES_PER_PARAMETER).toLong() }
    return ModelDescriptor(
        logicalId = "${kind.name.lowercase()}:$repository",
        engineModelId = repository,
        displayName = displayName,
        family = familyFor(searchable),
        parameterCount = parameters,
        quantization = quantization,
        contextLength = context,
        source = ModelSource(kind, repository),
        format = format,
        estimatedMemoryBytes = memory,
        compatibility = if (format == ModelFormat.MNN) ModelCompatibility.COMPATIBLE else ModelCompatibility.UNKNOWN,
        files = files,
    )
}

private fun familyFor(value: String): String = FAMILY_PATTERN.find(value)?.value?.lowercase() ?: "unknown"

private fun strings(values: JSONArray?): List<String> = values?.let { array ->
    (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
}.orEmpty()

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun encodePath(value: String): String = value.split('/').joinToString("/") { encode(it) }

private fun isSha256(value: String): Boolean = value.matches(Regex("(?i)[0-9a-f]{64}"))

private const val NETWORK_TIMEOUT_MILLIS = 15_000
private const val PARAMETERS_PER_BILLION = 1_000_000_000.0
private const val BYTES_PER_PARAMETER = 2.4
private val PARAMETER_PATTERN = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*[- ]?B(?:\\b|$)")
private val QUANTIZATION_PATTERN = Regex("(?i)(?:Q[234568]_[A-Z0-9_]+|INT[248])")
private val CONTEXT_PATTERN = Regex("(?i)(?:context|ctx)[-_ ]?([0-9]{3,7})")
private val FAMILY_PATTERN = Regex("(?i)qwen|llama|gemma|mistral|deepseek|phi|smollm|internlm|glm")
