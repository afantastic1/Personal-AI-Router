/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nv.pair.runtime.NativeBinaryRegistry
import com.nv.pair.runtime.PairProcess
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBinarySmokeInstrumentedTest {
    @Test
    fun settingsBinaryRunsFromNativeLibraryDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = NativeBinaryRegistry(File(context.applicationInfo.nativeLibraryDir))
        val pairProcess = PairProcess(listOf(registry.settings().absolutePath))
        val process = pairProcess.start()

        val stdout = process.inputStream.bufferedReader()
        val readyFrame = stdout.readLine()
        assertFalse("settings worker did not emit a ready frame", readyFrame.isNullOrBlank())
        val ready = JSONObject(readyFrame)
        assertEquals("2.0", ready.getString("jsonrpc"))
        assertEquals("ready", ready.getString("method"))
        assertFalse(ready.getJSONObject("params").getString("version").isBlank())

        val exitCode = pairProcess.stop(process, 5_000)
        val remainingStdout = stdout.use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }

        assertEquals(0, exitCode)
        assertTrue("unexpected additional stdout: $remainingStdout", remainingStdout.isBlank())
        assertTrue("shutdown message missing from stderr: $stderr", stderr.contains("shutdown complete"))
    }
}
