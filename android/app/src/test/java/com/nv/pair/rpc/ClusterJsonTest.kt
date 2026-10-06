/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterJsonTest {
    @Test
    fun inboundInviteNeverImportsPinFromNotification() {
        val invite = parseClusterInvite(JSONObject("""{"inviteId":"i-1","fromNodeId":"DESKTOP","fromNodeUuid":"u-1","fromNodeName":"Desk","pin":"123456","state":"pending"}"""), inbound = true)

        assertEquals("i-1", invite.inviteId)
        assertNull(invite.pin)
    }

    @Test
    fun outboundPendingInviteKeepsPinButTerminalInviteDoesNot() {
        val pending = parseClusterInvite(JSONObject("""{"inviteId":"i-2","state":"pending","pin":"402199"}"""))
        val paired = parseClusterInvite(JSONObject("""{"inviteId":"i-2","state":"paired","pin":"402199"}"""))

        assertEquals("402199", pending.pin)
        assertNull(paired.pin)
    }

    @Test
    fun parsesMemberSnapshotAndLocalIdentity() {
        val members = parseClusterMembers(JSONObject("""{"nodes":[{"id":"DESKTOP","nodeUuid":"u-2","name":"Desk","ipAddress":"192.168.1.5","port":14321,"clusterId":"c-1","state":"member","joinedAt":12,"lastSeen":13}]}"""))
        val identity = parseClusterIdentity(JSONObject("""{"nodeUuid":"u-1","nodeId":"PHONE","name":"Phone","clusterId":"c-1","clusterFriendlyName":"Home"}"""))

        assertEquals("u-2", members.single().nodeUuid)
        assertEquals("192.168.1.5", members.single().ipAddress)
        assertEquals("Home", identity.clusterFriendlyName)
    }
}
