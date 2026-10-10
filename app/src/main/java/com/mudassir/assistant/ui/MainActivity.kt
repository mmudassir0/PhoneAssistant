package com.mudassir.assistant.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import com.mudassir.assistant.wake.WakeModel
import com.mudassir.assistant.wake.WakeWordService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalTime

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
        b.messages.itemAnimator?.changeDuration = 0 // streamed text updates shouldn't flash

        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.notesButton.setOnClickListener { startActivity(Intent(this, NotesActivity::class.java)) }
        b.historyButton.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        b.newChatButton.setOnClickListener { Assistant.newChat() }
        b.sendButton.setOnClickListener { sendTyped() }
        b.input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        b.micArea.setOnClickListener { onOrbTap() }
        b.bigOrb.setOnClickListener { onOrbTap() }
        b.downloadButton.setOnClickListener {
            if (ModelManager.state.value is ModelManager.State.Downloading) ModelManager.pause() else ModelManager.start(this)
        }

        listOf(
            "What can you do?", "Good morning", "Next namaz", "Add milk to my shopping list",
            "15% of 2400", "Weather today", "Tell me a fun fact"
        ).forEach { text ->
            b.suggestions.addView(Chip(this).apply {
                this.text = text
                chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface))
                chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.outline))
                chipStrokeWidth = resources.displayMetrics.density
                setTextColor(ContextCompat.getColor(context, R.color.text))
                setOnClickListener { send(if (text == "What can you do?") "help" else text) }
            })
        }

        listener = Listener(
            this,
            onPartial = { b.liveText.text = it },
            onFinal = { text ->
                b.liveText.text = ""
                Assistant.send(this, text, byVoice = true)
            },
            onState = { setListening(it) },
            onError = { msg -> setListening(false); toast(msg) },
            onLevel = { b.orb.level = it; b.bigOrb.level = it }
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Assistant.messages.collect { list ->
                        adapter.submitList(list) { if (list.isNotEmpty()) b.messages.scrollToPosition(list.size - 1) }
                        val empty = list.isEmpty()
                        b.emptyState.isVisible = empty
                        b.micArea.isVisible = !empty // the big orb is the mic on the empty screen
                        if (empty) b.greeting.text = greeting()
                    }
                }
                launch { combine(Assistant.busy, Assistant.speaking) { busy, speaking -> busy to speaking }.collect { updateOrb() } }
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

    override fun onStart() {
        super.onStart()
        // The app uses the microphone itself while open; the wake-word service waits.
        WakeWordService.pause()
        WakeModel.refresh(this)
        WakeWordService.start(this)
    }

    override fun onResume() {
        super.onResume()
        ModelManager.refresh(this)
        renderStatus()
    }

    override fun onStop() {
        super.onStop()
        if (listening) { listener.stop(); setListening(false) }
        WakeWordService.resume()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            intent.removeExtra(EXTRA_LISTEN)
            b.root.postDelayed({ startListening() }, 300) // let the wake-word service free the mic
        }
    }

    private fun onOrbTap() = when {
        Assistant.busy.value || Assistant.speaking.value -> Assistant.stop()
        listening -> { listener.stop(); setListening(false) }
        else -> startListening()
    }

    private fun send(text: String) = Assistant.send(this, text, byVoice = false)

    private fun sendTyped() {
        val text = b.input.text?.toString().orEmpty()
        if (text.isBlank()) return
        b.input.setText("")
        send(text)
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
        b.liveText.isVisible = on
        if (on) b.liveText.text = "Listening…"
        updateOrb()
    }

    private fun updateOrb() {
        val mode = when {
            listening -> OrbView.Mode.LISTENING
            Assistant.speaking.value -> OrbView.Mode.SPEAKING
            Assistant.busy.value -> OrbView.Mode.THINKING
            else -> OrbView.Mode.IDLE
        }
        b.orb.mode = mode
        b.bigOrb.mode = mode
        val stop = mode != OrbView.Mode.IDLE
        b.micIcon.setImageResource(if (stop) R.drawable.ic_stop else R.drawable.ic_mic)
        b.micArea.contentDescription = if (stop) "Stop" else "Speak"
        b.sendButton.isEnabled = !Assistant.busy.value
    }

    private fun renderModel(state: ModelManager.State) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.modelBanner.isVisible = state !is ModelManager.State.Ready
        b.modelProgress.isVisible = state is ModelManager.State.Downloading
        when (state) {
            is ModelManager.State.Missing -> {
                val partial = ModelManager.partialBytes(this)
                b.modelText.text = if (partial > 0) "Offline brain is ${partial / 1_000_000} MB in. Continue to answer questions without internet."
                else "Download the offline brain (about 1 GB, once) to answer questions without internet. Wi-Fi recommended."
                b.downloadButton.text = if (partial > 0) "Continue" else "Download"
            }
            is ModelManager.State.Downloading -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                b.modelText.text = "Downloading the offline brain: ${state.done / 1_000_000} of ${state.total / 1_000_000} MB"
                b.modelProgress.setProgressCompat(state.percent, true)
                b.downloadButton.text = "Pause"
            }
            is ModelManager.State.Failed -> {
                b.modelText.text = "Download stopped (${state.message}). Your progress is kept."
                b.downloadButton.text = "Resume"
            }
            ModelManager.State.Ready -> {}
        }
        renderStatus()
    }

    private fun renderStatus() {
        val s = Settings(this)
        val online = s.onlineMode && s.geminiKey.isNotBlank() && Assistant.isOnline(this)
        val local = ModelManager.state.value is ModelManager.State.Ready
        val (text, color) = when {
            online -> "Online" to R.color.violet
            local -> "Offline" to R.color.teal
            else -> "Commands only" to R.color.idle_dot
        }
        b.statusText.text = text
        b.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
    }

    private fun greeting(): String {
        val h = LocalTime.now().hour
        val name = com.mudassir.assistant.actions.Notes.name.value?.split(" ")?.first()
        val base = when (h) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Still up"
        }
        return base + (name?.let { ", $it" } ?: "") + if (base == "Still up") "?" else ""
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
