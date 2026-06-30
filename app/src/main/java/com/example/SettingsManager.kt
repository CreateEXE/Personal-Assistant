package com.example

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "assistant_settings")

class SettingsManager(private val context: Context) {

    companion object {
        val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        val PERSONALITY = stringPreferencesKey("personality")
        val MODEL_PATH = stringPreferencesKey("model_path")
        val SENSOR_POLLING = booleanPreferencesKey("sensor_polling")
        val FILE_INDEXING_PATH = stringPreferencesKey("file_indexing_path")
        val LOW_POWER_MODE = booleanPreferencesKey("low_power_mode")
        val SELECTED_PERSONA = stringPreferencesKey("selected_persona")
    }

    val assistantNameFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ASSISTANT_NAME] ?: "Assistant"
    }

    val personalityFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PERSONALITY] ?: "You are an autonomous, offline assistant for Alexander. You are concise, highly capable, and respond strictly in JSON."
    }

    val modelPathFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[MODEL_PATH] ?: ""
    }

    val sensorPollingFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SENSOR_POLLING] ?: true
    }

    val fileIndexingPathFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[FILE_INDEXING_PATH] ?: "/sdcard/assistant_notes"
    }

    val lowPowerModeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LOW_POWER_MODE] ?: false
    }

    val selectedPersonaFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_PERSONA] ?: "Concise Assistant"
    }

    // Synchronous compatibility properties using runBlocking
    var assistantName: String
        get() = runBlocking { assistantNameFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[ASSISTANT_NAME] = value
            }
        }

    var personality: String
        get() = runBlocking { personalityFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[PERSONALITY] = value
            }
        }

    var modelPath: String
        get() = runBlocking { modelPathFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[MODEL_PATH] = value
            }
        }

    var sensorPollingEnabled: Boolean
        get() = runBlocking { sensorPollingFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[SENSOR_POLLING] = value
            }
        }

    var fileIndexingPath: String
        get() = runBlocking { fileIndexingPathFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[FILE_INDEXING_PATH] = value
            }
        }

    var lowPowerModeEnabled: Boolean
        get() = runBlocking { lowPowerModeFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[LOW_POWER_MODE] = value
            }
        }

    var selectedPersona: String
        get() = runBlocking { selectedPersonaFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[SELECTED_PERSONA] = value
            }
        }
}
