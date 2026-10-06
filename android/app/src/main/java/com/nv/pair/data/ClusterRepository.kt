/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ClusterRepository {
    private val lock = Any()
    private var membersGeneration = 0L
    private var latestClusterIdentity: Pair<String, String>? = null
    private val _state = MutableStateFlow(ClusterState())

    val state: StateFlow<ClusterState> = _state.asStateFlow()

    fun setIdentity(identity: ClusterIdentity) {
        synchronized(lock) {
            val latest = latestClusterIdentity
            _state.value = _state.value.copy(
                identity = latest?.let { identity.copy(clusterId = it.first, clusterFriendlyName = it.second) } ?: identity,
            )
        }
    }

    fun applyClusterIdentityChanged(clusterId: String, friendlyName: String) {
        synchronized(lock) {
            latestClusterIdentity = clusterId to friendlyName
            val identity = _state.value.identity ?: return
            _state.value = _state.value.copy(
                identity = identity.copy(clusterId = clusterId, clusterFriendlyName = friendlyName),
            )
        }
    }

    fun membersGeneration(): Long = synchronized(lock) { membersGeneration }

    fun applyMembersChanged(members: List<ClusterMember>) {
        synchronized(lock) {
            membersGeneration++
            _state.value = _state.value.copy(members = members.toList())
        }
    }

    fun applyInitialMembersIfUnchanged(expectedGeneration: Long, members: List<ClusterMember>): Boolean =
        synchronized(lock) {
            if (membersGeneration != expectedGeneration) {
                false
            } else {
                _state.value = _state.value.copy(members = members.toList())
                true
            }
        }

    fun applyInvite(invite: ClusterInvite) {
        synchronized(lock) {
            val current = _state.value.invites
            val updated = current.filterNot { it.inviteId == invite.inviteId } + invite
            _state.value = _state.value.copy(invites = updated.sortedBy(ClusterInvite::createdAt))
        }
    }

    fun removeInvite(inviteId: String) {
        synchronized(lock) {
            _state.value = _state.value.copy(invites = _state.value.invites.filterNot { it.inviteId == inviteId })
        }
    }

    fun clearPendingInvites() {
        synchronized(lock) {
            _state.value = _state.value.copy(invites = _state.value.invites.filterNot { it.state == "pending" })
        }
    }

    fun setBusy(busy: Boolean) {
        synchronized(lock) { _state.value = _state.value.copy(busy = busy) }
    }

    fun setError(error: String?) {
        synchronized(lock) { _state.value = _state.value.copy(error = error, busy = false) }
    }
}
