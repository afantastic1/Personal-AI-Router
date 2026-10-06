/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nv.pair.network.AndroidNetworkContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNetworkContextInstrumentedTest {
    @Test
    fun androidReportsWifiInterfaceThroughConnectivityManager() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val interfaceInfo = AndroidNetworkContext(context).wifiInterface()
        assumeTrue("test device is not connected through Wi-Fi", interfaceInfo != null)
        assertTrue("Wi-Fi interface index is invalid", requireNotNull(interfaceInfo).index > 0)
        assertTrue("Wi-Fi interface has no IPv4 address", requireNotNull(interfaceInfo).ipv4Address.isNotBlank())
    }
}
