/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairRepositoryTest {
    @Test
    fun pushSnapshotWinsOverStaleInitialResponse() {
        val repository = PairRepository()
        val generationBeforeRequest = repository.nodesGeneration()
        val pushed = listOf(PairNode(id = "new", name = "New node", ipAddress = "10.0.0.2"))
        repository.applyNodesChanged(pushed)

        val applied = repository.applyInitialSnapshotIfUnchanged(
            generationBeforeRequest,
            listOf(PairNode(id = "old", name = "Old node", ipAddress = "10.0.0.1")),
        )

        assertFalse(applied)
        assertEquals(pushed, repository.nodes.value)
    }

    @Test
    fun appliesInitialSnapshotWhenNoPushArrived() {
        val repository = PairRepository()
        val generationBeforeRequest = repository.nodesGeneration()
        val snapshot = listOf(PairNode(id = "pc", name = "Workstation", ipAddress = "10.0.0.3"))

        assertTrue(repository.applyInitialSnapshotIfUnchanged(generationBeforeRequest, snapshot))
        assertEquals(snapshot, repository.nodes.value)
    }
}
