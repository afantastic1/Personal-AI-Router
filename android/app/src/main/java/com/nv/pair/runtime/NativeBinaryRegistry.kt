/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import java.io.File

class NativeBinaryRegistry(private val nativeLibraryDir: File) {
    fun broker(): File = binary("libnvpair_ui_broker.so")

    fun scanner(): File = binary("libnvpair_node_scanner.so")

    fun proxy(): File = binary("libnvpair_proxy.so")

    fun clusterManager(): File = binary("libnvpair_cluster_manager.so")

    fun settings(): File = binary("libnvpair_node_settings.so")

    fun scheduler(): File = binary("libnvpair_job_scheduler.so")

    fun workloadManager(): File = binary("libnvpair_workload_manager.so")

    fun errors(): File = binary("libnvpair_errors.so")

    fun manualNodes(): File = binary("libnvpair_manual_nodes.so")

    fun engineManager(): File = binary("libnvpair_engine_manager.so")

    fun nodeInfo(): File = binary("libnvpair_node_info.so")

    private fun binary(name: String): File {
        val binary = nativeLibraryDir.resolve(name)
        require(binary.isFile) { "PAIR native binary is not installed: ${binary.absolutePath}" }
        return binary
    }
}
