/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

enum class RuntimePhase {
    STOPPED,
    STARTING,
    WAITING_READY,
    RUNNING,
    STOPPING,
    STARTUP_FAILED,
    CRASHED,
    RESTART_BACKOFF,
}

data class PairRuntimeState(
    val phase: RuntimePhase,
    val desiredRunning: Boolean,
    val version: String? = null,
    val uptimeMillis: Long? = null,
    val error: String? = null,
) {
    fun transitionTo(
        next: RuntimePhase,
        desiredRunning: Boolean = this.desiredRunning,
        version: String? = this.version,
        uptimeMillis: Long? = this.uptimeMillis,
        error: String? = null,
    ): PairRuntimeState {
        check(next in allowedTransitions.getValue(phase)) {
            "invalid PAIR runtime transition: $phase -> $next"
        }
        val clearRuntime = next == RuntimePhase.STARTING || next == RuntimePhase.STOPPED
        return PairRuntimeState(
            phase = next,
            desiredRunning = desiredRunning,
            version = if (clearRuntime) null else version,
            uptimeMillis = if (clearRuntime) null else uptimeMillis,
            error = error,
        )
    }

    companion object {
        private val allowedTransitions = mapOf(
            RuntimePhase.STOPPED to setOf(RuntimePhase.STARTING, RuntimePhase.STARTUP_FAILED),
            RuntimePhase.STARTING to setOf(RuntimePhase.WAITING_READY, RuntimePhase.STARTUP_FAILED, RuntimePhase.STOPPING),
            RuntimePhase.WAITING_READY to setOf(RuntimePhase.RUNNING, RuntimePhase.STARTUP_FAILED, RuntimePhase.STOPPING),
            RuntimePhase.RUNNING to setOf(RuntimePhase.CRASHED, RuntimePhase.STOPPING),
            RuntimePhase.STOPPING to setOf(RuntimePhase.STOPPED),
            RuntimePhase.STARTUP_FAILED to setOf(
                RuntimePhase.STARTING,
                RuntimePhase.RESTART_BACKOFF,
                RuntimePhase.STOPPING,
                RuntimePhase.STOPPED,
            ),
            RuntimePhase.CRASHED to setOf(RuntimePhase.RESTART_BACKOFF, RuntimePhase.STARTUP_FAILED, RuntimePhase.STOPPING),
            RuntimePhase.RESTART_BACKOFF to setOf(RuntimePhase.STARTING, RuntimePhase.STOPPING),
        )

        fun stopped(): PairRuntimeState = PairRuntimeState(RuntimePhase.STOPPED, desiredRunning = false)
    }
}
