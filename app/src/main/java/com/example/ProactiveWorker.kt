package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@JsonClass(generateAdapter = true)
data class ProactiveResponse(
    val should_notify: Boolean,
    val message: String
)

class ProactiveWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i("ProactiveWorker", "Running proactive awareness check...")
        
        val calendarHelper = CalendarHelper(context)
        val upcomingEvents = calendarHelper.getUpcomingEvents(5)
        
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentTimeStr = timeFormat.format(Date())
        
        var contextStr = "Current Time: $currentTimeStr\nUpcoming Events:\n"
        if (upcomingEvents.isEmpty()) {
            contextStr += "None\n"
        } else {
            upcomingEvents.forEach {
                val start = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.startTime))
                contextStr += "- ${it.title} at $start\n"
            }
        }
        
        val settingsManager = SettingsManager(context)
        val prompt = "$contextStr\nSystem Prompt: ${settingsManager.personality}\nIs there anything critical the user needs to know right now? Respond strictly in JSON with `should_notify` (boolean) and `message` (string)."
        
        val llamaModel = OfflineLlamaModel()
        try {
            val modelPath = settingsManager.modelPath.takeIf { it.isNotEmpty() } ?: "/data/local/tmp/model.gguf"
            llamaModel.loadModel(modelPath)
            val responseJson = llamaModel.generateText(prompt)
            
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(ProactiveResponse::class.java)
            
            val proactiveResponse = adapter.fromJson(responseJson)
            
            if (proactiveResponse?.should_notify == true) {
                showProactiveNotification(proactiveResponse.message)
            }
        } catch (e: Exception) {
            Log.e("ProactiveWorker", "Error evaluating proactive prompt", e)
            return Result.failure()
        }

        return Result.success()
    }
    
    private fun showProactiveNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Proactive Assistant"
            val channel = NotificationChannel("proactive_channel", name, NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, "proactive_channel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Assistant Insights")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: SecurityException) {
            // Missing POST_NOTIFICATIONS
        }
    }
}
