/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nv.pair.runtime.PairRuntimeController
import com.nv.pair.runtime.PairRuntimeState
import com.nv.pair.runtime.RuntimePhase
import com.nv.pair.data.PairNode
import com.nv.pair.data.ClusterState
import com.nv.pair.data.EngineProxyStatus
import com.nv.pair.data.PairWorkload
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.runtime.MnnLocalEngineStatus
import com.nv.pair.ui.theme.PAIRTheme
import com.nv.pair.models.ModelHubScreen
import com.nv.pair.models.GatewayModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var runtimeController: PairRuntimeController
    private var startAfterNotificationPermission = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (startAfterNotificationPermission) runtimeController.start()
        startAfterNotificationPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runtimeController = PairRuntimeController(applicationContext)
        setContent {
            PAIRTheme {
                val scope = rememberCoroutineScope()
                val state by runtimeController.state.collectAsState()
                val nodes by runtimeController.nodes.collectAsState()
                val cluster by runtimeController.cluster.collectAsState()
                val proxies by runtimeController.proxies.collectAsState()
                val workloads by runtimeController.workloads.collectAsState()
                val cloudProviderSettings by runtimeController.cloudProviderSettings.collectAsState()
                var gatewayModels by remember { mutableStateOf<List<com.nv.pair.models.GatewayModel>?>(null) }
                LaunchedEffect(state.phase) {
                    gatewayModels = null
                    if (state.phase == RuntimePhase.RUNNING) {
                        while (true) {
                            gatewayModels = runCatching {
                                withContext(Dispatchers.IO) {
                                    GatewayModelRepository(accessToken = com.nv.pair.runtime.GatewayTokenStore(applicationContext).getOrCreate()).models()
                                }
                            }.getOrNull()
                            delay(GATEWAY_MODEL_REFRESH_MILLIS)
                        }
                    }
                }
                val preferredMnnBackend by runtimeController.preferredMnnBackend.collectAsState(initial = MnnBackend.CPU)
                val mnnLocalEngine by runtimeController.mnnLocalEngine.collectAsState()
                val displayedMnnBackend = if (mnnLocalEngine.available) {
                    mnnLocalEngine.backend ?: preferredMnnBackend
                } else {
                    preferredMnnBackend
                }
                PairHomeScreen(
                    state = state,
                    nodes = nodes,
                    cluster = cluster,
                    proxies = proxies,
                    workloads = workloads,
                    cloudProviderSettings = cloudProviderSettings,
                    onCloudEnabledChange = runtimeController::setCloudEnabled,
                    onCloudPolicyChange = runtimeController::setCloudPolicy,
                    gatewayModels = gatewayModels,
                    preferredMnnBackend = displayedMnnBackend,
                    mnnLocalEngine = mnnLocalEngine,
                    onPreferredMnnBackendChange = { backend ->
                        scope.launch { runtimeController.setPreferredMnnBackend(backend) }
                    },
                    onStart = ::startRuntime,
                    onStop = runtimeController::stop,
                    onCreateCluster = runtimeController::createCluster,
                    onInvite = runtimeController::invite,
                    onRespond = runtimeController::respond,
                    onCancelInvite = runtimeController::cancelInvite,
                    onLeave = runtimeController::leaveCluster,
                    onRemove = runtimeController::removeMember,
                )
            }
        }
    }

    private fun startRuntime() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            startAfterNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runtimeController.start()
        }
    }
}

