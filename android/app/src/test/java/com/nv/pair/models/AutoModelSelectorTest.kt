/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoModelSelectorTest {
    @Test
    fun autoUsesBalancedPolicyAndNeverSelectsFromCatalogOnly() {
        val selector = AutoModelSelector()
        val catalogOnly = descriptor("catalog-large", parameterCount = 70_000_000_000)
        val local = runtime(descriptor("phone-small", parameterCount = 1_700_000_000), loaded = true)

        val selection = selector.select("auto", ModelRequirements(), listOf(catalogOnly), listOf(local))

        assertNotNull(selection)
        assertEquals("auto-balanced", selection?.policyAlias)
        assertEquals("phone-small", selection?.model?.engineModelId)
        assertNull(selector.select("auto-best", ModelRequirements(), listOf(catalogOnly), emptyList()))
    }

    @Test
    fun fastPrefersLoadedLowLatencyModels() {
        val loadedSlow = runtime(descriptor("loaded"), loaded = true, ttfbMillis = 900)
        val unloadedFast = runtime(descriptor("unloaded"), loaded = false, ttfbMillis = 80)

        val selected = AutoModelSelector().select("auto-fast", ModelRequirements(), emptyList(), listOf(loadedSlow, unloadedFast))

        assertEquals("loaded", selected?.model?.engineModelId)
    }

    @Test
    fun bestRequiresCapabilitiesAndSufficientContextAndMemory() {
        val text = runtime(descriptor("text-large", parameterCount = 30_000_000_000, contextLength = 8192))
        val vision = runtime(
            descriptor(
                "vision-small",
                parameterCount = 2_000_000_000,
                contextLength = 32768,
                capabilities = setOf(ModelCapability.CHAT, ModelCapability.VISION),
                estimatedMemoryBytes = 4_000_000_000,
            ),
            availableMemoryBytes = 5_000_000_000,
        )

        val selected = AutoModelSelector().select(
            "auto-best",
            ModelRequirements(setOf(ModelCapability.VISION), minimumContextLength = 16000),
            emptyList(),
            listOf(text, vision),
        )

        assertEquals("vision-small", selected?.model?.engineModelId)
    }

    @Test
    fun rejectsUnknownAliasesAndUnavailableOrIncompatibleInventory() {
        val selector = AutoModelSelector()
        val incompatible = runtime(descriptor("bad", compatibility = ModelCompatibility.INCOMPATIBLE), available = false)

        assertNull(selector.select("not-auto", ModelRequirements(), emptyList(), listOf(incompatible)))
        assertNull(selector.select("auto-fast", ModelRequirements(), emptyList(), listOf(incompatible)))
    }

    private fun descriptor(
        id: String,
        parameterCount: Long = 2_000_000_000,
        contextLength: Int = 4096,
        capabilities: Set<ModelCapability> = setOf(ModelCapability.CHAT),
        estimatedMemoryBytes: Long = 3_000_000_000,
        compatibility: ModelCompatibility = ModelCompatibility.COMPATIBLE,
    ) = ModelDescriptor(
        logicalId = id,
        engineModelId = id,
        displayName = id,
        family = "test",
        parameterCount = parameterCount,
        quantization = null,
        contextLength = contextLength,
        capabilities = capabilities,
        source = ModelSource(ModelSourceKind.LOCAL, id),
        format = ModelFormat.MNN,
        estimatedMemoryBytes = estimatedMemoryBytes,
        compatibility = compatibility,
    )

    private fun runtime(
        model: ModelDescriptor,
        loaded: Boolean = false,
        ttfbMillis: Long = 300,
        available: Boolean = true,
        availableMemoryBytes: Long = 8_000_000_000,
    ) = RuntimeModel(
        model = model,
        nodeId = "node-${model.logicalId}",
        engine = "mnn",
        available = available,
        loaded = loaded,
        timeToFirstTokenMillis = ttfbMillis,
        tokensPerSecond = 20.0,
        availableMemoryBytes = availableMemoryBytes,
    )
}
