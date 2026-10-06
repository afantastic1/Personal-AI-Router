/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrokerRestartPolicyTest {
    @Test
    fun allowsFiveCappedExponentialRestarts() {
        val policy = BrokerRestartPolicy()

        assertEquals(1_000L, policy.nextDelayMillis())
        assertEquals(2_000L, policy.nextDelayMillis())
        assertEquals(4_000L, policy.nextDelayMillis())
        assertEquals(8_000L, policy.nextDelayMillis())
        assertEquals(16_000L, policy.nextDelayMillis())
        assertNull(policy.nextDelayMillis())
    }

    @Test
    fun stableRuntimeResetsTheCrashStreak() {
        val policy = BrokerRestartPolicy()
        policy.nextDelayMillis()
        policy.nextDelayMillis()

        policy.recordRuntime(60_000)

        assertEquals(1_000L, policy.nextDelayMillis())
    }

    @Test
    fun shortRuntimeKeepsTheCrashStreak() {
        val policy = BrokerRestartPolicy()
        policy.nextDelayMillis()

        policy.recordRuntime(59_999)

        assertEquals(2_000L, policy.nextDelayMillis())
    }
}
