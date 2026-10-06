/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PairRepository {
    private val lock = Any()
    private var generation = 0L
    private val _nodes = MutableStateFlow<List<PairNode>>(emptyList())

    val nodes: StateFlow<List<PairNode>> = _nodes.asStateFlow()

    fun nodesGeneration(): Long = synchronized(lock) { generation }

    fun applyNodesChanged(nodes: List<PairNode>) {
        synchronized(lock) {
            generation++
            _nodes.value = nodes.toList()
        }
    }

    fun applyInitialSnapshotIfUnchanged(expectedGeneration: Long, nodes: List<PairNode>): Boolean =
        synchronized(lock) {
            if (generation != expectedGeneration) {
                false
            } else {
                _nodes.value = nodes.toList()
                true
            }
        }
}
