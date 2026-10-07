/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import com.nv.pair.mnn.MnnHealthStatus
import com.nv.pair.mnn.MnnFinishReason
import com.nv.pair.mnn.MnnLoadedModel
import com.nv.pair.mnn.MnnModelDescriptor
import com.nv.pair.mnn.MnnRuntimeStatus
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

object OpenAiResponseWriter {
    fun models(models: List<MnnModelDescriptor>): JSONObject = JSONObject()
        .put("object", "list")
        .put("data", JSONArray().apply {
            models.forEach { model ->
                put(JSONObject()
                    .put("id", model.modelId)
                    .put("object", "model")
                    .put("owned_by", "local"))
            }
        })

    fun loaded(status: MnnRuntimeStatus): JSONObject = JSONObject()
        .put("loaded", status.modelId != null)
        .put("model", status.modelId ?: JSONObject.NULL)
        .put("models", JSONArray().apply {
            status.modelId?.let { put(JSONObject().put("id", it)) }
        })
        .put("backend", status.backend?.name?.lowercase() ?: JSONObject.NULL)
        .put("state", status.state.name.lowercase())

    fun loaded(model: MnnLoadedModel): JSONObject = JSONObject()
        .put("loaded", true)
        .put("model", model.model.modelId)
        .put("models", JSONArray().put(JSONObject().put("id", model.model.modelId)))
        .put("backend", model.backend.name.lowercase())
        .put("state", "ready")

    fun health(status: MnnHealthStatus, modelLoaded: Boolean): JSONObject = JSONObject()
        .put("status", if (status.available) "ok" else "unavailable")
        .put("runtime_state", status.state.name.lowercase())
        .put("model_loaded", modelLoaded)
        .apply {
            if (!status.available) put("error_code", status.error?.code?.name?.lowercase())
        }

    fun completion(
        id: String,
        model: String,
        text: String,
        promptTokens: Int,
        generatedTokens: Int,
        finishReason: MnnFinishReason,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("object", "chat.completion")
        .put("created", System.currentTimeMillis() / 1_000L)
        .put("model", model)
        .put("choices", JSONArray().put(
            JSONObject()
                .put("index", 0)
                .put("message", JSONObject().put("role", "assistant").put("content", text))
                .put("finish_reason", finishReason.wireName),
        ))
        .put("usage", JSONObject()
            .put("prompt_tokens", promptTokens)
            .put("completion_tokens", generatedTokens)
            .put("total_tokens", promptTokens + generatedTokens))

    fun streamChunk(
        id: String,
        model: String,
        content: String,
        finishReason: MnnFinishReason? = null,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("object", "chat.completion.chunk")
        .put("created", System.currentTimeMillis() / 1_000L)
        .put("model", model)
        .put("choices", JSONArray().put(
            JSONObject()
                .put("index", 0)
                .put("delta", JSONObject().put("content", content))
                .put("finish_reason", finishReason?.wireName ?: JSONObject.NULL),
        ))

    fun streamUsageChunk(
        id: String,
        model: String,
        promptTokens: Int,
        generatedTokens: Int,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("object", "chat.completion.chunk")
        .put("created", System.currentTimeMillis() / 1_000L)
        .put("model", model)
        .put("choices", JSONArray())
        .put("usage", JSONObject()
            .put("prompt_tokens", promptTokens)
            .put("completion_tokens", generatedTokens)
            .put("total_tokens", promptTokens + generatedTokens))

    fun error(type: String, code: String, message: String): JSONObject = JSONObject()
        .put("error", JSONObject()
            .put("message", message)
            .put("type", type)
            .put("code", code))

    fun bytes(json: JSONObject): ByteArray = json.toString().toByteArray(StandardCharsets.UTF_8)
}
