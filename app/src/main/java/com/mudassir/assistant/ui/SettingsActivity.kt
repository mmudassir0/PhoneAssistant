package com.mudassir.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mudassir.assistant.R
import com.mudassir.assistant.Updater
import com.mudassir.assistant.actions.NotificationCollector
import com.mudassir.assistant.actions.Reminders
import com.mudassir.assistant.ai.LocalBrain
import com.mudassir.assistant.ai.ModelManager
import com.mudassir.assistant.wake.WakeModel
import com.mudassir.assistant.wake.WakeWordService
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.backButton).setOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, Fragment()).commit()
        }
    }

    class Fragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()

            findPreference<EditTextPreference>("gemini_key")?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    if (p.text.isNullOrBlank()) "Not set" else "Key saved (…${p.text!!.takeLast(4)})"
                }
            }
            findPreference<Preference>("get_key")?.setOnPreferenceClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                true
            }
            findPreference<Preference>("threads")?.setOnPreferenceChangeListener { _, _ ->
                LocalBrain.unload() // reloads with the new thread count on the next question
                true
            }
            findPreference<Preference>("model")?.setOnPreferenceClickListener {
                when (ModelManager.state.value) {
                    ModelManager.State.Ready -> MaterialAlertDialogBuilder(ctx)
                        .setTitle("Delete the offline brain?")
                        .setMessage("Frees about 1 GB. You can download it again later.")
                        .setPositiveButton("Delete") { _, _ -> ModelManager.delete(ctx) }
                        .setNegativeButton("Cancel", null)
                        .show()
                    is ModelManager.State.Downloading -> ModelManager.pause()
                    else -> ModelManager.start(ctx)
                }
                true
            }
            findPreference<Preference>("notification_access")?.setOnPreferenceClickListener {
                startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                true
            }
            findPreference<Preference>("speech_settings")?.setOnPreferenceClickListener {
                runCatching { startActivity(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS)) }
                    .onFailure { startActivity(Intent(AndroidSettings.ACTION_SETTINGS)) }
                true
            }
            findPreference<SwitchPreferenceCompat>("wake_word")?.setOnPreferenceChangeListener { _, value ->
                val on = value as Boolean
                if (on && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    android.widget.Toast.makeText(ctx, "Tap the mic on the main screen once to allow the microphone, then turn this on.", android.widget.Toast.LENGTH_LONG).show()
                    return@setOnPreferenceChangeListener false
                }
                com.mudassir.assistant.Settings(ctx).wakeWord = on // saved before starting, the service checks it
                if (on) {
                    if (!AndroidSettings.canDrawOverlays(ctx)) {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("One more permission")
                            .setMessage("So \"Hey Assistant\" can open the app while you're in another app, allow " +
                                "\"Display over other apps\" on the next screen. Without it you'll get a notification to tap instead.")
                            .setPositiveButton("Open settings") { _, _ -> startActivity(overlayIntent()) }
                            .setNegativeButton("Skip", null)
                            .show()
                    }
                    if (WakeModel.state.value == WakeModel.State.Ready) WakeWordService.start(ctx)
                    else WakeModel.download(ctx) { WakeWordService.start(ctx) }
                } else {
                    WakeWordService.stop(ctx)
                }
                true
            }
            findPreference<Preference>("overlay")?.setOnPreferenceClickListener {
                startActivity(overlayIntent())
                true
            }

            findPreference<Preference>("check_now")?.setOnPreferenceClickListener {
                UpdateUi.check(requireActivity() as AppCompatActivity, manual = true)
                true
            }
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            findPreference<Preference>("version")?.summary = "${info.versionName} · build ${Updater.installedBuild(ctx)}"

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { ModelManager.state.collect { renderModel(it) } }
                    launch {
                        WakeModel.state.collect { st ->
                            findPreference<Preference>("wake_model")?.summary = when (st) {
                                WakeModel.State.Ready -> "Installed (about 40 MB)"
                                is WakeModel.State.Downloading -> "Downloading ${st.percent}%…"
                                WakeModel.State.Unpacking -> "Unpacking…"
                                is WakeModel.State.Failed -> "Download failed (${st.message}). Turn the switch off and on to retry."
                                WakeModel.State.Missing -> "Downloads automatically (about 40 MB) when you turn on the wake word"
                            }
                        }
                    }
                }
            }
        }

        private fun overlayIntent() =
            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))

        override fun onResume() {
            super.onResume()
            val ctx = requireContext()
            WakeModel.refresh(ctx)
            findPreference<Preference>("overlay")?.summary =
                if (AndroidSettings.canDrawOverlays(ctx)) "Allowed: \"Hey Assistant\" opens the app directly"
                else "Not allowed: tap to allow \"Display over other apps\" so the wake word can open the app"
            findPreference<Preference>("notification_access")?.summary =
                if (NotificationCollector.isEnabled(ctx)) "On: \"what did I miss?\" works"
                else "Off: tap to allow, so I can summarise your messages"
            val upcoming = Reminders.upcoming(ctx)
            findPreference<Preference>("reminders")?.summary =
                if (upcoming.isEmpty()) "None"
                else upcoming.take(5).joinToString("\n") {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.at)) + " · " + it.text
                }
        }

        private fun renderModel(state: ModelManager.State) {
            val pref = findPreference<Preference>("model") ?: return
            pref.summary = when (state) {
                ModelManager.State.Ready -> "Qwen 2.5 1.5B (about 1 GB) installed. Tap to delete."
                is ModelManager.State.Downloading -> "Downloading ${state.percent}%… tap to pause"
                is ModelManager.State.Failed -> "Stopped (${state.message}). Tap to resume."
                ModelManager.State.Missing -> "Not downloaded. Tap to download (about 1 GB)."
            }
        }
    }
}
