/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerApiTest {
    @Test
    fun parsesDiscoverySnapshotIdentityTrustAndEngineModels() {
        val nodes = parseNodesChanged(
            """[{"id":"workstation","name":"Workstation","hostUuid":"host-1","ipAddress":"192.168.1.8","ipAddresses":["192.168.1.8","10.0.0.2"],"port":14321,"trusted":true,"clustered":true,"models":["model-a"],"modelsByEngine":{"ollama":["model-a"]},"loadedByEngine":{"ollama":["model-a"]},"lastSeen":42}]"""
        )

        assertEquals(1, nodes.size)
        assertEquals("host-1", nodes.single().hostUuid)
        assertEquals(listOf("192.168.1.8", "10.0.0.2"), nodes.single().ipAddresses)
        assertTrue(nodes.single().trusted)
        assertEquals(listOf("model-a"), nodes.single().modelsByEngine["ollama"])
        assertEquals(listOf("model-a"), nodes.single().loadedByEngine["ollama"])
    }

    @Test
    fun acceptsManualNodeWithoutHostUuid() {
        val node = parseNodesChanged(
            """[{"id":"manual","name":"Manual host","ipAddress":"192.168.1.9","port":14321,"trusted":false}]"""
        ).single()

        assertEquals("", node.hostUuid)
        assertFalse(node.trusted)
    }
}
