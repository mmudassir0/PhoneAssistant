package com.mudassir.assistant.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.mudassir.assistant.Assistant
import com.mudassir.assistant.R
import com.mudassir.assistant.Settings
import com.mudassir.assistant.UiEvent
import com.mudassir.assistant.ai.ModelManager
import com.mudassir.assistant.databinding.ActivityMainBinding
import com.mudassir.assistant.voice.Listener
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var listener: Listener
    private val adapter = ChatAdapter()
    private var listening = false

    /** The command waiting for a permission answer. */
    private var waitingCommand: UiEvent.AskPermissions? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else toast("I need the microphone to hear you.")
    }

    private val actionPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ask = waitingCommand ?: return@registerForActivityResult
        waitingCommand = null
        if (result.values.all { it }) Assistant.retry(this, ask.text)
        else Assistant.permissionRefused(ask.fallback, if (ask.fallback != null) "Okay, opening it for you instead." else "I can't do that without permission.")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        b.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.messages.adapter = adapter

        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.newChatButton.setOnClickListener { Assistant.newChat() }
        b.sendButton.setOnClickListener { sendTyped() }
        b.input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        b.micButton.setOnClickListener {
            when {
                Assistant.busy.value -> Assistant.stop()
                listening -> { listener.stop(); setListening(false) }
                else -> startListening()
            }
        }
        b.downloadButton.setOnClickListener {
            if (ModelManager.state.value is ModelManager.State.Downloading) ModelManager.pause() else ModelManager.start(this)
        }

        listOf(
            "What's the time?", "Set a timer for 5 minutes", "Remind me in 1 hour to drink water",
            "What did I miss?", "Tell me a fun fact", "Turn on the flashlight"
        ).forEach { text ->
            b.suggestions.addView(Chip(this).apply {
                this.text = text
                setOnClickListener { Assistant.send(this@MainActivity, text, byVoice = false) }
            })
        }

        listener = Listener(
            this,
            onPartial = { b.input.setText(it); b.input.setSelection(it.length) },
            onFinal = { text ->
                b.input.setText("")
                Assistant.send(this, text, byVoice = true)
            },
            onState = { setListening(it) },
            onError = { msg -> setListening(false); toast(msg) }
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Assistant.messages.collect { list ->
                        adapter.submitList(list) { if (list.isNotEmpty()) b.messages.scrollToPosition(list.size - 1) }
                        b.emptyState.isVisible = list.isEmpty()
                    }
                }
                launch { Assistant.busy.collect { updateMic() } }
                launch { ModelManager.state.collect { renderModel(it) } }
                launch { Assistant.events.collect { handle(it) } }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        handleIntent(intent)
        UpdateUi.maybeCheck(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ModelManager.refresh(this)
        b.statusText.text = statusLine()
    }

    override fun onStop() {
        super.onStop()
        if (listening) { listener.stop(); setListening(false) }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            intent.removeExtra(EXTRA_LISTEN)
            b.root.post { startListening() }
        }
    }

    private fun sendTyped() {
        val text = b.input.text?.toString().orEmpty()
        if (text.isBlank()) return
        b.input.setText("")
        Assistant.send(this, text, byVoice = false)
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!listener.isAvailable()) {
            toast("No speech recogniser on this phone. Install or update the Google app.")
            return
        }
        Assistant.stop() // stop talking so it doesn't hear itself
        listener.start(Settings(this).speechLanguage)
        setListening(true)
    }

    private fun setListening(on: Boolean) {
        listening = on
        updateMic()
        b.statusText.text = if (on) "Listening…" else statusLine()
    }

    private fun updateMic() {
        val busy = Assistant.busy.value
        b.micButton.setImageResource(if (busy || listening) R.drawable.ic_stop else R.drawable.ic_mic)
        b.micButton.contentDescription = if (busy) "Stop" else if (listening) "Stop listening" else "Speak"
        b.sendButton.isEnabled = !busy
    }

    private fun renderModel(state: ModelManager.State) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.modelBanner.isVisible = state !is ModelManager.State.Ready
        b.modelProgress.isVisible = state is ModelManager.State.Downloading
        when (state) {
            is ModelManager.State.Missing -> {
                val partial = ModelManager.partialBytes(this)
                b.modelText.text = if (partial > 0) "Offline brain partly downloaded (${partial / 1_000_000} MB). Tap to continue."
                else "Download the offline brain (about 1 GB, once) so I can answer questions without internet. Wi-Fi recommended."
                b.downloadButton.text = if (partial > 0) "Continue" else "Download"
            }
            is ModelManager.State.Downloading -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                b.modelText.text = "Downloading offline brain… ${state.done / 1_000_000} / ${state.total / 1_000_000} MB"
                b.modelProgress.setProgressCompat(state.percent, true)
                b.downloadButton.text = "Pause"
            }
            is ModelManager.State.Failed -> {
                b.modelText.text = "Download stopped (${state.message}). Your progress is kept."
                b.downloadButton.text = "Resume"
            }
            ModelManager.State.Ready -> {}
        }
        b.statusText.text = statusLine()
    }

    private fun statusLine(): String {
        val s = Settings(this)
        val online = s.onlineMode && s.geminiKey.isNotBlank() && Assistant.isOnline(this)
        val local = ModelManager.state.value is ModelManager.State.Ready
        return when {
            online -> "Online · Gemini" + if (local) " (offline brain as backup)" else ""
            local -> "Offline brain"
            else -> "Phone commands only"
        }
    }

    private fun handle(e: UiEvent) {
        when (e) {
            is UiEvent.Open -> try {
                startActivity(e.intent)
            } catch (ex: ActivityNotFoundException) {
                toast("No app on this phone can do that.")
            } catch (ex: SecurityException) {
                toast("Android blocked that action.")
            }
            is UiEvent.AskPermissions -> {
                waitingCommand = e
                actionPermissions.launch(e.permissions.toTypedArray())
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        listener.stop()
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
    }
}
