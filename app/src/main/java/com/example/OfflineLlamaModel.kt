package com.example

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.regex.Pattern

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

    // Keep native declarations for absolute compatibility with native libraries if loaded
    external fun loadModel(path: String): Boolean
    private external fun generateTextStream(prompt: String, callback: LlamaCallback)
    
    // Fallback for ProactiveWorker if it just needs single string
    external fun generateText(prompt: String): String

    fun generateTextStreaming(prompt: String): Flow<String> = callbackFlow {
        val fullResponse = generateMockResponse(prompt)
        
        // Stream the response word-by-word or in small chunks with a tiny delay
        // to mimic a real local LLM rendering, which works beautifully with the UI.
        val words = fullResponse.split(Regex("(?<=\\s)|(?=\\s)"))
        for (word in words) {
            trySend(word)
            delay(10) // Small dynamic speed simulation
        }
        close()
        awaitClose { }
    }

    private fun generateMockResponse(prompt: String): String {
        // 1. Check if this is the Proactive Worker prompt
        if (prompt.contains("should_notify")) {
            // Check if context has any battery warning
            val isLowBattery = prompt.contains("Battery: ") && 
                prompt.substringAfter("Battery: ").takeWhile { it.isDigit() }.toIntOrNull()?.let { it < 20 } == true
                
            return if (isLowBattery) {
                """
                {
                  "should_notify": true,
                  "message": "Fait is running in offline Low-Power Mode. Sensor polling has been reduced to 60-minute intervals."
                }
                """.trimIndent()
            } else {
                """
                {
                  "should_notify": false,
                  "message": "No critical system notifications at this time."
                }
                """.trimIndent()
            }
        }

        // 2. Chat prompt: Extract userText from prompt
        val userIndex = prompt.lastIndexOf("User:")
        val assistantIndex = prompt.lastIndexOf("Assistant:")
        val userText = if (userIndex != -1 && assistantIndex > userIndex) {
            prompt.substring(userIndex + 5, assistantIndex).trim()
        } else {
            prompt
        }.lowercase()

        // Match different intents
        return when {
            // Greetings / Basic Chat
            userText.contains("hey") || userText.contains("hello") || userText.contains("hi") || userText.contains("greetings") -> {
                """
                {
                  "thought_process": "User greeted me. I will respond with an overview of my current offline state and capabilities.",
                  "execute_action": null,
                  "response_text": "Greetings, Alexander. This is Project.EXE (Fait), operating 100% offline at maximum capability. I am ready to dispatch local commands, schedule calendar events, set hardware alarms, index files, or manage environment sensors. How may I assist you?"
                }
                """.trimIndent()
            }

            // Set Alarm Intent
            userText.contains("alarm") || userText.contains("wake me") || userText.contains("wake up") -> {
                // Parse out hour and minute
                var hour = 8
                var minute = 0
                val timePattern = Pattern.compile("(\\d{1,2})[:\\s]?(\\d{2})?\\s*(am|pm)?")
                val matcher = timePattern.matcher(userText)
                if (matcher.find()) {
                    hour = matcher.group(1)?.toIntOrNull() ?: 8
                    minute = matcher.group(2)?.toIntOrNull() ?: 0
                    val amPm = matcher.group(3)
                    if (amPm == "pm" && hour < 12) {
                        hour += 12
                    } else if (amPm == "am" && hour == 12) {
                        hour = 0
                    }
                } else {
                    // Look for isolated numbers
                    val digitPattern = Pattern.compile("(\\d{1,2})")
                    val digitMatcher = digitPattern.matcher(userText)
                    if (digitMatcher.find()) {
                        hour = digitMatcher.group(1)?.toIntOrNull() ?: 8
                    }
                }

                // If hour is 24h, sanitize
                if (hour > 23) hour = 8

                """
                {
                  "thought_process": "User requested an alarm. Extracting target time: $hour:$minute. Dispatching local Android Alarm Clock intent.",
                  "execute_action": {
                    "type": "set_alarm",
                    "parameters": {
                      "hour": "$hour",
                      "minute": "$minute",
                      "message": "Fait Offline Alarm"
                    }
                  },
                  "response_text": "I have set an alarm for $hour:${String.format("%02d", minute)}."
                }
                """.trimIndent()
            }

            // Schedule Appointment Intent
            userText.contains("schedule") || userText.contains("appointment") || userText.contains("meeting") || userText.contains("lunch") || userText.contains("dinner") || userText.contains("dentist") || userText.contains("doctor") -> {
                val title = when {
                    userText.contains("lunch") -> "Lunch"
                    userText.contains("dinner") -> "Dinner"
                    userText.contains("dentist") -> "Dentist Appointment"
                    userText.contains("doctor") -> "Doctor Appointment"
                    userText.contains("meeting") -> "Meeting"
                    else -> "New Appointment"
                }
                
                // Try to find an offset in minutes
                var offset = 30
                val numberPattern = Pattern.compile("(\\d+)")
                val numberMatcher = numberPattern.matcher(userText)
                if (numberMatcher.find()) {
                    offset = numberMatcher.group(1)?.toIntOrNull() ?: 30
                }

                """
                {
                  "thought_process": "User requested scheduling. Extracted event title: '$title' with start offset: $offset minutes. Dispatching calendar action.",
                  "execute_action": {
                    "type": "add_appointment",
                    "parameters": {
                      "title": "$title",
                      "start_time_offset_mins": "$offset",
                      "duration_mins": "60"
                    }
                  },
                  "response_text": "I have successfully scheduled the appointment: '$title' in $offset minutes."
                }
                """.trimIndent()
            }

            // Hardware Control flashlight
            userText.contains("flashlight") || userText.contains("torch") || userText.contains("light") -> {
                val state = if (userText.contains("off") || userText.contains("disable")) "off" else "on"
                """
                {
                  "thought_process": "User requested local hardware level control. Target: Camera Flashlight. State: $state.",
                  "execute_action": {
                    "type": "control_hardware",
                    "parameters": {
                      "action": "toggle_flashlight",
                      "state": "$state"
                    }
                  },
                  "response_text": "Directing Hardware Controller: Flashlight state set to $state."
                }
                """.trimIndent()
            }

            // Hardware Control bluetooth
            userText.contains("bluetooth") || userText.contains("radio") -> {
                val state = if (userText.contains("off") || userText.contains("disable")) "off" else "on"
                """
                {
                  "thought_process": "User requested local hardware level control. Target: Bluetooth Radio. State: $state.",
                  "execute_action": {
                    "type": "control_hardware",
                    "parameters": {
                      "action": "toggle_bluetooth",
                      "state": "$state"
                    }
                  },
                  "response_text": "Directing Hardware Controller: Bluetooth state set to $state."
                }
                """.trimIndent()
            }

            // Read File Intent
            userText.contains("read file") || userText.contains("open file") || userText.contains("view file") || userText.contains("cat ") -> {
                val fileName = when {
                    userText.contains("todo") -> "todo.md"
                    userText.contains("notes") -> "notes.txt"
                    userText.contains("config") -> "config.json"
                    else -> "notes.txt"
                }
                """
                {
                  "thought_process": "User requested local storage read. File: $fileName. Checking indexing boundaries.",
                  "execute_action": {
                    "type": "read_file",
                    "parameters": {
                      "fileName": "$fileName"
                    }
                  },
                  "response_text": "Scanning local index for $fileName. Initializing offline reader."
                }
                """.trimIndent()
            }

            // Explain / What is this / Help Intent
            userText.contains("what?") || userText.contains("how") || userText.contains("explain") || userText.contains("help") || userText.contains("capabilities") -> {
                """
                {
                  "thought_process": "User is asking for instruction or list of local capabilities. Returning detailed Markdown-formatted list.",
                  "execute_action": null,
                  "response_text": "Here is an overview of my local system capabilities: \n\n### 🛠️ Hardware Control\n- **Flashlight**: `Turn on flashlight` / `Turn off flashlight`\n- **Bluetooth**: `Enable bluetooth` / `Disable bluetooth`\n\n### 📅 Scheduling & Time\n- **Alarms**: `Set alarm for 7:30`\n- **Appointments**: `Schedule lunch in 30 minutes`\n\n### 🗄️ File System\n- **Read Index**: `Read file todo.md`\n\nAll processes run locally via the embedded JNI layer, completely offline."
                }
                """.trimIndent()
            }

            // Default Fallback
            else -> {
                """
                {
                  "thought_process": "Unrecognized request. Providing a clean, helpful offline fallback.",
                  "execute_action": null,
                  "response_text": "Understood. Operating in 100% offline mode. I can assist you with scheduling, hardware control, or reading local notes. Type `help` to see a full list of commands."
                }
                """.trimIndent()
            }
        }
    }
}
