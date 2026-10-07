/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nv.pair.runtime.PairRuntimeController
import com.nv.pair.runtime.RuntimePhase
import com.nv.pair.network.AndroidNetworkContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MnnHttpInstrumentedTest {
    @Test
    fun runtimeLifecycleServesOpenAiChatAndRecoversAfterClientDisconnect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val modelPath = arguments.getString(ARG_MODEL_DIR)
        assertFalse("MNN HTTP acceptance requires pairMnnModelDir.", modelPath.isNullOrBlank())
        val modelDirectory = File(requireNotNull(modelPath))
        val modelId = modelDirectory.name
        assertTrue("MNN acceptance model directory is missing.", modelDirectory.isDirectory)

        val controller = PairRuntimeController(context)
        controller.start()
        try {
            withTimeout(RUNTIME_TIMEOUT_MILLIS) {
                controller.state.first { it.phase == RuntimePhase.RUNNING }
            }
            var health = Response(0, "", "")
            repeat(HTTP_READY_RETRY_COUNT) {
                if (health.statusCode != HTTP_OK) {
                    delayBriefly()
                    health = get(HEALTH_PATH)
                }
            }
            assertEquals(HTTP_OK, health.statusCode)
            val wifiAddress = AndroidNetworkContext(context).wifiInterface()?.ipv4Address
            if (wifiAddress != null) {
                assertFalse("MNN HTTP listener accepted a non-loopback connection.", canConnect(wifiAddress))
            }
            val models = get(MODELS_PATH)
            assertEquals(HTTP_OK, models.statusCode)
            assertTrue(JSONObject(models.body).getJSONArray("data").hasModel(modelId))

            val loaded = post(LOAD_PATH, JSONObject().put("model", modelId).put("backend", "cpu"))
            assertEquals(HTTP_OK, loaded.statusCode)
            assertEquals(modelId, JSONObject(loaded.body).optString("model"))
            assertEquals(modelId, JSONObject(get(LOADED_PATH).body).optString("model"))

            val completion = post(CHAT_PATH, chatRequest(modelId, stream = false))
            assertEquals(HTTP_OK, completion.statusCode)
            val response = JSONObject(completion.body)
            assertEquals("chat.completion", response.optString("object"))
            assertTrue(response.getJSONArray("choices").length() > 0)

            val stream = post(CHAT_PATH, chatRequest(modelId, stream = true))
            assertEquals(HTTP_OK, stream.statusCode)
            assertTrue(stream.contentType.startsWith("text/event-stream"))
            val firstToken = stream.body.indexOfFirstContentEvent()
            val done = stream.body.indexOf("data: [DONE]")
            assertTrue("SSE stream returned no visible token delta.", firstToken >= 0)
            assertTrue("SSE stream finished before the first token arrived.", done > firstToken)

            disconnectDuringStream(modelId)
            var nextRequest = Response(HTTP_CONFLICT, "application/json", "")
            repeat(RETRY_AFTER_CANCEL_COUNT) {
                if (nextRequest.statusCode != HTTP_OK) {
                    delayBriefly()
                    nextRequest = post(CHAT_PATH, chatRequest(modelId, stream = false))
                }
            }
            assertEquals("MNN did not accept a request after client disconnect cancellation.", HTTP_OK, nextRequest.statusCode)

            val unload = post(UNLOAD_PATH, JSONObject())
            assertEquals(HTTP_OK, unload.statusCode)
            assertFalse(JSONObject(get(LOADED_PATH).body).optBoolean("loaded"))
        } finally {
            controller.stop()
            withTimeout(RUNTIME_TIMEOUT_MILLIS) {
                controller.state.first { it.phase == RuntimePhase.STOPPED && !it.desiredRunning }
            }
            assertFalse("MNN HTTP listener remained open after runtime shutdown.", canConnect(LOOPBACK))
        }
    }

    private fun disconnectDuringStream(modelId: String) {
        val socket = Socket()
        socket.setSoLinger(true, 0)
        socket.connect(InetSocketAddress(LOOPBACK, MNN_PORT), HTTP_CONNECT_TIMEOUT_MILLIS)
        socket.soTimeout = HTTP_READ_TIMEOUT_MILLIS
        val body = chatRequest(modelId, stream = true, maxTokens = CANCELLATION_MAX_TOKENS).toString()
        socket.getOutputStream().write(requestBytes("POST", CHAT_PATH, body))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        assertTrue(reader.readLine().orEmpty().contains(" 200 "))
        while (reader.readLine()?.isEmpty() == false) Unit
        var event: String?
        do {
            event = reader.readLine()
        } while (event != null && !event.startsWith("data: "))
        assertTrue("Streaming response ended before its first token.", event != null)
        socket.close()
    }

    private fun get(path: String): Response = exchange("GET", path, "")

    private fun post(path: String, body: JSONObject): Response = exchange("POST", path, body.toString())

    private fun exchange(method: String, path: String, body: String): Response =
        Socket(LOOPBACK, MNN_PORT).use { socket ->
            socket.soTimeout = HTTP_READ_TIMEOUT_MILLIS
            socket.getOutputStream().write(requestBytes(method, path, body))
            val responseBytes = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val headerEnd = responseBytes.indexOf("\r\n\r\n")
            assertTrue("HTTP response headers are missing.", headerEnd >= 0)
            val headers = responseBytes.substring(0, headerEnd)
            val statusCode = headers.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val contentType = headers.lineSequence()
                .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
            Response(statusCode, contentType, responseBytes.substring(headerEnd + HTTP_HEADER_END.length))
        }

    private fun requestBytes(method: String, path: String, body: String): ByteArray {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
        return headers.toByteArray(StandardCharsets.US_ASCII) + bodyBytes
    }

    private fun chatRequest(modelId: String, stream: Boolean, maxTokens: Int = NORMAL_MAX_TOKENS): JSONObject = JSONObject()
        .put("model", modelId)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_MESSAGE))
            .put(JSONObject().put("role", "user").put("content", USER_MESSAGE)))
        .put("stream", stream)
        .put("max_tokens", maxTokens)
        .put("temperature", 0.2)
        .put("top_p", 0.9)
        .put("seed", 13)

    private fun String.indexOfFirstContentEvent(): Int = lineSequence()
        .firstOrNull { line ->
            line.startsWith("data: ") && !line.startsWith("data: [DONE]") &&
                JSONObject(line.removePrefix("data: ")).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty().isNotBlank()
        }
        ?.let { indexOf(it) }
        ?: -1

    private fun JSONArray.hasModel(modelId: String): Boolean = (0 until length()).any { index ->
        optJSONObject(index)?.optString("id") == modelId
    }

    private fun delayBriefly() {
        Thread.sleep(RETRY_DELAY_MILLIS)
    }

    private fun canConnect(address: String): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(address, MNN_PORT), HTTP_CONNECT_TIMEOUT_MILLIS)
            true
        } catch (_: IOException) {
            false
        } finally {
            socket.close()
        }
    }

    private data class Response(val statusCode: Int, val contentType: String, val body: String)

    private companion object {
        const val ARG_MODEL_DIR = "pairMnnModelDir"
        const val HEALTH_PATH = "/healthz"
        const val MODELS_PATH = "/v1/models"
        const val LOADED_PATH = "/internal/models/loaded"
        const val LOAD_PATH = "/internal/models/load"
        const val UNLOAD_PATH = "/internal/models/unload"
        const val CHAT_PATH = "/v1/chat/completions"
        const val MNN_PORT = 14325
        const val HTTP_OK = 200
        const val HTTP_CONFLICT = 409
        const val HTTP_CONNECT_TIMEOUT_MILLIS = 5_000
        const val HTTP_READ_TIMEOUT_MILLIS = 120_000
        const val RUNTIME_TIMEOUT_MILLIS = 60_000L
        const val HTTP_READY_RETRY_COUNT = 30
        const val NORMAL_MAX_TOKENS = 128
        const val CANCELLATION_MAX_TOKENS = 512
        const val RETRY_AFTER_CANCEL_COUNT = 30
        const val RETRY_DELAY_MILLIS = 250L
        const val SYSTEM_MESSAGE = "Answer briefly."
        const val USER_MESSAGE = "Reply with a short greeting and count to three. /no_think"
        const val HTTP_HEADER_END = "\r\n\r\n"
        const val LOOPBACK = "127.0.0.1"
    }
}
