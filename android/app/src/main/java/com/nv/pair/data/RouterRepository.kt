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
    private val _proxies = MutableStateFlow(listOf(
        EngineProxyStatus("ollama", false, 0),
        EngineProxyStatus("lmstudio", false, 0),
    ))
    private val _workloads = MutableStateFlow<List<PairWorkload>>(emptyList())

    val proxies: StateFlow<List<EngineProxyStatus>> = _proxies.asStateFlow()
    val workloads: StateFlow<List<PairWorkload>> = _workloads.asStateFlow()

    fun setProxyStatus(status: EngineProxyStatus) {
        synchronized(lock) {
            _proxies.value = (_proxies.value.filterNot { it.engine == status.engine } + status).sortedBy {
                when (it.engine) {
                    "ollama" -> 0
                    "lmstudio" -> 1
                    else -> 2
                }
            }
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
}
