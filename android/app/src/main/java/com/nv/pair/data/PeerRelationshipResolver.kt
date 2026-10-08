/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

enum class PeerRelationshipStatus {
    PAIRED_ONLINE,
    PAIRED_OFFLINE,
    INVITE_OUTBOUND,
    INVITE_INBOUND,
    IN_OTHER_CLUSTER,
    INCONSISTENT,
    UNKNOWN_IDENTITY,
    DISCOVERED_UNPAIRED,
}

data class PeerRelationshipView(
    val nodeUuid: String?,
    val nodeId: String,
    val displayName: String,
    val status: PeerRelationshipStatus,
    val address: String?,
    val canInvite: Boolean,
    val reason: String? = null,
)

sealed interface InviteDecision {
    data object Allowed : InviteDecision
    data class NotAllowed(val reason: String) : InviteDecision
}

fun checkInviteDecision(node: PairNode, cluster: ClusterState): InviteDecision {
    val localIdentity = cluster.identity
        ?: return InviteDecision.NotAllowed("Pairing identity is not ready yet.")
    val nodeUuid = node.hostUuid
    if (nodeUuid.isBlank()) return InviteDecision.NotAllowed("The discovered device has no stable identity.")
    if (nodeUuid == localIdentity.nodeUuid) return InviteDecision.NotAllowed("This device cannot pair with itself.")

    val member = cluster.members.firstOrNull { it.state == "member" && it.nodeUuid == nodeUuid }
    if (member != null) {
        return if (member.clusterId == localIdentity.clusterId && node.trusted) {
            InviteDecision.NotAllowed("This device is already paired.")
        } else {
            InviteDecision.NotAllowed("Pairing state is inconsistent; refresh before inviting.")
        }
    }
    if (node.clustered) {
        return if (node.clusterId.isNotBlank() && node.clusterId != localIdentity.clusterId) {
            InviteDecision.NotAllowed("This device belongs to another cluster.")
        } else {
            InviteDecision.NotAllowed("This device reports cluster membership that is not confirmed here.")
        }
    }
    if (node.trusted) return InviteDecision.NotAllowed("Trust exists without a matching member record.")
    val pendingInvite = cluster.invites.any { invite ->
        invite.state == "pending" && (
            invite.toNodeId == node.id || invite.toNodeId == nodeUuid || invite.fromNodeUuid == nodeUuid
            )
    }
    if (pendingInvite) return InviteDecision.NotAllowed("Pairing is already in progress for this device.")
    return InviteDecision.Allowed
}

fun buildPeerViews(
    discovered: List<PairNode>,
    cluster: ClusterState,
    loaded: Boolean,
): List<PeerRelationshipView> {
    if (!loaded) return emptyList()

    val duplicateUuids = discovered.asSequence()
        .mapNotNull { it.hostUuid.takeIf(String::isNotBlank) }
        .groupingBy { it }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
    val uniqueDiscovery = discovered.distinctBy { node ->
        node.hostUuid.takeIf(String::isNotBlank)?.let { "uuid:$it" } ?: "id:${node.id}"
    }

    val identity = cluster.identity ?: return uniqueDiscovery.map { node ->
        node.toPeerView(PeerRelationshipStatus.UNKNOWN_IDENTITY, canInvite = false)
    }
    val localUuid = identity.nodeUuid
    val clusterId = identity.clusterId
    val members = cluster.members.filter { it.state == "member" }
    val pairedByUuid = members.filter { it.nodeUuid.isNotBlank() }.associateBy(ClusterMember::nodeUuid)
    val pairedById = members.filter { it.nodeUuid.isBlank() }.associateBy(ClusterMember::id)

    val views = uniqueDiscovery.map { node ->
        val member = node.hostUuid.takeIf(String::isNotBlank)?.let(pairedByUuid::get)
            ?: pairedById[node.id]
        val invite = cluster.invites.firstOrNull { candidate ->
            candidate.state == "pending" && (
                candidate.fromNodeUuid == node.hostUuid ||
                    candidate.toNodeId == node.id || candidate.toNodeId == node.hostUuid
                )
        }
        when {
            node.hostUuid.isBlank() -> node.toPeerView(PeerRelationshipStatus.UNKNOWN_IDENTITY, false)
            node.hostUuid in duplicateUuids -> node.toPeerView(PeerRelationshipStatus.INCONSISTENT, false, "Discovery reports conflicting records for this identity.")
            node.hostUuid == localUuid -> node.toPeerView(PeerRelationshipStatus.INCONSISTENT, false, "This is this device's own identity.")
            member != null && member.clusterId != clusterId -> node.toPeerView(PeerRelationshipStatus.INCONSISTENT, false, "Membership and local cluster identity disagree.")
            member != null && !node.trusted -> node.toPeerView(PeerRelationshipStatus.INCONSISTENT, false, "Membership exists without matching discovery trust.")
            member != null -> node.toPeerView(PeerRelationshipStatus.PAIRED_ONLINE, false)
            invite != null && invite.fromNodeUuid == node.hostUuid -> node.toPeerView(PeerRelationshipStatus.INVITE_INBOUND, false)
            invite != null -> node.toPeerView(PeerRelationshipStatus.INVITE_OUTBOUND, false)
            node.clustered && node.clusterId != clusterId -> node.toPeerView(PeerRelationshipStatus.IN_OTHER_CLUSTER, false)
            node.clustered -> node.toPeerView(PeerRelationshipStatus.INCONSISTENT, false, "Peer reports cluster membership without a matching member record.")
            node.trusted -> node.toPeerView(PeerRelationshipStatus.INCONSISTENT, false, "Discovery trust exists without a matching member record.")
            else -> node.toPeerView(PeerRelationshipStatus.DISCOVERED_UNPAIRED, true)
        }
    }

    val knownUuids = uniqueDiscovery.mapNotNull { it.hostUuid.takeIf(String::isNotBlank) }.toSet()
    val offlineMembers = members.filter { member ->
        member.nodeUuid.isNotBlank() && member.nodeUuid != localUuid && member.nodeUuid !in knownUuids
    }.map { member ->
        PeerRelationshipView(
            nodeUuid = member.nodeUuid,
            nodeId = member.id,
            displayName = member.name.ifBlank { member.id },
            status = PeerRelationshipStatus.PAIRED_OFFLINE,
            address = member.ipAddress.takeIf(String::isNotBlank),
            canInvite = false,
        )
    }
    return views + offlineMembers
}

private fun PairNode.toPeerView(
    status: PeerRelationshipStatus,
    canInvite: Boolean,
    reason: String? = null,
) = PeerRelationshipView(
    nodeUuid = hostUuid.takeIf(String::isNotBlank),
    nodeId = id,
    displayName = name,
    status = status,
    address = ipAddress.takeIf(String::isNotBlank),
    canInvite = canInvite,
    reason = reason,
)
