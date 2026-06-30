package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.widget.Toast
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

class AssistantViewModel(private val repository: com.example.data.ChatRepository) : ViewModel() {
    val messages: StateFlow<List<com.example.data.ChatMessageEntity>> = repository.allMessages.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Idle)
    val status = _status.asStateFlow()

    private val _streamingMessage = MutableStateFlow("")
    val streamingMessage = _streamingMessage.asStateFlow()

    private val llamaModel = OfflineLlamaModel()
    
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val jsonAdapter = moshi.adapter(LlamaResponse::class.java)

    fun initModel(settingsManager: SettingsManager) {
        val modelPath = settingsManager.modelPath.takeIf { it.isNotEmpty() } ?: "/data/local/tmp/model.gguf"
        try {
            llamaModel.loadModel(modelPath)
        } catch (e: UnsatisfiedLinkError) {
            _status.value = ModelStatus.Error("Could not load JNI bridge.")
        }
    }

    fun sendMessage(userText: String, calendarContext: String, environmentContext: String, settingsManager: SettingsManager, ttsManager: TextToSpeechManager, onAction: (LlamaAction) -> Unit) {
        val personality = settingsManager.personality
        
        // Sentiment Mapping
        val lowerText = userText.lowercase()
        val sentimentTag = when {
            lowerText.contains("stress") || lowerText.contains("anxious") || lowerText.contains("hard") -> "[SENTIMENT_TAG: STRESSED]"
            lowerText.contains("work") || lowerText.contains("done") || lowerText.contains("focus") -> "[SENTIMENT_TAG: PRODUCTIVE]"
            else -> "[SENTIMENT_TAG: CASUAL]"
        }
        
        val prompt = "System: $personality\n$sentimentTag\nModify tone and thought_process based on sentiment tag.\n$calendarContext\n$environmentContext\n\nUser: $userText\nAssistant:"
        
        viewModelScope.launch {
            repository.insertMessage(userText, true)
            _status.value = ModelStatus.Loading
            _streamingMessage.value = ""

            try {
                // First simulate Loading, then generating
                withContext(Dispatchers.IO) {
                    _status.value = ModelStatus.Generating
                    var fullResponse = ""
                    llamaModel.generateTextStreaming(prompt).collect { token ->
                        fullResponse += token
                        _streamingMessage.value = fullResponse
                    }
                    
                    val llamaResponse = try {
                        jsonAdapter.fromJson(fullResponse)
                    } catch (e: Throwable) {
                        null
                    }

                    if (llamaResponse != null) {
                        repository.insertMessage(llamaResponse.response_text, false)
                        ttsManager.speak(llamaResponse.response_text)
                        
                        llamaResponse.actions.forEach { action ->
                            withContext(Dispatchers.Main) {
                                onAction(action)
                            }
                        }
                    } else {
                        repository.insertMessage("Error parsing JSON response: $fullResponse", false)
                    }
                }
            } catch (e: Throwable) {
                _status.value = ModelStatus.Error(e.message ?: "Unknown error")
                repository.insertMessage("Error during generation: ${e.message}", false)
            } finally {
                _streamingMessage.value = ""
                _status.value = ModelStatus.Idle
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
        
        sensorManager.startListening()

        checkPermissions()
        startProactiveEngine()
        
        setContent {
            val settingsManager = remember { SettingsManager(this) }
            val calendarHelper = remember { CalendarHelper(this) }
            MyApplicationTheme {
                MainScreen(
                    settingsManager = settingsManager,
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
        val title = action.title ?: "New Appointment"
        val offset = action.start_time_offset_mins ?: 0
        val duration = action.duration_mins ?: 60
        
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
        val hour = action.hour ?: 8
        val minute = action.minute ?: 0
        val message = action.message ?: "Assistant Alarm"
        
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
        val actionType = action.action ?: return
        val state = action.state ?: "on"
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
    val viewModel: AssistantViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AssistantViewModelFactory(myApplication.chatRepository)
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
                        viewModel.sendMessage(injectedText, calendarHelper.getTodaySchedule(), sensorManager.getEnvironmentContext(), settingsManager, ttsManager) {}
                    } else {
                        onAction(action)
                    }
                }
                ViewMode.TODAY -> CalendarViewScreen(mode = ViewMode.TODAY)
                ViewMode.WEEK -> CalendarViewScreen(mode = ViewMode.WEEK)
                ViewMode.MONTH -> CalendarViewScreen(mode = ViewMode.MONTH)
                ViewMode.SETTINGS -> SettingsScreen(settingsManager, sttManager, ttsManager)
                else -> {}
            }
        }
    }
    }
}

@Composable
fun SettingsScreen(settingsManager: SettingsManager, sttManager: SpeechToTextManager, ttsManager: TextToSpeechManager) {
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
fun AssistantScreen(viewModel: AssistantViewModel, settingsManager: SettingsManager, sttManager: SpeechToTextManager, ttsManager: TextToSpeechManager, sensorManager: EnvironmentSensorManager, knowledgeIndexer: KnowledgeIndexer, onAction: (LlamaAction) -> Unit) {
    val context = LocalContext.current
    val calendarHelper = remember { CalendarHelper(context) }
    var todaySchedule by remember { mutableStateOf("") }
    
    val messages by viewModel.messages.collectAsState()
    val status by viewModel.status.collectAsState()
    val streamingMessage by viewModel.streamingMessage.collectAsState()
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
            if (status == ModelStatus.Loading) {
                item {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            } else if (status == ModelStatus.Generating && streamingMessage.isNotEmpty()) {
                item {
                    ChatBubble(message = com.example.data.ChatMessageEntity(text = streamingMessage, isUser = false))
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
                        viewModel.sendMessage(inputText, todaySchedule, sensorManager.getEnvironmentContext(), settingsManager, ttsManager, onAction)
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
            Text(
                text = message.text,
                color = textColor,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
