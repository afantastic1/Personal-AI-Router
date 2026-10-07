/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import com.nv.pair.runtime.NativeBinaryRegistry
import com.nv.pair.runtime.PairProcess
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

data class BrokerRuntimeInfo(val version: String, val uptimeMillis: Long)

class BrokerSession(
    private val binaries: NativeBinaryRegistry,
    private val filesDir: File,
    private val cacheDir: File,
    private val onLog: (String) -> Unit = {},
    private val onCrash: (Int) -> Unit = {},
    private val onWaitingReady: () -> Unit = {},
    private val onNotification: (RpcNotification) -> Unit = {},
    private val scannerBinaryOverride: File? = null,
    private val additionalEnvironment: Map<String, String> = emptyMap(),
) : AutoCloseable {
    private val stopping = AtomicBoolean()
    private val ready = CompletableFuture<String>()
    private var process: Process? = null
    private var processRunner: PairProcess? = null
    private var rpc: JsonRpcClient? = null
    private var stderrThread: Thread? = null
    private var monitorThread: Thread? = null

    @Synchronized
    fun start(): BrokerRuntimeInfo {
        check(process == null) { "broker session already started" }
        val broker = binaries.broker()
        val scanner = scannerBinaryOverride ?: binaries.scanner()
        val environment = runtimeEnvironment()
        val settings = binaries.settings()
        val clusterManager = binaries.clusterManager()
        val proxy = binaries.proxy()
        val scheduler = binaries.scheduler()
        val workloadManager = binaries.workloadManager()
        val errors = binaries.errors()
        val engineManager = binaries.engineManager()
        val runner = PairProcess(
            listOf(
                broker.absolutePath,
                "--scanner-path", scanner.absolutePath,
                "--settings-path", settings.absolutePath,
                "--cluster-manager-path", clusterManager.absolutePath,
                "--proxy-path", proxy.absolutePath,
                "--scheduler-path", scheduler.absolutePath,
                "--workload-manager-path", workloadManager.absolutePath,
                "--errors-path", errors.absolutePath,
                "--engine-manager-path", engineManager.absolutePath,
            ),
            environment,
        )
        processRunner = runner
        val child = runner.start()
        process = child

        val client = JsonRpcClient(
            child.inputStream,
            child.outputStream,
            onNotification = { notification ->
                handleNotification(notification)
                onNotification(notification)
            },
        )
        rpc = client
        client.start()
        startStderrReader(child)
        startExitMonitor(child, client)
        onWaitingReady()

        val readyVersion = try {
            ready.get(15, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            close()
            throw IOException("broker did not emit app:ready within 15 seconds", timeout)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            close()
            throw IOException("interrupted while waiting for broker app:ready", interrupted)
        } catch (failure: ExecutionException) {
            close()
            throw IOException("broker exited before app:ready", failure.cause)
        }

        try {
            val ping = BrokerApi(client).ping()
            val reportedVersion = BrokerApi(client).version()
            if (ping.version != readyVersion || reportedVersion != readyVersion) {
                throw IOException("broker version mismatch across ready, ping, and version")
            }
            return BrokerRuntimeInfo(reportedVersion, ping.uptimeMillis)
        } catch (failure: Exception) {
            close()
            if (failure is IOException) throw failure
            throw IOException("broker RPC verification failed", failure)
        }
    }

    fun initializeDiscovery(repository: com.nv.pair.data.PairRepository) {
        val client = rpc ?: throw IOException("broker session has not started")
        BrokerApi(client).initializeDiscovery(repository)
    }

    fun request(
        method: String,
        params: JSONObject? = null,
        timeoutMillis: Long = 5_000,
    ): JSONObject {
        val client = rpc ?: throw IOException("broker session has not started")
        return client.request(method, params, timeoutMillis)
    }

    private fun handleNotification(notification: RpcNotification) {
        if (notification.method != "app:ready") return
        val params = notification.paramsJson?.let(::JSONObject) ?: JSONObject()
        val version = params.optString("version")
        if (version.isBlank()) {
            ready.completeExceptionally(IOException("broker app:ready notification has no version"))
        } else {
            ready.complete(version)
        }
    }

    private fun runtimeEnvironment(): Map<String, String> {
        val config = File(filesDir, "pair-config")
        val cache = File(cacheDir, "pair-cache")
        val temp = File(cacheDir, "pair-tmp")
        listOf(filesDir, config, cache, temp).forEach { directory ->
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("could not create PAIR runtime directory: ${directory.absolutePath}")
            }
        }
        return mapOf(
            "HOME" to filesDir.absolutePath,
            "XDG_CONFIG_HOME" to config.absolutePath,
            "XDG_CACHE_HOME" to cache.absolutePath,
            "TMPDIR" to temp.absolutePath,
            "NVPAIR_LOG_LEVEL" to "info",
        ) + additionalEnvironment
    }

    private fun startStderrReader(child: Process) {
        stderrThread = Thread({
            child.errorStream.bufferedReader().useLines { lines ->
                lines.forEach(onLog)
            }
        }, "pair-broker-stderr").apply {
            isDaemon = true
            start()
        }
    }

    private fun startExitMonitor(child: Process, client: JsonRpcClient) {
        monitorThread = Thread({
            val exitCode = child.waitFor()
            if (!stopping.get()) {
                onCrash(exitCode)
                ready.completeExceptionally(IOException("broker exited before ready with code $exitCode"))
                client.close()
            }
        }, "pair-broker-exit-monitor").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        val child = process
        val runner = processRunner
        if (child != null && runner != null && child.isAlive) {
            runner.stop(child, 15_000)
        }
        rpc?.close()
        stderrThread?.join(2_000)
    }
}
