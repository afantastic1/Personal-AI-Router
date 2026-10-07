/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnChatMessage
import com.nv.pair.mnn.MnnChatRequest
import com.nv.pair.mnn.MnnChatRole
import com.nv.pair.mnn.MnnError
import com.nv.pair.mnn.MnnErrorCode
import com.nv.pair.mnn.MnnGenerationResult
import com.nv.pair.mnn.MnnInferenceService
import com.nv.pair.mnn.MnnLoadedModel
import com.nv.pair.mnn.MnnModelDescriptor
import com.nv.pair.mnn.MnnResult
import com.nv.pair.mnn.MnnRuntimeMetrics
import com.nv.pair.mnn.MnnRuntimeStatus
import com.nv.pair.mnn.MnnEngineState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnHttpServerTest {
    @Test
    fun healthModelsAndChatUseLoopbackOpenAiContracts() {
        val service = FakeInferenceService()
        val server = MnnHttpServer(service, TEST_PORT)
        server.start()
        try {
            assertEquals("127.0.0.1", InetAddress.getByName("127.0.0.1").hostAddress)
            val health = http(server.localPort, "GET", "/healthz")
            val models = http(server.localPort, "GET", "/v1/models")
            val completion = http(
                server.localPort,
                "POST",
                "/v1/chat/completions",
                chatBody(stream = false),
            )

            assertTrue(health.startsWith("HTTP/1.1 200"))
            assertTrue(models.contains("\"id\":\"qwen3-0.6b\""))
            assertTrue(completion.startsWith("HTTP/1.1 200"))
            assertTrue(completion.contains("\"object\":\"chat.completion\""))
            assertTrue(completion.contains("\"content\":\"Hello world\""))
            assertEquals(listOf(MnnChatMessage(MnnChatRole.USER, "Say hello.")), service.lastRequest?.messages)
        } finally {
            server.close()
        }
    }

    @Test
    fun streamsFirstTokenBeforeDoneAndRejectsUnsupportedCapabilities() {
        val server = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        server.start()
        try {
            val stream = http(server.localPort, "POST", "/v1/chat/completions", chatBody(stream = true))
            val firstEvent = stream.indexOf("data: {")
            val done = stream.indexOf("data: [DONE]")
            assertTrue(stream.contains("Content-Type: text/event-stream"))
            assertTrue(stream, firstEvent >= 0)
            assertTrue(done > firstEvent)
            assertTrue(stream.contains("\"content\":\"Hello\""))

            val usageStream = http(
                server.localPort,
                "POST",
                "/v1/chat/completions",
                """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"x"}],"stream":true,"stream_options":{"include_usage":true}}""",
            )
            assertTrue(usageStream.contains("\"prompt_tokens\":0"))
            assertTrue(usageStream.contains("\"completion_tokens\":2"))

            val unsupported = http(
                server.localPort,
                "POST",
                "/v1/chat/completions",
                """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"x"}],"tools":[]}""",
            )
            assertTrue(unsupported.startsWith("HTTP/1.1 400"))
            assertTrue(unsupported.contains("\"code\":\"unsupported_parameter\""))
        } finally {
            server.close()
        }
    }

    @Test
    fun loadedModelInventoryUsesTheEngineManagerManifestShape() {
        val service = FakeInferenceService()
        val server = MnnHttpServer(service, TEST_PORT)
        server.start()
        try {
            val unloaded = http(server.localPort, "GET", "/internal/models/loaded")
            assertTrue(unloaded.contains("\"models\":[]"))

            http(server.localPort, "POST", "/v1/chat/completions", chatBody(stream = false))
            val loaded = http(server.localPort, "GET", "/internal/models/loaded")
            assertTrue(loaded.contains("\"models\":[{\"id\":\"qwen3-0.6b\"}]"))
        } finally {
            server.close()
        }
    }

    @Test
    fun clientDisconnectDuringErrorResponseDoesNotKillServer() {
        val server = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { client ->
                client.setSoLinger(true, 0)
                client.getOutputStream().write(requestBytes("GET", "/bad path", ""))
            }
            Thread.sleep(100)
            assertTrue(http(server.localPort, "GET", "/healthz").startsWith("HTTP/1.1 200"))
        } finally {
            server.close()
        }
    }

    @Test
    fun returnsConflictForSecondGenerationAndCancelsOnStreamDisconnect() {
        val service = FakeInferenceService(blockAfterFirstToken = true)
        val server = MnnHttpServer(service, TEST_PORT)
        server.start()
        val firstClient = Socket("127.0.0.1", server.localPort)
        firstClient.setSoLinger(true, 0)
        firstClient.getOutputStream().write(requestBytes("POST", "/v1/chat/completions", chatBody(stream = true)))
        val firstReader = BufferedReader(InputStreamReader(firstClient.getInputStream(), StandardCharsets.UTF_8))
        try {
            assertTrue(firstReader.readLine().orEmpty().contains("200"))
            while (firstReader.readLine()?.isEmpty() == false) Unit
            assertTrue(service.firstToken.await(2, TimeUnit.SECONDS))

            val second = http(server.localPort, "POST", "/v1/chat/completions", chatBody(stream = false))
            assertTrue(second.startsWith("HTTP/1.1 409"))
            assertTrue(second.contains("\"code\":\"engine_busy\""))

            firstClient.close()
            service.releaseGeneration.countDown()
            assertTrue(service.cancelled.await(3, TimeUnit.SECONDS))
        } finally {
            firstClient.close()
            service.releaseGeneration.countDown()
            server.close()
        }
    }

    private fun http(port: Int, method: String, path: String, body: String = ""): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(requestBytes(method, path, body))
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }

    private fun requestBytes(method: String, path: String, body: String): ByteArray {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val request = "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
        return request.toByteArray(StandardCharsets.US_ASCII) + bodyBytes
    }

    private fun chatBody(stream: Boolean): String =
        """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"Say hello."}],"stream":$stream}"""

    private class FakeInferenceService(
        private val blockAfterFirstToken: Boolean = false,
    ) : MnnInferenceService {
        private val model = MnnModelDescriptor("qwen3-0.6b", "/models/qwen3-0.6b/config.json")
        val firstToken = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        @Volatile var lastRequest: MnnChatRequest? = null
            private set
        @Volatile private var loaded = false

        override fun listModels(): List<MnnModelDescriptor> = listOf(model)

        override fun status(): MnnRuntimeStatus = MnnRuntimeStatus(
            state = if (loaded) MnnEngineState.READY else MnnEngineState.UNLOADED,
            backend = if (loaded) MnnBackend.CPU else null,
            modelId = if (loaded) model.modelId else null,
        )

        override fun ensureLoaded(modelId: String, backend: MnnBackend): MnnResult<MnnLoadedModel> {
            if (modelId != model.modelId) return MnnResult.failure(MnnErrorCode.MODEL_NOT_FOUND, "Model not found.")
            loaded = true
            return MnnResult.success(MnnLoadedModel(model, backend))
        }

        override fun generate(
            requestId: Long,
            request: MnnChatRequest,
            onToken: (String) -> Unit,
        ): MnnResult<MnnGenerationResult> {
            if (blockAfterFirstToken && firstToken.count == 0L) {
                return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is already active.")
            }
            lastRequest = request
            ensureLoaded(request.modelId, MnnBackend.CPU)
            onToken("Hello")
            firstToken.countDown()
            if (blockAfterFirstToken) releaseGeneration.await(5, TimeUnit.SECONDS)
            onToken(" world")
            return MnnResult.success(MnnGenerationResult("Hello world", MnnRuntimeMetrics(generatedTokens = 2)))
        }

        override fun cancel(requestId: Long) {
            cancelled.countDown()
        }

        override fun unload(): MnnResult<Unit> {
            loaded = false
            return MnnResult.success(Unit)
        }
    }

    private companion object {
        const val TEST_PORT = 0
    }
}
