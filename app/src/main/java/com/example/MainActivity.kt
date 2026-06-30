package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
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
    private val hapticManager: HapticManager
) : ViewModel() {
    val messages: StateFlow<List<com.example.data.ChatMessageEntity>> = repository.allMessages.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Idle)
    val status = _status.asStateFlow()

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

    fun initModel(settingsManager: SettingsManager) {
        val modelPath = settingsManager.modelPath.takeIf { it.isNotEmpty() } ?: "/data/local/tmp/model.gguf"
        
        // Load the stored selected persona
        val selectedPersonaName = settingsManager.selectedPersona
        val matchedPersona = Persona.DEFAULT_PERSONAS.find { it.name == selectedPersonaName } ?: Persona.CONCISE_ASSISTANT
        _currentSystemPrompt.value = matchedPersona.systemPrompt

        try {
            llamaModel.loadModel(modelPath)
        } catch (e: UnsatisfiedLinkError) {
            _status.value = ModelStatus.Error("Could not load JNI bridge.")
        }
    }

    fun selectPersona(persona: Persona, settingsManager: SettingsManager) {
        _currentSystemPrompt.value = persona.systemPrompt
        settingsManager.selectedPersona = persona.name
    }

    fun pruneHistory() {
        // Keep only the last 10 exchanges (each exchange is 1 User + 1 Assistant, total of 20 messages)
        while (historyBuffer.size > 20) {
            historyBuffer.removeFirst()
        }
    }

    private fun getSafeContext(history: List<ChatMessage>): List<ChatMessage> {
        val systemPromptMessage = com.example.data.ChatMessageEntity(
            text = "System: ${_currentSystemPrompt.value}",
            isUser = false
        )
        // Truncate to the most recent 6 exchanges (12 total messages)
        val truncatedHistory = history.takeLast(12)
        // Ensure System Prompt is always prepended to start of this list
        return listOf(systemPromptMessage) + truncatedHistory
    }

    fun sendMessage(
        userText: String, 
        calendarContext: String, 
        environmentContext: String, 
        settingsManager: SettingsManager, 
        ttsManager: TextToSpeechManager, 
        context: android.content.Context,
        onAction: (LlamaAction) -> Unit
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
              "execute_action": { "type": "action_type", "parameters": { "param_key": "param_value" } },
              "response_text": "Your natural language response to the user."
            }
            Actions you can trigger:
            - type: "add_appointment" with parameters: "title", "start_time_offset_mins", "duration_mins"
            - type: "set_alarm" with parameters: "hour", "minute", "message"
            - type: "control_hardware" with parameters: "action" (toggle_bluetooth, toggle_flashlight), "state" (on, off)
            - type: "read_file" with parameters: "fileName"
            
            If no action is needed, set "execute_action" to null.
            CRITICAL: Do NOT output confirmation of an action in "response_text" unless the action is confirmed successful by the backend.
        """.trimIndent()
        
        // Truncate sliding window history to most recent 6 exchanges (12 messages) using getSafeContext
        val safeContext = getSafeContext(historyBuffer.toList())
        val systemMsg = safeContext.firstOrNull()?.text ?: "System: $personality"
        val historyMsgList = safeContext.drop(1)

        val historyPrompt = if (historyMsgList.isNotEmpty()) {
            historyMsgList.joinToString("\n") { msg ->
                if (msg.isUser) "User: ${msg.text}" else "Assistant: ${msg.text}"
            } + "\n"
        } else {
            ""
        }

        val prompt = "$systemMsg\n\n$schemaFormat\n\n$sentimentTag\nModify tone and thought_process based on sentiment tag.\n$calendarContext\n$environmentContext\n\n${historyPrompt}User: $userText\nAssistant:"
        
        viewModelScope.launch {
            repository.insertMessage(userText, true)
            
            // Add to sliding history and prune
            historyBuffer.addLast(com.example.data.ChatMessageEntity(text = userText, isUser = true))
            pruneHistory()

            _status.value = ModelStatus.Loading
            _streamingMessage.value = ""
            _isGenerating.value = true

            try {
                withContext(Dispatchers.IO) {
                    _status.value = ModelStatus.Generating
                    var fullResponse = ""
                    llamaModel.generateTextStreaming(prompt).collect { token ->
                        if (token != null) {
                            fullResponse += token
                            _streamingMessage.value = fullResponse
                        }
                    }
                    
                    val llamaResponse = try {
                        if (fullResponse.isNotEmpty()) {
                            val parsed = jsonAdapter.fromJson(fullResponse)
                            if (parsed != null && !parsed.response_text.isNullOrBlank()) {
                                parsed
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    } catch (e: Throwable) {
                        Log.e("AssistantViewModel", "Gibberish or invalid JSON output from LLM: $fullResponse", e)
                        null
                    }

                    if (llamaResponse != null) {
                        val actionsToExecute = mutableListOf<LlamaAction>()
                        llamaResponse.execute_action?.let { actionsToExecute.add(it) }
                        llamaResponse.actions?.let { actions ->
                            actions.filterNotNull().let { actionsToExecute.addAll(it) }
                        }
                        
                        // Execute actions silently in the background BEFORE the UI updates
                        actionsToExecute.forEach { action ->
                            withContext(Dispatchers.Main) {
                                onAction(action)
                            }
                        }

                        // Trigger haptic success feedback if actions were triggered
                        if (actionsToExecute.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                hapticManager.triggerSuccess()
                            }
                        }

                        val responseText = llamaResponse.response_text!!

                        // Save and speak actual response text
                        repository.insertMessage(responseText, false)
                        
                        // Add response to history buffer and prune
                        historyBuffer.addLast(com.example.data.ChatMessageEntity(text = responseText, isUser = false))
                        pruneHistory()

                        ttsManager.speak(responseText)
                    } else {
                        // Safe-Mode Robust JSON Parsing failure (e.g. gibberish or corrupt JSON detected)
                        Log.e("AssistantViewModel", "Gibberish or invalid JSON output from LLM: $fullResponse")
                        llamaModel.clearContext()
                        withContext(Dispatchers.Main) {
                            _isGenerating.value = false
                            _status.value = ModelStatus.Idle
                            _streamingMessage.value = ""
                            Toast.makeText(context, "Assistant is currently resetting context.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Throwable) {
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
                    knowledgeIndexer = knowledgeIndexer,
                    onAction = { action ->
                        when (action.type) {
                            "add_appointment" -> handleAddAppointment(action)
                            "set_alarm" -> handleSetAlarm(action)
                            "control_hardware" -> handleControlHardware(action)
                        }
                    }
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
    
    private fun handleAddAppointment(action: LlamaAction) {
        val title = action.title ?: action.getParam("title") ?: "New Appointment"
        val offset = action.start_time_offset_mins ?: action.getParam("start_time_offset_mins")?.toIntOrNull() ?: 0
        val duration = action.duration_mins ?: action.getParam("duration_mins")?.toIntOrNull() ?: 60
        
        val startMillis = Calendar.getInstance().apply {
            add(Calendar.MINUTE, offset)
        }.timeInMillis
        
        val endMillis = startMillis + (duration * 60 * 1000)
        
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        }
        
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No calendar app found", Toast.LENGTH_SHORT).show()
        }
    }
    private fun handleSetAlarm(action: LlamaAction) {
        val hour = action.hour ?: action.getParam("hour")?.toIntOrNull() ?: 8
        val minute = action.minute ?: action.getParam("minute")?.toIntOrNull() ?: 0
        val message = action.message ?: action.getParam("message") ?: "Assistant Alarm"
        
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        
        try {
            startActivity(intent)
            Toast.makeText(this, "Alarm set for $hour:$minute", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No alarm app found", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun handleControlHardware(action: LlamaAction) {
        val actionType = action.action ?: action.getParam("action") ?: return
        val state = action.state ?: action.getParam("state") ?: "on"
        when (actionType) {
            "toggle_bluetooth" -> hardwareController.toggleBluetooth(state)
            "toggle_flashlight" -> hardwareController.toggleFlashlight(state)
            "take_photo" -> hardwareController.takePhoto()
            else -> Toast.makeText(this, "Unknown hardware action: $actionType", Toast.LENGTH_SHORT).show()
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
    knowledgeIndexer: KnowledgeIndexer,
    onAction: (LlamaAction) -> Unit
) {
    val context = LocalContext.current
    val myApplication = context.applicationContext as MyApplication
    val hapticManager = remember { HapticManager(context) }
    val viewModel: AssistantViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AssistantViewModelFactory(myApplication.chatRepository, hapticManager)
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
                ViewMode.ASSISTANT -> AssistantScreen(viewModel, settingsManager, sttManager, ttsManager, sensorManager, knowledgeIndexer) { action ->
                    if (action.type == "read_file") {
                        val fileName = action.fileName ?: return@AssistantScreen
                        val content = knowledgeIndexer.readFile(fileName) ?: "File not found"
                        val injectedText = "I have read the file $fileName. Its content is:\n$content\nWhat would you like me to do next?"
                        val calendarHelper = CalendarHelper(context)
                        viewModel.sendMessage(injectedText, calendarHelper.getTodaySchedule(), sensorManager.getEnvironmentContext(), settingsManager, ttsManager, context) {}
                    } else {
                        onAction(action)
                    }
                }
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

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
fun AssistantScreen(viewModel: AssistantViewModel, settingsManager: SettingsManager, sttManager: SpeechToTextManager, ttsManager: TextToSpeechManager, sensorManager: EnvironmentSensorManager, knowledgeIndexer: KnowledgeIndexer, onAction: (LlamaAction) -> Unit) {
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
                        viewModel.sendMessage(inputText, todaySchedule, sensorManager.getEnvironmentContext(), settingsManager, ttsManager, context, onAction)
                        inputText = ""
                    }
                }
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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
