package com.example

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

interface LlamaCallback {
    fun onTokenGenerated(token: String)
    fun onComplete()
    fun onError(error: String)
}

class OfflineLlamaModel {
    companion object {
        init {
            try {
                System.loadLibrary("llama_jni")
            } catch (e: Throwable) {
                Log.e("OfflineLlamaModel", "Failed to load llama_jni library", e)
            }
        }
    }

    external fun loadModel(path: String): Boolean
    private external fun generateTextStream(prompt: String, callback: LlamaCallback)
    
    // Fallback for ProactiveWorker if it just needs single string
    external fun generateText(prompt: String): String

    fun generateTextStreaming(prompt: String): Flow<String> = callbackFlow {
        val callback = object : LlamaCallback {
            override fun onTokenGenerated(token: String) {
                trySend(token)
            }
            override fun onComplete() {
                close()
            }
            override fun onError(error: String) {
                close(Exception(error))
            }
        }
        generateTextStream(prompt, callback)
        awaitClose { }
    }
}

