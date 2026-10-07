/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MnnSettingsRepositoryTest {
    @Test
    fun backendPreferencePersistsAcrossRepositoryRecreation() = runBlocking {
        val root = Files.createTempDirectory("pair-mnn-settings").toFile()
        val preferencesFile = root.resolve("mnn-settings.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstStore = PreferenceDataStoreFactory.create(
            scope = firstScope,
            produceFile = { preferencesFile },
        )

        try {
            val repository = MnnSettingsRepository(firstStore)
            assertEquals(MnnBackend.CPU, repository.readPreferredBackend())
            repository.setPreferredBackend(MnnBackend.OPENCL)
            assertEquals("opencl", firstStore.data.first()[stringPreferencesKey("mnn_preferred_backend")])
        } finally {
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondStore = PreferenceDataStoreFactory.create(
            scope = secondScope,
            produceFile = { preferencesFile },
        )
        try {
            assertEquals(MnnBackend.OPENCL, MnnSettingsRepository(secondStore).readPreferredBackend())
        } finally {
            secondScope.cancel()
            root.deleteRecursively()
        }
    }
}