@Composable
private fun PairHomeScreen(
    state: PairRuntimeState,
    nodes: List<PairNode>,
    cluster: ClusterState,
    proxies: List<EngineProxyStatus>,
    workloads: List<PairWorkload>,
    cloudProviderSettings: com.nv.pair.rpc.CloudProviderSettings?,
    onCloudEnabledChange: (Boolean) -> Unit,
    onCloudPolicyChange: (String) -> Unit,
    gatewayModels: List<com.nv.pair.models.GatewayModel>?,
    preferredMnnBackend: MnnBackend,
    mnnLocalEngine: MnnLocalEngineStatus,
    onPreferredMnnBackendChange: (MnnBackend) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCreateCluster: (String) -> Unit,
    onInvite: (String) -> Unit,
    onRespond: (String, Boolean, String) -> Unit,
    onCancelInvite: (String) -> Unit,
    onLeave: () -> Unit,
    onRemove: (String) -> Unit,
) {
    var destination by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val running = state.phase == RuntimePhase.RUNNING
    val transitional = state.phase == RuntimePhase.STARTING ||
        state.phase == RuntimePhase.WAITING_READY ||
        state.phase == RuntimePhase.RESTART_BACKOFF ||
        state.phase == RuntimePhase.STOPPING

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = destination == 0,
                    onClick = { destination = 0 },
                    icon = {},
                    label = { Text("Network") },
                )
                NavigationBarItem(
                    selected = destination == 1,
                    onClick = { destination = 1 },
                    icon = {},
                    label = { Text("Cluster") },
                )
                NavigationBarItem(
                    selected = destination == 2,
                    onClick = { destination = 2 },
                    icon = {},
                    label = { Text("Models") },
                )
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(if (destination == 0) "PAIR · Network" else if (destination == 1) "PAIR · Cluster" else "PAIR · Models", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Personal AI Router",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (destination == 0) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Runtime status", style = MaterialTheme.typography.titleMedium)
                    Text(phaseLabel(state.phase), style = MaterialTheme.typography.headlineSmall)
                    state.version?.let { Text("Broker version $it") }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("Local router", style = MaterialTheme.typography.titleLarge)
            proxies.forEach { ProxyStatusCard(it) }
            MnnLocalEngineCard(mnnLocalEngine)
            LocalApiCard(running = running, modelIds = gatewayModels?.map(com.nv.pair.models.GatewayModel::id))
            Text("Recent workloads", style = MaterialTheme.typography.titleLarge)
            if (workloads.isEmpty()) {
                Text("No requests have been routed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                workloads.take(8).forEach { WorkloadCard(it) }
            }
            Text("Nodes · ${nodes.size}", style = MaterialTheme.typography.titleLarge)
            if (nodes.isEmpty()) {
                Text(
                    if (running) "No PAIR nodes found yet." else "Start PAIR to discover nodes on your network.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                nodes.forEach { node -> PairNodeCard(node) }
            }
            Spacer(Modifier.height(4.dp))
            if (transitional) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Button(
                onClick = if (running || state.desiredRunning) onStop else onStart,
                enabled = !transitional || state.phase == RuntimePhase.RESTART_BACKOFF,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running || state.desiredRunning) "Stop PAIR" else "Start PAIR")
            }
            } else if (destination == 1) {
                ClusterManagement(
                    cluster = cluster,
                    discoveredNodes = nodes,
                    relationshipStateLoaded = running,
                    enabled = running && !cluster.busy,
                    onCreate = onCreateCluster,
                    onInvite = onInvite,
                    onRespond = onRespond,
                    onCancel = onCancelInvite,
                    onLeave = onLeave,
                    onRemove = onRemove,
                )
            } else {
                ModelHubScreen(
                    nodes = nodes,
                    gatewayModels = gatewayModels,
                    cloudProviderSettings = cloudProviderSettings,
                    onCloudEnabledChange = onCloudEnabledChange,
                    onCloudPolicyChange = onCloudPolicyChange,
                    preferredBackend = preferredMnnBackend,
                    localEngineStatus = mnnLocalEngine,
                    onBackendChange = onPreferredMnnBackendChange,
                )
            }
        }
    }
}

@Composable
private fun LocalApiCard(running: Boolean, modelIds: List<String>?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val url = "http://127.0.0.1:14326/v1"
    val token = remember(context) { com.nv.pair.runtime.GatewayTokenStore(context).getOrCreate() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Local API", style = MaterialTheme.typography.titleMedium)
            Text("Status: ${if (running) "Running" else "Stopped"}")
            Text("URL: $url", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Key: stored securely on this device", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Models: ${modelIds?.size?.toString() ?: "Unavailable"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    ?: return@OutlinedButton
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PAIR Local API", "$url\n$token"))
            }) {
                Text("Copy URL and key")
            }
        }
    }
}

private const val GATEWAY_MODEL_REFRESH_MILLIS = 5_000L

@Composable
private fun ProxyStatusCard(status: EngineProxyStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (status.engine == "mnn") "MNN facade" else status.engine, style = MaterialTheme.typography.titleMedium)
            Text(
                if (status.ready) "http://127.0.0.1:${status.port}"
                else if (status.engine == "mnn") "MNN facade is unavailable" else "Router facade is starting or unavailable",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MnnLocalEngineCard(status: MnnLocalEngineStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Local MNN engine", style = MaterialTheme.typography.titleMedium)
            Text(if (status.available) "Available" else "Unavailable")
            status.errorCode?.let { Text("Error: ${it.name}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun WorkloadCard(workload: PairWorkload) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(workload.model.ifBlank { "Inference request" }, style = MaterialTheme.typography.titleMedium)
            workload.requesterId?.let { Text("Caller node $it", style = MaterialTheme.typography.bodySmall) }
            val source = if (workload.kind == "cloud") "Cloud ${workload.providerId.orEmpty()}" else workload.engine
            Text("$source · ${workload.state}")
            if (workload.scheduledOn.isNotBlank()) {
                Text("Destination · ${workload.scheduledOn}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PairNodeCard(node: PairNode) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(node.name, style = MaterialTheme.typography.titleMedium)
            Text(node.ipAddress, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (node.trusted) "Trusted" else "Not paired",
                color = if (node.trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            node.modelsByEngine.forEach { (engine, models) ->
                Text("$engine · ${models.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun phaseLabel(phase: RuntimePhase): String = when (phase) {
    RuntimePhase.STOPPED -> "Stopped"
    RuntimePhase.STARTING -> "Starting"
    RuntimePhase.WAITING_READY -> "Waiting for broker"
    RuntimePhase.RUNNING -> "Running"
    RuntimePhase.STOPPING -> "Stopping"
    RuntimePhase.STARTUP_FAILED -> "Startup failed"
    RuntimePhase.CRASHED -> "Broker stopped unexpectedly"
    RuntimePhase.RESTART_BACKOFF -> "Reconnecting"
}
