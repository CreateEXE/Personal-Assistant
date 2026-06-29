package com.example

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)

    var assistantName: String
        get() = prefs.getString("assistant_name", "Assistant") ?: "Assistant"
        set(value) = prefs.edit().putString("assistant_name", value).apply()

    var personality: String
        get() = prefs.getString("personality", "You are an autonomous, offline assistant for Alexander. You are concise, highly capable, and respond strictly in JSON.") ?: "You are an autonomous, offline assistant for Alexander. You are concise, highly capable, and respond strictly in JSON."
        set(value) = prefs.edit().putString("personality", value).apply()
        
    var modelPath: String
        get() = prefs.getString("model_path", "") ?: ""
        set(value) = prefs.edit().putString("model_path", value).apply()
}
