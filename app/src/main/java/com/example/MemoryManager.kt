package com.example

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

data class MemoryItem(
    val keywords: List<String>,
    val fact: String
)

data class MemoryWrapper(
    val memories: List<MemoryItem>
)

class MemoryManager(private val context: Context) {
    private val memoryFile = File(context.filesDir, "memory.json")
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(MemoryWrapper::class.java)

    init {
        // Initialize with default facts if the file doesn't exist or is empty
        if (!memoryFile.exists() || memoryFile.length() == 0L) {
            val defaultMemories = listOf(
                MemoryItem(
                    keywords = listOf("cats", "pets", "brutal", "derpa", "hey"),
                    fact = "The user has three cats named Brutal, Derpa, and Hey."
                ),
                MemoryItem(
                    keywords = listOf("snow", "developer", "creator"),
                    fact = "The user's name is Snow, and he is a low-level systems and machine learning systems engineer."
                )
            )
            writeMemories(defaultMemories)
        } else {
            // Migrate existing memories from Alexander to Snow
            try {
                val rawText = memoryFile.readText()
                if (rawText.contains("Alexander", ignoreCase = true)) {
                    val migratedText = rawText
                        .replace("Alexander", "Snow")
                        .replace("alexander", "snow")
                    memoryFile.writeText(migratedText)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    fun getAllMemories(): List<MemoryItem> {
        if (!memoryFile.exists()) return emptyList()
        return try {
            val json = memoryFile.readText()
            val wrapper = adapter.fromJson(json)
            wrapper?.memories ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    fun writeMemories(memories: List<MemoryItem>) {
        try {
            val wrapper = MemoryWrapper(memories)
            val json = adapter.toJson(wrapper)
            memoryFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun saveMemory(keywords: List<String>, fact: String) {
        val current = getAllMemories().toMutableList()
        current.add(MemoryItem(keywords = keywords.map { it.lowercase().trim() }, fact = fact))
        writeMemories(current)
    }

    @Synchronized
    fun deleteMemory(index: Int) {
        val current = getAllMemories().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            writeMemories(current)
        }
    }

    fun getRelevantMemory(userInput: String): String? {
        val lowerInput = userInput.lowercase()
        val memories = getAllMemories()
        val matchedFacts = mutableListOf<String>()

        for (memory in memories) {
            // Check if any keyword matches the user input
            val isMatch = memory.keywords.any { keyword ->
                val kw = keyword.lowercase().trim()
                kw.isNotEmpty() && (lowerInput.contains(kw) || kw.contains(lowerInput))
            }
            if (isMatch) {
                matchedFacts.add(memory.fact)
            }
        }

        return if (matchedFacts.isNotEmpty()) {
            matchedFacts.joinToString(" ")
        } else {
            null
        }
    }
}
