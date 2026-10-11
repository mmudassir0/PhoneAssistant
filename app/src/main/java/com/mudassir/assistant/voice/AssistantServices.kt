package com.mudassir.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.mudassir.assistant.ui.MainActivity

/*
 * These three services let you choose Phone Assistant as the phone's default digital assistant
 * (Settings → Apps → Default apps → Digital assistant app). Then holding the power or home
 * button opens it, listening.
 */

/** Registered with the system as the assistant. It has nothing to do itself. */
class AssistantInteractionService : VoiceInteractionService()

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

/** When you hold the button: open the app, listening, and close the system overlay. */
class AssistantSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_LISTEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        hide()
    }
}

/**
 * Android requires an assistant to name a speech recognizer. The app itself uses Google's
 * recognizer (see Listener), so this one only reports that it isn't available.
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
