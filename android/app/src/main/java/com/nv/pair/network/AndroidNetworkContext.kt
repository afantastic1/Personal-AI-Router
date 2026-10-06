/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

data class AndroidWifiInterface(
    val index: Int,
    val name: String,
    val ipv4Address: String,
)

class AndroidNetworkContext(context: Context) {
    private val connectivity = requireNotNull(
        context.applicationContext.getSystemService(ConnectivityManager::class.java),
    ) { "Android connectivity service is unavailable" }

    fun wifiInterface(): AndroidWifiInterface? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val properties = connectivity.getLinkProperties(network) ?: return null
        val name = properties.interfaceName ?: return null
        val networkInterface = NetworkInterface.getByName(name) ?: return null
        val address = properties.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
            ?: return null

        return AndroidWifiInterface(networkInterface.index, name, address)
    }
}
