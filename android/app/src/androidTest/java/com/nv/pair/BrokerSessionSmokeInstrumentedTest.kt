/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nv.pair.rpc.BrokerRuntimeInfo
import com.nv.pair.rpc.BrokerSession
import com.nv.pair.rpc.ClusterApi
import com.nv.pair.rpc.RouterApi
import com.nv.pair.data.ClusterRepository
import com.nv.pair.data.RouterRepository
import com.nv.pair.mnn.MnnRuntimeContainer
import com.nv.pair.runtime.NativeBinaryRegistry
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class BrokerSessionSmokeInstrumentedTest {
    @Test
    fun brokerReportsRuntimeBoundProxyPorts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binaries = NativeBinaryRegistry(File(context.applicationInfo.nativeLibraryDir))
        val session = BrokerSession(binaries, context.filesDir, context.cacheDir)
        try {
            session.start()
            RouterApi(session).initializeWorkloads(RouterRepository())
            val ollama = awaitReadyProxy(session, "ollama-proxy:get-status")
            val lmStudio = awaitReadyProxy(session, "lmstudio-proxy:get-status")

            assertTrue("Ollama proxy returned an invalid port", ollama.optInt("port") in 1..65535)
            assertTrue("LM Studio proxy returned an invalid port", lmStudio.optInt("port") in 1..65535)
        } finally {
            session.close()
        }
    }

    @Test
    fun brokerProbesHostedMnnAndDoesNotStopItsParentOwnedRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = MnnRuntimeContainer(File(context.filesDir, "mnn/models"))
        val session = BrokerSession(
            NativeBinaryRegistry(File(context.applicationInfo.nativeLibraryDir)),
            context.filesDir,
            context.cacheDir,
        )
        try {
            runtime.start()
            session.start()

            val statusParams = JSONObject().put("engine", "mnn")
            val status = session.request("engine:status", statusParams)
            assertTrue("MNN hosted engine was not reported installed", status.optBoolean("installed"))
            assertTrue("MNN hosted engine was not reported running", status.optBoolean("running"))
            assertTrue("MNN hosted engine was not reported healthy", status.optBoolean("healthy"))
            assertEquals(14325, status.optInt("port"))

            val inventory = session.request("engine:models")
            val byEngine = inventory.optJSONObject("modelsByEngine")
            assertTrue("MNN model inventory was not present", byEngine?.has("mnn") == true)

            val facade = awaitReadyProxy(session, "mnn-proxy:get-status")
            assertEquals(14324, facade.optInt("port"))
            val stop = runCatching { session.request("engine:stop", statusParams) }
            assertTrue("hosted MNN stop unexpectedly succeeded", stop.isFailure)
            val afterStop = session.request("engine:status", statusParams)
            assertTrue("broker stopped the parent-owned MNN runtime", afterStop.optBoolean("running"))
            val discoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var discoveredMnn = false
            while (System.nanoTime() < discoveryDeadline && !discoveredMnn) {
                val nodes = session.request("discovery:get-nodes").optJSONArray("nodes")
                if (nodes != null) {
                    for (index in 0 until nodes.length()) {
                        val modelsByEngine = nodes.optJSONObject(index)?.optJSONObject("modelsByEngine")
                        if (modelsByEngine?.has("mnn") == true) {
                            discoveredMnn = true
                            break
                        }
                    }
                }
                if (!discoveredMnn) Thread.sleep(250)
            }
            assertTrue("PAIR discovery did not expose modelsByEngine.mnn", discoveredMnn)
        } finally {
            session.close()
            runtime.close()
        }
    }

    @Test
    fun androidProxyServesItsOpenAiModelRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = BrokerSession(
            NativeBinaryRegistry(File(context.applicationInfo.nativeLibraryDir)),
            context.filesDir,
            context.cacheDir,
        )
        try {
            session.start()
            val status = awaitReadyProxy(session, "ollama-proxy:get-status")
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", status.optInt("port")), 5_000)
                socket.soTimeout = 5_000
                socket.getOutputStream().write(
                    "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                val responseLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                val responseCode = responseLine.split(' ').getOrNull(1)?.toIntOrNull()
                assertTrue("proxy did not return an HTTP response", responseLine.startsWith("HTTP/1."))
                assertTrue("proxy did not recognize the OpenAI model-list route", responseCode != 404)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun unifiedGatewayServesOpenAiModelListOnLoopback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = BrokerSession(
            NativeBinaryRegistry(File(context.applicationInfo.nativeLibraryDir)),
            context.filesDir,
            context.cacheDir,
        )
        try {
            session.start()
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 14326), 5_000)
                socket.soTimeout = 5_000
                socket.getOutputStream().write(
                    "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue("gateway did not serve HTTP 200", response.startsWith("HTTP/1.1 200"))
                assertTrue("gateway did not return the OpenAI list shape", response.contains("\"object\":\"list\""))
                assertTrue("gateway leaked engine/node details", !response.contains("127.0.0.1:14325"))
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun stagesRouterWorkersRequiredForTheAndroidProxy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binaries = NativeBinaryRegistry(File(context.applicationInfo.nativeLibraryDir))

        assertTrue("nvpair-proxy is not packaged", binaries.proxy().isFile)
        assertTrue("nvpair-job-scheduler is not packaged", binaries.scheduler().isFile)
        assertTrue("nvpair-workload-manager is not packaged", binaries.workloadManager().isFile)
        assertTrue("nvpair-errors is not packaged", binaries.errors().isFile)
    }

    @Test
    fun createdClusterIdentitySurvivesBrokerRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binaries = NativeBinaryRegistry(File(context.applicationInfo.nativeLibraryDir))
        val isolatedHome = File(context.cacheDir, "cluster-persistence-${UUID.randomUUID()}")
        val isolatedCache = File(isolatedHome, "cache")
        try {
            val first = BrokerSession(binaries, isolatedHome, isolatedCache)
            first.start()
            val created = ClusterApi(first).create("PAIR persistence test")
            val clusterId = created.optString("clusterId")
            assertTrue("cluster manager did not create a cluster identity", clusterId.isNotBlank())
            first.close()

            val restarted = BrokerSession(binaries, isolatedHome, isolatedCache)
            try {
                restarted.start()
                val repository = com.nv.pair.data.ClusterRepository()
                ClusterApi(restarted).initialize(repository)
                assertEquals(clusterId, repository.state.value.identity?.clusterId)
                assertEquals("PAIR persistence test", repository.state.value.identity?.clusterFriendlyName)
            } finally {
                restarted.close()
            }
        } finally {
            isolatedHome.deleteRecursively()
        }
    }

    @Test
    fun brokerStartsScannerAnswersPingAndStopsOnStdinEof() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binaries = NativeBinaryRegistry(File(context.applicationInfo.nativeLibraryDir))
        val crashes = AtomicInteger()
        val session = BrokerSession(
            binaries = binaries,
            filesDir = context.filesDir,
            cacheDir = context.cacheDir,
            onCrash = { crashes.set(it) },
        )

        val runtime: BrokerRuntimeInfo = session.start()
        assertEquals("1.2.1", runtime.version)
        assertTrue(runtime.uptimeMillis >= 0)
        val clusterId = session.request("settings/get-cluster-id").optString("value")
        assertEquals("", clusterId)
        val nodeIdentity = session.request("cluster:get-node-id")
        assertTrue("cluster-manager did not provide a node UUID", nodeIdentity.optString("nodeUuid").isNotBlank())
        val clusterRepository = ClusterRepository()
        ClusterApi(session).initialize(clusterRepository)
        assertEquals(nodeIdentity.optString("nodeUuid"), clusterRepository.state.value.identity?.nodeUuid)

        session.close()

        assertEquals(0, crashes.get())
    }

    @Test
    fun reportsBrokerExitBeforeReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binaries = NativeBinaryRegistry(File(context.applicationInfo.nativeLibraryDir))
        val crashes = AtomicInteger(-1)
        val crashDetected = CountDownLatch(1)
        val missingScanner = File(context.cacheDir, "missing-pair-scanner")
        val session = BrokerSession(
            binaries = binaries,
            filesDir = context.filesDir,
            cacheDir = context.cacheDir,
            onCrash = {
                crashes.set(it)
                crashDetected.countDown()
            },
            scannerBinaryOverride = missingScanner,
        )

        val failure = runCatching { session.start() }.exceptionOrNull()

        assertTrue("start should report broker exit before ready: $failure", failure is IOException)
        assertTrue("unexpected broker exit was not reported", crashDetected.await(2, TimeUnit.SECONDS))
        assertEquals(1, crashes.get())
        session.close()
    }

    private fun awaitReadyProxy(session: BrokerSession, method: String): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val status = session.request(method)
            if (status.optBoolean("ready")) return status
            Thread.sleep(250)
        }
        throw AssertionError("$method never reported a ready local facade")
    }
}
