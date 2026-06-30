package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

class AssistantVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}

class AssistantVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistantVoiceInteractionSession(this)
    }
}

class AssistantVoiceInteractionSession(val ctx: Context) : VoiceInteractionSession(ctx) {
    private var sttManager: SpeechToTextManager? = null

    override fun onCreate() {
        super.onCreate()
        sttManager = SpeechToTextManager(ctx)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sttManager?.startListening()
    }

    override fun onHide() {
        super.onHide()
        sttManager?.stopListening()
    }
}

class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
