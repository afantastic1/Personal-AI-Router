/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.network

import android.content.Context
import android.net.wifi.WifiManager
import java.io.Closeable

class MulticastLockManager(context: Context) : Closeable {
    private val lock: WifiManager.MulticastLock =
        requireNotNull(context.applicationContext.getSystemService(WifiManager::class.java)) {
            "Wi-Fi service is unavailable"
        }
            .createMulticastLock("pair-runtime-mdns")
            .apply { setReferenceCounted(false) }

    val isHeld: Boolean
        get() = lock.isHeld

    @Synchronized
    fun acquire() {
        if (!lock.isHeld) lock.acquire()
    }

    @Synchronized
    fun release() {
        if (lock.isHeld) lock.release()
    }

    override fun close() {
        release()
    }
}
