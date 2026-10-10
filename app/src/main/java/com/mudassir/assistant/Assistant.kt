package com.mudassir.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.mudassir.assistant.actions.Actions
import com.mudassir.assistant.ai.BrainException
import com.mudassir.assistant.ai.GeminiBrain
import com.mudassir.assistant.ai.LocalBrain
import com.mudassir.assistant.ai.ModelManager
import com.mudassir.assistant.ai.Turn
import com.mudassir.assistant.commands.CommandParser
import com.mudassir.assistant.voice.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** A line in the chat. [source] is "offline", "online" or "phone" for assistant messages. */
data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val source: String? = null,
    val pending: Boolean = false
)

/** Things only the screen can do. */
sealed class UiEvent {
    data class Open(val intent: Intent) : UiEvent()
    /** Ask for [permissions], then call [Assistant.retry] with [text]; if refused, open [fallback]. */
    data class AskPermissions(val permissions: List<String>, val text: String, val fallback: Intent?) : UiEvent()
}

/**
 * The brain of the app: decides whether what you said is a phone command or a question,
 * runs it, and streams the answer into [messages]. Lives as long as the app process, so the
 * chat survives screen rotation and leaving the app.
 */
object Assistant {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ids = AtomicLong(1)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    /** A reminder waiting for its time ("remind me to buy milk" → "when?"). */
    private var pendingReminder: String? = null
    private var job: Job? = null
    private var speaker: Speaker? = null

    fun init(ctx: Context) {
        if (speaker == null) speaker = Speaker(ctx)
    }

    /** Handle something you typed or said. */
    fun send(ctx: Context, input: String, byVoice: Boolean) {
        val text = input.trim()
        if (text.isEmpty() || _busy.value) return
        val app = ctx.applicationContext
        speaker?.stop()
        add(ChatMessage(ids.getAndIncrement(), fromUser = true, text = text))
        job = scope.launch { handle(app, text, speak = shouldSpeak(app, byVoice)) }
    }

    /** Run a command again after its permission was granted (the request is already in the chat). */
    fun retry(ctx: Context, text: String) {
        if (_busy.value) return
        job = scope.launch { handle(ctx.applicationContext, text, speak = false) }
    }

    /** A permission was refused: say so, and open the fallback screen if there is one. */
    fun permissionRefused(fallback: Intent?, reply: String) {
        add(ChatMessage(ids.getAndIncrement(), fromUser = false, text = reply, source = "phone"))
        fallback?.let { _events.tryEmit(UiEvent.Open(it)) }
    }

    fun stop() {
        LocalBrain.stop()
        job?.cancel()
        speaker?.stop()
    }

    fun newChat() {
        stop()
        pendingReminder = null
        _messages.value = emptyList()
        LocalBrain.reset()
    }

    // -------------------------------------------------------------------------------------

