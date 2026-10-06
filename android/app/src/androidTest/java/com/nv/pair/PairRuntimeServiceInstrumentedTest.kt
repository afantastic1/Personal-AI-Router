/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nv.pair.data.UiPreferencesRepository
import com.nv.pair.runtime.PairRuntimeController
import com.nv.pair.runtime.PairRuntimeService
import com.nv.pair.runtime.RuntimePhase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairRuntimeServiceInstrumentedTest {
    @Test
    fun startsForegroundRuntimeAndStopsGracefully() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = PairRuntimeController(context)
        val preferences = UiPreferencesRepository(context)

        controller.start()
        val running = withTimeout(30_000) {
            controller.state.first { it.phase == RuntimePhase.RUNNING }
        }
        assertEquals("1.2.1", running.version)
        assertTrue(preferences.readDesiredRuntimeRunning())

        controller.stop()
        val stopped = withTimeout(30_000) {
            controller.state.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
        }
        assertEquals(RuntimePhase.STOPPED, stopped.phase)
        assertFalse(preferences.readDesiredRuntimeRunning())
    }

    @Test
    fun stoppingRuntimeLeavesClusterBeforeBrokerCloses() = runBlocking {
        val controller = PairRuntimeController(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            controller.start()
            withTimeout(30_000) { controller.state.first { it.phase == RuntimePhase.RUNNING } }

            controller.createCluster("PAIR runtime stop test")
            val clustered = withTimeout(15_000) { controller.cluster.first { it.isClustered } }
            assertTrue(clustered.identity?.clusterId?.isNotBlank() == true)

            controller.stop()
            withTimeout(30_000) {
                controller.state.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
            }
            val unclustered = withTimeout(5_000) { controller.cluster.first { !it.isClustered } }
            assertTrue(unclustered.members.isEmpty())
        } finally {
            controller.stop()
        }
    }

    @Test
    fun restoresDesiredRuntimeFromStickyNullIntent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = UiPreferencesRepository(context)
        preferences.setDesiredRuntimeRunning(true)

        ContextCompat.startForegroundService(context, Intent(context, PairRuntimeService::class.java))
        val running = withTimeout(30_000) {
            PairRuntimeService.runtimeState.first { it.phase == RuntimePhase.RUNNING }
        }
        assertEquals("1.2.1", running.version)

        PairRuntimeController(context).stop()
        withTimeout(30_000) {
            PairRuntimeService.runtimeState.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
        }
        assertFalse(preferences.readDesiredRuntimeRunning())
    }

    @Test
    fun discoversConfiguredLanHost() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuredHostIp = InstrumentationRegistry.getArguments().getString("pairHostIp")
        assumeTrue("pass pairHostIp to run live LAN discovery acceptance", !configuredHostIp.isNullOrBlank())
        val hostIp = requireNotNull(configuredHostIp)
        val controller = PairRuntimeController(context)

        controller.start()
        withTimeout(30_000) { controller.state.first { it.phase == RuntimePhase.RUNNING } }
        val host = withTimeout(45_000) {
            controller.nodes.first { nodes -> nodes.any { it.ipAddress == hostIp || hostIp in it.ipAddresses } }
                .first { it.ipAddress == hostIp || hostIp in it.ipAddresses }
        }

        assertTrue("host UUID missing for $hostIp", host.hostUuid.isNotBlank())
        assertTrue("node name missing for $hostIp", host.name.isNotBlank())
        assertEquals(hostIp, host.ipAddress)
        controller.stop()
        val stopped = withTimeout(30_000) {
            controller.state.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
        }
        assertEquals(RuntimePhase.STOPPED, stopped.phase)
    }
}
