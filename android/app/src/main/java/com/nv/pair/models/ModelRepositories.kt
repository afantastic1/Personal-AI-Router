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

data class ModelInventoryItem(
    val modelId: String,
    val nodeId: String,
    val engine: String,
    val loaded: Boolean,
)

class ModelInventoryRepository {
    fun fromNetwork(nodes: List<PairNode>): List<ModelInventoryItem> = nodes.flatMap { node ->
        node.modelsByEngine.flatMap { (engine, models) ->
            models.map { modelId ->
                ModelInventoryItem(
                    modelId = modelId,
                    nodeId = node.hostUuid.ifBlank { node.id },
                    engine = engine,
                    loaded = node.loadedByEngine[engine].orEmpty().any { it == modelId },
                )
            }
        }
    }
}
