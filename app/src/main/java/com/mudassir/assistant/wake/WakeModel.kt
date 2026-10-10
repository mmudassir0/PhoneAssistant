package com.mudassir.assistant.wake

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The small offline English speech model (Vosk, about 40 MB) that listens for "Hey Assistant".
 * Downloaded once and unzipped into app storage.
 */
object WakeModel {

    private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    sealed class State {
        data object Missing : State()
        data class Downloading(val percent: Int) : State()
        data object Unpacking : State()
        data class Failed(val message: String) : State()
        data object Ready : State()
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun dir(ctx: Context) = File(ctx.filesDir, "wake-model")
    private fun ready(ctx: Context) = File(dir(ctx), "am/final.mdl").exists() || File(dir(ctx), "conf/model.conf").exists()

    fun refresh(ctx: Context) {
        if (job?.isActive == true) return
        _state.value = if (ready(ctx)) State.Ready else State.Missing
    }

    fun download(ctx: Context, onReady: () -> Unit = {}) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            try {
                val zip = File(app.cacheDir, "wake-model.zip")
                _state.value = State.Downloading(0)
                client.newCall(Request.Builder().url(URL).build()).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                    val body = res.body ?: throw IOException("Empty response")
                    val total = body.contentLength()
                    var done = 0L
                    zip.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) _state.value = State.Downloading((done * 100 / total).toInt())
                            }
                        }
                    }
                }
                _state.value = State.Unpacking
                val target = dir(app)
                target.deleteRecursively()
                target.mkdirs()
                ZipInputStream(zip.inputStream().buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        // Drop the top folder ("vosk-model-small-en-us-0.15/") and refuse paths leaving the folder.
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isEmpty() || rel.contains("..")) continue
                        val out = File(target, rel)
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { z.copyTo(it) }
                        }
                    }
                }
                zip.delete()
                if (!ready(app)) throw IOException("The speech model looks incomplete")
                _state.value = State.Ready
                launch(Dispatchers.Main) { onReady() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Missing
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "Download failed")
            }
        }
    }
}
