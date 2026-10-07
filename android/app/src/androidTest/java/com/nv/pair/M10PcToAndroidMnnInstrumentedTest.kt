/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nv.pair.data.PairNode
import com.nv.pair.runtime.PairRuntimeController
import com.nv.pair.runtime.RuntimePhase
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M10PcToAndroidMnnInstrumentedTest {
    @Test
    fun hostsAndroidMnnWhilePcAcceptanceClientExercisesRemoteRoute() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val pcAddress = requireNotNull(arguments.getString(ARG_PC_ADDRESS)) {
            "M10 acceptance requires pairHostIp for the paired PC broker."
        }
        val modelId = requireNotNull(arguments.getString(ARG_MODEL_ID)) {
            "M10 acceptance requires pairModelId for the Android MNN model."
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pairingFile = File(context.cacheDir, PAIRING_FILE_NAME)
        val hostCompleteFile = File(context.cacheDir, HOST_COMPLETE_FILE_NAME)
        val controller = PairRuntimeController(context)
        var createdCluster = false

        assertFalse("M10 acceptance requires a clean, unpaired Android test node.", controller.cluster.value.isClustered)
        hostCompleteFile.delete()
        awaitStagedModel(modelId)
        controller.start()
        try {
            withTimeout(RUNTIME_TIMEOUT_MILLIS) {
                controller.state.first { it.phase == RuntimePhase.RUNNING }
            }
            assertFalse("M10 acceptance cannot replace an existing Android cluster.", controller.cluster.value.isClustered)
            val localModels = withContext(Dispatchers.IO) { getLocalMnnModels() }
            assertTrue("The Android MNN runtime does not advertise model $modelId.", localModels.hasModel(modelId))

            val pc = withTimeout(DISCOVERY_TIMEOUT_MILLIS) {
                controller.nodes.first { nodes -> nodes.any { it.isTargetPc(pcAddress) } }
                    .first { it.isTargetPc(pcAddress) }
            }
            controller.createCluster(CLUSTER_NAME)
            withTimeout(CLUSTER_TIMEOUT_MILLIS) { controller.cluster.first { it.isClustered } }
            createdCluster = true
            controller.invite(pc.id)
            val invite = withTimeout(CLUSTER_TIMEOUT_MILLIS) {
                controller.cluster.first { state ->
                    state.invites.any { it.state == INVITE_PENDING && !it.pin.isNullOrBlank() }
                }.invites.first { it.state == INVITE_PENDING && !it.pin.isNullOrBlank() }
            }
            pairingFile.writeText(JSONObject().put("inviteId", invite.inviteId).put("pin", invite.pin).toString())
            try {
                withTimeout(CLUSTER_TIMEOUT_MILLIS) {
                    controller.cluster.first { state ->
                        state.members.any { member -> member.nodeUuid == pc.hostUuid || member.id == pc.id }
                    }
                }
            } finally {
                pairingFile.delete()
            }
            withTimeout(DISCOVERY_TIMEOUT_MILLIS) {
                controller.nodes.first { nodes ->
                    nodes.any { node -> node.hostUuid == pc.hostUuid && node.trusted }
                }
            }

            withTimeout(HOST_ACCEPTANCE_TIMEOUT_MILLIS) {
                while (!hostCompleteFile.isFile) delay(HOST_SIGNAL_POLL_MILLIS)
            }
        } finally {
            pairingFile.delete()
            hostCompleteFile.delete()
            if (createdCluster && controller.cluster.value.isClustered) {
                controller.leaveCluster()
                withTimeout(CLUSTER_TIMEOUT_MILLIS) { controller.cluster.first { !it.isClustered } }
            }
            controller.stop()
            withTimeout(RUNTIME_TIMEOUT_MILLIS) {
                controller.state.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
            }
        }
    }

    private suspend fun awaitStagedModel(modelId: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDirectory = File(context.filesDir, "mnn/models/$modelId")
        val requiredFiles = listOf("config.json", "llm.mnn", "llm.mnn.weight")
        withTimeout(MODEL_STAGE_TIMEOUT_MILLIS) {
            while (!modelDirectory.isDirectory || requiredFiles.any { name ->
                    !File(modelDirectory, name).isFile || File(modelDirectory, name).length() == 0L
                } || (!File(modelDirectory, "tokenizer.txt").isFile && !File(modelDirectory, "tokenizer.mtok").isFile)) {
                delay(HOST_SIGNAL_POLL_MILLIS)
            }
        }
    }

    private suspend fun getLocalMnnModels(): JSONArray = withContext(Dispatchers.IO) {
        val body = Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, LOCAL_MNN_PORT), HTTP_CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = HTTP_READ_TIMEOUT_MILLIS
            socket.getOutputStream().write(
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
        val headerEnd = body.indexOf(HTTP_HEADER_END)
        check(headerEnd >= 0) { "Android MNN /v1/models response omitted HTTP headers." }
        val status = body.substring(0, headerEnd).lineSequence().firstOrNull().orEmpty()
        check(status.contains(" 200 ")) { "Android MNN /v1/models returned $status." }
        JSONObject(body.substring(headerEnd + HTTP_HEADER_END.length)).getJSONArray("data")
    }

    private fun PairNode.isTargetPc(address: String): Boolean = ipAddress == address || address in ipAddresses

    private fun JSONArray.hasModel(modelId: String): Boolean = (0 until length()).any { index ->
        optJSONObject(index)?.optString("id") == modelId
    }

    private companion object {
        const val ARG_PC_ADDRESS = "pairHostIp"
        const val ARG_MODEL_ID = "pairModelId"
        const val LOOPBACK = "127.0.0.1"
        const val LOCAL_MNN_PORT = 14325
        const val HTTP_CONNECT_TIMEOUT_MILLIS = 5_000
        const val HTTP_READ_TIMEOUT_MILLIS = 30_000
        const val HTTP_HEADER_END = "\r\n\r\n"
        const val PAIRING_FILE_NAME = "m10-pairing.json"
        const val HOST_COMPLETE_FILE_NAME = "m10-host-complete"
        const val CLUSTER_NAME = "PAIR M10 PC to Android MNN acceptance"
        const val INVITE_PENDING = "pending"
        const val RUNTIME_TIMEOUT_MILLIS = 30_000L
        const val DISCOVERY_TIMEOUT_MILLIS = 45_000L
        const val CLUSTER_TIMEOUT_MILLIS = 60_000L
        const val HOST_ACCEPTANCE_TIMEOUT_MILLIS = 240_000L
        const val MODEL_STAGE_TIMEOUT_MILLIS = 120_000L
        const val HOST_SIGNAL_POLL_MILLIS = 250L
    }
}
