/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nv.pair.data.PairNode
import com.nv.pair.data.UiPreferencesRepository
import com.nv.pair.network.AndroidNetworkContext
import com.nv.pair.runtime.PairRuntimeController
import com.nv.pair.runtime.PairRuntimeService
import com.nv.pair.runtime.RuntimePhase
import java.io.IOException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairRuntimeServiceInstrumentedTest {
    @Test
    fun pairsPcSoItCanDiscoverHostedMnnInventory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pcAddress = InstrumentationRegistry.getArguments().getString("pairHostIp")
        assumeTrue("M9 paired discovery requires the PC acceptance harness", !pcAddress.isNullOrBlank())
        val targetPcAddress = requireNotNull(pcAddress)
        val pairingFile = File(context.cacheDir, "m9-pairing.json")
        val controller = PairRuntimeController(context)
        assertTrue("M9 pairing acceptance requires an unpaired Android node", !controller.cluster.value.isClustered)

        controller.start()
        try {
            withTimeout(30_000) { controller.state.first { it.phase == RuntimePhase.RUNNING } }
            val pc = withTimeout(45_000) {
                controller.nodes.first { nodes -> nodes.any { it.isTargetPc(targetPcAddress) } }
                    .first { it.isTargetPc(targetPcAddress) }
            }
            controller.createCluster("PAIR M9 hosted MNN discovery")
            withTimeout(30_000) { controller.cluster.first { it.isClustered } }
            controller.invite(pc.id)
            val invite = withTimeout(30_000) {
                controller.cluster.first { state ->
                    state.invites.any { it.state == "pending" && !it.pin.isNullOrBlank() }
                }.invites.first { it.state == "pending" && !it.pin.isNullOrBlank() }
            }
            assertTrue("PC invite was not pending with a pairing PIN", invite.state == "pending" && !invite.pin.isNullOrBlank())
            pairingFile.writeText(JSONObject().put("inviteId", invite.inviteId).put("pin", invite.pin).toString())

            withTimeout(45_000) {
                controller.cluster.first { state ->
                    state.members.any { member -> member.nodeUuid == pc.hostUuid || member.id == pc.id }
                }
            }
            withTimeout(45_000) {
                controller.nodes.first { nodes -> nodes.any { it.hostUuid == pc.hostUuid && it.trusted } }
            }
            Thread.sleep(10_000)
        } finally {
            pairingFile.delete()
            if (controller.cluster.value.isClustered) {
                controller.leaveCluster()
                withTimeout(30_000) { controller.cluster.first { !it.isClustered } }
            }
            controller.stop()
            withTimeout(30_000) {
                controller.state.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
            }
        }
    }

    @Test
    fun startsHostedMnnFacadeForLanDiscovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wifiAddress = requireNotNull(AndroidNetworkContext(context).wifiInterface()?.ipv4Address) {
            "an active Wi-Fi interface is required for MNN discovery acceptance"
        }
        val controller = PairRuntimeController(context)
        controller.start()
        try {
            withTimeout(30_000) {
                controller.state.first { it.phase == RuntimePhase.RUNNING }
            }
            assertTrue(
                "PAIR MNN facade is not reachable on the Wi-Fi interface",
                canConnectMnnFacade(wifiAddress),
            )
            if (InstrumentationRegistry.getArguments().getString("m9ExternalDiscoveryProbe") == "true") {
                Thread.sleep(45_000)
            }
        } finally {
            controller.stop()
            withTimeout(30_000) {
                controller.state.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
            }
        }
    }

    @Test
    fun mnnHttpFollowsPairRuntimeLifecycle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = PairRuntimeController(context)

        controller.start()
        try {
            withTimeout(30_000) { controller.state.first { it.phase == RuntimePhase.RUNNING } }
            val response = httpHealth()
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("\"status\":\"ok\""))
            AndroidNetworkContext(context).wifiInterface()?.ipv4Address?.let { wifiAddress ->
                assertFalse(
                    "MNN HTTP listener accepted a non-loopback connection.",
                    canConnectMnn(wifiAddress),
                )
            }
        } finally {
            controller.stop()
            withTimeout(30_000) {
                controller.state.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
            }
        }
        assertFalse("MNN HTTP listener remained open after PAIR stopped.", canConnectMnn(MNN_LOOPBACK))
    }

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

    private fun httpHealth(): String = Socket(MNN_LOOPBACK, MNN_PORT).use { socket ->
        socket.soTimeout = MNN_HTTP_TIMEOUT_MILLIS
        socket.getOutputStream().write(
            "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII),
        )
        socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
    }

    private fun canConnectMnn(address: String): Boolean = Socket().use { socket ->
        try {
            socket.connect(InetSocketAddress(address, MNN_PORT), MNN_HTTP_TIMEOUT_MILLIS)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun canConnectMnnFacade(address: String): Boolean = Socket().use { socket ->
        try {
            socket.connect(InetSocketAddress(address, MNN_FACADE_PORT), MNN_HTTP_TIMEOUT_MILLIS)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun PairNode.isTargetPc(address: String): Boolean = ipAddress == address || address in ipAddresses

    private companion object {
        const val MNN_LOOPBACK = "127.0.0.1"
        const val MNN_PORT = 14325
        const val MNN_FACADE_PORT = 14324
        const val MNN_HTTP_TIMEOUT_MILLIS = 5_000
    }
}
