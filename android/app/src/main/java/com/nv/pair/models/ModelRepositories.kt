/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import com.nv.pair.data.PairNode

class ModelCatalogRepository(
    private val adapters: List<ModelSourceAdapter>,
) {
    private val entries = linkedMapOf<String, ModelDescriptor>()

    fun search(query: String, source: ModelSourceKind): List<ModelDescriptor> {
        val adapter = adapters.firstOrNull { it.kind == source }
            ?: throw IllegalArgumentException("No adapter is configured for this model source.")
        val results = adapter.search(query)
        synchronized(this) {
            results.forEach { entries[it.logicalId] = it }
        }
        return results
    }

    @Synchronized
    fun entries(): List<ModelDescriptor> = entries.values.toList()

    @Synchronized
    fun addLocal(model: ModelDescriptor) {
        require(model.source.kind == ModelSourceKind.LOCAL) { "Only locally imported models belong in the local catalog." }
        entries[model.logicalId] = model
    }
}

class ModelInventoryRepository {
    fun fromNetwork(nodes: List<PairNode>): List<RuntimeModel> = nodes.flatMap { node ->
        node.modelsByEngine.flatMap { (engine, models) ->
            models.map { modelId ->
                val descriptor = ModelDescriptor(
                    logicalId = "$engine:$modelId",
                    engineModelId = modelId,
                    displayName = modelId,
                    family = inferFamily(modelId),
                    parameterCount = inferParameterCount(modelId),
                    quantization = null,
                    contextLength = null,
                    capabilities = inferCapabilities(modelId),
                    source = ModelSource(ModelSourceKind.RUNTIME, node.hostUuid.ifBlank { node.id }),
                    format = if (engine == "mnn") ModelFormat.MNN else ModelFormat.UNKNOWN,
                    estimatedMemoryBytes = null,
                    compatibility = ModelCompatibility.UNKNOWN,
                )
                RuntimeModel(
                    model = descriptor,
                    nodeId = node.hostUuid.ifBlank { node.id },
                    engine = engine,
                    available = true,
                    loaded = node.loadedByEngine[engine].orEmpty().any { it == modelId },
                )
            }
        }
    }

    private fun inferFamily(modelId: String): String = FAMILY_NAMES.firstOrNull { modelId.contains(it, ignoreCase = true) } ?: "unknown"

    private fun inferParameterCount(modelId: String): Long? = PARAMETER_PATTERN.find(modelId)?.groupValues?.get(1)
        ?.toDoubleOrNull()?.let { (it * BILLION).toLong() }

    private fun inferCapabilities(modelId: String): Set<ModelCapability> = buildSet {
        val lower = modelId.lowercase()
        if ("embed" in lower) add(ModelCapability.EMBEDDINGS) else add(ModelCapability.CHAT)
        if ("vision" in lower || "llava" in lower || "-vl" in lower || "_vl" in lower) add(ModelCapability.VISION)
        if ("tool" in lower) add(ModelCapability.TOOLS)
    }

    private companion object {
        const val BILLION = 1_000_000_000.0
        val FAMILY_NAMES = listOf("qwen", "llama", "gemma", "mistral", "deepseek", "phi", "smollm", "internlm")
        val PARAMETER_PATTERN = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*[- ]?B(?:\\b|$)")
    }
}
