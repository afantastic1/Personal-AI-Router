/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import java.util.concurrent.TimeUnit
import java.io.IOException

class PairProcess(
    private val command: List<String>,
    private val environment: Map<String, String> = emptyMap(),
) {
    fun start(): Process {
        check(command.isNotEmpty()) { "PAIR process command must not be empty" }

        val processBuilder = ProcessBuilder(command)
        processBuilder.redirectErrorStream(false)
        processBuilder.environment().putAll(environment)
        return processBuilder.start()
    }

    fun stop(process: Process, timeoutMillis: Long): Int {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        var failure: IOException? = null
        try {
            process.outputStream.close()
        } catch (closeFailure: Exception) {
            failure = IOException("Could not close PAIR process stdin", closeFailure)
        }

        var interrupted = false
        try {
            if (process.isAlive && !waitFor(process, timeoutMillis)) {
                try {
                    process.destroy()
                } catch (destroyFailure: Exception) {
                    failure = failure.withAdditionalFailure(IOException("Could not request PAIR process shutdown", destroyFailure))
                }
            }
            if (process.isAlive && !waitFor(process, timeoutMillis)) {
                try {
                    process.destroyForcibly()
                } catch (forceFailure: Exception) {
                    failure = failure.withAdditionalFailure(IOException("Could not force PAIR process shutdown", forceFailure))
                }
            }
            if (process.isAlive && !waitFor(process, timeoutMillis)) {
                failure = failure.withAdditionalFailure(IOException("PAIR process did not exit after forced termination"))
            }
        } catch (waitFailure: InterruptedException) {
            interrupted = true
            runCatching { process.destroyForcibly() }
                .onFailure { failure = failure.withAdditionalFailure(IOException("Could not force PAIR process shutdown", it)) }
            failure = failure.withAdditionalFailure(IOException("Interrupted while stopping PAIR process", waitFailure))
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }

        failure?.let { throw it }
        return process.exitValue()
    }

    private fun waitFor(process: Process, timeoutMillis: Long): Boolean =
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun IOException?.withAdditionalFailure(additional: IOException): IOException = when (this) {
        null -> additional
        else -> apply { addSuppressed(additional) }
    }
}
