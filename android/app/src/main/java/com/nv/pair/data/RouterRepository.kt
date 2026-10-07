/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RouterRepository {
    private val lock = Any()
    private var workloadGeneration = 0L
    private val _proxies = MutableStateFlow(emptyProxyStatuses())
    private val _workloads = MutableStateFlow<List<PairWorkload>>(emptyList())

    val proxies: StateFlow<List<EngineProxyStatus>> = _proxies.asStateFlow()
    val workloads: StateFlow<List<PairWorkload>> = _workloads.asStateFlow()

    fun setProxyStatus(status: EngineProxyStatus) {
        synchronized(lock) {
            if (status.engine !in ROUTER_ENGINES) return
            val statusesByEngine = _proxies.value.associateBy(EngineProxyStatus::engine) + (status.engine to status)
            _proxies.value = ROUTER_ENGINES.mapNotNull(statusesByEngine::get)
        }
    }

    fun resetProxyStatuses() {
        synchronized(lock) {
            _proxies.value = emptyProxyStatuses()
        }
    }

    fun workloadGeneration(): Long = synchronized(lock) { workloadGeneration }

    fun setWorkloadsIfUnchanged(expectedGeneration: Long, workloads: List<PairWorkload>): Boolean = synchronized(lock) {
        if (workloadGeneration != expectedGeneration) {
            false
        } else {
            _workloads.value = workloads.sortedByDescending(PairWorkload::createdAt)
            true
        }
    }

    fun upsertWorkload(workload: PairWorkload) {
        synchronized(lock) {
            workloadGeneration++
            val current = _workloads.value.filterNot { it.key() == workload.key() }
            _workloads.value = (current + workload).sortedByDescending(PairWorkload::createdAt)
        }
    }

    fun removeWorkload(workloadId: String, origin: String) {
        synchronized(lock) {
            workloadGeneration++
            _workloads.value = _workloads.value.filterNot { it.id == workloadId && it.originatedFrom == origin }
        }
    }

    private fun PairWorkload.key(): String = "$originatedFrom\u0000$engine\u0000$runId\u0000$id"

    private fun emptyProxyStatuses(): List<EngineProxyStatus> =
        ROUTER_ENGINES.map { engine -> EngineProxyStatus(engine, false, 0) }

    companion object {
        val ROUTER_ENGINES = listOf("ollama", "lmstudio", "mnn")
    }
}