    private suspend fun handle(ctx: Context, text: String, speak: Boolean) {
        _busy.value = true
        try {
            val now = LocalDateTime.now()
            val waiting = pendingReminder
            pendingReminder = null
            val command = if (waiting != null && CommandParser.parse(text, now) == null) {
                // Answer to "when should I remind you?"
                CommandParser.parse("remind me to $waiting $text", now)
            } else CommandParser.parse(text, now)

            if (command == null) {
                chat(ctx, history(), speak)
                return
            }

            val result = Actions.run(ctx, command, online = isOnline(ctx))
            val missing = result.permissions.filter {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                _events.emit(UiEvent.AskPermissions(missing, text, result.intent))
                return
            }
            if (result.newChat) {
                newChat()
                return
            }
            add(ChatMessage(ids.getAndIncrement(), fromUser = false, text = result.reply, source = "phone"))
            if (speak) speaker?.say(result.reply)
            result.intent?.let { _events.emit(UiEvent.Open(it)) }
            result.askReminderTime?.let { pendingReminder = it }
            result.summarize?.let { prompt ->
                chat(ctx, listOf(Turn(Turn.SYSTEM, systemPrompt(online = false)), Turn(Turn.USER, prompt)), speak)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            add(ChatMessage(ids.getAndIncrement(), fromUser = false, text = "Something went wrong: ${e.message}", source = "phone"))
        } finally {
            _busy.value = false
        }
    }

    /** Ask a brain and stream its answer into a new chat message. */
    private suspend fun chat(ctx: Context, turns: List<Turn>, speak: Boolean) {
        val s = Settings(ctx)
        val online = s.onlineMode && s.geminiKey.isNotBlank() && isOnline(ctx)
        val hasLocal = ModelManager.file(ctx).exists()
        if (!online && !hasLocal) {
            val why = if (s.onlineMode && s.geminiKey.isNotBlank()) "You're offline, and the offline brain isn't downloaded yet."
            else "I can do phone commands now (alarms, timers, reminders, calls, apps). For questions, download the offline brain " +
                "from the banner above, or turn on online mode in Settings."
            add(ChatMessage(ids.getAndIncrement(), fromUser = false, text = why, source = "phone"))
            return
        }

        val id = ids.getAndIncrement()
        add(ChatMessage(id, fromUser = false, text = "", source = if (online) "online" else "offline", pending = true))
        val stream: (String) -> Unit = { piece ->
            _messages.update { list -> list.map { if (it.id == id) it.copy(text = it.text + piece) else it } }
            if (speak) speaker?.feed(piece)
        }
        val withPrompt = turns.mapIndexed { i, t -> if (i == 0 && t.role == Turn.SYSTEM) Turn(Turn.SYSTEM, systemPrompt(online)) else t }

        try {
            if (online) {
                try {
                    GeminiBrain.reply(s.geminiKey, s.geminiModel, withPrompt, stream)
                } catch (e: BrainException) {
                    if (!hasLocal) throw e
                    // Online failed (limit, key, network): answer offline instead.
                    stream("(${e.message}) ")
                    setSource(id, "offline")
                    LocalBrain.reply(ctx, s.threads, trimForLocal(turns), stream)
                }
            } else {
                LocalBrain.reply(ctx, s.threads, trimForLocal(turns), stream)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stream(if (e is BrainException) e.message.orEmpty() else "Something went wrong: ${e.message}")
        } finally {
            _messages.update { list -> list.map { if (it.id == id) it.copy(pending = false, text = it.text.trim().ifEmpty { "…" }) else it } }
            if (speak) speaker?.finish()
        }
    }

    /** The conversation so far, for the brain. */
    private fun history(): List<Turn> {
        val turns = _messages.value.filter { !it.pending && it.text.isNotBlank() }
            .takeLast(12)
            .map { Turn(if (it.fromUser) Turn.USER else Turn.ASSISTANT, it.text) }
            .dropWhile { it.role != Turn.USER }
        return listOf(Turn(Turn.SYSTEM, "")) + turns
    }

    /** The small offline model does best with a short history. */
    private fun trimForLocal(turns: List<Turn>): List<Turn> {
        val system = Turn(Turn.SYSTEM, systemPrompt(online = false))
        val rest = turns.filter { it.role != Turn.SYSTEM }.takeLast(6).dropWhile { it.role != Turn.USER }
        return listOf(system) + rest
    }

    private fun systemPrompt(online: Boolean): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.US))
        val length = if (online) "Keep answers short and clear (a few sentences unless asked for more)."
        else "Answer in 1 to 3 short sentences."
        return "You are a helpful voice assistant on the user's Android phone in Pakistan. $length " +
            "Use plain text without markdown, because answers may be read aloud. " +
            "If you don't know something or it needs live information, say so briefly. It is now $now."
    }

    private fun setSource(id: Long, source: String) =
        _messages.update { list -> list.map { if (it.id == id) it.copy(source = source) else it } }

    private fun add(m: ChatMessage) = _messages.update { it + m }

    private fun shouldSpeak(ctx: Context, byVoice: Boolean) = when (Settings(ctx).speakReplies) {
        "always" -> true
        "never" -> false
        else -> byVoice
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
