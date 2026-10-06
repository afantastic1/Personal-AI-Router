/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

data class EngineProxyStatus(
    val engine: String,
    val ready: Boolean,
    val port: Int,
)

data class PairWorkload(
    val id: String,
    val model: String,
    val engine: String,
    val runId: String,
    val state: String,
    val originatedFrom: String,
    val scheduledOn: String,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val error: String?,
)
