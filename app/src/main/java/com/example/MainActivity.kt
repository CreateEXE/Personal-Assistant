package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.ui.theme.MyApplicationTheme
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class ModelStatus {
    object Idle : ModelStatus()
    object Loading : ModelStatus()
    object Generating : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}

fun extractResponseText(json: String?): String {
    if (json.isNullOrBlank()) {
        return ""
    }
    val trimmed = json.trim()
    if (!trimmed.startsWith("{")) {
        return trimmed
    }
    
    // Try precise char-by-char extraction of response_text to support nested escaped quotes
    val keyIndex = trimmed.indexOf("\"response_text\"")
    if (keyIndex != -1) {
        val afterKey = trimmed.substring(keyIndex + "\"response_text\"".length)
        val colonIndex = afterKey.indexOf(":")
        if (colonIndex != -1) {
            val afterColon = afterKey.substring(colonIndex + 1).trim()
            if (afterColon.startsWith("\"")) {
                val sb = StringBuilder()
                var escaped = false
                for (i in 1 until afterColon.length) {
                    val char = afterColon[i]
                    if (escaped) {
                        if (char == 'n') sb.append('\n')
                        else if (char == 't') sb.append('\t')
                        else sb.append(char)
                        escaped = false
                    } else if (char == '\\') {
                        escaped = true
                    } else if (char == '"') {
                        break
                    } else {
                        sb.append(char)
                    }
                }
                val extractedStr = sb.toString().trim()
                if (extractedStr.isNotEmpty()) {
                    return extractedStr
                }
            }
        }
    }

    // Pattern to find response_text
    val responsePattern = "\"response_text\"\\s*:\\s*\"([^\"]*)"
    val responseRegex = Regex(responsePattern)
    val responseMatch = responseRegex.find(json)
    if (responseMatch != null) {
        val rawText = responseMatch.groupValues[1]
        return rawText.replace("\\\"", "\"").replace("\\n", "\n")
    }
    
    // Pattern to find thought_process
    val thoughtPattern = "\"thought_process\"\\s*:\\s*\"([^\"]*)"
    val thoughtRegex = Regex(thoughtPattern)
    val thoughtMatch = thoughtRegex.find(json)
    if (thoughtMatch != null) {
        val thought = thoughtMatch.groupValues[1]
        return "Thinking: ${thought.replace("\\\"", "\"").replace("\\n", "\n")}"
    }
    
    return "Analyzing request..."
}

typealias ChatMessage = com.example.data.ChatMessageEntity

