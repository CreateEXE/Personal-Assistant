package com.example

import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CalendarEvent(
    val title: String,
    val startTime: Long,
    val endTime: Long
)

class CalendarHelper(private val context: Context) {
    fun getTodaySchedule(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        val currentTime = timeFormat.format(Date())
        return "[DATETIME CONTEXT]\nDate: $currentDate\nTime: $currentTime"
    }

    fun getEventsBetween(startMillis: Long, endMillis: Long): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )
        
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())
        
        try {
            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )
            
            cursor?.use {
                val titleIndex = it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val startIndex = it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val endIndex = it.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                
                while (it.moveToNext()) {
                    val title = it.getString(titleIndex)
                    val start = it.getLong(startIndex)
                    val end = it.getLong(endIndex)
                    events.add(CalendarEvent(title, start, end))
                }
            }
        } catch (e: SecurityException) {
            // Permission denied
        } catch (e: Exception) {
            // Error reading calendar
        }
        return events
    }
    
    fun getUpcomingEvents(limit: Int = 10): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val end = now + (7L * 24 * 60 * 60 * 1000) // Next 7 days roughly
        return getEventsBetween(now, end).take(limit)
    }
}
