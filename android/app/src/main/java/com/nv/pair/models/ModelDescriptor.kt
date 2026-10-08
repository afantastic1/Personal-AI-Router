/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

enum class ModelSourceKind { MODELSCOPE, HUGGING_FACE, LOCAL, RUNTIME }

enum class ModelFormat { MNN, GGUF, ONNX, UNKNOWN }

enum class ModelCompatibility { COMPATIBLE, UNKNOWN, INCOMPATIBLE }

enum class ModelInstallability {
    VERIFIED_INSTALLABLE,
    LOCAL_DIGEST_ONLY,
    UNPINNED_SOURCE_REVISION,
    UNSUPPORTED_FORMAT,
    INCOMPLETE_ARTIFACT_SET,
}

data class ModelSource(
    val kind: ModelSourceKind,
    val repository: String,
    val revision: String = if (kind == ModelSourceKind.MODELSCOPE) "master" else "main",
)

data class ModelFile(
    val path: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

data class ModelDescriptor(
    val logicalId: String,
    val engineModelId: String,
    val displayName: String,
    val family: String,
    val parameterCount: Long?,
    val quantization: String?,
    val contextLength: Int?,
    val source: ModelSource,
    val format: ModelFormat,
    val estimatedMemoryBytes: Long?,
    val compatibility: ModelCompatibility,
    val files: List<ModelFile> = emptyList(),
    val requiredArtifactPaths: List<String> = emptyList(),
    val description: String = "",
    val tags: List<String> = emptyList(),
)

fun ModelDescriptor.installability(): ModelInstallability {
    if (format != ModelFormat.MNN) return ModelInstallability.UNSUPPORTED_FORMAT
    if (!IMMUTABLE_REVISION.matches(source.revision)) return ModelInstallability.UNPINNED_SOURCE_REVISION

    val filesByPath = files.associateBy(ModelFile::path)
    if (requiredArtifactPaths.isEmpty()) return ModelInstallability.INCOMPLETE_ARTIFACT_SET
    val requiredPaths = listOf("config.json") + requiredArtifactPaths
    if (requiredPaths.any { filesByPath[it] == null }) {
        return ModelInstallability.INCOMPLETE_ARTIFACT_SET
    }
    return if (requiredPaths.all { path -> filesByPath[path]?.sha256?.let(::isTrustedSha256) == true }) {
        ModelInstallability.VERIFIED_INSTALLABLE
    } else ModelInstallability.LOCAL_DIGEST_ONLY
}

internal fun isTrustedSha256(value: String): Boolean = value.matches(TRUSTED_SHA256_PATTERN)

private val TRUSTED_SHA256_PATTERN = Regex("(?i)[0-9a-f]{64}")
private val IMMUTABLE_REVISION = Regex("(?i)(?:[0-9a-f]{40}|[0-9a-f]{64})")
