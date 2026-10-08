/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

data class PairNode(
    val id: String,
    val name: String,
    val hostUuid: String = "",
    val ipAddress: String,
    val ipAddresses: List<String> = emptyList(),
    val port: Int = 0,
    val trusted: Boolean = false,
    val clustered: Boolean = false,
    val clusterId: String = "",
    val models: List<String> = emptyList(),
    val modelsByEngine: Map<String, List<String>> = emptyMap(),
    val loadedByEngine: Map<String, List<String>> = emptyMap(),
    val lastSeen: Long = 0,
)
