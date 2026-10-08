/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GatewayTokenStoreInstrumentedTest {
    @Test
    fun tokenPersistsAcrossStoreInstancesAndPreferencesContainOnlyCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = GatewayTokenStore(context).getOrCreate()

        assertTrue("Gateway token must meet the Proxy's minimum length", token.length >= MINIMUM_TOKEN_LENGTH)
        assertEquals(token, GatewayTokenStore(context).getOrCreate())

        val encryptedValue = context.getSharedPreferences(PREFERENCES, 0).getString(TOKEN, null)
        assertTrue("Encrypted token must be persisted", !encryptedValue.isNullOrBlank())
        assertNotEquals(token, encryptedValue)
    }

    private companion object {
        const val PREFERENCES = "pair-gateway-auth"
        const val TOKEN = "encrypted-client-token"
        const val MINIMUM_TOKEN_LENGTH = 32
    }
}
