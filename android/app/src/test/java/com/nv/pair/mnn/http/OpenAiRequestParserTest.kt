/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import com.nv.pair.mnn.MnnChatRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestParserTest {
    @Test
    fun parsesStructuredMessagesAndSamplingOptions() {
        val request = OpenAiRequestParser.parse(
            JSONObject(
                """{"model":"qwen3-0.6b","messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"Hello."}],"stream":true,"max_tokens":48,"temperature":0.25,"top_p":0.8,"seed":7}""",
            ),
        )

        assertEquals("qwen3-0.6b", request.chat.modelId)
        assertEquals(listOf(MnnChatRole.SYSTEM, MnnChatRole.USER), request.chat.messages.map { it.role })
        assertEquals("Be brief.", request.chat.messages.first().content)
        assertEquals(48, request.chat.maxTokens)
        assertEquals(0.25f, request.chat.temperature)
        assertEquals(0.8f, request.chat.topP)
        assertEquals(7, request.chat.seed)
        assertTrue(request.stream)
    }

    @Test
    fun acceptsOpenAiStreamingUsageOptions() {
        val request = OpenAiRequestParser.parse(
            JSONObject(
                """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"Hello."}],"stream":true,"stream_options":{"include_usage":true}}""",
            ),
        )

        assertTrue(request.stream)
        assertTrue(request.includeUsage)
    }

    @Test
    fun defaultsToNonStreamingWithRuntimeSamplingDefaults() {
        val request = OpenAiRequestParser.parse(
            JSONObject("""{"model":"qwen3-0.6b","messages":[{"role":"user","content":"Hello."}]}"""),
        )

        assertFalse(request.stream)
        assertEquals(128, request.chat.maxTokens)
        assertEquals(0.7f, request.chat.temperature)
        assertEquals(0.9f, request.chat.topP)
        assertEquals(null, request.chat.seed)
    }

    @Test
    fun explicitlyRejectsUnsupportedCapabilities() {
        listOf(
            """{"model":"m","messages":[{"role":"user","content":"x"}],"tools":[]}""",
            """{"model":"m","messages":[{"role":"user","content":"x"}],"logprobs":true}""",
            """{"model":"m","messages":[{"role":"user","content":[{"type":"image_url"}]}]}""",
        ).forEach { body ->
            val failure = runCatching { OpenAiRequestParser.parse(JSONObject(body)) }.exceptionOrNull()
            assertTrue("Expected an explicit unsupported request error", failure is OpenAiRequestException)
            val statusCode = when (failure) {
                is OpenAiRequestException -> failure.statusCode
                else -> error("Expected an HTTP request error")
            }
            assertEquals(400, statusCode)
        }
    }

    @Test
    fun rejectsUnknownRolesAndMalformedSamplingOptions() {
        val roleFailure = runCatching {
            OpenAiRequestParser.parse(
                JSONObject("""{"model":"m","messages":[{"role":"tool","content":"x"}]}"""),
            )
        }.exceptionOrNull()
        val samplingFailure = runCatching {
            OpenAiRequestParser.parse(
                JSONObject("""{"model":"m","messages":[{"role":"user","content":"x"}],"top_p":0}"""),
            )
        }.exceptionOrNull()

        assertTrue(roleFailure is OpenAiRequestException)
        assertTrue(samplingFailure is OpenAiRequestException)
    }
}
