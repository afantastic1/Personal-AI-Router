/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

/** Stable provider identity used by catalog state and diagnostics. */
enum class ProviderId { MODELSCOPE, HUGGING_FACE }

data class RemoteArtifact(
    val path: String,
    val sizeBytes: Long?,
    val sha256: String?,
)

data class RemoteModelSummary(
    val provider: ProviderId,
    val repository: String,
    val displayName: String,
    val description: String,
    val tags: List<String>,
)

data class RemoteModelDetails(
    val summary: RemoteModelSummary,
    val immutableRevision: String,
    val artifacts: List<RemoteArtifact>,
)

data class RemoteModelPage(
    val models: List<RemoteModelSummary>,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
)

data class ModelSearchPage(
    val descriptors: List<ModelDescriptor>,
    val providerPage: RemoteModelPage,
)

enum class ProviderErrorCategory { AUTHENTICATION, NOT_FOUND, RATE_LIMITED, SERVER, NETWORK, INVALID_RESPONSE }

data class ProviderError(
    val provider: ProviderId,
    val operation: String,
    val category: ProviderErrorCategory,
    val httpStatus: Int? = null,
    val retryable: Boolean,
    val requestId: String? = null,
    val detail: String? = null,
) : Exception("$provider $operation failed ($category)${detail?.let { ": $it" }.orEmpty()}")
