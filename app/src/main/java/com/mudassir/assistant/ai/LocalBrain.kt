package com.mudassir.assistant.ai

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The offline brain, running on the phone with llama.cpp. All native calls run on one
 * dedicated thread; the model stays loaded between questions so follow-ups are quick.
 */
object LocalBrain {

    private const val CONTEXT = 2048
    private const val MAX_REPLY_TOKENS = 320

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "local-brain") }
    private val dispatcher = thread.asCoroutineDispatcher()

    @Volatile private var handle = 0L
    @Volatile private var loadedThreads = 0

    fun isLoaded() = handle != 0L

    /** Loads the model if needed (a few seconds the first time). */
    suspend fun load(ctx: Context, threads: Int) = withContext(dispatcher) {
        if (handle != 0L && loadedThreads == threads) return@withContext
        if (handle != 0L) { LlamaNative.nativeFree(handle); handle = 0L }
        val file = ModelManager.file(ctx)
        if (!file.exists()) throw BrainException("The offline brain isn't downloaded yet.")
        val h = LlamaNative.nativeLoad(file.path, CONTEXT, threads)
        if (h == 0L) throw BrainException("Couldn't load the offline brain. Try deleting and downloading it again in Settings.")
        handle = h
        loadedThreads = threads
    }

    /** Streams a reply to [onText] (called on the brain's thread). Returns the full reply. */
    suspend fun reply(ctx: Context, threads: Int, turns: List<Turn>, onText: (String) -> Unit): String {
        load(ctx, threads)
        return withContext(dispatcher) {
            val out = StringBuilder()
            // Keep the prompt inside the context: drop the oldest turns (never the system prompt).
            var history = turns
            var result: Int
            while (true) {
                result = LlamaNative.nativeGenerate(
                    handle,
                    history.map { it.role }.toTypedArray(),
                    history.map { it.text.toByteArray(Charsets.UTF_8) as Any }.toTypedArray(),
                    MAX_REPLY_TOKENS, 0.6f
                ) { bytes ->
                    val piece = String(bytes, Charsets.UTF_8)
                    out.append(piece)
                    onText(piece) // called on the brain thread

                    true
                }
                if (result != LlamaNative.ERR_TOO_LONG || history.size <= 2) break
                history = listOf(history.first()) + history.drop(2)
            }
            if (result < 0 && out.isEmpty()) throw BrainException("The offline brain hit an error ($result).")
            out.toString().trim()
        }
    }

    /** Stops the reply being generated (the stop button). */
    fun stop() {
        val h = handle
        if (h != 0L) LlamaNative.nativeStop(h)
    }

    /** New conversation: forget what's cached. */
    fun reset() {
        thread.execute { if (handle != 0L) LlamaNative.nativeReset(handle) }
    }

    fun unload() {
        stop()
        thread.execute {
            if (handle != 0L) LlamaNative.nativeFree(handle)
            handle = 0L
        }
    }
}
