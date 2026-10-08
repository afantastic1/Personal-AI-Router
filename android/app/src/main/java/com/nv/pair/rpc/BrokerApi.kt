/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import com.nv.pair.data.PairNode
import com.nv.pair.data.PairRepository
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class BrokerPing(val version: String, val uptimeMillis: Long)

class BrokerApi(private val rpc: JsonRpcClient) {
    fun ping(): BrokerPing {
        val result = rpc.request("ping")
        if (!result.optBoolean("pong")) throw IllegalStateException("broker ping returned pong=false")
        val version = result.optString("version")
        if (version.isBlank()) throw IllegalStateException("broker ping returned no version")
        return BrokerPing(version, result.optLong("uptime_ms"))
    }

    fun version(): String {
        val version = rpc.request("version").optString("version")
        if (version.isBlank()) throw IllegalStateException("broker version response was empty")
        return version
    }

    fun subscribeToDiscovery() {
        val result = rpc.request("discovery:subscribe", JSONObject())
        if (!result.optBoolean("subscribed")) {
            throw IOException("broker rejected discovery subscription")
        }
    }

    fun getNodes(): List<PairNode> {
        val result = rpc.request("discovery:get-nodes")
        val nodes = result.optJSONArray("nodes")
            ?: throw IOException("broker discovery snapshot has no nodes array")
        return nodes.toPairNodes()
    }

    fun initializeDiscovery(repository: PairRepository) {
        subscribeToDiscovery()
        val generationBeforeRequest = repository.nodesGeneration()
        repository.applyInitialSnapshotIfUnchanged(generationBeforeRequest, getNodes())
    }
}

fun parseNodesChanged(paramsJson: String?): List<PairNode> {
    val params = try {
        JSONArray(paramsJson ?: throw IOException("discovery notification has no params"))
    } catch (failure: JSONException) {
        throw IOException("discovery notification params are not a node array", failure)
    }
    return params.toPairNodes()
}

private fun JSONArray.toPairNodes(): List<PairNode> = List(length()) { index ->
    val node = optJSONObject(index) ?: throw IOException("discovery node at index $index is not an object")
    PairNode(
        id = node.requiredString("id"),
        name = node.requiredString("name"),
        hostUuid = node.optString("hostUuid"),
        ipAddress = node.requiredString("ipAddress"),
        ipAddresses = node.optJSONArray("ipAddresses").toStringList(),
        port = node.optInt("port"),
        trusted = node.optBoolean("trusted"),
        clustered = node.optBoolean("clustered"),
        clusterId = node.optString("clusterUuid"),
        models = node.optJSONArray("models").toStringList(),
        modelsByEngine = node.optJSONObject("modelsByEngine").toStringListMap(),
        loadedByEngine = node.optJSONObject("loadedByEngine").toStringListMap(),
        lastSeen = node.optLong("lastSeen"),
    )
}

private fun JSONObject.requiredString(key: String): String {
    val value = optString(key)
    if (value.isBlank()) throw IOException("discovery node has no $key")
    return value
}

private fun JSONArray?.toStringList(): List<String> {
    val array = this ?: return emptyList()
    return List(array.length()) { index -> array.optString(index) }.filter(String::isNotBlank)
}

private fun JSONObject?.toStringListMap(): Map<String, List<String>> {
    val objectValue = this ?: return emptyMap()
    val values = mutableMapOf<String, List<String>>()
    val keys = objectValue.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        values[key] = objectValue.optJSONArray(key).toStringList()
    }
    return values.toMap()
}
