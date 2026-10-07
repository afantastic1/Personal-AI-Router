/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nv.pair.data.PairNode
import com.nv.pair.mnn.MnnErrorCode
import com.nv.pair.runtime.PairRuntimeController
import com.nv.pair.runtime.RuntimePhase
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M65AndroidToPcRoutingInstrumentedTest {
    @Test
    fun streamsPcOnlyModelThroughAndroidEngineFacade() = runBlocking {
        runPcRoutingAcceptance(mnnPortUnavailable = false)
    }

    @Test
    fun mnnUnavailableStillAllowsDiscoveryAndPcRouting() = runBlocking {
        runPcRoutingAcceptance(mnnPortUnavailable = true)
    }

    private suspend fun runPcRoutingAcceptance(mnnPortUnavailable: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val pcAddress = requireNotNull(arguments.getString(ARG_PC_ADDRESS))
        val modelId = requireNotNull(arguments.getString(ARG_MODEL_ID))
        val engine = arguments.getString(ARG_ENGINE) ?: DEFAULT_ENGINE
        val controller = PairRuntimeController(context)
        val pairingFile = File(context.cacheDir, PAIRING_FILE_NAME)

        assertTrue("PC address argument is empty", pcAddress.isNotBlank())
        assertTrue("model ID argument is empty", modelId.isNotBlank())
        assertFalse("M6.5 requires an unpaired Android node", controller.cluster.value.isClustered)

        val mnnPortReservation = if (mnnPortUnavailable) {
            ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress("127.0.0.1", MNN_PORT))
            }
        } else {
            null
        }
        try {
            controller.start()
            withTimeout(RUNTIME_TIMEOUT_MILLIS) {
                controller.state.first { it.phase == RuntimePhase.RUNNING }
            }
            if (mnnPortUnavailable) {
                assertFalse("local MNN must report unavailable", controller.mnnLocalEngine.value.available)
                assertEquals(MnnErrorCode.INTERNAL_ERROR, controller.mnnLocalEngine.value.errorCode)
                assertFalse("MNN facade must not be started", canConnect("127.0.0.1", MNN_FACADE_PORT))
            }

            val pc = withTimeout(DISCOVERY_TIMEOUT_MILLIS) {
                controller.nodes.first { nodes -> nodes.any { it.isTargetPc(pcAddress) } }
                    .first { it.isTargetPc(pcAddress) }
            }
            controller.createCluster(CLUSTER_NAME)
            withTimeout(CLUSTER_TIMEOUT_MILLIS) {
                controller.cluster.first { it.isClustered }
            }
            controller.invite(pc.id)
            val invite = withTimeout(CLUSTER_TIMEOUT_MILLIS) {
                controller.cluster.first { state ->
                    state.invites.any { it.state == INVITE_PENDING && !it.pin.isNullOrBlank() }
                }.invites.first { it.state == INVITE_PENDING && !it.pin.isNullOrBlank() }
            }
            val pin = requireNotNull(invite.pin)
            pairingFile.writeText(
                JSONObject()
                    .put("inviteId", invite.inviteId)
                    .put("pin", pin)
                    .toString(),
            )

            try {
                withTimeout(CLUSTER_TIMEOUT_MILLIS) {
                    controller.cluster.first { state ->
                        state.members.any { member ->
                            member.nodeUuid == pc.hostUuid || member.id == pc.id
                        }
                    }
                }
            } finally {
                pairingFile.delete()
            }

            val trustedPc = withTimeout(DISCOVERY_TIMEOUT_MILLIS) {
                controller.nodes.first { nodes -> nodes.any { it.hostUuid == pc.hostUuid && it.trusted } }
                    .first { it.hostUuid == pc.hostUuid && it.trusted }
            }
            val pcWithModel = withTimeout(DISCOVERY_TIMEOUT_MILLIS) {
                controller.nodes.first { nodes ->
                    nodes.any { node -> node.hostUuid == trustedPc.hostUuid && modelId in node.modelsByEngine[engine].orEmpty() }
                }.first { node -> node.hostUuid == trustedPc.hostUuid && modelId in node.modelsByEngine[engine].orEmpty() }
            }
            assertEquals(trustedPc.hostUuid, pcWithModel.hostUuid)
            assertTrue("the phone connected to the PC engine backend directly", engineBackendIsUnreachable(pcAddress))

            val facadePort = withTimeout(PROXY_TIMEOUT_MILLIS) {
                controller.proxies.first { proxies -> proxies.any { it.engine == engine && it.ready } }
                    .first { it.engine == engine && it.ready }.port
            }
            val stream = requestStream(facadePort, modelId)
            assertTrue("the engine facade did not return HTTP 200", stream.httpOk)
            assertTrue("the engine facade did not return an SSE response", stream.contentType.startsWith("text/event-stream"))
            assertTrue(
                "the stream did not contain a non-empty token delta " +
                    "(events=${stream.eventCount}, topLevel=${stream.topLevelKeys}, " +
                    "choice=${stream.choiceKeys}, delta=${stream.deltaKeys}, " +
                    "nonEmptyDelta=${stream.nonEmptyDeltaKeys}, error=${stream.errorCode})",
                stream.tokenCount > 0,
            )
            assertTrue("the stream ended before a token arrived", stream.firstTokenMillis < stream.completedMillis)
            assertTrue("the SSE stream did not terminate with [DONE]", stream.done)

            val workload = withTimeout(WORKLOAD_TIMEOUT_MILLIS) {
                controller.workloads.first { workloads ->
                    workloads.any { it.model == modelId && it.engine == engine && it.completedAt != null }
                }.first { it.model == modelId && it.engine == engine && it.completedAt != null }
            }
            assertEquals("the request was not scheduled on the PC", pc.hostUuid, workload.scheduledOn)
            assertEquals("the request did not complete", WORKLOAD_COMPLETED, workload.state)
        } finally {
            try {
                pairingFile.delete()
                if (controller.cluster.value.isClustered) {
                    controller.leaveCluster()
                    withTimeout(CLUSTER_TIMEOUT_MILLIS) {
                        controller.cluster.first { !it.isClustered }
                    }
                }
                controller.stop()
                withTimeout(RUNTIME_TIMEOUT_MILLIS) {
                    controller.state.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
                }
            } finally {
                mnnPortReservation?.close()
            }
        }
    }

    private fun PairNode.isTargetPc(address: String): Boolean = ipAddress == address || address in ipAddresses

    private fun canConnect(address: String, port: Int): Boolean = Socket().use { socket ->
        try {
            socket.connect(InetSocketAddress(address, port), BACKEND_CONNECT_TIMEOUT_MILLIS)
            true
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun engineBackendIsUnreachable(address: String): Boolean = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, ENGINE_BACKEND_PORT), BACKEND_CONNECT_TIMEOUT_MILLIS)
            false
        } catch (_: IOException) {
            true
        } finally {
            socket.close()
        }
    }

    private suspend fun requestStream(port: Int, modelId: String): StreamResult = withContext(Dispatchers.IO) {
        val request = JSONObject()
            .put("model", modelId)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", TEST_MESSAGE),
            ))
            .put("stream", true)
            .put("max_tokens", 24)
            .put("temperature", 0.2)
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), HTTP_CONNECT_TIMEOUT_MILLIS)
        socket.soTimeout = HTTP_READ_TIMEOUT_MILLIS
        val requestBody = request.toString().toByteArray(StandardCharsets.UTF_8)
        socket.getOutputStream().write(
            ("POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Accept: text/event-stream\r\n" +
                "Content-Length: ${requestBody.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8) + requestBody,
        )
        var tokenCount = 0
        var eventCount = 0
        var topLevelKeys = ""
        var choiceKeys = ""
        var deltaKeys = ""
        val nonEmptyDeltaKeys = mutableSetOf<String>()
        var errorCode = ""
        var firstTokenMillis = Long.MAX_VALUE
        var completedMillis = Long.MAX_VALUE
        var done = false
        var statusLine = ""
        var contentType = ""
        val startedAt = android.os.SystemClock.elapsedRealtime()
        BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).use { reader ->
            statusLine = reader.readLine().orEmpty()
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Type:", ignoreCase = true)) {
                    contentType = header.substringAfter(':').trim()
                }
            }
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith(SSE_DATA_PREFIX)) continue
                val payload = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (payload == SSE_DONE) {
                    done = true
                    completedMillis = android.os.SystemClock.elapsedRealtime() - startedAt
                    break
                }
                eventCount++
                val message = JSONObject(payload)
                topLevelKeys = message.keys().asSequence().sorted().joinToString(",")
                errorCode = message.optJSONObject("error")?.optString("code").orEmpty()
                val choices = message.optJSONArray("choices") ?: continue
                val choice = choices.optJSONObject(0) ?: continue
                choiceKeys = choice.keys().asSequence().sorted().joinToString(",")
                val deltaObject = choice.optJSONObject("delta") ?: continue
                deltaKeys = deltaObject.keys().asSequence().sorted().joinToString(",")
                deltaObject.keys().forEach { key ->
                    if (deltaObject.opt(key)?.toString()?.isNotBlank() == true) {
                        nonEmptyDeltaKeys.add(key)
                    }
                }
                val delta = deltaObject.optString("content")
                if (delta.isNotBlank()) {
                    tokenCount++
                    if (firstTokenMillis == Long.MAX_VALUE) {
                        firstTokenMillis = android.os.SystemClock.elapsedRealtime() - startedAt
                    }
                }
            }
        }
        val result = StreamResult(
            httpOk = statusLine.contains(HTTP_OK_STATUS),
            contentType = contentType,
            tokenCount = tokenCount,
            firstTokenMillis = firstTokenMillis,
            completedMillis = completedMillis,
            done = done,
            eventCount = eventCount,
            topLevelKeys = topLevelKeys,
            choiceKeys = choiceKeys,
            deltaKeys = deltaKeys,
            nonEmptyDeltaKeys = nonEmptyDeltaKeys.sorted().joinToString(","),
            errorCode = errorCode,
        )
        socket.close()
        result
    }

    private data class StreamResult(
        val httpOk: Boolean,
        val contentType: String,
        val tokenCount: Int,
        val firstTokenMillis: Long,
        val completedMillis: Long,
        val done: Boolean,
        val eventCount: Int,
        val topLevelKeys: String,
        val choiceKeys: String,
        val deltaKeys: String,
        val nonEmptyDeltaKeys: String,
        val errorCode: String,
    )

    private companion object {
        const val ARG_PC_ADDRESS = "pairHostIp"
        const val ARG_MODEL_ID = "pairModelId"
        const val ARG_ENGINE = "pairEngine"
        const val DEFAULT_ENGINE = "lmstudio"
        const val PAIRING_FILE_NAME = "m65-pairing.json"
        const val CLUSTER_NAME = "PAIR M6.5 Android to PC acceptance"
        const val ENGINE_BACKEND_PORT = 1235
        const val MNN_PORT = 14325
        const val MNN_FACADE_PORT = 14324
        const val BACKEND_CONNECT_TIMEOUT_MILLIS = 750
        const val RUNTIME_TIMEOUT_MILLIS = 30_000L
        const val DISCOVERY_TIMEOUT_MILLIS = 45_000L
        const val CLUSTER_TIMEOUT_MILLIS = 60_000L
        const val PROXY_TIMEOUT_MILLIS = 30_000L
        const val WORKLOAD_TIMEOUT_MILLIS = 90_000L
        const val HTTP_CONNECT_TIMEOUT_MILLIS = 10_000
        const val HTTP_READ_TIMEOUT_MILLIS = 120_000
        const val HTTP_OK_STATUS = " 200 "
        const val INVITE_PENDING = "pending"
        const val WORKLOAD_COMPLETED = "completed"
        const val SSE_DATA_PREFIX = "data:"
        const val SSE_DONE = "[DONE]"
        const val TEST_MESSAGE = "Reply with a short greeting and count to three. /no_think"
    }
}
