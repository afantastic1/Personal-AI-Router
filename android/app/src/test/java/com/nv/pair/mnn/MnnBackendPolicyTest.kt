/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import org.junit.Assert.assertEquals
import org.junit.Test

class MnnBackendPolicyTest {
    @Test
    fun automaticChoiceUsesOpenClOnlyAfterSuccessfulProbe() {
        assertEquals(MnnBackend.OPENCL, resolveBackendChoice(BackendChoice.Auto, ProbeState.AVAILABLE).effectiveBackend)
        assertEquals(MnnBackend.CPU, resolveBackendChoice(BackendChoice.Auto, ProbeState.UNAVAILABLE).effectiveBackend)
        assertEquals(MnnBackend.CPU, resolveBackendChoice(BackendChoice.Auto, ProbeState.ERROR).effectiveBackend)
        assertEquals(MnnBackend.CPU, resolveBackendChoice(BackendChoice.Auto, ProbeState.CHECKING).effectiveBackend)
        assertEquals(true, resolveBackendChoice(BackendChoice.Auto, ProbeState.CHECKING).resolving)
    }

    @Test
    fun manualCpuIsNotOverriddenAndUnavailableManualOpenClFallsBackWithoutLosingChoice() {
        assertEquals(MnnBackend.CPU, resolveBackendChoice(BackendChoice.Manual(MnnBackend.CPU), ProbeState.AVAILABLE).effectiveBackend)
        val result = resolveBackendChoice(BackendChoice.Manual(MnnBackend.OPENCL), ProbeState.ERROR)
        assertEquals(BackendChoice.Manual(MnnBackend.OPENCL), result.choice)
        assertEquals(MnnBackend.CPU, result.effectiveBackend)
        assertEquals(true, result.unavailableManualChoice)
    }

    @Test
    fun unhealthyEnginePreventsAnEffectiveOpenClSelection() {
        val result = resolveBackendChoice(BackendChoice.Auto, ProbeState.AVAILABLE, engineAvailable = false)
        assertEquals(MnnBackend.CPU, result.effectiveBackend)
        assertEquals(false, result.resolving)
    }

    @Test
    fun manualOpenClRemainsUnavailableUntilProbeSucceedsAgain() {
        val choice = BackendChoice.Manual(MnnBackend.OPENCL)
        assertEquals(MnnBackend.CPU, resolveBackendChoice(choice, ProbeState.CHECKING).effectiveBackend)
        assertEquals(MnnBackend.OPENCL, resolveBackendChoice(choice, ProbeState.AVAILABLE).effectiveBackend)
    }
}
