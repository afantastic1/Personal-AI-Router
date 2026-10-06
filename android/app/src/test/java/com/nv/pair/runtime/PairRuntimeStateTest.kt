/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairRuntimeStateTest {
    @Test
    fun followsStartupRunningCrashBackoffAndStopTransitions() {
        val starting = PairRuntimeState.stopped().transitionTo(RuntimePhase.STARTING, desiredRunning = true)
        val waiting = starting.transitionTo(RuntimePhase.WAITING_READY)
        val running = waiting.transitionTo(RuntimePhase.RUNNING, version = "1.2.1")
        val crashed = running.transitionTo(RuntimePhase.CRASHED, error = "broker exited")
        val backoff = crashed.transitionTo(RuntimePhase.RESTART_BACKOFF)
        val restarting = backoff.transitionTo(RuntimePhase.STARTING)
        val stopping = restarting.transitionTo(RuntimePhase.STOPPING, desiredRunning = false)
        val stopped = stopping.transitionTo(RuntimePhase.STOPPED)

        assertEquals(RuntimePhase.WAITING_READY, waiting.phase)
        assertEquals("1.2.1", running.version)
        assertEquals(false, stopped.desiredRunning)
        assertEquals(RuntimePhase.STOPPED, stopped.phase)
    }

    @Test
    fun rejectsSkippingBrokerReadyGate() {
        val starting = PairRuntimeState.stopped().transitionTo(RuntimePhase.STARTING, desiredRunning = true)

        assertThrows(IllegalStateException::class.java) {
            starting.transitionTo(RuntimePhase.RUNNING, version = "1.2.1")
        }
    }

    @Test
    fun canReportForegroundStartupFailureBeforeLaunchingBroker() {
        val failed = PairRuntimeState.stopped()
            .transitionTo(RuntimePhase.STARTUP_FAILED, desiredRunning = false, error = "Wi-Fi unavailable")

        assertEquals(RuntimePhase.STARTUP_FAILED, failed.phase)
        assertEquals("Wi-Fi unavailable", failed.error)
    }
}
