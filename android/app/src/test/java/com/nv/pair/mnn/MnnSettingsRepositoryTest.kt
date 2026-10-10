/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
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
            assertEquals(BackendChoice.Auto, repository.readBackendChoice())
            assertEquals(BackendChoice.Auto, repository.backendChoice.first())
            repository.setBackendChoice(BackendChoice.Manual(MnnBackend.OPENCL))
            assertEquals(BackendChoice.Manual(MnnBackend.OPENCL), repository.readBackendChoice())
            assertEquals("manual", firstStore.data.first()[stringPreferencesKey("mnn_backend_choice_mode")])
            assertEquals("opencl", firstStore.data.first()[stringPreferencesKey("mnn_manual_backend")])
        } finally {
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondStore = PreferenceDataStoreFactory.create(
            scope = secondScope,
            produceFile = { preferencesFile },
        )
        try {
            assertEquals(BackendChoice.Manual(MnnBackend.OPENCL), MnnSettingsRepository(secondStore).readBackendChoice())
        } finally {
            secondScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyPreferenceIsReadAsManualChoice() = runBlocking {
        val initial = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("mnn_preferred_backend")] = "cpu"
        }
        val store = InMemoryPreferencesStore(initial)
        val repository = MnnSettingsRepository(store)
        assertEquals(BackendChoice.Manual(MnnBackend.CPU), repository.readBackendChoice())
        val migrated = store.data.first()
        assertEquals("manual", migrated[stringPreferencesKey("mnn_backend_choice_mode")])
        assertEquals("cpu", migrated[stringPreferencesKey("mnn_manual_backend")])
        repository.resetBackendToAuto()
        assertEquals(BackendChoice.Auto, repository.readBackendChoice())
    }

    private class InMemoryPreferencesStore(initial: Preferences) : DataStore<Preferences> {
        private val values = MutableStateFlow(initial)
        override val data: Flow<Preferences> = values

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(values.value)
            values.value = updated
            return updated
        }
    }
}
