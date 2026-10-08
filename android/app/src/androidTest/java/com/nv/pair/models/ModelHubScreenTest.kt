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
                    gatewayModels = listOf(
                        GatewayModel("qwen3-1.7b", GatewayModelKind.LOCAL),
                        GatewayModel("auto", GatewayModelKind.AUTOMATIC),
                        GatewayModel("auto-balanced", GatewayModelKind.AUTOMATIC),
                        GatewayModel("auto-new-policy", GatewayModelKind.AUTOMATIC),
                    ),
                    preferredBackend = MnnBackend.CPU,
                    localEngineStatus = MnnLocalEngineStatus(available = false),
                    onBackendChange = {},
                    cloudProviderSettings = null,
                    onCloudEnabledChange = {},
                    onCloudPolicyChange = {},
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
                gatewayModels = listOf(
                    GatewayModel("auto", GatewayModelKind.AUTOMATIC),
                    GatewayModel("auto-balanced", GatewayModelKind.AUTOMATIC),
                ),
                preferredBackend = MnnBackend.CPU,
                localEngineStatus = MnnLocalEngineStatus(available = true),
                onBackendChange = { selectedBackend = it },
                cloudProviderSettings = null,
                onCloudEnabledChange = {},
                onCloudPolicyChange = {},
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
                gatewayModels = null,
                preferredBackend = MnnBackend.OPENCL,
                localEngineStatus = MnnLocalEngineStatus(
                    available = true,
                    backend = MnnBackend.OPENCL,
                    errorCode = MnnErrorCode.BACKEND_UNSUPPORTED,
                ),
                onBackendChange = {},
                cloudProviderSettings = null,
                onCloudEnabledChange = {},
                onCloudPolicyChange = {},
            )
        }

        composeRule.onNodeWithText(
            "OpenCL is not available on this device/runtime. CPU remains available.",
        ).assertIsDisplayed()
    }

    @Test
    fun cloudCardListsGatewayCloudModelsAndHostOnlyNotice() {
        composeRule.setContent {
            ModelHubScreen(
                nodes = emptyList(),
                gatewayModels = listOf(GatewayModel("cloud/work/chat", GatewayModelKind.CLOUD)),
                preferredBackend = MnnBackend.CPU,
                localEngineStatus = MnnLocalEngineStatus(available = false),
                onBackendChange = {},
                cloudProviderSettings = null,
                onCloudEnabledChange = {},
                onCloudPolicyChange = {},
            )
        }

        composeRule.onNodeWithText("Cloud resources").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("cloud/work/chat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Requests run on the host that holds the Provider key. This device never receives that key; the host must authorize this paired node.",
        ).performScrollTo().assertIsDisplayed()
    }
}
