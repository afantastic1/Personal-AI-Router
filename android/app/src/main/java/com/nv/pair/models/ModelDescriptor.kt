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
    MISSING_VERIFICATION_METADATA,
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
)

fun ModelDescriptor.installability(): ModelInstallability {
    if (format != ModelFormat.MNN) return ModelInstallability.UNSUPPORTED_FORMAT

    val filesByPath = files.associateBy(ModelFile::path)
    val requiredPaths = listOf("config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt")
    if (requiredPaths.any { filesByPath[it] == null }) {
        return ModelInstallability.INCOMPLETE_ARTIFACT_SET
    }
    if (requiredPaths.any { path -> filesByPath[path]?.sha256?.let(::isTrustedSha256) != true }) {
        return ModelInstallability.MISSING_VERIFICATION_METADATA
    }
    return ModelInstallability.VERIFIED_INSTALLABLE
}

internal fun isTrustedSha256(value: String): Boolean = value.matches(TRUSTED_SHA256_PATTERN)

private val TRUSTED_SHA256_PATTERN = Regex("(?i)[0-9a-f]{64}")
