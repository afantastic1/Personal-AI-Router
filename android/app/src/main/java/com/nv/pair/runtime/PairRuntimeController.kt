/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nv.pair.data.PairNode
import com.nv.pair.data.ClusterState
import com.nv.pair.data.EngineProxyStatus
import com.nv.pair.data.PairWorkload
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class PairRuntimeController(context: Context) {
    private val applicationContext = context.applicationContext
    private val mnnSettings = MnnSettingsRepository(applicationContext)

    val state: StateFlow<PairRuntimeState> = PairRuntimeService.runtimeState
    val nodes: StateFlow<List<PairNode>> = PairRuntimeService.discoveredNodes
    val cluster: StateFlow<ClusterState> = PairRuntimeService.clusterState
    val proxies: StateFlow<List<EngineProxyStatus>> = PairRuntimeService.proxyStatuses
    val mnnLocalEngine: StateFlow<MnnLocalEngineStatus> = PairRuntimeService.mnnLocalEngine
    val preferredMnnBackend: Flow<MnnBackend> = mnnSettings.preferredBackend
    val workloads: StateFlow<List<PairWorkload>> = PairRuntimeService.workloads

    suspend fun setPreferredMnnBackend(backend: MnnBackend) {
        mnnSettings.setPreferredBackend(backend)
        if (PairRuntimeService.runtimeState.value.desiredRunning) {
            applicationContext.startService(
                Intent(applicationContext, PairRuntimeService::class.java)
                    .setAction(PairRuntimeService.ACTION_MNN_BACKEND_CHANGED)
                    .putExtra(PairRuntimeService.EXTRA_MNN_BACKEND, backend.preferenceValue),
            )
        }
    }

    fun start() {
        val intent = Intent(applicationContext, PairRuntimeService::class.java)
            .setAction(PairRuntimeService.ACTION_START)
        ContextCompat.startForegroundService(applicationContext, intent)
    }

    fun stop() {
        val intent = Intent(applicationContext, PairRuntimeService::class.java)
            .setAction(PairRuntimeService.ACTION_STOP)
        applicationContext.startService(intent)
    }

    fun createCluster(name: String) = clusterAction(PairRuntimeService.ACTION_CLUSTER_CREATE, name)

    fun invite(nodeId: String) = clusterAction(PairRuntimeService.ACTION_CLUSTER_INVITE, nodeId)

    fun respond(inviteId: String, accept: Boolean, pin: String = "") {
        clusterIntent(PairRuntimeService.ACTION_CLUSTER_RESPOND)
            .putExtra(PairRuntimeService.EXTRA_VALUE, inviteId)
            .putExtra(PairRuntimeService.EXTRA_SECONDARY, pin)
            .putExtra(PairRuntimeService.EXTRA_ACCEPT, accept)
            .also(applicationContext::startService)
    }

    fun cancelInvite(inviteId: String) = clusterAction(PairRuntimeService.ACTION_CLUSTER_CANCEL, inviteId)

    fun leaveCluster() = clusterAction(PairRuntimeService.ACTION_CLUSTER_LEAVE)

    fun removeMember(nodeId: String) = clusterAction(PairRuntimeService.ACTION_CLUSTER_REMOVE, nodeId)

    private fun clusterAction(action: String, value: String = "") {
        clusterIntent(action).putExtra(PairRuntimeService.EXTRA_VALUE, value).also(applicationContext::startService)
    }

    private fun clusterIntent(action: String) =
        Intent(applicationContext, PairRuntimeService::class.java).setAction(action)
}
