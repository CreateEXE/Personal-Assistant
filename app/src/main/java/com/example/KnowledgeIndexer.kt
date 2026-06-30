package com.example

import android.os.Environment
import java.io.File

class KnowledgeIndexer {
    private val notesDir = File(Environment.getExternalStorageDirectory(), "assistant_notes")

    init {
        if (!notesDir.exists()) {
            notesDir.mkdirs()
        }
    }

    fun listFiles(): List<String> {
        val files = mutableListOf<String>()
        if (notesDir.exists() && notesDir.isDirectory) {
            notesDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".txt") || file.name.endsWith(".md"))) {
                    files.add(file.name)
                }
            }
        }
        return files
    }

    fun readFile(fileName: String): String? {
        val file = File(notesDir, fileName)
        return if (file.exists() && file.isFile) {
            file.readText()
        } else {
            null
        }
    }
}
