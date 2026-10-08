/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelCatalogRepositoryTest {
    @Test
    fun pagedSearchCachesInstallableCatalogDescriptors() {
        val adapter = ModelScopeAdapter(
            endpointBase = "https://modelscope.test",
            transport = ModelHubHttpTransport { _, _ ->
                """{"data":{"total_count":21,"models":[{"id":"MNN/Qwen3-MNN","name":"Qwen3 MNN"}]}}"""
            },
        )
        val repository = ModelCatalogRepository(listOf(adapter))

        val result = repository.searchPage("Qwen", ModelSourceKind.MODELSCOPE, page = 2, pageSize = 20)

        assertEquals("MNN/Qwen3-MNN", result.descriptors.single().source.repository)
        assertEquals("MNN/Qwen3-MNN", repository.entries().single().source.repository)
        assertEquals(2, result.providerPage.page)
        assertFalse(result.providerPage.hasMore)
    }
}
