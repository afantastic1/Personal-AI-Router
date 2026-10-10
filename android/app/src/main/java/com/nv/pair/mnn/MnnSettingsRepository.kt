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

    val backendChoice: Flow<BackendChoice> = preferences.data.map(::decodeChoice)

    suspend fun setBackendChoice(choice: BackendChoice) {
        preferences.edit { values ->
            when (choice) {
                BackendChoice.Auto -> {
                    values[ChoiceMode] = AUTO
                    values.remove(ManualBackend)
                }
                is BackendChoice.Manual -> {
                    values[ChoiceMode] = MANUAL
                    values[ManualBackend] = choice.backend.preferenceValue
                }
            }
        }
    }

    suspend fun resetBackendToAuto() = setBackendChoice(BackendChoice.Auto)

    suspend fun readBackendChoice(): BackendChoice {
        val values = preferences.data.first()
        if (ChoiceMode !in values && PreferredBackend in values) {
            migrateLegacyChoice()
            return backendChoice.first()
        }
        return decodeChoice(values)
    }

    private suspend fun migrateLegacyChoice() {
        preferences.edit { values ->
            if (ChoiceMode !in values) {
                val legacy = values[PreferredBackend]
                if (legacy != null) {
                    values[ChoiceMode] = MANUAL
                    values[ManualBackend] = MnnBackend.fromPreferenceValue(legacy).preferenceValue
                }
            }
        }
    }

    private fun decodeChoice(values: Preferences): BackendChoice = when (values[ChoiceMode]) {
        AUTO -> BackendChoice.Auto
        MANUAL -> BackendChoice.Manual(MnnBackend.fromPreferenceValue(values[ManualBackend]))
        else -> values[PreferredBackend]?.let { legacy ->
            BackendChoice.Manual(MnnBackend.fromPreferenceValue(legacy))
        } ?: BackendChoice.Auto
    }

    private companion object {
        val PreferredBackend = stringPreferencesKey("mnn_preferred_backend")
        val ChoiceMode = stringPreferencesKey("mnn_backend_choice_mode")
        val ManualBackend = stringPreferencesKey("mnn_manual_backend")
        const val AUTO = "auto"
        const val MANUAL = "manual"
    }
}
