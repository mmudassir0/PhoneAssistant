package com.mudassir.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale

/**
 * Reads replies aloud with Android's text-to-speech (offline voices on most phones).
 * Streaming: whole sentences are spoken as soon as they are complete, so it starts
 * talking while the AI is still writing.
 */
class Speaker(ctx: Context) {
    private var ready = false
    private val pending = StringBuilder()
    private var utterance = 0

    private val queued = AtomicInteger(0)
    private val _speaking = MutableStateFlow(false)
    /** True while a reply is being read aloud (drives the orb animation). */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            runCatching { tts.language = Locale.US }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { _speaking.value = true }
                override fun onDone(id: String?) { if (queued.decrementAndGet() <= 0) { queued.set(0); _speaking.value = false } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { onDone(id) }
                override fun onStop(id: String?, interrupted: Boolean) { queued.set(0); _speaking.value = false }
            })
        }
    }

    /** Add streamed text; speaks every finished sentence. */
    fun feed(text: String) {
        pending.append(text)
        // A sentence is finished once something follows its full stop ("3.5" stays together).
        while (true) {
            val m = SENTENCE_END.find(pending) ?: break
            speak(pending.substring(0, m.range.first + 1))
            pending.delete(0, m.range.last + 1)
        }
    }

    /** Speak whatever is left at the end of a reply. */
    fun finish() {
        if (pending.isNotBlank()) speak(pending.toString())
        pending.clear()
    }

    fun say(text: String) {
        pending.clear()
        speak(text)
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*_#`>]"), "").replace(Regex("\\s+"), " ").trim()
        if (!ready || clean.isEmpty()) return
        queued.incrementAndGet()
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "u${utterance++}")
    }

    fun stop() {
        pending.clear()
        queued.set(0)
        _speaking.value = false
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()

    private companion object {
        val SENTENCE_END = Regex("[.!?\\n]\\s")
    }
}
