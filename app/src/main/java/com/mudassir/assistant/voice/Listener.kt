package com.mudassir.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech to text with Android's recogniser, preferring the on-device one (offline, Android 12+).
 * If the on-device model for the language isn't installed, it falls back to the normal
 * recogniser and says so once.
 */
class Listener(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onState: (listening: Boolean) -> Unit,
    private val onError: (String) -> Unit,
    /** Microphone loudness, roughly 0..1, for the orb animation. */
    private val onLevel: (Float) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = true
    var usingOnDevice = false
        private set

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(language: String) {
        stop()
        val useOnDevice = onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        usingOnDevice = useOnDevice
        val r = if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onState(false)
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                onState(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onFinal(text) else onError("I didn't catch that.")
            }

            override fun onError(error: Int) {
                onState(false)
                val nothingHeard = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (useOnDevice && !nothingHeard && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    // The on-device recogniser failed (missing language pack, disconnected (11),
                    // busy…): use the phone's regular recogniser from now on, and retry right away.
                    onDevice = false
                    // Restart after this callback returns: destroying a recogniser inside its own callback can crash.
                    android.os.Handler(android.os.Looper.getMainLooper()).post { start(language) }
                    return
                }
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap the mic and try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech needs internet right now. For offline speech: Settings → Offline speech → download English."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 10 /* too many requests */ -> "The microphone is busy, try again in a moment."
                        11 /* server disconnected */, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT ->
                            "Speech service stopped. Try again; if it keeps happening, update the Google app."
                        12, 13 /* language not supported / unavailable */ ->
                            "Speech isn't available for $language. Change the speech language in Settings."
                        else -> "Speech didn't work (code $error). Try again."
                    }
                )
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        )
    }

    fun stop() {
        recognizer?.run {
            runCatching { cancel() }
            destroy()
        }
        recognizer = null
    }
}
