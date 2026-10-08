/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
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

    @Test
    fun stopTerminatesProcessEvenWhenClosingStdinFails() {
        val process = ProcessWithFailingStdinClose()

        val failure = runCatching { PairProcess(emptyList()).stop(process, 1_000) }.exceptionOrNull()

        assertTrue("stdin close failure should be reported", failure is IOException)
        assertFalse("process termination must still be attempted", process.isAlive)
    }

    private class ProcessWithFailingStdinClose : Process() {
        private var alive = true

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() {
                throw IOException("stdin close failed")
            }
        }

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            alive = false
            return true
        }

        override fun exitValue(): Int = 0

        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly(): Process {
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive
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
