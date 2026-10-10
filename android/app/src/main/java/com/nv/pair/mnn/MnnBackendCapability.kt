/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

enum class ProbeState { UNKNOWN, CHECKING, AVAILABLE, UNAVAILABLE, ERROR }

enum class ProbeReason {
    NOT_COMPILED,
    NATIVE_LIBRARY_UNAVAILABLE,
    RUNTIME_INIT_FAILED,
    PROBE_FAILED,
    RUNTIME_STOPPED,
}

data class BackendCapability(
    val backend: MnnBackend,
    val state: ProbeState,
    val reason: ProbeReason? = null,
    val checkedAtEpochMillis: Long? = null,
)

data class MnnCapabilitySnapshot(
    val cpu: BackendCapability = BackendCapability(MnnBackend.CPU, ProbeState.UNKNOWN),
    val openCl: BackendCapability = BackendCapability(MnnBackend.OPENCL, ProbeState.UNKNOWN),
)

sealed interface BackendCapabilityResult {
    data object Available : BackendCapabilityResult
    data class Unavailable(val reason: ProbeReason) : BackendCapabilityResult
    data class Error(val reason: ProbeReason) : BackendCapabilityResult
}
