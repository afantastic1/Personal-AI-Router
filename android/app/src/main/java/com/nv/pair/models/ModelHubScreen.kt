/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nv.pair.data.PairNode
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.BackendChoice
import com.nv.pair.mnn.EffectiveBackendSelection
import com.nv.pair.mnn.MnnCapabilitySnapshot
import com.nv.pair.mnn.ProbeReason
import com.nv.pair.mnn.ProbeState
import com.nv.pair.runtime.MnnLocalEngineStatus
import com.nv.pair.rpc.CloudProviderSettings

@Composable
fun ModelHubScreen(
    nodes: List<PairNode>,
    gatewayModels: List<GatewayModel>?,
    backendChoice: BackendChoice,
    capabilities: MnnCapabilitySnapshot,
    effectiveBackendSelection: EffectiveBackendSelection,
    localEngineStatus: MnnLocalEngineStatus,
    serviceRunning: Boolean,
    onManualBackendChange: (MnnBackend) -> Unit,
    onResetBackendToAuto: () -> Unit,
    onReprobeOpenCl: () -> Unit,
    cloudProviderSettings: CloudProviderSettings?,
    onCloudEnabledChange: (Boolean) -> Unit,
    onCloudPolicyChange: (String) -> Unit,
) {
    val modelHubViewModel: ModelHubViewModel = viewModel()
    val hubState by modelHubViewModel.state.collectAsStateWithLifecycle()
    var source by remember { mutableStateOf(ModelSourceKind.MODELSCOPE) }
    var query by remember { mutableStateOf("") }
    var importModelId by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ModelDescriptor?>(null) }
    var pendingUnverifiedInstall by remember { mutableStateOf<ModelDescriptor?>(null) }
    val catalogFailureIsRetryable = when (val phase = hubState.catalogPhase) {
        is CatalogPhase.Failed -> phase.failure.retryable
        CatalogPhase.Idle, CatalogPhase.Searching -> false
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val modelId = importModelId.trim()
        if (modelId.isNotBlank() && uris.isNotEmpty()) {
            modelHubViewModel.import(modelId, uris)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Model Hub", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Search model catalogs, install verified MNN models to this device, or import files from local storage.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MNN Compute Engine", style = MaterialTheme.typography.titleLarge)
                Text(
                    "The selected engine applies to the next MNN model request.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Selection mode: ${if (backendChoice == BackendChoice.Auto) "Automatic" else "Manual"}")
                Text(
                    if (effectiveBackendSelection.resolving) {
                        "Effective engine: CPU (temporary while checking)"
                    } else {
                        "Effective engine: ${effectiveBackendSelection.effectiveBackend.name}"
                    },
                )
                Text(if (localEngineStatus.available) "CPU runtime: Available" else "CPU runtime: Runtime unavailable")
                Text("OpenCL: ${openClProbeLabel(capabilities.openCl.state, capabilities.openCl.reason)}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onReprobeOpenCl,
                        enabled = serviceRunning && capabilities.openCl.state != ProbeState.CHECKING,
                    ) { Text("Reprobe") }
                    TextButton(
                        onClick = onResetBackendToAuto,
                        enabled = backendChoice !is BackendChoice.Auto,
                    ) { Text("Restore automatic selection") }
                }
                Column(Modifier.selectableGroup()) {
                    MnnBackendOption(
                        "CPU",
                        MnnBackend.CPU,
                        backendChoice,
                        enabled = true,
                        onManualBackendChange,
                    )
                    MnnBackendOption(
                        "OpenCL",
                        MnnBackend.OPENCL,
                        backendChoice,
                        enabled = capabilities.openCl.state == ProbeState.AVAILABLE,
                        onManualBackendChange,
                    )
                }
                if (effectiveBackendSelection.unavailableManualChoice) {
                    Text(
                        "Manual OpenCL preference is saved. OpenCL is currently unavailable, so CPU is the safe fallback.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Catalog", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceChoice("ModelScope", source == ModelSourceKind.MODELSCOPE) {
                        source = ModelSourceKind.MODELSCOPE
                        modelHubViewModel.changeSource()
                    }
                    SourceChoice("Hugging Face", source == ModelSourceKind.HUGGING_FACE) {
                        source = ModelSourceKind.HUGGING_FACE
                        modelHubViewModel.changeSource()
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Model name or family") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !hubState.isBusy && query.isNotBlank(),
                    onClick = { modelHubViewModel.search(query.trim(), source) },
                ) {
                    Text(if (hubState.catalogPhase == CatalogPhase.Searching) "Searching…" else "Search models")
                }
                if (hubState.catalogPage > 1 || hubState.hasMoreCatalogPages) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = !hubState.isBusy && hubState.catalogPage > 1,
                            onClick = modelHubViewModel::previousCatalogPage,
                        ) {
                            Text("Previous page")
                        }
                        Text("Page ${hubState.catalogPage}", modifier = Modifier.weight(1f))
                        Button(
                            enabled = !hubState.isBusy && hubState.hasMoreCatalogPages,
                            onClick = modelHubViewModel::nextCatalogPage,
                        ) {
                            Text("Next page")
                        }
                    }
                }
                hubState.downloadProgress?.let { progress ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Downloading ${progress.artifactPath}", style = MaterialTheme.typography.bodySmall)
                        val total = progress.overallTotalBytes
                        val received = progress.overallBytesReceived
                        if (total == null || total <= 0L) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(formatTransferBytes(received), style = MaterialTheme.typography.bodySmall)
                        } else {
                            val fraction = (received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "${formatTransferBytes(received)} of ${formatTransferBytes(total)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(onClick = modelHubViewModel::cancelActiveOperation) {
                            Text("Cancel download")
                        }
                    }
                }
                hubState.searchResults.forEach { descriptor ->
                    ModelCatalogCard(
                        descriptor = descriptor,
                        busy = hubState.isBusy || hubState.activeModelId == descriptor.logicalId,
                        onInspect = { modelHubViewModel.inspect(descriptor) },
                        onInstall = {
                            if (descriptor.installability() == ModelInstallability.LOCAL_DIGEST_ONLY) {
                                pendingUnverifiedInstall = descriptor
                            } else modelHubViewModel.install(descriptor)
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = importModelId,
                        onValueChange = { importModelId = it },
                        label = { Text("Local model ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = !hubState.isBusy && importModelId.isNotBlank(),
                        onClick = { importPicker.launch(arrayOf("*/*")) },
                    ) {
                        Text("Import MNN files")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Installed on this device", style = MaterialTheme.typography.titleLarge)
                if (hubState.installedModels.isEmpty()) {
                    Text("No MNN models are installed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    hubState.installedModels.forEach { model ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(model.engineModelId, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatBytes(model.estimatedMemoryBytes), style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { pendingDelete = model }) { Text("Delete") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PAIR runtime inventory", style = MaterialTheme.typography.titleLarge)
                Text("Models advertised by the PAIR gateway are available to local API clients.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val inventory = remember(nodes) { ModelInventoryRepository().fromNetwork(nodes) }
                if (inventory.isEmpty()) {
                    Text("No models are currently available in the PAIR network.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    inventory.forEach { item ->
                        Text("${item.modelId} · ${item.engine} · ${item.nodeId}${if (item.loaded) " · loaded" else ""}")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud resources", style = MaterialTheme.typography.titleLarge)
                val cloudModels = gatewayModels.orEmpty()
                    .filter { it.kind == GatewayModelKind.CLOUD || it.kind == GatewayModelKind.REMOTE_CLOUD }
                    .map(GatewayModel::id)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable Cloud on this device")
                        Text(
                            "This controls only this device's Gateway. It does not authorize other paired nodes.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = cloudProviderSettings?.cloudEnabled == true,
                        enabled = cloudProviderSettings != null,
                        onCheckedChange = onCloudEnabledChange,
                    )
                }
                val hasRemoteCloudModels = gatewayModels.orEmpty().any { it.kind == GatewayModelKind.REMOTE_CLOUD }
                Text(
                    if (hasRemoteCloudModels) {
                        "The execution host applies its configured monthly and per-request budget limits."
                    } else {
                        "Configured budget: $${cloudProviderSettings?.monthlyBudgetUsd ?: 0.0} monthly; " +
                            "$${cloudProviderSettings?.perRequestMaxEstimatedCostUsd ?: 0.0} per request."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Routing policy", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    listOf("local_only", "cloud_only", "prefer_local", "prefer_cloud").forEach { policy ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = cloudProviderSettings?.policy == policy,
                                onClick = { onCloudPolicyChange(policy) },
                                role = Role.RadioButton,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = cloudProviderSettings?.policy == policy,
                                onClick = null,
                            )
                            Text(policy.replace('_', ' '))
                        }
                    }
                }
                if (cloudModels.isEmpty()) {
                    Text(
                        "No Cloud models are configured on this device. Provider setup and credentials are managed on the host that owns them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    cloudModels.forEach { Text(it) }
                }
                Text(
                    "Requests run on the host that holds the Provider key. This device never receives that key; the host must authorize this paired node.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Automatic model policies", style = MaterialTheme.typography.titleLarge)
                val aliases = gatewayModels.orEmpty()
                    .filter { it.kind == GatewayModelKind.AUTOMATIC }
                    .map(GatewayModel::id)
                if (gatewayModels == null) {
                    Text("Automatic model aliases are unavailable while the gateway model list cannot be read.")
                } else if (aliases.isEmpty()) {
                    Text("The gateway is not advertising automatic model aliases.")
                } else {
                    aliases.forEach { alias -> Text(alias) }
                }
                Text("The Go gateway chooses the model and engine; the existing PAIR scheduler chooses the node.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (hubState.statusMessage.isNotBlank()) Text(hubState.statusMessage, color = MaterialTheme.colorScheme.primary)
        if (hubState.failureMessage.isNotBlank()) Text(hubState.failureMessage, color = MaterialTheme.colorScheme.error)
        if (catalogFailureIsRetryable) {
            OutlinedButton(enabled = !hubState.isBusy, onClick = modelHubViewModel::retryCatalogSearch) {
                Text("Retry search")
            }
        }
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${model.displayName}?") },
            text = { Text("This removes the installed model files from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    modelHubViewModel.delete(model)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    pendingUnverifiedInstall?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingUnverifiedInstall = null },
            title = { Text("Install without a provider checksum?") },
            text = { Text("This model uses a pinned revision and HTTPS, but the provider has no trusted SHA-256 for every required file. PAIR will record local digests; they cannot verify the files against the source. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnverifiedInstall = null
                    modelHubViewModel.install(model, allowUnverifiedSource = true)
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { pendingUnverifiedInstall = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MnnBackendOption(
    label: String,
    backend: MnnBackend,
    choice: BackendChoice,
    enabled: Boolean,
    onBackendChange: (MnnBackend) -> Unit,
) {
    val selected = when (choice) {
        BackendChoice.Auto -> false
        is BackendChoice.Manual -> choice.backend == backend
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onBackendChange(backend) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.weight(1f))
    }
}

private fun openClProbeLabel(state: ProbeState, reason: ProbeReason?): String = when (state) {
    ProbeState.UNKNOWN -> "Not checked"
    ProbeState.CHECKING -> "Checking"
    ProbeState.AVAILABLE -> "Can initialize (model not verified)"
    ProbeState.UNAVAILABLE -> when (reason) {
        ProbeReason.NOT_COMPILED -> "OpenCL is not compiled into this APK"
        ProbeReason.RUNTIME_INIT_FAILED -> "OpenCL runtime unavailable on this device or driver"
        else -> "OpenCL unavailable"
    }
    ProbeState.ERROR -> "Probe failed; retry is available"
}

@Composable
private fun SourceChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun ModelCatalogCard(
    descriptor: ModelDescriptor,
    busy: Boolean,
    onInspect: () -> Unit,
    onInstall: () -> Unit,
) {
    val installability = descriptor.installability()
    val supported = installability == ModelInstallability.VERIFIED_INSTALLABLE ||
        installability == ModelInstallability.LOCAL_DIGEST_ONLY
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(descriptor.displayName, style = MaterialTheme.typography.titleMedium)
            Text(descriptor.source.repository, style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(descriptor.family, descriptor.parameterCount?.let { "${it / 1_000_000}M" }, descriptor.quantization, descriptor.format.name)
                    .joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (installability) {
                        ModelInstallability.VERIFIED_INSTALLABLE -> "SHA-256 verified MNN files available"
                        ModelInstallability.LOCAL_DIGEST_ONLY -> "Pinned source; local digest only (confirmation required)"
                        ModelInstallability.UNPINNED_SOURCE_REVISION -> "Immutable source revision unavailable"
                        ModelInstallability.UNSUPPORTED_FORMAT -> "Unsupported model format"
                        ModelInstallability.INCOMPLETE_ARTIFACT_SET -> "Required MNN artifacts unavailable"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (descriptor.requiredArtifactPaths.isEmpty()) {
                    OutlinedButton(enabled = !busy, onClick = onInspect) {
                        Text(if (busy) "Inspecting…" else "Inspect")
                    }
                } else {
                    Button(enabled = supported && !busy, onClick = onInstall) {
                        Text(if (busy) "Installing…" else "Install")
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long?): String = bytes?.let { "Estimated memory: ${it / (1024 * 1024)} MiB" } ?: "Memory estimate unavailable"

private fun formatTransferBytes(bytes: Long): String = "${bytes / (1024 * 1024)} MiB"
