/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

data class MnnHttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)
