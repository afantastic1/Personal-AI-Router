/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

interface MnnRuntime {
    fun loadModel(model: MnnModelDescriptor, backend: MnnBackend): MnnResult<Unit>

    fun generate(
        requestId: Long,
        request: MnnGenerationRequest,
        onToken: (String) -> Unit
    ): MnnResult<MnnGenerationResult>

    fun cancel(requestId: Long)

    fun unloadModel(): MnnResult<Unit>

    fun getStatus(): MnnRuntimeStatus

    fun getLoadedModel(): MnnLoadedModel?

    fun getMetrics(): MnnRuntimeMetrics
}
