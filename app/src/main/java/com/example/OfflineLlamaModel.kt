package com.example

import android.util.Log

class OfflineLlamaModel {
    companion object {
        init {
            try {
                System.loadLibrary("llama_jni")
            } catch (e: Exception) {
                Log.e("OfflineLlamaModel", "Failed to load llama_jni library", e)
            }
        }
    }

    external fun loadModel(path: String): Boolean
    external fun generateText(prompt: String): String
}
