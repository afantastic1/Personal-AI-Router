/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.util.concurrent.atomic.AtomicReference

class MnnBackendSelection(initial: MnnBackend = MnnBackend.CPU) {
    private val value = AtomicReference(initial)

    fun current(): MnnBackend = value.get()

    fun update(next: MnnBackend) {
        value.set(next)
    }
}
