package com.example.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {
    val allMessages: Flow<List<ChatMessageEntity>> = chatDao.getAllMessages()

    suspend fun insertMessage(text: String, isUser: Boolean) {
        chatDao.insertMessage(ChatMessageEntity(text = text, isUser = isUser))
    }

    suspend fun clearMessages() {
        chatDao.clearMessages()
    }
}
