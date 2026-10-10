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

    /** A question we asked ("what time?"): the next answer is read as this prefix + answer. */
    private var pendingPrefix: String? = null
    /** The last phone command, so "turn it off" after "flashlight on" works. */
    private var lastCommand: CommandParser.Type? = null
    private var job: Job? = null
    private var speaker: Speaker? = null
    private val notSpeaking = MutableStateFlow(false)

    /** True while a reply is being read aloud. */
    val speaking: StateFlow<Boolean> get() = speaker?.speaking ?: notSpeaking

    /** The conversation being shown; saved to ChatStore after every reply. */
    var conversationId: String = newId()
        private set

    fun init(ctx: Context) {
        if (speaker == null) speaker = Speaker(ctx)
        ChatStore.init(ctx)
        // Continue the most recent chat if it's from the last 6 hours.
        ChatStore.list().firstOrNull()?.takeIf { System.currentTimeMillis() - it.updated < 6 * 3600_000L }?.let { open(it.id) }
    }

    /** Show a saved conversation (from History). */
    fun open(id: String) {
        stop()
        pendingPrefix = null
        lastCommand = null
        conversationId = id
        val msgs = ChatStore.load(id)
        _messages.value = msgs
        ids.set((msgs.maxOfOrNull { it.id } ?: 0L) + 1)
        LocalBrain.reset()
    }

    private fun newId() = System.currentTimeMillis().toString()

    private fun persist() = ChatStore.save(conversationId, _messages.value)

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

    /** Start a fresh conversation. [save] = keep the current one in history (false after deleting it). */
    fun newChat(save: Boolean = true) {
        stop()
        pendingPrefix = null
        lastCommand = null
        if (save) persist()
        conversationId = newId()
        _messages.value = emptyList()
        LocalBrain.reset()
    }

    // -------------------------------------------------------------------------------------

    private suspend fun handle(ctx: Context, text: String, speak: Boolean) {
        _busy.value = true
        try {
            val now = LocalDateTime.now()
            val waiting = pendingPrefix
            pendingPrefix = null
            val direct = CommandParser.parse(text, now)
            val command = direct
                // Answer to a question we asked, like "what time?"
                ?: waiting?.let { CommandParser.parse(it + text, now)?.takeIf { c -> c.type != CommandParser.Type.ALARM || c.time != null } }
                // "Turn it off" right after "flashlight on"
                ?: CommandParser.followUp(text, lastCommand)
            lastCommand = command?.type

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
            if (result.reply.isNotBlank()) {
                add(ChatMessage(ids.getAndIncrement(), fromUser = false, text = result.reply, source = "phone"))
                if (speak && !result.quiet) speaker?.say(result.reply)
            }
            result.intent?.let { _events.emit(UiEvent.Open(it)) }
            result.answerPrefix?.let { pendingPrefix = it }
            result.aiPrompt?.let { prompt ->
                chat(ctx, listOf(Turn(Turn.SYSTEM, systemPrompt(online = false)), Turn(Turn.USER, prompt)), speak)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            add(ChatMessage(ids.getAndIncrement(), fromUser = false, text = "Something went wrong: ${e.message}", source = "phone"))
        } finally {
            _busy.value = false
            persist()
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
        val all = _messages.value.filter { !it.pending && it.text.isNotBlank() }
        // Leave out phone-command exchanges ("Flashlight on.") so the AI doesn't learn to
        // imitate them and pretend it controls the phone.
        val kept = all.filterIndexed { i, m ->
            if (m.fromUser) all.getOrNull(i + 1)?.source != "phone" else m.source != "phone"
        }
        val turns = kept.takeLast(12)
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
            "If you don't know something or it needs live information, say so briefly. " +
            "IMPORTANT: you cannot control the phone or any app. Never claim you did something (turned on or off, " +
            "set, opened, called, sent). If asked to do something on the phone, say you can't, and suggest a clear " +
            "command like 'turn off the flashlight' or 'set an alarm for 7 am'. It is now $now." + personal()
    }

    /** What the user told us about themselves, so answers can be personal. */
    private fun personal(): String {
        val name = com.mudassir.assistant.actions.Notes.name.value
        val facts = com.mudassir.assistant.actions.Notes.memories.value.takeLast(15).map { it.text }
        if (name == null && facts.isEmpty()) return ""
        return buildString {
            if (name != null) append(" The user's name is $name.")
            if (facts.isNotEmpty()) append(" Things the user asked you to remember (use them when relevant): " + facts.joinToString("; ") + ".")
        }
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
