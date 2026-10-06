/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RouterRepositoryTest {
    @Test
    fun proxyStatusIsStoredByEngineAndSorted() {
        val repository = RouterRepository()
        repository.setProxyStatus(EngineProxyStatus("lmstudio", true, 12345))
        repository.setProxyStatus(EngineProxyStatus("ollama", true, 11435))

        assertEquals(listOf("ollama", "lmstudio"), repository.proxies.value.map(EngineProxyStatus::engine))
        assertEquals(11435, repository.proxies.value.first().port)
    }

    @Test
    fun stoppingFacadeClearsItsPublishedPort() {
        val repository = RouterRepository()
        repository.setProxyStatus(EngineProxyStatus("ollama", true, 11435))

        repository.setProxyStatus(EngineProxyStatus("ollama", false, 0))

        assertEquals(0, repository.proxies.value.first().port)
        assertEquals(false, repository.proxies.value.first().ready)
    }

    @Test
    fun workloadPushWinsOverOlderInitialSnapshot() {
        val repository = RouterRepository()
        val generation = repository.workloadGeneration()
        val pushed = PairWorkload("2", "model-b", "ollama", "run-1", "running", "NODE", "GPU-NODE", 2, 2, null, null)
        repository.upsertWorkload(pushed)

        assertEquals(false, repository.setWorkloadsIfUnchanged(
            generation,
            listOf(PairWorkload("1", "model-a", "ollama", "run-1", "queued", "NODE", "", 1, null, null, null)),
        ))
        assertEquals(pushed, repository.workloads.value.single())
    }

    @Test
    fun workloadRemovalUsesOriginAndIdWithoutDroppingAnotherNode() {
        val repository = RouterRepository()
        repository.upsertWorkload(PairWorkload("same", "model", "ollama", "run-a", "running", "NODE-A", "", 1, null, null, null))
        repository.upsertWorkload(PairWorkload("same", "model", "ollama", "run-b", "running", "NODE-B", "", 2, null, null, null))

        repository.removeWorkload("same", "NODE-A")

        assertEquals("NODE-B", repository.workloads.value.single().originatedFrom)
    }
}
