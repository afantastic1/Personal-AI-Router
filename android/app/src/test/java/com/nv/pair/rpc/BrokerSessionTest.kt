/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import com.nv.pair.runtime.NativeBinaryRegistry
import java.nio.file.Files
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
