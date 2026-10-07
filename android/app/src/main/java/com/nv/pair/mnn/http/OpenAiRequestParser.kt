/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import com.nv.pair.mnn.MnnChatMessage
import com.nv.pair.mnn.MnnChatRequest
import com.nv.pair.mnn.MnnChatRole
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class OpenAiChatCompletionRequest(
    val chat: MnnChatRequest,
    val stream: Boolean,
    val includeUsage: Boolean,
)

class OpenAiRequestException(
    val statusCode: Int,
    val code: String,
    message: String,
) : Exception(message)

object OpenAiRequestParser {
    private val supportedFields = setOf("model", "messages", "stream", "stream_options", "max_tokens", "temperature", "top_p", "seed")
    private val unsupportedFields = setOf(
        "tools", "tool_choice", "functions", "function_call", "response_format", "logprobs", "top_logprobs",
        "audio", "modalities", "n", "stop", "presence_penalty", "frequency_penalty", "logit_bias",
    )

    fun parse(body: JSONObject): OpenAiChatCompletionRequest {
        body.keys().forEach { key ->
            if (key in unsupportedFields || key !in supportedFields) {
                throw badRequest("unsupported_parameter", "The '$key' parameter is not supported.")
            }
        }
        val modelId = requiredString(body, "model")
        val messages = parseMessages(body.optJSONArray("messages"))
        val stream = optionalBoolean(body, "stream", false)
        val includeUsage = if (body.has("stream_options")) {
            val options = body.optJSONObject("stream_options")
                ?: throw badRequest("invalid_request_error", "'stream_options' must be an object.")
            options.keys().forEach { key ->
                if (key != "include_usage") {
                    throw badRequest("unsupported_parameter", "The 'stream_options.$key' parameter is not supported.")
                }
            }
            optionalBoolean(options, "include_usage", false)
        } else {
            false
        }
        val maxTokens = optionalInteger(body, "max_tokens", DEFAULT_MAX_TOKENS)
        val temperature = optionalNumber(body, "temperature", DEFAULT_TEMPERATURE).toFloat()
        val topP = optionalNumber(body, "top_p", DEFAULT_TOP_P).toFloat()
        val seed = if (body.has("seed")) optionalInteger(body, "seed", 0) else null
        val chat = MnnChatRequest(modelId, messages, maxTokens, temperature, topP, seed)
        when (val validation = chat.validate()) {
            is com.nv.pair.mnn.MnnResult.Failure -> throw badRequest(
                "invalid_request_error",
                validation.error.message,
            )
            is com.nv.pair.mnn.MnnResult.Success -> Unit
        }
        return OpenAiChatCompletionRequest(chat, stream, includeUsage)
    }

    private fun parseMessages(messages: JSONArray?): List<MnnChatMessage> {
        if (messages == null || messages.length() == 0) {
            throw badRequest("invalid_request_error", "'messages' must be a non-empty array.")
        }
        return (0 until messages.length()).map { index ->
            val message = try {
                messages.getJSONObject(index)
            } catch (_: JSONException) {
                throw badRequest("invalid_request_error", "Each message must be an object with role and content.")
            }
            message.keys().forEach { key ->
                if (key != "role" && key != "content") {
                    throw badRequest("unsupported_parameter", "The message '$key' field is not supported.")
                }
            }
            val roleName = requiredString(message, "role")
            val role = MnnChatRole.entries.firstOrNull { it.wireName == roleName }
                ?: throw badRequest("invalid_request_error", "Message role '$roleName' is not supported.")
            val content = requiredString(message, "content")
            MnnChatMessage(role, content)
        }
    }

    private fun requiredString(body: JSONObject, field: String): String {
        val value = body.opt(field)
        if (value !is String || value.isBlank()) {
            throw badRequest("invalid_request_error", "'$field' must be a non-empty string.")
        }
        return value
    }

    private fun optionalBoolean(body: JSONObject, field: String, default: Boolean): Boolean {
        if (!body.has(field)) return default
        return when (val value = body.opt(field)) {
            is Boolean -> value
            else -> throw badRequest("invalid_request_error", "'$field' must be a boolean.")
        }
    }

    private fun optionalInteger(body: JSONObject, field: String, default: Int): Int {
        if (!body.has(field)) return default
        val number = when (val value = body.opt(field)) {
            is Number -> value
            else -> throw badRequest("invalid_request_error", "'$field' must be an integer.")
        }
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw badRequest("invalid_request_error", "'$field' must be an integer.")
        }
        return value.toInt()
    }

    private fun optionalNumber(body: JSONObject, field: String, default: Double): Double {
        if (!body.has(field)) return default
        val number = when (val value = body.opt(field)) {
            is Number -> value
            else -> throw badRequest("invalid_request_error", "'$field' must be a number.")
        }
        val value = number.toDouble()
        if (!value.isFinite()) throw badRequest("invalid_request_error", "'$field' must be finite.")
        return value
    }

    private fun badRequest(code: String, message: String) = OpenAiRequestException(400, code, message)

    private const val DEFAULT_MAX_TOKENS = 128
    private const val DEFAULT_TEMPERATURE = 0.7
    private const val DEFAULT_TOP_P = 0.9
}
