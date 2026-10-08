/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import com.nv.pair.runtime.NativeBinaryRegistry
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BrokerSessionTest {
    @Test
    fun brokerCommandIncludesConfiguredProxyEngines() {
        val (session, root) = session(listOf("ollama", "lmstudio", "mnn"))
        try {
            assertEquals("ollama,lmstudio,mnn", proxyEngines(session.commandArguments()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun brokerCommandExcludesMnnWhenLocalMnnDidNotStart() {
        val (session, root) = session(listOf("ollama", "lmstudio"))
        try {
            assertEquals("ollama,lmstudio", proxyEngines(session.commandArguments()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupContinuesAfterFailureAndRetriesOnlyTheFailedAction() {
        val failedActionAttempts = AtomicInteger()
        val independentActionAttempts = AtomicInteger()
        val cleanup = RetryableCleanup(actionCount = 2)
        val actions = listOf<() -> Unit>(
            {
                if (failedActionAttempts.getAndIncrement() == 0) throw IOException("transient close failure")
            },
            { independentActionAttempts.incrementAndGet() },
        )

        val firstFailure = runCatching { cleanup.run(actions) }.exceptionOrNull()
        assertTrue(firstFailure is IOException)
        assertEquals(1, independentActionAttempts.get())

        cleanup.run(actions)
        cleanup.run(actions)

        assertEquals(2, failedActionAttempts.get())
        assertEquals("successful cleanup actions must not run twice", 1, independentActionAttempts.get())
    }

    private fun session(proxyEngines: List<String>): Pair<BrokerSession, java.io.File> {
        val root = Files.createTempDirectory("pair-broker-session").toFile()
        listOf(
            "libnvpair_ui_broker.so",
            "libnvpair_node_scanner.so",
            "libnvpair_node_settings.so",
            "libnvpair_cluster_manager.so",
            "libnvpair_proxy.so",
            "libnvpair_job_scheduler.so",
            "libnvpair_workload_manager.so",
            "libnvpair_errors.so",
            "libnvpair_engine_manager.so",
        ).forEach { root.resolve(it).createNewFile() }
        return BrokerSession(
            binaries = NativeBinaryRegistry(root),
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            proxyEngines = proxyEngines,
        ) to root
    }

    private fun proxyEngines(arguments: List<String>): String {
        val flagIndex = arguments.indexOf("--proxy-engines")
        return arguments[flagIndex + 1]
    }
}
