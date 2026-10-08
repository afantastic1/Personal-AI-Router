/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcClientTest {
    @Test
    fun correlatesOutOfOrderResponses() {
        val harness = FakeBrokerHarness()
        harness.client.start()
        val first = CompletableFuture.supplyAsync { harness.client.request("first") }
        val firstRequest = harness.readRequest()
        val second = CompletableFuture.supplyAsync { harness.client.request("second") }
        val secondRequest = harness.readRequest()

        harness.respond(secondRequest, JSONObject().put("value", "second"))
        harness.respond(firstRequest, JSONObject().put("value", "first"))

        assertEquals("first", first.get(2, TimeUnit.SECONDS).getString("value"))
        assertEquals("second", second.get(2, TimeUnit.SECONDS).getString("value"))
        harness.close()
    }

    @Test
    fun routesNotificationsWithoutRequestIds() {
        val notificationReceived = CountDownLatch(1)
        var receivedMethod = ""
        val harness = FakeBrokerHarness(
            onNotification = { notification ->
                receivedMethod = notification.method
                notificationReceived.countDown()
            }
        )
        harness.client.start()

        harness.emit(JSONObject().put("jsonrpc", "2.0").put("method", "app:ready").put("params", JSONObject().put("version", "1.2.3")))

        assertTrue(notificationReceived.await(2, TimeUnit.SECONDS))
        assertEquals("app:ready", receivedMethod)
        harness.close()
    }

    @Test
    fun preservesArrayNotificationParameters() {
        val notificationReceived = CountDownLatch(1)
        var receivedParams = ""
        val harness = FakeBrokerHarness(
            onNotification = { notification ->
                receivedParams = notification.paramsJson.orEmpty()
                notificationReceived.countDown()
            }
        )
        harness.client.start()

        harness.emit(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "discovery:nodes-changed")
                .put("params", org.json.JSONArray().put(JSONObject().put("hostUuid", "host-a")))
        )

        assertTrue(notificationReceived.await(2, TimeUnit.SECONDS))
        assertTrue(receivedParams.startsWith("["))
        assertTrue(receivedParams.contains("host-a"))
        harness.close()
    }

    @Test
    fun propagatesRpcErrorsToMatchingRequest() {
        val harness = FakeBrokerHarness()
        harness.client.start()
        val request = CompletableFuture.supplyAsync { harness.client.request("broken") }
        val frame = harness.readRequest()
        harness.respondError(frame, -32601, "method not found")

        val failure = runCatching { request.get(2, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue(failure.toString(), failure.toString().contains("method not found"))
        harness.close()
    }

    @Test
    fun preservesStructuredRpcErrorData() {
        val harness = FakeBrokerHarness()
        harness.client.start()
        val request = CompletableFuture.supplyAsync { runCatching { harness.client.request("invite") } }
        val frame = harness.readRequest()
        harness.respondError(
            frame,
            -32004,
            "pairing already in progress",
            JSONObject().put("reason", "invite-in-progress").put("inviteId", "invite-1"),
        )

        val failure = request.get(2, TimeUnit.SECONDS).exceptionOrNull()
        if (failure !is RpcException) throw AssertionError("failure was not a JSON-RPC error", failure)
        assertEquals("invite-in-progress", failure.data?.getString("reason"))
        assertEquals("invite-1", failure.data?.getString("inviteId"))
        harness.close()
    }

    @Test
    fun malformedFrameFailsPendingRequests() {
        val harness = FakeBrokerHarness()
        harness.client.start()
        val request = CompletableFuture.supplyAsync { harness.client.request("pending") }
        harness.readRequest()
        harness.emit("not-json")

        val failure = runCatching { request.get(2, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue("pending request must not hang", request.isDone)
        assertTrue(failure.toString(), failure.toString().contains("invalid JSON-RPC frame"))
        harness.close()
    }

    @Test
    fun timedOutRequestDoesNotConsumeALateResponse() {
        val harness = FakeBrokerHarness()
        harness.client.start()
        val first = CompletableFuture.supplyAsync {
            runCatching { harness.client.request("slow", timeoutMillis = 100) }
        }
        val lateRequest = harness.readRequest()
        assertTrue(first.get(2, TimeUnit.SECONDS).isFailure)
        harness.respond(lateRequest, JSONObject().put("value", "late"))

        val next = CompletableFuture.supplyAsync { harness.client.request("next") }
        val nextRequest = harness.readRequest()
        harness.respond(nextRequest, JSONObject().put("value", "current"))

        assertEquals("current", next.get(2, TimeUnit.SECONDS).getString("value"))
        harness.close()
    }

    private class FakeBrokerHarness(onNotification: (RpcNotification) -> Unit = {}) {
        private val brokerOutput = PipedOutputStream()
        private val clientInput = PipedInputStream(brokerOutput)
        private val clientOutput = PipedOutputStream()
        private val brokerInput = PipedInputStream(clientOutput)
        private val brokerReader = BufferedReader(InputStreamReader(brokerInput))
        val client = JsonRpcClient(clientInput, clientOutput, onNotification)

        fun readRequest(): JSONObject = JSONObject(requireNotNull(brokerReader.readLine()))

        fun respond(request: JSONObject, result: JSONObject) {
            emit(JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id")).put("result", result))
        }

        fun respondError(request: JSONObject, code: Int, message: String, data: JSONObject? = null) {
            val error = JSONObject().put("code", code).put("message", message)
            data?.let { error.put("data", it) }
            emit(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", request.getLong("id"))
                    .put("error", error)
            )
        }

        fun emit(frame: JSONObject) = emit(frame.toString())

        fun emit(frame: String) {
            emitFrame(brokerOutput, frame)
        }

        fun close() {
            brokerOutput.close()
            client.close()
            brokerReader.close()
        }
    }
}

private fun emitFrame(output: OutputStream, frame: String) {
    output.write((frame + "\n").toByteArray())
    output.flush()
}
