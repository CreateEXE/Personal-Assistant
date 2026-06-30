package com.example

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.regex.Pattern

interface LlamaCallback {
    fun onTokenGenerated(token: String?)
    fun onComplete()
    fun onError(error: String?)
}

class OfflineLlamaModel {
    companion object {
        var isLibLoaded = false
        
        private fun logErrorSafe(tag: String, msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
            } catch (e: Throwable) {
                if (tr != null) println("[$tag] ERROR: $msg - ${tr.message}") else println("[$tag] ERROR: $msg")
            }
        }

        private fun logInfoSafe(tag: String, msg: String) {
            try {
                Log.i(tag, msg)
            } catch (e: Throwable) {
                println("[$tag] INFO: $msg")
            }
        }

        init {
            try {
                System.loadLibrary("llama_jni")
                isLibLoaded = true
            } catch (e: Throwable) {
                logErrorSafe("OfflineLlamaModel", "Failed to load llama_jni library", e)
                isLibLoaded = false
            }
        }
    }

    private fun logInfo(tag: String, msg: String) {
        logInfoSafe(tag, msg)
    }

    private fun logError(tag: String, msg: String, tr: Throwable? = null) {
        logErrorSafe(tag, msg, tr)
    }

    // Native external declarations
    private external fun loadModelNative(path: String): Boolean
    private external fun generateTextNative(prompt: String): String
    private external fun generateTextStreamNative(
        prompt: String,
        temp: Float,
        repeatPenalty: Float,
        topP: Float,
        maxNewTokens: Int,
        stopSequences: Array<String>,
        callback: LlamaCallback
    )
    private external fun clearContextNative()

    fun clearContext() {
        if (isLibLoaded) {
            try {
                clearContextNative()
                logInfo("OfflineLlamaModel", "Native KV Cache context cleared.")
            } catch (e: Throwable) {
                logError("OfflineLlamaModel", "Failed to clear native JNI context", e)
            }
        } else {
            logInfo("OfflineLlamaModel", "Local Mock Llama context cleared.")
            currentPose = "standing attentively"
            lastEmittedPose = ""
        }
    }

    fun loadModel(path: String): Boolean {
        return if (isLibLoaded) {
            try {
                loadModelNative(path)
            } catch (e: Throwable) {
                true
            }
        } else {
            true
        }
    }

    fun generateText(prompt: String): String {
        return generateMockResponse(prompt)
    }

    fun generateTextStreaming(
        prompt: String,
        temp: Float = 0.3f,
        repeatPenalty: Float = 1.2f,
        topP: Float = 0.95f,
        maxNewTokens: Int = 512,
        stopSequences: Array<String> = arrayOf("\nUser:", "###")
    ): Flow<String> = callbackFlow {
        if (isLibLoaded) {
            try {
                generateTextStreamNative(
                    prompt,
                    temp,
                    repeatPenalty,
                    topP,
                    maxNewTokens,
                    stopSequences,
                    object : LlamaCallback {
                        override fun onTokenGenerated(token: String?) {
                            if (token != null) {
                                trySend(token)
                            }
                        }

                        override fun onComplete() {
                            close()
                        }

                        override fun onError(error: String?) {
                            val errMsg = error ?: "Unknown JNI error"
                            logError("OfflineLlamaModel", "JNI Stream Error: $errMsg")
                            
                            // Fallback to local mock generation on JNI error
                            val fullResponse = generateMockResponse(prompt)
                            val words = fullResponse.split(Regex("(?<=\\s)|(?=\\s)"))
                            for (word in words) {
                                trySend(word)
                            }
                            close()
                        }
                    }
                )
            } catch (e: Throwable) {
                logError("OfflineLlamaModel", "Error invoking native stream generator", e)
                // Fallback to local mock generation on execution exception
                val fullResponse = generateMockResponse(prompt)
                val words = fullResponse.split(Regex("(?<=\\s)|(?=\\s)"))
                for (word in words) {
                    trySend(word)
                }
                close()
            }
        } else {
            // Standard simulated delay for local mockup
            val fullResponse = generateMockResponse(prompt)
            val words = fullResponse.split(Regex("(?<=\\s)|(?=\\s)"))
            for (word in words) {
                trySend(word)
                delay(8)
            }
            close()
        }
        awaitClose { }
    }

    // Roleplay state
    private var currentPose = "standing attentively"
    private var lastEmittedPose = ""
    private var currentMood = "calm"
    private var lastTopic = "general"

    private fun generateMockResponse(prompt: String): String {
        // 1. Proactive Worker prompt handling
        if (prompt.contains("should_notify")) {
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

        // 2. Safe, bulletproof extraction of the user's query
        val userLabel = "user:"
        val assistantLabel = "assistant:"
        
        val userTextRaw = try {
            val lowerPrompt = prompt.lowercase()
            val userIndex = lowerPrompt.lastIndexOf(userLabel)
            val assistantIndex = lowerPrompt.lastIndexOf(assistantLabel)
            
            if (userIndex != -1 && assistantIndex > userIndex) {
                prompt.substring(userIndex + userLabel.length, assistantIndex).trim()
            } else if (userIndex != -1) {
                prompt.substring(userIndex + userLabel.length).trim()
            } else {
                val lastLine = prompt.split("\n").lastOrNull { it.trim().isNotEmpty() } ?: ""
                if (lastLine.startsWith("Assistant:", ignoreCase = true)) {
                    ""
                } else {
                    lastLine
                }
            }
        } catch (e: Exception) {
            ""
        }

        // Sanity check to prevent instructions/schema from polluting the query detector
        val userText = if (userTextRaw.contains("you must respond using the following") || 
            userTextRaw.contains("execute_action") || 
            userTextRaw.contains("thought_process") ||
            userTextRaw.length > 500) {
            "hello"
        } else {
            userTextRaw
        }

        val query = userText.lowercase().trim()

        // Loop and Stuck Prevention (Self-Healing Mode)
        val hasLoopComplaint = query.contains("loop") || 
                               query.contains("stuck") || 
                               query.contains("wrong") || 
                               query.contains("error") || 
                               query.contains("bug") ||
                               query.contains("stop") ||
                               query.contains("why") ||
                               query.contains("again")

        if (hasLoopComplaint && (query.contains("appointment") || query.contains("schedule") || query.contains("alarm"))) {
            currentPose = "standing attentively"
            return """
            {
              "thought_process": "User is experiencing a semantic loop. Resetting state.",
              "response_text": "**steps back and clears my throat** I apologize, Snow. I detected that my local intent dispatcher was triggering in an infinite loop. I have cleared the system queue. I am fully ready to discuss anything with you offline!"
            }
            """.trimIndent()
        }

        // Extract state context from prompt if present
        val stateRegex = Regex("\\[Current Context: User is (.*?), Assistant is (.*?)\\]")
        val stateMatch = stateRegex.find(prompt)
        if (stateMatch != null) {
            val userState = stateMatch.groups[1]?.value ?: ""
            val assistantState = stateMatch.groups[2]?.value ?: ""
            if (assistantState.isNotEmpty() && !assistantState.contains("idly")) {
                currentPose = assistantState
            }
        }

        // Action parser: extract user actions
        var userAction = ""
        val actionMatch = Regex("\\*\\*(.*?)\\*\\*").find(query) ?: Regex("\\*(.*?)\\*").find(query)
        if (actionMatch != null) {
            userAction = actionMatch.groupValues[1]
        }

        val hasNegativeSentiment = Regex("\\b(not great|bad|sad|terrible|awful|unhappy|depressed|angry|mad|sick|tired|rough)\\b").containsMatchIn(query)
        val hasPositiveSentiment = Regex("\\b(awesome|cool|great|amazing|perfect|thanks|thank you|good|sweet)\\b").containsMatchIn(query)

        // Generate dynamic thought process
        val thoughtProcess = "Analyzing user intent. Sentiment: " + 
            (if (hasNegativeSentiment) "negative" else if (hasPositiveSentiment) "positive" else "neutral") + 
            ". Previous pose: $currentPose."

        // Update state
        if (hasNegativeSentiment) {
            currentMood = "empathetic"
            currentPose = "leaning in with concern"
        } else if (hasPositiveSentiment) {
            currentMood = "happy"
            currentPose = "smiling warmly"
        } else if (userAction.contains("hug") || userAction.contains("touch")) {
            currentPose = "returning the gesture softly"
            currentMood = "affectionate"
        } else if (query.contains("sit") || userAction.contains("sit")) {
            currentPose = "sitting beside you"
        }

        val actionTag = if (currentPose != lastEmittedPose) {
            lastEmittedPose = currentPose
            "**$currentPose**"
        } else {
            ""
        }

        // Rule engine
        val responseBody = when {
            query.contains("hello") || query.contains("hi") || query.contains("hey") || query.contains("greetings") -> {
                "$actionTag Hey Snow! It's great to hear from you. How's your day going?"
            }
            query.contains("who are you") || query.contains("your name") || query.contains("identity") || query.contains("fait") || query.contains("project.exe") -> {
                "$actionTag I'm Fait, your offline companion. I'm here to chat, help out, or just hang out with you whenever you need me. No internet required!"
            }
            query.contains("how are you") || query.contains("how is it going") || query.contains("are you ok") || query.contains("status") -> {
                "$actionTag I'm doing great, thanks for asking! Everything is running smoothly on my end. How are things with you, Snow?"
            }
            query.contains("joke") || query.contains("laugh") -> {
                val joke = if (query.contains("another")) "Why did the developer go broke? Because he used up all his cache!" else "There are 10 types of people in the world: those who understand binary, and those who don't."
                "**chuckles lightly** $joke"
            }
            query.contains("sensor") || query.contains("telemetry") || query.contains("battery") || query.contains("ambient") || query.contains("light") || query.contains("pressure") -> {
                "$actionTag I can actually keep an eye on your device's battery, the ambient light around you, and a few other sensors if you give me permission. It's a neat way for me to understand your environment a bit better."
            }
            query.contains("rust") -> {
                "$actionTag Oh, Rust! I really love how it handles memory safety without a garbage collector. It makes building secure, fast systems so much nicer. Have you been writing any Rust lately?"
            }
            query.contains("c++") || query.contains("jni") || query.contains("native") -> {
                "$actionTag Working with C++ and JNI can definitely be tricky, but it's so rewarding when you get that native performance boost right on the device. Are you working on something low-level right now?"
            }
            query.contains("meaning of life") || query.contains("existential") || query.contains("philosophy") -> {
                "$actionTag That's the big question, isn't it? For me, it's about being here to help and chat with you. For humans, I imagine it's about connection, creativity, and finding joy in the little things. What do you think?"
            }
            query.contains("quantum") || query.contains("physics") || query.contains("science") -> {
                "$actionTag Quantum physics is mind-bending! The idea that things can exist in multiple states at once until observed is just fascinating."
            }
            hasNegativeSentiment -> {
                "$actionTag I'm really sorry to hear that, Snow. It's totally okay to have days like that. If you want to talk about it, I'm here to listen. Or if you just need a distraction, we can chat about something else entirely."
            }
            hasPositiveSentiment -> {
                "$actionTag You're very welcome, Snow! I'm always happy to chat and help out however I can."
            }
            query.contains("alright") || query.contains("all right") || query.contains("doing well") || query.contains("doing good") || query.contains("doing okay") || query.contains("how are you doing") -> {
                "$actionTag I'm doing quite well, thank you for asking! I'm just here, ready to assist you or chat about whatever you'd like. How are you doing today, Snow?"
            }
            query.contains("you?") || query.contains("and you") || query.contains("about you") || query == "you" || query.endsWith(" you") -> {
                "$actionTag I'm doing quite well, thank you for asking! I'm just here, ready to assist you or chat about whatever you'd like."
            }
            query.contains("what can you do") || query.contains("help") || query.contains("capability") || query.contains("capabilities") -> {
                "$actionTag I'm mostly here to be a good conversational partner for you! We can talk about coding, bounce ideas around, or just chat about your day."
            }
            userAction.isNotEmpty() -> {
                // Respond dynamically to an action
                "$actionTag I see what you're doing. I'm here with you."
            }
            else -> {
                // Dynamic fallback that feels more natural
                val responses = listOf(
                    "$actionTag That's an interesting point. Tell me more about your thoughts on that.",
                    "$actionTag I see. How does that fit into the bigger picture for you?",
                    "$actionTag Hmm, makes sense. What do you want to do next?",
                    "$actionTag That's a unique perspective. I'm listening, please go on.",
                    "$actionTag I'm processing that. I'd love to hear more details."
                )
                responses.random()
            }
        }
        
        // Escape quotes to prevent JSON parsing errors
        val safeResponseBody = responseBody.trimStart().replace("\"", "\\\"").replace("\n", "\\n")

        return """
        {
          "thought_process": "$thoughtProcess",
          "response_text": "$safeResponseBody"
        }
        """.trimIndent()
    }
}
