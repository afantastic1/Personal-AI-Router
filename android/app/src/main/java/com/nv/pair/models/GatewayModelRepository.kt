/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class GatewayModelRepository(
    private val endpoint: URL = URL(GATEWAY_MODELS_URL),
) {
    fun modelIds(): List<String> {
        val opened = endpoint.openConnection()
        if (opened !is HttpURLConnection) throw IOException("PAIR gateway model list must use HTTP.")
        val connection = opened
        connection.connectTimeout = REQUEST_TIMEOUT_MILLIS
        connection.readTimeout = REQUEST_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("PAIR gateway model list returned HTTP ${connection.responseCode}.")
            }
            val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val models = root.optJSONArray("data")
                ?: throw IOException("PAIR gateway model list response is missing data.")
            return (0 until models.length()).mapNotNull { index ->
                models.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)
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
