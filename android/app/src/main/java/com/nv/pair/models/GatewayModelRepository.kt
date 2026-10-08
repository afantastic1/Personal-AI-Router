/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

enum class GatewayModelKind { LOCAL, CLOUD, REMOTE_CLOUD, AUTOMATIC }

data class GatewayModel(val id: String, val kind: GatewayModelKind)

class GatewayModelRepository(
    private val endpoint: URL = URL(GATEWAY_MODELS_URL),
    private val accessToken: String = "",
) {
    fun models(): List<GatewayModel> {
        val opened = endpoint.openConnection()
        if (opened !is HttpURLConnection) throw IOException("PAIR gateway model list must use HTTP.")
        val connection = opened
        connection.connectTimeout = REQUEST_TIMEOUT_MILLIS
        connection.readTimeout = REQUEST_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        if (accessToken.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $accessToken")
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("PAIR gateway model list returned HTTP ${connection.responseCode}.")
            }
            val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val models = root.optJSONArray("data")
                ?: throw IOException("PAIR gateway model list response is missing data.")
            return (0 until models.length()).mapNotNull { index ->
                val id = models.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val kind = when {
                    models.optJSONObject(index)?.optString("owned_by") == "pair-remote-cloud" -> GatewayModelKind.REMOTE_CLOUD
                    id.startsWith("cloud/") -> GatewayModelKind.CLOUD
                    id == "auto" || id.startsWith("auto-") -> GatewayModelKind.AUTOMATIC
                    else -> GatewayModelKind.LOCAL
                }
                GatewayModel(id, kind)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val GATEWAY_MODELS_URL = "http://127.0.0.1:14326/v1/models"
        const val REQUEST_TIMEOUT_MILLIS = 3_000
    }
}
