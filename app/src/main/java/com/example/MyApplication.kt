package com.example

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import com.example.data.AppDatabase
import com.example.data.ChatRepository

class MyApplication : Application(), Configuration.Provider {

    lateinit var database: AppDatabase
        private set
        
    lateinit var chatRepository: ChatRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "assistant_database"
        ).build()
        chatRepository = ChatRepository(database.chatDao())
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
