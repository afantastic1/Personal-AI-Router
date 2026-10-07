/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import com.nv.pair.mnn.MnnFinishReason
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiFinishReasonTest {
    @Test
    fun nonStreamingCompletionUsesNativeFinishReason() {
        val completion = OpenAiResponseWriter.completion(
            id = "chatcmpl-test",
            model = "qwen-test",
            text = "response",
            promptTokens = 2,
            generatedTokens = 3,
            finishReason = MnnFinishReason.LENGTH,
        )

        assertEquals("length", completion.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    @Test
    fun streamFinalChunkUsesNativeFinishReasonAndSendsDoneOnce() {
        val output = ByteArrayOutputStream()
        val stream = SseStream(output, "chatcmpl-test", "qwen-test")
        stream.token("response")

        stream.finish(finishReason = MnnFinishReason.LENGTH)

        val response = output.toString(StandardCharsets.UTF_8)
        assertEquals(1, Regex("\\\"finish_reason\\\":\\\"length\\\"").findAll(response).count())
        assertEquals(1, Regex("data: \\[DONE\\]").findAll(response).count())
    }
}