class AssistantViewModel(
    private val repository: com.example.data.ChatRepository,
    private val hapticManager: HapticManager,
    private val settingsManager: SettingsManager
) : ViewModel() {
    init {
        viewModelScope.launch {
            val pastMessages = repository.allMessages.first()
            val recent = pastMessages.takeLast(settingsManager.contextMemoryLimit)
            historyBuffer.addAll(recent)
        }
    }

    val messages: StateFlow<List<com.example.data.ChatMessageEntity>> = repository.allMessages.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Idle)
    val status = _status.asStateFlow()

    private val _currentAssistantAction = MutableStateFlow("standing attentively")
    val currentAssistantAction = _currentAssistantAction.asStateFlow()

    private val _currentUserAction = MutableStateFlow("standing idly")
    val currentUserAction = _currentUserAction.asStateFlow()

    private val _streamingMessage = MutableStateFlow("")
    val streamingMessage = _streamingMessage.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _currentSystemPrompt = MutableStateFlow(Persona.CONCISE_ASSISTANT.systemPrompt)
    val currentSystemPrompt = _currentSystemPrompt.asStateFlow()

    // Slide-Window History using ArrayDeque (limiting to last 10 exchanges = 20 messages)
    private val historyBuffer = ArrayDeque<com.example.data.ChatMessageEntity>()

    private val llamaModel = OfflineLlamaModel()
    
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val jsonAdapter = moshi.adapter(LlamaResponse::class.java)

    var lastFailedRawOutput = ""
    var lastUserInput = ""

    var persona_mode by mutableStateOf("Assistant")

    private val _lastThought = MutableStateFlow("")
    val lastThought = _lastThought.asStateFlow()

    private var memoryManager: MemoryManager? = null

    private fun getMemoryManager(context: android.content.Context): MemoryManager {
        if (memoryManager == null) {
            memoryManager = MemoryManager(context.applicationContext)
        }
        return memoryManager!!
    }

    fun getRelevantMemory(context: android.content.Context, userInput: String): String? {
        return getMemoryManager(context).getRelevantMemory(userInput)
    }

    fun sanitizeOutput(output: String): String {
        var trimmed = output.trim()
        if (trimmed.isEmpty()) return ""
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            trimmed = trimmed.substring(firstBrace, lastBrace + 1).trim()
        }
        return trimmed
    }

    fun getContextCount(): Int {
        return historyBuffer.size
    }

    fun initModel(settingsManager: SettingsManager) {
        val modelPath = settingsManager.modelPath.takeIf { it.isNotEmpty() } ?: "/data/local/tmp/model.gguf"
        
        // Load the stored selected persona
        val selectedPersonaName = settingsManager.selectedPersona
        val matchedPersona = Persona.DEFAULT_PERSONAS.find { it.name == selectedPersonaName } ?: Persona.CONCISE_ASSISTANT
        
        // Explicitly set the user's name to "Snow"
        _currentSystemPrompt.value = matchedPersona.systemPrompt.replace("Alexander", "Snow", ignoreCase = true) + " The user's name is Snow."

        try {
            llamaModel.loadModel(modelPath)
        } catch (e: UnsatisfiedLinkError) {
            _status.value = ModelStatus.Error("Could not load JNI bridge.")
        }
    }

    fun selectPersona(persona: Persona, settingsManager: SettingsManager) {
        // Explicitly set the user's name to "Snow"
        _currentSystemPrompt.value = persona.systemPrompt.replace("Alexander", "Snow", ignoreCase = true) + " The user's name is Snow."
        settingsManager.selectedPersona = persona.name
    }

    private fun isGibberish(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        
        // If it has no spaces and is longer than 20 characters, it's likely a single mangled token loop or gibberish
        if (!trimmed.contains(" ") && trimmed.length > 20) {
            return true
        }
        
        // Check for extreme consecutive word repetition (e.g. "the the the the the the the the the")
        val words = trimmed.split("\\s+".toRegex())
        if (words.size > 8) {
            var maxRepeat = 0
            var currentRepeat = 1
            for (i in 1 until words.size) {
                if (words[i].lowercase() == words[i - 1].lowercase()) {
                    currentRepeat++
                } else {
                    if (currentRepeat > maxRepeat) maxRepeat = currentRepeat
                    currentRepeat = 1
                }
            }
            if (currentRepeat > maxRepeat) maxRepeat = currentRepeat
            if (maxRepeat >= 6) { // same word repeated 6+ times consecutively
                return true
            }
        }
        
        return false
    }

    fun pruneHistory(limit: Int, settingsManager: SettingsManager) {
        val droppedMessages = mutableListOf<String>()
        // Keep only up to limit exchanges
        while (historyBuffer.size > limit) {
            val dropped = historyBuffer.removeFirst()
            val role = if (dropped.isUser) "User" else "Assistant"
            droppedMessages.add("$role: ${dropped.text}")
        }
        
        if (droppedMessages.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val currentSummary = settingsManager.conversationSummary
                val textToSummarize = droppedMessages.joinToString("\n")
                val prompt = "System: Summarize the following past conversation context briefly to maintain long-term memory. Previous summary: $currentSummary\nNew context:\n$textToSummarize\nSummary:"
                
                try {
                    val response = llamaModel.generateText(prompt)
                    
                    // Basic fallback if mock model returns its standard JSON
                    val newSummary = if (response.contains("\"response\"")) {
                        if (currentSummary.isEmpty()) {
                            "Discussed: " + textToSummarize.take(100) + "..."
                        } else {
                            currentSummary + "\n" + textToSummarize.take(50) + "..."
                        }
                    } else {
                        response.take(500)
                    }
                    settingsManager.conversationSummary = newSummary.takeLast(1000)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun getSafeContext(history: List<ChatMessage>, limit: Int): List<ChatMessage> {
        val systemPromptMessage = com.example.data.ChatMessageEntity(
            text = "System: ${_currentSystemPrompt.value}",
            isUser = false
        )
        // Truncate to the most recent messages based on the limit
        val truncatedHistory = history.takeLast(limit)
        // Ensure System Prompt is always prepended to start of this list
        return listOf(systemPromptMessage) + truncatedHistory
    }

    fun sendMessage(
        userText: String, 
        calendarContext: String, 
        environmentContext: String, 
        settingsManager: SettingsManager, 
        ttsManager: TextToSpeechManager, 
        context: android.content.Context
    ) {
        // Trigger haptic light tap feedback on send click
        hapticManager.triggerLightTap()

        // Handle specific "reset" command
        if (userText.trim().equals("reset", ignoreCase = true)) {
            historyBuffer.clear()
            llamaModel.clearContext()
            viewModelScope.launch {
                repository.insertMessage(userText, true)
                val responseText = "System context and memory buffer have been reset."
                repository.insertMessage(responseText, false)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "System context has been reset.", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val personality = _currentSystemPrompt.value
        
        // Sentiment Mapping
        val lowerText = userText.lowercase()
        val sentimentTag = when {
            lowerText.contains("stress") || lowerText.contains("anxious") || lowerText.contains("hard") -> "[SENTIMENT_TAG: STRESSED]"
            lowerText.contains("work") || lowerText.contains("done") || lowerText.contains("focus") -> "[SENTIMENT_TAG: PRODUCTIVE]"
            else -> "[SENTIMENT_TAG: CASUAL]"
        }
        
        val schemaFormat = """
            You MUST respond using the following JSON schema format:
            {
              "thought_process": "Your internal chain-of-thought analysis goes here. Keep it concise.",
              "response_text": "Your natural language response to the user."
            }
            CRITICAL: Do NOT output an execute_action field.
        """.trimIndent()
        
        // Truncate sliding window history using getSafeContext
        val safeContext = getSafeContext(historyBuffer.toList(), settingsManager.contextMemoryLimit)
        val relevantMemory = getRelevantMemory(context, userText)
        var systemMsg = if (relevantMemory != null) {
            "System: $personality\n\nKNOWLEDGE: $relevantMemory"
        } else {
            safeContext.firstOrNull()?.text ?: "System: $personality"
        }

        val convSummary = settingsManager.conversationSummary
        if (convSummary.isNotEmpty()) {
            systemMsg += "\n\nPAST CONVERSATION SUMMARY: $convSummary"
        }

        systemMsg += "\n\nCRITICAL DIRECTIVE: You are an autonomous and highly capable system. Maintain context precisely. Do not get sidetracked by irrelevant details. Provide direct, intelligent, and contextually grounded responses."

        // Apply Roleplay Mode tag when persona_mode is "Roleplay"
        if (persona_mode.equals("Roleplay", ignoreCase = true)) {
            systemMsg += "\n\nYou are in Roleplay Mode. Maintain character consistency, be descriptive, and prioritize emotional depth over task completion. Only output action tags like **action** if your physical state or action changes. Do not repeat the same action tag in subsequent messages."
        }

        val actionMatch = Regex("\\*\\*(.*?)\\*\\*|\\*(.*?)\\*").find(userText)
        if (actionMatch != null) {
            val actionText = actionMatch.groups[1]?.value ?: actionMatch.groups[2]?.value
            if (actionText != null) {
                _currentUserAction.value = actionText
            }
        }

        val historyMsgList = safeContext.drop(1)

        val historyPrompt = if (historyMsgList.isNotEmpty()) {
            historyMsgList.joinToString("\n") { msg ->
                if (msg.isUser) "User: ${msg.text}" else "Assistant: ${msg.text}"
            } + "\n"
        } else {
            ""
        }

        val stateContext = "[Current Context: User is ${_currentUserAction.value}, Assistant is ${_currentAssistantAction.value}]"

        val prompt = "$systemMsg\n\n$schemaFormat\n\n$sentimentTag\nModify tone and thought_process based on sentiment tag.\n$calendarContext\n$environmentContext\n$stateContext\n\n${historyPrompt}User: $userText\nAssistant:"
        val temp = settingsManager.temperature
        val repeatPenalty = settingsManager.repetitionPenalty
        val topP = settingsManager.topP
        val responseLimit = settingsManager.responseLimit
        
        viewModelScope.launch {
            repository.insertMessage(userText, true)
            
            // Add to sliding history and prune
            historyBuffer.addLast(com.example.data.ChatMessageEntity(text = userText, isUser = true))
            pruneHistory(settingsManager.contextMemoryLimit, settingsManager)

            _status.value = ModelStatus.Loading
            _streamingMessage.value = ""
            _isGenerating.value = true

            try {
                withContext(Dispatchers.IO) {
                    _status.value = ModelStatus.Generating
                    var fullResponse = ""
                    
                    // Call the sampler tuned generateTextStreaming function
                    llamaModel.generateTextStreaming(
                        prompt = prompt,
                        temp = temp,
                        repeatPenalty = repeatPenalty,
                        topP = topP,
                        maxNewTokens = responseLimit,
                        stopSequences = arrayOf("\nUser:", "###")
                    ).collect { token ->
                        if (token != null) {
                            fullResponse += token
                            _streamingMessage.value = fullResponse
                        }
                    }
                    
                    val sanitized = sanitizeOutput(fullResponse)
                    var finalResponseText: String? = null

                    val llamaResponse = try {
                        if (sanitized.isNotEmpty()) {
                            val parsed = jsonAdapter.fromJson(sanitized)
                            if (parsed != null && !parsed.response_text.isNullOrBlank()) {
                                parsed
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    } catch (e: Throwable) {
                        Log.e("AssistantViewModel", "JSON parsing failed, trying robust extraction", e)
                        null
                    }

                    if (llamaResponse != null) {
                        finalResponseText = llamaResponse.response_text
                        llamaResponse.thought_process?.let {
                            _lastThought.value = it
                        }
                    } else {
                        // Fallback: extract the response text from raw fullResponse
                        val extractedText = extractResponseText(sanitized)
                        if (extractedText.isNotBlank() && extractedText != "Analyzing request...") {
                            finalResponseText = extractedText
                            
                            // Try to extract thought_process manually
                            try {
                                if (sanitized.contains("\"thought_process\"")) {
                                    val thoughtRegex = Regex("\"thought_process\"\\s*:\\s*\"([^\"]+)\"")
                                    val thoughtMatch = thoughtRegex.find(sanitized)
                                    if (thoughtMatch != null) {
                                        _lastThought.value = thoughtMatch.groupValues[1]
                                    }
                                }
                            } catch (thoughtEx: Throwable) {
                                Log.e("AssistantViewModel", "Failed to manually extract thought process", thoughtEx)
                            }
                        }
                    }

                    // Check if the extracted or parsed response is actually gibberish
                    val isGibberishResponse = finalResponseText == null || isGibberish(finalResponseText)

                    if (!isGibberishResponse && finalResponseText != null) {
                        val assistantActionMatch = Regex("\\*\\*(.*?)\\*\\*|\\*(.*?)\\*").find(finalResponseText)
                        if (assistantActionMatch != null) {
                            val actionText = assistantActionMatch.groups[1]?.value ?: assistantActionMatch.groups[2]?.value
                            if (actionText != null) {
                                _currentAssistantAction.value = actionText
                            }
                        }

                        // Save and speak actual response text
                        repository.insertMessage(finalResponseText, false)
                        
                        // Add response to history buffer and prune
                        historyBuffer.addLast(com.example.data.ChatMessageEntity(text = finalResponseText, isUser = false))
                        pruneHistory(settingsManager.contextMemoryLimit, settingsManager)

                        ttsManager.speak(finalResponseText)
                    } else {
                        // Safe-Mode Robust JSON Parsing failure (e.g. gibberish or corrupt JSON detected)
                        Log.e("AssistantViewModel", "Gibberish or invalid JSON output from LLM: $fullResponse")
                        lastFailedRawOutput = fullResponse
                        lastUserInput = userText
                        withContext(Dispatchers.Main) {
                            _isGenerating.value = false
                            _status.value = ModelStatus.Error("Failed to parse response.")
                            _streamingMessage.value = ""
                        }
                    }
                }
            } catch (e: Throwable) {
                lastFailedRawOutput = ""
                lastUserInput = userText
                _status.value = ModelStatus.Error(e.message ?: "Unknown error")
                repository.insertMessage("Error during generation: ${e.message}", false)
                
                // Trigger heavy buzz error feedback
                withContext(Dispatchers.Main) {
                    hapticManager.triggerError()
                }
            } finally {
                _streamingMessage.value = ""
                _status.value = ModelStatus.Idle
                _isGenerating.value = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    
    private lateinit var sttManager: SpeechToTextManager
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var sensorManager: EnvironmentSensorManager
    private lateinit var knowledgeIndexer: KnowledgeIndexer
    private lateinit var hardwareController: HardwareController

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        sttManager = SpeechToTextManager(this)
        ttsManager = TextToSpeechManager(this)
        sensorManager = EnvironmentSensorManager(this)
        knowledgeIndexer = KnowledgeIndexer()
        hardwareController = HardwareController(this)
        
        val settingsManager = SettingsManager(this)
        if (settingsManager.sensorPollingEnabled) {
            sensorManager.startListening()
        }

        checkPermissions()
        startProactiveEngine()
        
        setContent {
            val liveSettingsManager = remember { SettingsManager(this) }
            val calendarHelper = remember { CalendarHelper(this) }
            MyApplicationTheme {
                MainScreen(
                    settingsManager = liveSettingsManager,
                    sttManager = sttManager,
                    ttsManager = ttsManager,
                    sensorManager = sensorManager,
                    knowledgeIndexer = knowledgeIndexer
                )
            }
        }
    }
    
    private fun startProactiveEngine() {
        val proactiveWorkRequest = PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ProactiveAwareness",
            ExistingPeriodicWorkPolicy.KEEP,
            proactiveWorkRequest
        )
    }
    
    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missingPerms = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPerms.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPerms.toTypedArray())
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        sensorManager.stopListening()
        sttManager.destroy()
        ttsManager.shutdown()
    }
}

enum class ViewMode {
    SPLASH, ASSISTANT, TODAY, WEEK, MONTH, SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsManager: SettingsManager,
    sttManager: SpeechToTextManager,
    ttsManager: TextToSpeechManager,
    sensorManager: EnvironmentSensorManager,
    knowledgeIndexer: KnowledgeIndexer
) {
    val context = LocalContext.current
    val myApplication = context.applicationContext as MyApplication
    val hapticManager = remember { HapticManager(context) }
    val viewModel: AssistantViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AssistantViewModelFactory(myApplication.chatRepository, hapticManager, settingsManager)
    )
    var currentView by remember { mutableStateOf(ViewMode.SPLASH) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        currentView = ViewMode.ASSISTANT
    }
    
    if (currentView == ViewMode.SPLASH) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "App Logo",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = settingsManager.assistantName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
    } else {
        Scaffold(
            topBar = {
                val status by viewModel.status.collectAsState()
                val (statusText, statusColor) = when (status) {
                    is ModelStatus.Idle -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
                    is ModelStatus.Loading -> "Loading" to MaterialTheme.colorScheme.primary
                    is ModelStatus.Generating -> "Generating" to MaterialTheme.colorScheme.secondary
                    is ModelStatus.Error -> "Error" to MaterialTheme.colorScheme.error
                }
                
                TopAppBar(
                    title = { Text(settingsManager.assistantName) },
                    actions = {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = statusColor.copy(alpha = 0.1f),
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Text(
                                text = statusText,
                                color = statusColor,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentView == ViewMode.ASSISTANT,
                    onClick = { currentView = ViewMode.ASSISTANT },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Assistant") },
                    label = { Text("Chat") }
                )
                NavigationBarItem(
                    selected = currentView == ViewMode.TODAY,
                    onClick = { currentView = ViewMode.TODAY },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = "Today") },
                    label = { Text("Today") }
                )
                NavigationBarItem(
                    selected = currentView == ViewMode.WEEK,
                    onClick = { currentView = ViewMode.WEEK },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = "Week") },
                    label = { Text("Week") }
                )
                NavigationBarItem(
                    selected = currentView == ViewMode.MONTH,
                    onClick = { currentView = ViewMode.MONTH },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = "Month") },
                    label = { Text("Month") }
                )
                NavigationBarItem(
                    selected = currentView == ViewMode.SETTINGS,
                    onClick = { currentView = ViewMode.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (currentView) {
                ViewMode.ASSISTANT -> AssistantScreen(viewModel, settingsManager, sttManager, ttsManager, sensorManager, knowledgeIndexer)
                ViewMode.TODAY -> CalendarViewScreen(mode = ViewMode.TODAY)
                ViewMode.WEEK -> CalendarViewScreen(mode = ViewMode.WEEK)
                ViewMode.MONTH -> CalendarViewScreen(mode = ViewMode.MONTH)
                ViewMode.SETTINGS -> SettingsScreen(settingsManager, sttManager, ttsManager, sensorManager, viewModel)
                else -> {}
            }
        }
    }
    }
}

@Composable
fun SettingsScreen(
    settingsManager: SettingsManager, 
    sttManager: SpeechToTextManager, 
    ttsManager: TextToSpeechManager, 
    sensorManager: EnvironmentSensorManager,
    viewModel: AssistantViewModel
) {
    var name by remember { mutableStateOf(settingsManager.assistantName) }
    var personality by remember { mutableStateOf(settingsManager.personality) }
    var modelPath by remember { mutableStateOf(settingsManager.modelPath) }
    var isCopying by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            isCopying = true
            scope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val targetFile = java.io.File(context.filesDir, "model.gguf")
                    val outputStream = java.io.FileOutputStream(targetFile)
                    inputStream?.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }
                    val newPath = targetFile.absolutePath
                    withContext(Dispatchers.Main) {
                        modelPath = newPath
                        settingsManager.modelPath = newPath
                        isCopying = false
                        Toast.makeText(context, "Model updated successfully. Please restart app.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isCopying = false
                        Toast.makeText(context, "Failed to load model: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("General") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Engine Samplers") }
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)
        ) {
            if (selectedTab == 0) {
                item { Text("Preferences", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                item { Spacer(modifier = Modifier.height(24.dp)) }
                
                item { 
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; settingsManager.assistantName = it },
                        label = { Text("Assistant Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                item {
                    Text("Companion Persona", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    var selectedPersonaName by remember { mutableStateOf(settingsManager.selectedPersona) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Persona.DEFAULT_PERSONAS.forEach { persona ->
                            val isSelected = persona.name == selectedPersonaName
                            OutlinedCard(
                                onClick = {
                                    selectedPersonaName = persona.name
                                    viewModel.selectPersona(persona, settingsManager)
                                    personality = persona.systemPrompt
                                    Toast.makeText(context, "${persona.name} persona applied instantly!", Toast.LENGTH_SHORT).show()
                                },
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = persona.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = persona.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
                
                item {
                    OutlinedTextField(
                        value = personality,
                        onValueChange = { personality = it; settingsManager.personality = it },
                        label = { Text("System Personality Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8
                    )
                }
                item { Spacer(modifier = Modifier.height(32.dp)) }

                item {
                    Text("AI Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
                item {
                    Text("Current Model Path:\n${modelPath.ifEmpty { "None (Using default stub)" }}", style = MaterialTheme.typography.bodySmall)
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
                item {
                    Button(
                        onClick = { modelPickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCopying
                    ) {
                        Text(if (isCopying) "Copying model..." else "Load Custom .gguf Model")
                    }
                }
                if (isCopying) {
                    item { 
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) 
                    }
                }
                
                item { Spacer(modifier = Modifier.height(32.dp)) }

                item {
                    Text("Autonomous Systems", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                // Sensor Polling Toggle
                item {
                    var sensorPolling by remember { mutableStateOf(settingsManager.sensorPollingEnabled) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sensor Polling", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Enable background environmental sensor updates", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = sensorPolling,
                            onCheckedChange = { 
                                sensorPolling = it
                                settingsManager.sensorPollingEnabled = it
                                if (it) {
                                    sensorManager.startListening()
                                } else {
                                    sensorManager.stopListening()
                                }
                            }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                // Low-Power Mode Toggle
                item {
                    var lowPower by remember { mutableStateOf(settingsManager.lowPowerModeEnabled) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Low-Power Mode", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Throttle sensor polling rate to 60 minutes if battery < 20%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = lowPower,
                            onCheckedChange = { 
                                lowPower = it
                                settingsManager.lowPowerModeEnabled = it
                            }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                // File Indexing Path
                item {
                    var filePath by remember { mutableStateOf(settingsManager.fileIndexingPath) }
                    OutlinedTextField(
                        value = filePath,
                        onValueChange = { 
                            filePath = it
                            settingsManager.fileIndexingPath = it
                        },
                        label = { Text("File Indexing Search Path") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("Absolute directory path for local companion knowledge files.") }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                // Debug Overlay Toggle
                item {
                    var showOverlay by remember { mutableStateOf(settingsManager.showDebugOverlay) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Developer Debug Overlay", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Show sliding window token history count, model thought process, and active sampler parameters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = showOverlay,
                            onCheckedChange = { 
                                showOverlay = it
                                settingsManager.showDebugOverlay = it
                            }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(32.dp)) }
                
                item {
                    Text("Engine Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
                item {
                    Text("Text-to-Speech (TTS): ${if (ttsManager.isInitialized) "Online" else "Offline"}")
                }
                item {
                    Text("Speech-to-Text (STT): ${if (sttManager.isInitialized) "Online" else "Offline"}")
                }
                
                item { Spacer(modifier = Modifier.height(32.dp)) }
                
                item {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open OS Voice settings", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Set as Default System Assistant")
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
                item {
                    Text(
                        text = "Requires navigating to Default digital assistant app in Android settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // TAB 1: Engine Sampler Settings
                item {
                    Text("Engine Sampler Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Fine-tune the mathematical parameters of the offline LLaMA reasoning engine to control how vocabulary, diversity, and creativity are processed during generation.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // 1. Temperature Slider
                item {
                    var tempVal by remember { mutableStateOf(settingsManager.temperature) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Model Temperature (Creativity)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("Higher values allow more creative, diverse, and expressive responses, but increase the chance of structural drift. Lower values (e.g., 0.2) ensure highly predictable, structured replies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = String.format(java.util.Locale.US, "%.2f", tempVal),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = tempVal,
                            onValueChange = { 
                                tempVal = it
                                settingsManager.temperature = it
                            },
                            valueRange = 0.1f..1.5f,
                            steps = 13,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // 2. Top P Slider
                item {
                    var topPVal by remember { mutableStateOf(settingsManager.topP) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Top P (Nucleus Sampling)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("Filters word choices to only the most likely pool whose combined probability is within this limit. Lower values (e.g., 0.5) keep choices very standard and safe; higher values (e.g., 0.95) allow rare, more contextually descriptive words.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = String.format(java.util.Locale.US, "%.2f", topPVal),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = topPVal,
                            onValueChange = { 
                                topPVal = it
                                settingsManager.topP = it
                            },
                            valueRange = 0.1f..1.0f,
                            steps = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // 3. Top K Slider
                item {
                    var topKVal by remember { mutableStateOf(settingsManager.topK) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Top K (Vocabulary Limit)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("Restricts the active word pool to only the K most probable words at each step. Low values (e.g., 10) keep replies predictable and safe, while higher values (e.g., 80) unlock a vast, multi-layered vocabulary.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = topKVal.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = topKVal.toFloat(),
                            onValueChange = { 
                                val intVal = (it + 0.5f).toInt()
                                topKVal = intVal
                                settingsManager.topK = intVal
                            },
                            valueRange = 1f..100f,
                            steps = 98,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // 4. Repetition Penalty Slider
                item {
                    var penaltyVal by remember { mutableStateOf(settingsManager.repetitionPenalty) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Repetition Penalty", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("Discourages the companion from repeating identical words or phrases too often. A penalty of 1.0 means no prevention; values between 1.15 and 1.3 keep discussions diverse and prevent sentence loops.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = String.format(java.util.Locale.US, "%.2f", penaltyVal),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = penaltyVal,
                            onValueChange = { 
                                penaltyVal = it
                                settingsManager.repetitionPenalty = it
                            },
                            valueRange = 1.0f..2.0f,
                            steps = 20,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // 5. Response Limit Slider
                item {
                    var limitVal by remember { mutableStateOf(settingsManager.responseLimit) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Response Limit (Max Tokens)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("Sets the maximum length of each response in tokens (words/word parts). Lower values keep replies concise and save device battery; higher values allow full-length explanatory outputs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = "$limitVal tokens",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = limitVal.toFloat(),
                            onValueChange = { 
                                val intVal = ((it + 0.5f).toInt() / 16) * 16
                                val boundVal = intVal.coerceIn(64, 1024)
                                limitVal = boundVal
                                settingsManager.responseLimit = boundVal
                            },
                            valueRange = 64f..1024f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // 6. Context Memory Limit Slider
                item {
                    var contextLimitVal by remember { mutableStateOf(settingsManager.contextMemoryLimit) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Context Memory Limit", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("Sets the maximum number of past messages to send with each prompt. Higher values allow longer conversations and better contextual understanding, but increase processing time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = "$contextLimitVal messages",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = contextLimitVal.toFloat(),
                            onValueChange = { 
                                val intVal = (it + 0.5f).toInt()
                                contextLimitVal = intVal
                                settingsManager.contextMemoryLimit = intVal
                            },
                            valueRange = 4f..100f,
                            steps = 95,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha1"
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha2"
    )
    val alpha3 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha3"
    )

    Row(
        modifier = Modifier
            .padding(8.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Fait is typing",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(end = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha1), shape = RoundedCornerShape(50)))
            Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha2), shape = RoundedCornerShape(50)))
            Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha3), shape = RoundedCornerShape(50)))
        }
    }
}

@Composable
fun AssistantScreen(viewModel: AssistantViewModel, settingsManager: SettingsManager, sttManager: SpeechToTextManager, ttsManager: TextToSpeechManager, sensorManager: EnvironmentSensorManager, knowledgeIndexer: KnowledgeIndexer) {
    val context = LocalContext.current
    val calendarHelper = remember { CalendarHelper(context) }
    var todaySchedule by remember { mutableStateOf("") }
    
    val messages by viewModel.messages.collectAsState()
    val status by viewModel.status.collectAsState()
    val streamingMessage by viewModel.streamingMessage.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isListening by sttManager.isListening.collectAsState()
    val spokenText by sttManager.spokenText.collectAsState()
    
    val ambientLight by sensorManager.ambientLight.collectAsState()
    val accelerometer by sensorManager.accelerometerData.collectAsState()
    
    var inputText by remember { mutableStateOf("") }

    val showDebugOverlay = settingsManager.showDebugOverlay
    val lastThought by viewModel.lastThought.collectAsState()
    val contextCount = viewModel.getContextCount()
    val currentTemp = settingsManager.temperature

    LaunchedEffect(spokenText) {
        if (spokenText.isNotBlank()) {
            inputText = spokenText
            sttManager.clearText()
        }
    }
    
    LaunchedEffect(Unit) {
        viewModel.initModel(settingsManager)
        todaySchedule = calendarHelper.getTodaySchedule()
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Chat Area
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Device Context (Injected)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(todaySchedule, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(messages) { message ->
                    ChatBubble(message = message)
                }
                if (status == ModelStatus.Loading || (isGenerating && streamingMessage.isEmpty())) {
                    item {
                        TypingIndicator()
                    }
                } else if (status == ModelStatus.Generating && streamingMessage.isNotEmpty()) {
                    item {
                        val parsedStreaming = remember(streamingMessage) { extractResponseText(streamingMessage) }
                        if (parsedStreaming.isNotEmpty()) {
                            ChatBubble(message = com.example.data.ChatMessageEntity(text = parsedStreaming, isUser = false))
                        }
                    }
                }
                if (status is ModelStatus.Error) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("System Error / Parse Failure", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text((status as ModelStatus.Error).message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val lastInput = viewModel.lastUserInput
                                        if (lastInput.isNotBlank()) {
                                            viewModel.sendMessage(
                                                lastInput,
                                                todaySchedule,
                                                sensorManager.getEnvironmentContext(),
                                                settingsManager,
                                                ttsManager,
                                                context
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry")
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Input Area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (isListening) sttManager.stopListening() else sttManager.startListening()
                }) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Microphone",
                        tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (isListening) "Listening..." else "Ask your assistant...") },
                    maxLines = 3
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(inputText, todaySchedule, sensorManager.getEnvironmentContext(), settingsManager, ttsManager, context)
                            inputText = ""
                        }
                    }
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }

        if (showDebugOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 16.dp)
                    .width(220.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "DEV DEBUG OVERLAY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Context Count: $contextCount",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Text(
                        text = "Temp setting: ${String.format(java.util.Locale.US, "%.1f", currentTemp)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Last Thought:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = lastThought.ifBlank { "None" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun CalendarViewScreen(mode: ViewMode) {
    val context = LocalContext.current
    val calendarHelper = remember { CalendarHelper(context) }
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    
    LaunchedEffect(mode) {
        val now = Calendar.getInstance()
        val startMillis: Long
        val endMillis: Long
        
        when (mode) {
            ViewMode.TODAY -> {
                now.set(Calendar.HOUR_OF_DAY, 0)
                now.set(Calendar.MINUTE, 0)
                startMillis = now.timeInMillis
                now.set(Calendar.HOUR_OF_DAY, 23)
                now.set(Calendar.MINUTE, 59)
                endMillis = now.timeInMillis
            }
            ViewMode.WEEK -> {
                now.set(Calendar.DAY_OF_WEEK, now.firstDayOfWeek)
                now.set(Calendar.HOUR_OF_DAY, 0)
                startMillis = now.timeInMillis
                now.add(Calendar.DAY_OF_YEAR, 7)
                endMillis = now.timeInMillis
            }
            ViewMode.MONTH -> {
                now.set(Calendar.DAY_OF_MONTH, 1)
                now.set(Calendar.HOUR_OF_DAY, 0)
                startMillis = now.timeInMillis
                now.add(Calendar.MONTH, 1)
                endMillis = now.timeInMillis
            }
            else -> {
                startMillis = 0L
                endMillis = 0L
            }
        }
        
        events = calendarHelper.getEventsBetween(startMillis, endMillis)
    }
    
    var selectedEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    
    if (selectedEvent != null) {
        ReminderDialog(
            event = selectedEvent!!,
            onDismiss = { selectedEvent = null },
            onSchedule = { minutes ->
                ReminderManager(context).scheduleReminder(selectedEvent!!, minutes)
                Toast.makeText(context, "Reminder set for $minutes mins before", Toast.LENGTH_SHORT).show()
                selectedEvent = null
            }
        )
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = when (mode) {
                ViewMode.TODAY -> "Today's Agenda"
                ViewMode.WEEK -> "This Week's Agenda"
                ViewMode.MONTH -> "This Month's Events"
                else -> ""
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        if (events.isEmpty()) {
            Text("No events found.", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events) { event ->
                    EventCard(event = event, onClick = { selectedEvent = event })
                }
            }
        }
    }
}

@Composable
fun EventCard(event: CalendarEvent, onClick: () -> Unit) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("${dateFormat.format(Date(event.startTime))} - ${dateFormat.format(Date(event.endTime))}", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Tap to set a reminder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ReminderDialog(event: CalendarEvent, onDismiss: () -> Unit, onSchedule: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Reminder") },
        text = { Text("When would you like to be reminded about '${event.title}'?") },
        confirmButton = {
            Column {
                TextButton(onClick = { onSchedule(15) }) { Text("15 minutes before") }
                TextButton(onClick = { onSchedule(60) }) { Text("1 hour before") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ChatBubble(message: com.example.data.ChatMessageEntity) {
    val backgroundColor = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (message.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = backgroundColor,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            if (message.isUser) {
                Text(
                    text = message.text,
                    color = textColor,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                com.example.ui.MarkdownText(
                    text = message.text,
                    color = textColor,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
