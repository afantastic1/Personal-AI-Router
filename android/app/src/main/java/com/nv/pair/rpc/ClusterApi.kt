/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import com.nv.pair.data.ClusterIdentity
import com.nv.pair.data.ClusterInvite
import com.nv.pair.data.ClusterMember
import com.nv.pair.data.ClusterRepository
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ClusterApi(private val session: BrokerSession) {
    fun initialize(repository: ClusterRepository) {
        refresh(repository)
    }

    fun refresh(repository: ClusterRepository) {
        val nodeIdentity = parseClusterIdentity(session.request("cluster:get-node-id"))
        val friendlyName = session.request("settings/get-cluster-friendly-name").optString("value")
        val identity = nodeIdentity.copy(clusterFriendlyName = friendlyName)
        repository.setIdentity(identity)
        val generation = repository.membersGeneration()
        val members = parseClusterMembers(session.request("nodes:get-initial"))
        repository.applyInitialMembersIfUnchanged(generation, members)
    }

    fun create(name: String): JSONObject = session.request(
        "cluster:create",
        JSONObject().put("clusterFriendlyName", name),
        PAIRING_TIMEOUT_MILLIS,
    )

    fun invite(address: String, port: Int, nodeId: String): ClusterInvite = parseClusterInvite(
        session.request("cluster:invite-node", JSONObject()
            .put("address", address)
            .put("port", port)
            .put("nodeId", nodeId), PAIRING_TIMEOUT_MILLIS),
    )

    fun inviteStatus(inviteId: String): ClusterInvite = parseClusterInvite(
        session.request("cluster:invite-status", JSONObject().put("inviteId", inviteId)),
    )

    fun respond(inviteId: String, accept: Boolean, pin: String = ""): ClusterInvite = parseClusterInvite(
        session.request("cluster:respond-to-invite", JSONObject()
            .put("inviteId", inviteId)
            .put("accept", accept)
            .put("pin", pin), PAIRING_TIMEOUT_MILLIS),
    )

    fun cancel(inviteId: String) {
        session.request("cluster:cancel-invite", JSONObject().put("inviteId", inviteId))
    }

    fun leave() {
        session.request("cluster:leave")
    }

    fun remove(nodeId: String) {
        session.request("nodes:remove", JSONObject().put("nodeId", nodeId))
    }

    companion object {
        private const val PAIRING_TIMEOUT_MILLIS = 35_000L
    }
}

fun parseClusterIdentity(value: JSONObject): ClusterIdentity = ClusterIdentity(
    nodeUuid = value.requiredClusterString("nodeUuid"),
    nodeId = value.requiredClusterString("nodeId"),
    name = value.requiredClusterString("name"),
    certFingerprint = value.optString("certFingerprint"),
    clusterId = value.optString("clusterId"),
    clusterFriendlyName = value.optString("clusterFriendlyName"),
)

fun parseClusterMembers(result: JSONObject): List<ClusterMember> {
    val nodes = result.optJSONArray("nodes")
        ?: throw IOException("cluster membership response has no nodes array")
    return nodes.toClusterMembers()
}

fun parseClusterMembersChanged(paramsJson: String?): List<ClusterMember> {
    val params = try {
        JSONObject(paramsJson ?: throw IOException("cluster notification has no params"))
    } catch (failure: JSONException) {
        throw IOException("cluster notification params are invalid", failure)
    }
    return parseClusterMembers(params)
}

fun parseClusterInvite(value: JSONObject, inbound: Boolean = false): ClusterInvite {
    val state = value.requiredClusterString("state")
    val pin = value.optString("pin").takeIf { !inbound && state == "pending" && it.isNotBlank() }
    return ClusterInvite(
        inviteId = value.requiredClusterString("inviteId"),
        fromNodeId = value.optString("fromNodeId"),
        fromNodeUuid = value.optString("fromNodeUuid"),
        fromNodeName = value.optString("fromNodeName"),
        toNodeId = value.optString("toNodeId"),
        clusterId = value.optString("clusterId"),
        clusterFriendlyName = value.optString("clusterFriendlyName"),
        pin = pin,
        state = state,
        reason = value.optString("reason"),
        createdAt = value.optLong("createdAt"),
        respondedAt = if (value.isNull("respondedAt")) null else value.optLong("respondedAt"),
    )
}

private fun JSONArray.toClusterMembers(): List<ClusterMember> = List(length()) { index ->
    val node = optJSONObject(index) ?: throw IOException("cluster node at index $index is not an object")
    ClusterMember(
        id = node.requiredClusterString("id"),
        nodeUuid = node.optString("nodeUuid"),
        name = node.optString("name"),
        ipAddress = node.optString("ipAddress"),
        port = node.optInt("port"),
        clusterId = node.optString("clusterId"),
        state = node.optString("state", "member"),
        joinedAt = if (node.isNull("joinedAt")) null else node.optLong("joinedAt"),
        lastSeen = if (node.isNull("lastSeen")) null else node.optLong("lastSeen"),
    )
}

private fun JSONObject.requiredClusterString(key: String): String {
    val value = optString(key)
    if (value.isBlank()) throw IOException("cluster response has no $key")
    return value
}
