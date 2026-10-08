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
        return searchPage(query, source, page = 1).descriptors
    }

    fun searchPage(
        query: String,
        source: ModelSourceKind,
        page: Int,
        pageSize: Int = ModelSourceAdapter.DEFAULT_SEARCH_LIMIT,
    ): ModelSearchPage {
        val adapter = adapters.firstOrNull { it.kind == source }
            ?: throw IllegalArgumentException("No adapter is configured for this model source.")
        val result = adapter.searchPage(query, page, pageSize)
        synchronized(this) {
            result.descriptors.forEach { entries[it.logicalId] = it }
        }
        return result
    }

    fun adapterFor(source: ModelSourceKind): ModelSourceAdapter = adapters.firstOrNull { it.kind == source }
        ?: throw IllegalArgumentException("No adapter is configured for this model source.")

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
