/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

data class ClusterIdentity(
    val nodeUuid: String,
    val nodeId: String,
    val name: String,
    val certFingerprint: String = "",
    val clusterId: String = "",
    val clusterFriendlyName: String = "",
)

data class ClusterMember(
    val id: String,
    val nodeUuid: String = "",
    val name: String = "",
    val ipAddress: String = "",
    val port: Int = 0,
    val clusterId: String = "",
    val state: String = "member",
    val joinedAt: Long? = null,
    val lastSeen: Long? = null,
)

data class ClusterInvite(
    val inviteId: String,
    val fromNodeId: String,
    val fromNodeUuid: String = "",
    val fromNodeName: String = "",
    val toNodeId: String = "",
    val clusterId: String = "",
    val clusterFriendlyName: String = "",
    val pin: String? = null,
    val state: String,
    val reason: String = "",
    val createdAt: Long = 0,
    val respondedAt: Long? = null,
)

data class ClusterState(
    val identity: ClusterIdentity? = null,
    val members: List<ClusterMember> = emptyList(),
    val invites: List<ClusterInvite> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isClustered: Boolean
        get() = !identity?.clusterId.isNullOrBlank()
}
