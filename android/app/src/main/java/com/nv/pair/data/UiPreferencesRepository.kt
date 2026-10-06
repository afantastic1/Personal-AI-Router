/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pairPreferences by preferencesDataStore(name = "pair-ui-preferences")

class UiPreferencesRepository(context: Context) {
    private val preferences = context.applicationContext.pairPreferences

    val desiredRuntimeRunning: Flow<Boolean> = preferences.data.map { values ->
        values[DesiredRuntimeRunning] ?: false
    }

    suspend fun setDesiredRuntimeRunning(desired: Boolean) {
        preferences.edit { values -> values[DesiredRuntimeRunning] = desired }
    }

    suspend fun readDesiredRuntimeRunning(): Boolean = desiredRuntimeRunning.first()

    private companion object {
        val DesiredRuntimeRunning = booleanPreferencesKey("desired_runtime_running")
    }
}
