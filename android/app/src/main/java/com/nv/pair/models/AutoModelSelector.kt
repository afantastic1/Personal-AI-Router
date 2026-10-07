/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import kotlin.math.ln1p

class AutoModelSelector {
    fun select(
        requestedModel: String,
        requirements: ModelRequirements,
        catalog: List<ModelDescriptor>,
        inventory: List<RuntimeModel>,
    ): ModelSelection? {
        val alias = when (requestedModel) {
            AUTO_ALIAS -> BALANCED_ALIAS
            FAST_ALIAS, BALANCED_ALIAS, BEST_ALIAS -> requestedModel
            else -> return null
        }
        // Catalog is metadata only. It cannot add candidates absent from inventory.
        val metadata = catalog.associateBy(ModelDescriptor::engineModelId)
        val enrichedInventory = inventory.map { candidate ->
            candidate.copy(model = metadata[candidate.model.engineModelId] ?: candidate.model)
        }
        val eligible = enrichedInventory.filter { candidate ->
            candidate.available &&
                candidate.model.compatibility != ModelCompatibility.INCOMPATIBLE &&
                candidate.model.capabilities.containsAll(requirements.capabilities) &&
                (candidate.model.contextLength ?: 0) >= requirements.minimumContextLength &&
                fitsMemory(candidate, requirements)
        }
        return eligible
            .map { candidate -> candidate to score(alias, candidate) }
            .maxWithOrNull(compareBy<Pair<RuntimeModel, Double>> { it.second }.thenBy { it.first.model.logicalId })
            ?.let { (candidate, score) ->
                ModelSelection(requestedModel, alias, candidate.model, candidate.engine, score)
            }
    }

    private fun fitsMemory(candidate: RuntimeModel, requirements: ModelRequirements): Boolean {
        val required = candidate.model.estimatedMemoryBytes ?: return requirements.maximumMemoryBytes == null
        if (requirements.maximumMemoryBytes != null && required > requirements.maximumMemoryBytes) return false
        return candidate.availableMemoryBytes == null || required <= candidate.availableMemoryBytes
    }

    private fun score(alias: String, candidate: RuntimeModel): Double = when (alias) {
        FAST_ALIAS -> fastScore(candidate)
        BEST_ALIAS -> bestScore(candidate)
        else -> balancedScore(candidate)
    }

    private fun fastScore(candidate: RuntimeModel): Double {
        val loaded = if (candidate.loaded) 1.0 else 0.0
        val latency = candidate.timeToFirstTokenMillis?.let { 1.0 / (1.0 + it / LATENCY_SCALE_MILLIS) } ?: 0.0
        val throughput = candidate.tokensPerSecond?.let { it / (it + THROUGHPUT_SCALE) } ?: 0.0
        val size = candidate.model.parameterCount?.let { 1.0 / (1.0 + it / SIZE_SCALE_PARAMETERS) } ?: 0.0
        return FAST_WEIGHTS.loaded * loaded + FAST_WEIGHTS.latency * latency +
            FAST_WEIGHTS.throughput * throughput + FAST_WEIGHTS.size * size
    }

    private fun balancedScore(candidate: RuntimeModel): Double {
        val quality = quality(candidate.model)
        val latency = candidate.timeToFirstTokenMillis?.let { 1.0 / (1.0 + it / LATENCY_SCALE_MILLIS) } ?: UNKNOWN_SIGNAL_SCORE
        val loaded = if (candidate.loaded) 1.0 else 0.0
        val network = 1.0 - candidate.networkCost.coerceIn(0.0, 1.0)
        val pressure = 1.0 - candidate.memoryPressure.coerceIn(0.0, 1.0)
        return BALANCED_WEIGHTS.quality * quality + BALANCED_WEIGHTS.latency * latency +
            BALANCED_WEIGHTS.loaded * loaded + BALANCED_WEIGHTS.network * network +
            BALANCED_WEIGHTS.pressure * pressure
    }

    private fun bestScore(candidate: RuntimeModel): Double {
        val quality = quality(candidate.model)
        val context = candidate.model.contextLength?.let { it.toDouble() / (it + CONTEXT_SCALE) } ?: 0.0
        val capabilities = candidate.model.capabilities.size.toDouble() / ModelCapability.entries.size
        return BEST_WEIGHTS.quality * quality + BEST_WEIGHTS.context * context + BEST_WEIGHTS.capabilities * capabilities
    }

    private fun quality(model: ModelDescriptor): Double {
        val parameters = model.parameterCount?.let { ln1p(it.toDouble()) / ln1p(MAX_PARAMETER_COUNT) } ?: UNKNOWN_SIGNAL_SCORE
        val context = model.contextLength?.let { it.toDouble() / (it + CONTEXT_SCALE) } ?: UNKNOWN_SIGNAL_SCORE
        return (QUALITY_WEIGHTS.parameters * parameters + QUALITY_WEIGHTS.context * context).coerceIn(0.0, 1.0)
    }

    private data class FastWeights(val loaded: Double, val latency: Double, val throughput: Double, val size: Double)
    private data class BalancedWeights(val quality: Double, val latency: Double, val loaded: Double, val network: Double, val pressure: Double)
    private data class BestWeights(val quality: Double, val context: Double, val capabilities: Double)
    private data class QualityWeights(val parameters: Double, val context: Double)

    companion object {
        const val AUTO_ALIAS = "auto"
        const val FAST_ALIAS = "auto-fast"
        const val BALANCED_ALIAS = "auto-balanced"
        const val BEST_ALIAS = "auto-best"
        val ALIASES = listOf(AUTO_ALIAS, FAST_ALIAS, BALANCED_ALIAS, BEST_ALIAS)

        private const val LATENCY_SCALE_MILLIS = 500.0
        private const val THROUGHPUT_SCALE = 20.0
        private const val SIZE_SCALE_PARAMETERS = 2_000_000_000.0
        private const val MAX_PARAMETER_COUNT = 100_000_000_000.0
        private const val CONTEXT_SCALE = 8_192.0
        private const val UNKNOWN_SIGNAL_SCORE = 0.5
        private val FAST_WEIGHTS = FastWeights(0.45, 0.30, 0.15, 0.10)
        private val BALANCED_WEIGHTS = BalancedWeights(0.35, 0.20, 0.15, 0.15, 0.15)
        private val BEST_WEIGHTS = BestWeights(0.65, 0.25, 0.10)
        private val QUALITY_WEIGHTS = QualityWeights(0.8, 0.2)
    }
}
