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
    fun modelIdsAndAutomaticAliasesComeFromGatewayModelList() {
        val response = """{"object":"list","data":[{"id":"qwen3-1.7b"},{"id":"auto"},{"id":"auto-balanced"}]}"""
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestTarget = AtomicReference<String?>()
        val worker = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                requestTarget.set(reader.readLine()?.split(' ')?.getOrNull(1))
                while (reader.readLine()?.isNotEmpty() == true) Unit
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
            val modelIds = GatewayModelRepository(URL("http://127.0.0.1:${server.localPort}/v1/models")).modelIds()

            assertEquals("/v1/models", requestTarget.get())
            assertEquals(listOf("qwen3-1.7b", "auto", "auto-balanced"), modelIds)
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}
