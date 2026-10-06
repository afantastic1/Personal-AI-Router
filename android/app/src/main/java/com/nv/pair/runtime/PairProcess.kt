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
        process.outputStream.close()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroy()
        }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw IOException("PAIR process did not exit after forced termination")
            }
        }
        return process.exitValue()
    }
}
