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
    engine = requiredRouterString("engine"),
    runId = optString("runId"),
    state = requiredRouterString("state"),
    originatedFrom = optString("originatedFrom"),
    scheduledOn = optString("scheduledOn"),
    createdAt = optLong("createdAt"),
    startedAt = if (isNull("startedAt")) null else optLong("startedAt"),
    completedAt = if (isNull("completedAt")) null else optLong("completedAt"),
    error = if (isNull("error")) null else optString("error"),
)

private fun JSONObject.requiredRouterString(key: String): String {
    val value = optString(key)
    if (value.isBlank()) throw IOException("router response has no $key")
    return value
}
