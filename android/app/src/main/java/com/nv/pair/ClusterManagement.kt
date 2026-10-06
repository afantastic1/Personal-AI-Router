/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nv.pair.data.ClusterState
import com.nv.pair.data.PairNode

@Composable
internal fun ClusterManagement(
    cluster: ClusterState,
    discoveredNodes: List<PairNode>,
    enabled: Boolean,
    onCreate: (String) -> Unit,
    onInvite: (String) -> Unit,
    onRespond: (String, Boolean, String) -> Unit,
    onCancel: (String) -> Unit,
    onLeave: () -> Unit,
    onRemove: (String) -> Unit,
) {
    var clusterName by remember { mutableStateOf("") }
    var pinByInvite by remember { mutableStateOf("") }
    val identity = cluster.identity
    Text("Your node", style = MaterialTheme.typography.titleLarge)
    if (identity == null) {
        Text("Start PAIR to load this device’s cluster identity.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(identity.name, style = MaterialTheme.typography.titleMedium)
        Text(if (cluster.isClustered) "Cluster · ${identity.clusterFriendlyName.ifBlank { identity.clusterId }}" else "Not in a cluster")
        Text("Node ID · ${identity.nodeId}", style = MaterialTheme.typography.bodySmall)
        if (!cluster.isClustered) {
            OutlinedTextField(
                value = clusterName,
                onValueChange = { clusterName = it },
                label = { Text("Cluster name") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onCreate(clusterName.trim()); clusterName = "" },
                enabled = enabled && clusterName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create cluster") }
        }
    }
    if (cluster.busy) CircularProgressIndicator()
    cluster.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    Text("Members", style = MaterialTheme.typography.titleLarge)
    if (cluster.members.none { it.state == "member" }) {
        Text("No other members yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    cluster.members.filter { it.state == "member" && it.nodeUuid != identity?.nodeUuid }.forEach { member ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(member.name.ifBlank { member.id }, style = MaterialTheme.typography.titleMedium)
                Text(member.ipAddress, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onRemove(member.nodeUuid.ifBlank { member.id }) }, enabled = enabled) {
                    Text("Remove member")
                }
            }
        }
    }
    if (cluster.isClustered) {
        Button(onClick = onLeave, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Leave cluster") }
    }

    Text("Invites", style = MaterialTheme.typography.titleLarge)
    if (cluster.invites.isEmpty()) Text("No pending invites.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    cluster.invites.forEach { invite ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(invite.fromNodeName.ifBlank { invite.fromNodeId.ifBlank { "Cluster invite" } }, style = MaterialTheme.typography.titleMedium)
                Text("${invite.state}${if (invite.reason == "incorrect-pin") " · Incorrect PIN" else ""}")
                when {
                    invite.state == "pending" && invite.pin != null -> {
                        Text("Share this PIN with the other device", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(invite.pin, style = MaterialTheme.typography.headlineMedium)
                        Button(onClick = { onCancel(invite.inviteId) }, enabled = enabled) { Text("Cancel invite") }
                    }
                    invite.state == "pending" -> {
                        OutlinedTextField(
                            value = pinByInvite,
                            onValueChange = { input -> pinByInvite = input.filter(Char::isDigit).take(6) },
                            label = { Text("6-digit PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onRespond(invite.inviteId, true, pinByInvite); pinByInvite = "" },
                                enabled = enabled && pinByInvite.length == 6,
                            ) { Text("Accept") }
                            Button(onClick = { onRespond(invite.inviteId, false, ""); pinByInvite = "" }, enabled = enabled) {
                                Text("Decline")
                            }
                        }
                    }
                    invite.state != "pending" -> Text("Invite ${invite.state}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (enabled) {
        Text("Nearby nodes", style = MaterialTheme.typography.titleLarge)
        val eligible = discoveredNodes.filterNot(PairNode::trusted)
        if (eligible.isEmpty()) Text("No unpaired nodes found yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        eligible.forEach { node ->
            Button(onClick = { onInvite(node.id) }, modifier = Modifier.fillMaxWidth()) {
                Text("Invite ${node.name}")
            }
        }
    }
}
