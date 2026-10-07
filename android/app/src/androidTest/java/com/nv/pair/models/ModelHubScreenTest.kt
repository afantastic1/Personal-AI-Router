/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnErrorCode
import com.nv.pair.runtime.MnnLocalEngineStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelHubScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun automaticAliasesReflectGatewayModelList() {
        composeRule.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ModelHubScreen(
                    nodes = emptyList(),
                    gatewayModelIds = listOf("qwen3-1.7b", "auto", "auto-balanced", "auto-new-policy"),
                    preferredBackend = MnnBackend.CPU,
                    localEngineStatus = MnnLocalEngineStatus(available = false),
                    onBackendChange = {},
                )
            }
        }

        composeRule.onNodeWithText("auto").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("auto-balanced").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("auto-new-policy").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectingOpenClUpdatesPreferredBackend() {
        var selectedBackend: MnnBackend? = null
        composeRule.setContent {
            ModelHubScreen(
                nodes = emptyList(),
                gatewayModelIds = listOf("auto", "auto-balanced"),
                preferredBackend = MnnBackend.CPU,
                localEngineStatus = MnnLocalEngineStatus(available = true),
                onBackendChange = { selectedBackend = it },
            )
        }

        composeRule.onNodeWithText("OpenCL").performClick()

        assertEquals(MnnBackend.OPENCL, selectedBackend)
    }

    @Test
    fun unsupportedOpenClExplainsCpuRemainsAvailable() {
        composeRule.setContent {
            ModelHubScreen(
                nodes = emptyList(),
                gatewayModelIds = null,
                preferredBackend = MnnBackend.OPENCL,
                localEngineStatus = MnnLocalEngineStatus(
                    available = true,
                    backend = MnnBackend.OPENCL,
                    errorCode = MnnErrorCode.BACKEND_UNSUPPORTED,
                ),
                onBackendChange = {},
            )
        }

        composeRule.onNodeWithText(
            "OpenCL is not available on this device/runtime. CPU remains available.",
        ).assertIsDisplayed()
    }
}
