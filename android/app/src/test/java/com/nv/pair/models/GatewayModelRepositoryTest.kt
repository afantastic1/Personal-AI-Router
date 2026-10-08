/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayModelRepositoryTest {
    @Test
    fun modelListDistinguishesLocalCloudAndAutomaticEntries() {
        val response = """{"object":"list","data":[{"id":"qwen3-1.7b"},{"id":"cloud/work/chat","owned_by":"pair-remote-cloud"},{"id":"auto"},{"id":"auto-balanced"}]}"""
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestTarget = AtomicReference<String?>()
        val authorization = AtomicReference<String?>()
        val worker = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                requestTarget.set(reader.readLine()?.split(' ')?.getOrNull(1))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Authorization:", ignoreCase = true)) authorization.set(line.substringAfter(':').trim())
                }
                val body = response.toByteArray(StandardCharsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(header.toByteArray(StandardCharsets.US_ASCII))
                    write(body)
                    flush()
                }
            }
        }.apply { start() }

        try {
            val models = GatewayModelRepository(
                URL("http://127.0.0.1:${server.localPort}/v1/models"),
                "android-gateway-token-12345678901234567890",
            ).models()

            assertEquals("/v1/models", requestTarget.get())
            assertEquals("Bearer android-gateway-token-12345678901234567890", authorization.get())
            assertEquals(
                listOf(
                    GatewayModel("qwen3-1.7b", GatewayModelKind.LOCAL),
                    GatewayModel("cloud/work/chat", GatewayModelKind.REMOTE_CLOUD),
                    GatewayModel("auto", GatewayModelKind.AUTOMATIC),
                    GatewayModel("auto-balanced", GatewayModelKind.AUTOMATIC),
                ),
                models,
            )
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}
