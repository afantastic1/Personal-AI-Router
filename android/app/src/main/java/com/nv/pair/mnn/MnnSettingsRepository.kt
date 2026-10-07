/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.mnnPreferences by preferencesDataStore(name = "pair-mnn-settings")

class MnnSettingsRepository internal constructor(
    private val preferences: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.mnnPreferences)

    val preferredBackend: Flow<MnnBackend> = preferences.data.map { values ->
        MnnBackend.fromPreferenceValue(values[PreferredBackend])
    }

    suspend fun setPreferredBackend(backend: MnnBackend) {
        preferences.edit { values -> values[PreferredBackend] = backend.preferenceValue }
    }

    suspend fun readPreferredBackend(): MnnBackend = preferredBackend.first()

    private companion object {
        val PreferredBackend = stringPreferencesKey("mnn_preferred_backend")
    }
}
