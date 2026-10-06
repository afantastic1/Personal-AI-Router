/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBinaryRegistryTest {
    @Test
    fun settingsResolvesInstalledNativeLibraryPath() {
        val nativeLibraryDir = Files.createTempDirectory("pair-native").toFile()
        val settingsBinary = nativeLibraryDir.resolve("libnvpair_node_settings.so")
        assertTrue(settingsBinary.createNewFile())

        val registry = NativeBinaryRegistry(nativeLibraryDir)

        assertEquals(settingsBinary, registry.settings())
    }
}
