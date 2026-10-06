/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairProcessTest {
    @Test
    fun processKeepsStdoutAndStderrSeparate() {
        val command = shellCommand("printf stdout; printf stderr >&2")
        val process = PairProcess(command).start()

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }

        assertEquals("stdout", stdout.trim())
        assertEquals("stderr", stderr.trim())
        assertEquals(0, process.waitFor())
    }

    @Test
    fun stopClosesStdinBeforeForcingProcessExit() {
        val pairProcess = PairProcess(shellCommand("read line"))
        val process = pairProcess.start()

        val exitCode = pairProcess.stop(process, 2_000)

        assertTrue(exitCode >= 0)
        assertFalse("process should have exited", process.isAlive)
    }

    private fun shellCommand(script: String): List<String> {
        val windowsShell = System.getenv("ComSpec")
        return if (windowsShell != null && File(windowsShell).exists()) {
            val windowsScript = when (script) {
                "printf stdout; printf stderr >&2" -> "echo stdout & echo stderr 1>&2"
                "read line" -> "set /p line="
                else -> script
            }
            listOf(windowsShell, "/d", "/s", "/c", windowsScript)
        } else {
            listOf("/bin/sh", "-c", script)
        }
    }
}
