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
import com.nv.pair.mnn.BackendChoice
import com.nv.pair.mnn.EffectiveBackendSelection
import com.nv.pair.mnn.MnnCapabilitySnapshot
import com.nv.pair.mnn.BackendCapability
import com.nv.pair.mnn.ProbeState
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
                    backendChoice = BackendChoice.Auto,
                    capabilities = MnnCapabilitySnapshot(),
                    effectiveBackendSelection = EffectiveBackendSelection(BackendChoice.Auto, MnnBackend.CPU, resolving = true),
                    localEngineStatus = MnnLocalEngineStatus(available = false),
                    serviceRunning = false,
                    onManualBackendChange = {},
                    onResetBackendToAuto = {},
                    onReprobeOpenCl = {},
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
                backendChoice = BackendChoice.Manual(MnnBackend.CPU),
                capabilities = MnnCapabilitySnapshot(openCl = BackendCapability(MnnBackend.OPENCL, ProbeState.AVAILABLE)),
                effectiveBackendSelection = EffectiveBackendSelection(BackendChoice.Manual(MnnBackend.CPU), MnnBackend.CPU),
                localEngineStatus = MnnLocalEngineStatus(available = true),
                serviceRunning = true,
                onManualBackendChange = { selectedBackend = it },
                onResetBackendToAuto = {},
                onReprobeOpenCl = {},
                cloudProviderSettings = null,
                onCloudEnabledChange = {},
                onCloudPolicyChange = {},
            )
        }

        composeRule.onNodeWithText("OpenCL").performClick()

        assertEquals(MnnBackend.OPENCL, selectedBackend)
    }

    @Test
    fun unavailableManualOpenClShowsSafeCpuFallback() {
        composeRule.setContent {
            ModelHubScreen(
                nodes = emptyList(),
                gatewayModels = null,
                backendChoice = BackendChoice.Manual(MnnBackend.OPENCL),
                capabilities = MnnCapabilitySnapshot(
                    openCl = BackendCapability(MnnBackend.OPENCL, ProbeState.UNAVAILABLE),
                ),
                effectiveBackendSelection = EffectiveBackendSelection(
                    BackendChoice.Manual(MnnBackend.OPENCL), MnnBackend.CPU, unavailableManualChoice = true,
                ),
                localEngineStatus = MnnLocalEngineStatus(
                    available = true,
                    backend = MnnBackend.CPU,
                ),
                serviceRunning = true,
                onManualBackendChange = {},
                onResetBackendToAuto = {},
                onReprobeOpenCl = {},
                cloudProviderSettings = null,
                onCloudEnabledChange = {},
                onCloudPolicyChange = {},
            )
        }

        composeRule.onNodeWithText(
            "Manual OpenCL preference is saved. OpenCL is currently unavailable, so CPU is the safe fallback.",
        ).assertIsDisplayed()
    }

    @Test
    fun cloudCardListsGatewayCloudModelsAndHostOnlyNotice() {
        composeRule.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ModelHubScreen(
                    nodes = emptyList(),
                    gatewayModels = listOf(GatewayModel("cloud/work/chat", GatewayModelKind.CLOUD)),
                    backendChoice = BackendChoice.Auto,
                    capabilities = MnnCapabilitySnapshot(),
                    effectiveBackendSelection = EffectiveBackendSelection(BackendChoice.Auto, MnnBackend.CPU, resolving = true),
                    localEngineStatus = MnnLocalEngineStatus(available = false),
                    serviceRunning = false,
                    onManualBackendChange = {},
                    onResetBackendToAuto = {},
                    onReprobeOpenCl = {},
                    cloudProviderSettings = null,
                    onCloudEnabledChange = {},
                    onCloudPolicyChange = {},
                )
            }
        }

        composeRule.onNodeWithText("Cloud resources").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("cloud/work/chat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Requests run on the host that holds the Provider key. This device never receives that key; the host must authorize this paired node.",
        ).performScrollTo().assertIsDisplayed()
    }
}
