/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import com.nv.pair.data.EngineProxyStatus
import com.nv.pair.data.PairWorkload
import com.nv.pair.data.RouterRepository
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class RouterApi(private val session: BrokerSession) {
    fun proxyStatus(engine: String): EngineProxyStatus = parseProxyStatus(
        engine,
        session.request("$engine-proxy:get-status"),
    )

    fun initializeWorkloads(repository: RouterRepository) {
        val subscription = session.request("workloads:subscribe")
        if (!subscription.optBoolean("subscribed")) throw IOException("broker rejected workload subscription")
        val generation = repository.workloadGeneration()
        repository.setWorkloadsIfUnchanged(generation, parseWorkloads(session.request("workloads:get-initial")))
    }

    fun cloudProviderSettings(): CloudProviderSettings = parseCloudProviderSettings(
        session.request("cloudproviders:get"),
    )

    fun updateCloudSettings(settings: CloudProviderSettings, enabled: Boolean, policy: String) {
        val params = settings.toJson(enabled, policy)
        val result = session.request("cloudproviders:save", params)
        if (!result.optBoolean("saved")) throw IOException("broker did not save cloud settings")
    }
}

data class CloudProviderSettings(
    val config: JSONObject,
    val policy: String,
    val allowPaidFallback: Boolean,
    val monthlyBudgetUsd: Double,
    val perRequestMaxEstimatedCostUsd: Double,
    val cloudEnabled: Boolean,
    val authorizedNodes: List<CloudNodeAuthorization>,
) {
    fun toJson(enabled: Boolean, routingPolicy: String): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("config", config)
        .put("cloudEnabled", enabled)
        .put("policy", routingPolicy)
        .put("allowPaidFallback", allowPaidFallback)
        .put("monthlyBudgetUSD", monthlyBudgetUsd)
        .put("perRequestMaxEstimatedCostUSD", perRequestMaxEstimatedCostUsd)
        .put("authorizedNodes", JSONArray().apply {
            authorizedNodes.forEach { authorization ->
                put(JSONObject()
                    .put("nodeUuid", authorization.nodeUuid)
                    .put("certFingerprint", authorization.certFingerprint))
            }
        })
}

data class CloudNodeAuthorization(val nodeUuid: String, val certFingerprint: String)
fun parseCloudProviderSettings(value: JSONObject): CloudProviderSettings {
    val config = value.optJSONObject("config") ?: throw IOException("cloud settings have no config")
    if (value.optInt("schema_version") != 1) throw IOException("unsupported cloud settings version")
    val policy = value.optString("policy")
    if (policy !in setOf("local_only", "cloud_only", "prefer_local", "prefer_cloud")) {
        throw IOException("cloud settings have an invalid policy")
    }
    return CloudProviderSettings(
        config = config,
        policy = policy,
        allowPaidFallback = value.optBoolean("allowPaidFallback"),
        monthlyBudgetUsd = value.optDouble("monthlyBudgetUSD"),
        perRequestMaxEstimatedCostUsd = value.optDouble("perRequestMaxEstimatedCostUSD"),
        cloudEnabled = value.optBoolean("cloudEnabled"),
        authorizedNodes = value.optJSONArray("authorizedNodes")?.let { array ->
            List(array.length()) { index ->
                val authorization = array.optJSONObject(index)
                    ?: throw IOException("cloud settings contain an invalid node authorization")
                val nodeUuid = authorization.optString("nodeUuid")
                val certFingerprint = authorization.optString("certFingerprint")
                if (nodeUuid.isBlank() || certFingerprint.isBlank()) {
                    throw IOException("cloud settings contain an invalid node authorization")
                }
                CloudNodeAuthorization(nodeUuid, certFingerprint)
            }
        }.orEmpty(),
    )
}

fun parseProxyStatus(engine: String, value: JSONObject): EngineProxyStatus {
    val ready = value.optBoolean("ready")
    val port = if (ready) value.optInt("port") else 0
    if (ready && port !in 1..65535) throw IOException("$engine proxy reported an invalid bound port")
    return EngineProxyStatus(engine, ready, port)
}

fun parseWorkloads(result: JSONObject): List<PairWorkload> {
    val array = result.optJSONArray("workloads")
        ?: throw IOException("workload snapshot has no workloads array")
    return array.toWorkloads()
}

fun parseWorkloadUpsert(paramsJson: String?): PairWorkload {
    val params = parseNotificationObject(paramsJson, "workload upsert")
    return params.optJSONObject("workloadInfo")?.toPairWorkload()
        ?: throw IOException("workload upsert has no workloadInfo object")
}

data class WorkloadRemoval(val workloadId: String, val origin: String)

fun parseWorkloadRemoval(paramsJson: String?): WorkloadRemoval {
    val params = parseNotificationObject(paramsJson, "workload removal")
    return WorkloadRemoval(
        workloadId = params.requiredRouterString("workloadId"),
        origin = params.optString("originatedFrom"),
    )
}

private fun parseNotificationObject(paramsJson: String?, label: String): JSONObject = try {
    JSONObject(paramsJson ?: throw IOException("$label has no params"))
} catch (failure: JSONException) {
    throw IOException("$label params are invalid", failure)
}

private fun JSONArray.toWorkloads(): List<PairWorkload> = List(length()) { index ->
    val value = optJSONObject(index) ?: throw IOException("workload at index $index is not an object")
    value.toPairWorkload()
}

private fun JSONObject.toPairWorkload(): PairWorkload = PairWorkload(
    id = requiredRouterString("id"),
    model = optString("model"),
    engine = optString("engine"),
    runId = optString("runId"),
    state = requiredRouterString("state"),
    originatedFrom = optString("originatedFrom"),
    scheduledOn = optString("scheduledOn"),
    createdAt = optLong("createdAt"),
    startedAt = if (isNull("startedAt")) null else optLong("startedAt"),
    completedAt = if (isNull("completedAt")) null else optLong("completedAt"),
    error = if (isNull("error")) null else optString("error"),
    kind = optString("kind", "local"),
    providerId = if (isNull("providerId")) null else optString("providerId"),
    publicModelId = if (isNull("publicModelId")) null else optString("publicModelId"),
    inputTokens = valueOptJSONObject("usage")?.optLong("inputTokens"),
    outputTokens = valueOptJSONObject("usage")?.optLong("outputTokens"),
    costEstimate = if (isNull("costEstimate")) null else optDouble("costEstimate"),
    requesterId = if (isNull("requesterId")) null else optString("requesterId"),
)

private fun JSONObject.valueOptJSONObject(key: String): JSONObject? =
    if (isNull(key)) null else optJSONObject(key)

private fun JSONObject.requiredRouterString(key: String): String {
    val value = optString(key)
    if (value.isBlank()) throw IOException("router response has no $key")
    return value
}
