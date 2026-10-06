/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterRepositoryTest {
    @Test
    fun membershipPushWinsOverStaleInitialResponse() {
        val repository = ClusterRepository()
        val generation = repository.membersGeneration()
        val pushed = listOf(ClusterMember(id = "host-new", name = "New workstation"))
        repository.applyMembersChanged(pushed)

        assertFalse(repository.applyInitialMembersIfUnchanged(
            generation,
            listOf(ClusterMember(id = "host-old", name = "Old workstation")),
        ))
        assertEquals(pushed, repository.state.value.members)
    }

    @Test
    fun receivedInviteDoesNotContainPin() {
        val repository = ClusterRepository()
        val invite = ClusterInvite(
            inviteId = "invite-1",
            fromNodeId = "DESKTOP",
            fromNodeName = "Workstation",
            state = "pending",
        )

        repository.applyInvite(invite)

        assertEquals(invite, repository.state.value.invites.single())
        assertNull(repository.state.value.invites.single().pin)
    }

    @Test
    fun terminalInviteClearsTheVisiblePin() {
        val repository = ClusterRepository()
        repository.applyInvite(ClusterInvite(
            inviteId = "invite-2",
            fromNodeId = "PHONE",
            fromNodeName = "Phone",
            pin = "123456",
            state = "pending",
        ))

        repository.applyInvite(repository.state.value.invites.single().copy(state = "paired", pin = null))

        assertEquals("paired", repository.state.value.invites.single().state)
        assertNull(repository.state.value.invites.single().pin)
    }

    @Test
    fun appliesIdentityChangedWithoutOverwritingNodeIdentity() {
        val repository = ClusterRepository()
        repository.setIdentity(ClusterIdentity(nodeUuid = "node-1", nodeId = "PHONE", name = "Phone"))

        repository.applyClusterIdentityChanged(clusterId = "cluster-1", friendlyName = "Home")

        assertEquals("node-1", repository.state.value.identity?.nodeUuid)
        assertEquals("cluster-1", repository.state.value.identity?.clusterId)
        assertTrue(repository.state.value.isClustered)
    }

    @Test
    fun clearsTransientInvitePinsWhenBrokerSessionEnds() {
        val repository = ClusterRepository()
        repository.applyInvite(ClusterInvite(
            inviteId = "invite-session",
            fromNodeId = "DESKTOP",
            pin = "402199",
            state = "pending",
        ))

        repository.clearPendingInvites()

        assertTrue(repository.state.value.invites.isEmpty())
    }

    @Test
    fun preservesIdentityChangeArrivingDuringInitialIdentityRead() {
        val repository = ClusterRepository()
        repository.applyClusterIdentityChanged(clusterId = "cluster-new", friendlyName = "Current")

        repository.setIdentity(ClusterIdentity(
            nodeUuid = "node-1",
            nodeId = "PHONE",
            name = "Phone",
            clusterId = "",
        ))

        assertEquals("cluster-new", repository.state.value.identity?.clusterId)
        assertEquals("Current", repository.state.value.identity?.clusterFriendlyName)
    }
}
