/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CloudProviderSettingsTest {
    @Test
    fun togglePreservesProviderConfigAndBudgetSettings() {
        val settings = parseCloudProviderSettings(
            JSONObject(
                """{"schema_version":1,"config":{"schema_version":1,"providers":[{"id":"work"}]},"cloudEnabled":false,"policy":"prefer_local","allowPaidFallback":true,"monthlyBudgetUSD":25,"perRequestMaxEstimatedCostUSD":0.5}""",
            ),
        )

        val saved = settings.toJson(enabled = true)

        assertTrue(saved.optBoolean("cloudEnabled"))
        assertEquals(settings.config.toString(), saved.getJSONObject("config").toString())
        assertEquals("prefer_local", saved.optString("policy"))
        assertTrue(saved.optBoolean("allowPaidFallback"))
        assertEquals(25.0, saved.optDouble("monthlyBudgetUSD"), 0.0)
        assertEquals(0.5, saved.optDouble("perRequestMaxEstimatedCostUSD"), 0.0)
        assertFalse(saved.has("credential"))
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsUnsupportedSchema() {
        parseCloudProviderSettings(JSONObject("""{"schema_version":2,"config":{},"policy":"local_only"}"""))
    }
}
