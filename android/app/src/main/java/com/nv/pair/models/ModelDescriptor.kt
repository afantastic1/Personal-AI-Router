/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

enum class ModelCapability { CHAT, VISION, TOOLS, EMBEDDINGS }

enum class ModelSourceKind { MODELSCOPE, HUGGING_FACE, LOCAL, RUNTIME }

enum class ModelFormat { MNN, GGUF, ONNX, UNKNOWN }

enum class ModelCompatibility { COMPATIBLE, UNKNOWN, INCOMPATIBLE }

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
    val capabilities: Set<ModelCapability>,
    val source: ModelSource,
    val format: ModelFormat,
    val estimatedMemoryBytes: Long?,
    val compatibility: ModelCompatibility,
    val files: List<ModelFile> = emptyList(),
)

/** A runtime observation. Catalog records are never eligible for selection by themselves. */
data class RuntimeModel(
    val model: ModelDescriptor,
    val nodeId: String,
    val engine: String,
    val available: Boolean,
    val loaded: Boolean,
    val tokensPerSecond: Double? = null,
    val timeToFirstTokenMillis: Long? = null,
    val memoryPressure: Double = 0.0,
    val availableMemoryBytes: Long? = null,
    val networkCost: Double = 0.0,
)

data class ModelRequirements(
    val capabilities: Set<ModelCapability> = setOf(ModelCapability.CHAT),
    val minimumContextLength: Int = 0,
    val maximumMemoryBytes: Long? = null,
)

data class ModelSelection(
    val requestedModel: String,
    val policyAlias: String,
    val model: ModelDescriptor,
    val engine: String,
    val score: Double,
)
