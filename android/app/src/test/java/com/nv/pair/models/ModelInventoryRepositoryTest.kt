/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import com.nv.pair.data.PairNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInventoryRepositoryTest {
    @Test
    fun networkInventoryKeepsEngineNodeAndLoadedStateSeparateFromCatalog() {
        val inventory = ModelInventoryRepository().fromNetwork(
            listOf(
                PairNode(
                    id = "tablet",
                    name = "Tablet",
                    hostUuid = "phone-uuid",
                    ipAddress = "127.0.0.1",
                    modelsByEngine = mapOf("mnn" to listOf("qwen3-1.7b")),
                    loadedByEngine = mapOf("mnn" to listOf("qwen3-1.7b")),
                ),
            ),
        )

        assertEquals(1, inventory.size)
        assertEquals("phone-uuid", inventory.single().nodeId)
        assertEquals("mnn", inventory.single().engine)
        assertTrue(inventory.single().loaded)
        assertEquals(ModelSourceKind.RUNTIME, inventory.single().model.source.kind)
        assertEquals(1_700_000_000L, inventory.single().model.parameterCount)
    }
}
