/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

sealed interface BackendChoice {
    data object Auto : BackendChoice
    data class Manual(val backend: MnnBackend) : BackendChoice
}

data class EffectiveBackendSelection(
    val choice: BackendChoice,
    val effectiveBackend: MnnBackend,
    val resolving: Boolean = false,
    val unavailableManualChoice: Boolean = false,
)

fun resolveBackendChoice(
    choice: BackendChoice,
    openClCapability: ProbeState,
    engineAvailable: Boolean = true,
): EffectiveBackendSelection {
    if (!engineAvailable) return EffectiveBackendSelection(choice, MnnBackend.CPU)
    return when (choice) {
        BackendChoice.Auto -> when (openClCapability) {
            ProbeState.AVAILABLE -> EffectiveBackendSelection(choice, MnnBackend.OPENCL)
            ProbeState.CHECKING, ProbeState.UNKNOWN -> EffectiveBackendSelection(choice, MnnBackend.CPU, resolving = true)
            ProbeState.UNAVAILABLE, ProbeState.ERROR -> EffectiveBackendSelection(choice, MnnBackend.CPU)
        }
        is BackendChoice.Manual -> when {
            choice.backend == MnnBackend.CPU -> EffectiveBackendSelection(choice, MnnBackend.CPU)
            openClCapability == ProbeState.AVAILABLE -> EffectiveBackendSelection(choice, MnnBackend.OPENCL)
            else -> EffectiveBackendSelection(choice, MnnBackend.CPU, unavailableManualChoice = true)
        }
    }
}
