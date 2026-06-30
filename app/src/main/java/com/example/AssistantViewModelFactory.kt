package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.ChatRepository

class AssistantViewModelFactory(
    private val repository: ChatRepository,
    private val hapticManager: HapticManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AssistantViewModel(repository, hapticManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
