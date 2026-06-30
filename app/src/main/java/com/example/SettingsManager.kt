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
        val TEMPERATURE = floatPreferencesKey("temperature")
        val SHOW_DEBUG_OVERLAY = booleanPreferencesKey("show_debug_overlay")
        val TOP_P = floatPreferencesKey("top_p")
        val TOP_K = intPreferencesKey("top_k")
        val REPETITION_PENALTY = floatPreferencesKey("repetition_penalty")
        val RESPONSE_LIMIT = intPreferencesKey("response_limit")
        val CONTEXT_MEMORY_LIMIT = intPreferencesKey("context_memory_limit")
        val CONVERSATION_SUMMARY = stringPreferencesKey("conversation_summary")
    }

    val assistantNameFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ASSISTANT_NAME] ?: "Assistant"
    }

    val personalityFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PERSONALITY] ?: "You are an autonomous, offline assistant for Snow. You are concise, highly capable, and respond strictly in JSON."
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

    val temperatureFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[TEMPERATURE] ?: 0.2f
    }

    val showDebugOverlayFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_DEBUG_OVERLAY] ?: false
    }

    val topPFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[TOP_P] ?: 0.95f
    }

    val topKFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[TOP_K] ?: 40
    }

    val repetitionPenaltyFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[REPETITION_PENALTY] ?: 1.2f
    }

    val responseLimitFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[RESPONSE_LIMIT] ?: 512
    }

    val contextMemoryLimitFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[CONTEXT_MEMORY_LIMIT] ?: 12
    }

    val conversationSummaryFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[CONVERSATION_SUMMARY] ?: ""
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

    var temperature: Float
        get() = runBlocking { temperatureFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[TEMPERATURE] = value
            }
        }

    var showDebugOverlay: Boolean
        get() = runBlocking { showDebugOverlayFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[SHOW_DEBUG_OVERLAY] = value
            }
        }

    var topP: Float
        get() = runBlocking { topPFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[TOP_P] = value
            }
        }

    var topK: Int
        get() = runBlocking { topKFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[TOP_K] = value
            }
        }

    var repetitionPenalty: Float
        get() = runBlocking { repetitionPenaltyFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[REPETITION_PENALTY] = value
            }
        }

    var responseLimit: Int
        get() = runBlocking { responseLimitFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[RESPONSE_LIMIT] = value
            }
        }

    var contextMemoryLimit: Int
        get() = runBlocking { contextMemoryLimitFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[CONTEXT_MEMORY_LIMIT] = value
            }
        }

    var conversationSummary: String
        get() = runBlocking { conversationSummaryFlow.first() }
        set(value) = runBlocking {
            context.dataStore.edit { preferences ->
                preferences[CONVERSATION_SUMMARY] = value
            }
        }
}
