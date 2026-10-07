/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import com.nv.pair.mnn.MnnRuntimeMetrics
import org.json.JSONObject

class SseStream(
    private val output: OutputStream,
    private val id: String,
    private val model: String,
) {
    var started: Boolean = false
        private set

    fun token(value: String) {
        if (!started) {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            started = true
        }
        event(OpenAiResponseWriter.streamChunk(id, model, value))
    }

    fun finish(metrics: MnnRuntimeMetrics? = null, includeUsage: Boolean = false) {
        if (!started) {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            started = true
        }
        event(OpenAiResponseWriter.streamChunk(id, model, "", "stop"))
        if (includeUsage && metrics != null) {
            event(OpenAiResponseWriter.streamUsageChunk(id, model, metrics.promptTokens, metrics.generatedTokens))
        }
        output.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    fun error(error: JSONObject) {
        if (!started) {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            started = true
        }
        event(error)
        output.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun event(json: JSONObject) {
        output.write("data: ".toByteArray(StandardCharsets.US_ASCII))
        output.write(OpenAiResponseWriter.bytes(json))
        output.write("\n\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }
}
