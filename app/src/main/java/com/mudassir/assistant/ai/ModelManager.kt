package com.mudassir.assistant.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * The offline brain: Qwen 2.5 1.5B Instruct, Q4_0 (about 1 GB). Q4_0 uses llama.cpp's
 * repacked ARM kernels, the fastest option on the Helio G99's cores.
 * Downloaded once from Hugging Face (no login needed) and resumed if interrupted.
 */
object ModelManager {

    const val FILE_NAME = "qwen2.5-1.5b-instruct-q4_0.gguf"
    private const val URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/$FILE_NAME"
    const val APPROX_SIZE = 1_065_000_000L

    sealed class State {
        data object Missing : State()
        data class Downloading(val done: Long, val total: Long) : State() {
            val percent get() = if (total > 0) ((done * 100) / total).toInt() else 0
        }
        data class Failed(val message: String) : State()
        data object Ready : State()
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Starts or resumes the download in the background (keeps going if you switch screens). */
    fun start(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch { download(app) }
    }

    fun pause() {
        job?.cancel()
    }

    fun file(ctx: Context) = File(File(ctx.filesDir, "models").apply { mkdirs() }, FILE_NAME)
    private fun partFile(ctx: Context) = File(file(ctx).path + ".part")

    fun refresh(ctx: Context) {
        if (_state.value is State.Downloading) return
        _state.value = if (file(ctx).exists()) State.Ready else State.Missing
    }

    fun partialBytes(ctx: Context) = partFile(ctx).takeIf { it.exists() }?.length() ?: 0L

    /** Downloads (or continues downloading) the model. Safe to call again after a failure. */
    suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        if (file(ctx).exists()) { _state.value = State.Ready; return@withContext }
        val part = partFile(ctx)
        var done = if (part.exists()) part.length() else 0L
        _state.value = State.Downloading(done, APPROX_SIZE)
        try {
            val req = Request.Builder().url(URL).apply { if (done > 0) header("Range", "bytes=$done-") }.build()
            client.newCall(req).execute().use { res ->
                if (res.code == 416) { // already complete
                    part.renameTo(file(ctx)); _state.value = State.Ready; return@withContext
                }
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                if (res.code == 200 && done > 0) done = 0 // server ignored the range: start over
                val body = res.body ?: throw IOException("Empty response")
                val total = if (body.contentLength() > 0) done + body.contentLength() else APPROX_SIZE
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(done)
                    out.seek(done)
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var lastEmit = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastEmit > 2_000_000) {
                                lastEmit = done
                                _state.value = State.Downloading(done, total)
                            }
                        }
                    }
                }
                if (done < total * 0.99) throw IOException("Download incomplete")
            }
            if (!part.renameTo(file(ctx))) throw IOException("Could not save the model")
            _state.value = State.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = State.Failed("Paused")
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Download failed")
        }
    }

    fun delete(ctx: Context) {
        LocalBrain.unload()
        file(ctx).delete()
        partFile(ctx).delete()
        _state.value = State.Missing
    }
}
