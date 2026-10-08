/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RouterJsonTest {
    @Test
    fun proxyStatusUsesBrokerReportedPort() {
        val status = parseProxyStatus("ollama", JSONObject("""{"ready":true,"port":11435}"""))

        assertEquals("ollama", status.engine)
        assertEquals(11435, status.port)
        assertEquals(true, status.ready)
    }

    @Test
    fun unavailableProxyDoesNotPublishPort() {
        val status = parseProxyStatus("lmstudio", JSONObject("""{"ready":false,"port":0}"""))

        assertFalse(status.ready)
        assertEquals(0, status.port)
    }

    @Test
    fun parsesWorkloadSnapshotAndNotificationWithoutRequestContent() {
        val workloadJson = """{"id":"w-1","model":"qwen","engine":"ollama","runId":"r-1","state":"running","originatedFrom":"PHONE","scheduledOn":"PC","createdAt":12,"startedAt":13,"completedAt":null,"error":null,"requesterId":null}"""
        val snapshot = parseWorkloads(JSONObject("""{"workloads":[$workloadJson]}"""))
        val notification = parseWorkloadUpsert("""{"workloadInfo":$workloadJson}""")

        assertEquals(snapshot.single(), notification)
        assertEquals("PC", notification.scheduledOn)
        assertEquals("running", notification.state)
    }

    @Test
    fun parsesCloudWorkloadWithoutAnEngine() {
        val notification = parseWorkloadUpsert(
            """{"workloadInfo":{"id":"cloud-1","model":"auto","kind":"cloud","providerId":"deepseek","publicModelId":"cloud/deepseek/chat","runId":"r-1","state":"completed","originatedFrom":"PC","createdAt":12,"startedAt":13,"completedAt":14,"error":null,"requesterId":"10000000-0000-0000-0000-000000000001","usage":{"inputTokens":10,"outputTokens":4}}}""",
        )

        assertEquals("cloud", notification.kind)
        assertEquals("", notification.engine)
        assertEquals("deepseek", notification.providerId)
        assertEquals(10L, notification.inputTokens)
        assertEquals(4L, notification.outputTokens)
        assertEquals("10000000-0000-0000-0000-000000000001", notification.requesterId)
    }

    @Test
    fun parsesWorkloadRemovalByOriginAndId() {
        val removed = parseWorkloadRemoval("""{"workloadId":"w-2","originatedFrom":"PC"}""")

        assertEquals("w-2", removed.workloadId)
        assertEquals("PC", removed.origin)
    }
}
