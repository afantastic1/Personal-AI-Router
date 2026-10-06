/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONException
import org.json.JSONObject

data class RpcNotification(val method: String, val paramsJson: String?)

class RpcException(val code: Int, message: String) : IOException("JSON-RPC $code: $message")

class JsonRpcClient(
    input: InputStream,
    private val output: OutputStream,
    private val onNotification: (RpcNotification) -> Unit = {},
    private val onClosed: (IOException) -> Unit = {},
) : Closeable {
    private val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val writeLock = Any()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    fun start() {
        check(started.compareAndSet(false, true)) { "JSON-RPC client already started" }
        Thread(::readFrames, "pair-jsonrpc-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun request(
        method: String,
        params: JSONObject? = null,
        timeoutMillis: Long = 5_000,
    ): JSONObject {
        check(started.get()) { "JSON-RPC client has not started" }
        check(!closed.get()) { "JSON-RPC client is closed" }
        require(method.isNotBlank()) { "JSON-RPC method must not be blank" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val id = ids.incrementAndGet()
        val response = CompletableFuture<JSONObject>()
        pending[id] = response

        try {
            val frame = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
            if (params != null) frame.put("params", params)
            synchronized(writeLock) {
                output.write((frame.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
            return response.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            throw IOException("JSON-RPC request timed out: $method", timeout)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted while waiting for JSON-RPC response: $method", interrupted)
        } catch (failure: java.util.concurrent.ExecutionException) {
            val cause = failure.cause
            if (cause is IOException) throw cause
            throw IOException("JSON-RPC request failed: $method", cause)
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("failed to send JSON-RPC request: $method", failure)
        } finally {
            pending.remove(id)
        }
    }

    private fun readFrames() {
        try {
            while (!closed.get()) {
                val line = reader.readLine() ?: throw IOException("JSON-RPC stream reached EOF")
                val frame = try {
                    JSONObject(line)
                } catch (failure: JSONException) {
                    throw IOException("invalid JSON-RPC frame", failure)
                }
                if (frame.optString("jsonrpc") != "2.0") {
                    throw IOException("invalid JSON-RPC version")
                }
                if (frame.has("method")) {
                    val method = frame.optString("method")
                    if (method.isBlank() || frame.has("id")) {
                        throw IOException("unsupported or invalid JSON-RPC request frame")
                    }
                    val params = if (frame.has("params") && !frame.isNull("params")) {
                        frame.get("params").toString()
                    } else {
                        null
                    }
                    onNotification(RpcNotification(method, params))
                } else {
                    routeResponse(frame)
                }
            }
        } catch (failure: IOException) {
            terminate(failure)
        } catch (failure: Exception) {
            terminate(IOException("JSON-RPC reader failed", failure))
        }
    }

    private fun routeResponse(frame: JSONObject) {
        if (!frame.has("id") || frame.isNull("id")) {
            throw IOException("JSON-RPC response has no id")
        }
        val id = try {
            frame.getLong("id")
        } catch (failure: JSONException) {
            throw IOException("JSON-RPC response id is invalid", failure)
        }
        val response = pending[id] ?: return
        if (frame.has("error")) {
            val error = frame.optJSONObject("error") ?: throw IOException("JSON-RPC error is malformed")
            response.completeExceptionally(
                RpcException(error.optInt("code"), error.optString("message", "unspecified error"))
            )
            return
        }
        response.complete(frame.optJSONObject("result") ?: JSONObject())
    }

    private fun terminate(failure: IOException) {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        runCatching { output.close() }
        onClosed(failure)
    }

    override fun close() {
        terminate(IOException("JSON-RPC client closed"))
    }
}
