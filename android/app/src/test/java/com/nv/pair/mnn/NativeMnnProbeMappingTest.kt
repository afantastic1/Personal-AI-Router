/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMnnProbeMappingTest {
    @Test
    fun nativeProbeCodesMapToStableCapabilityResults() {
        assertEquals(BackendCapabilityResult.Available, mapNativeOpenClProbeResult(0))
        assertEquals(BackendCapabilityResult.Unavailable(ProbeReason.NOT_COMPILED), mapNativeOpenClProbeResult(1))
        assertEquals(BackendCapabilityResult.Unavailable(ProbeReason.RUNTIME_INIT_FAILED), mapNativeOpenClProbeResult(2))
        assertEquals(BackendCapabilityResult.Error(ProbeReason.PROBE_FAILED), mapNativeOpenClProbeResult(3))
        assertEquals(BackendCapabilityResult.Error(ProbeReason.PROBE_FAILED), mapNativeOpenClProbeResult(99))
    }
}
