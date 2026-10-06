/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

class BrokerRestartPolicy(
    private val delaysMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000),
    private val stableRuntimeMillis: Long = 60_000,
) {
    private var failureCount = 0

    fun nextDelayMillis(): Long? {
        if (failureCount >= delaysMillis.size) return null
        return delaysMillis[failureCount++]
    }

    fun recordRuntime(runtimeMillis: Long) {
        require(runtimeMillis >= 0) { "runtimeMillis must not be negative" }
        if (runtimeMillis >= stableRuntimeMillis) failureCount = 0
    }
}
