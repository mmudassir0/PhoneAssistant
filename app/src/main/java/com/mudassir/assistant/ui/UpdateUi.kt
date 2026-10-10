package com.mudassir.assistant.ui

import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mudassir.assistant.Settings
import com.mudassir.assistant.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Update available" dialog, download progress and install, shared by the chat and settings screens. */
object UpdateUi {

    fun maybeCheck(activity: AppCompatActivity) {
        val s = Settings(activity)
        if (!s.autoUpdateCheck || System.currentTimeMillis() - s.lastUpdateCheck < 6 * 3600_000L) return
        check(activity, manual = false)
    }

    fun check(activity: AppCompatActivity, manual: Boolean) {
        if (manual) Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val release = runCatching { withContext(Dispatchers.IO) { Updater.latest() } }.getOrNull()
            Settings(activity).lastUpdateCheck = System.currentTimeMillis()
            when {
                release == null -> if (manual) Toast.makeText(activity, "Couldn't reach GitHub. Try again later.", Toast.LENGTH_SHORT).show()
                release.build > Updater.installedBuild(activity) -> offer(activity, release)
                manual -> Toast.makeText(activity, "You're on the latest version", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun offer(activity: AppCompatActivity, r: Updater.Release) {
        if (activity.isFinishing) return
        val notes = r.notes.lines()
            .filter { it.isNotBlank() && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") }
            .joinToString("\n").take(800)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Update available (build ${r.build})")
            .setMessage(if (notes.isBlank()) "A newer version is ready to install." else "What's new:\n\n$notes")
            .setPositiveButton("Update") { _, _ -> install(activity, r) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun install(activity: AppCompatActivity, r: Updater.Release) {
        if (!Updater.canInstall(activity)) {
            Toast.makeText(activity, "Allow installs from Phone Assistant, then tap Update again", Toast.LENGTH_LONG).show()
            activity.startActivity(Updater.allowInstallsIntent(activity))
            return
        }
        val bar = LinearProgressIndicator(activity).apply {
            isIndeterminate = true
            max = 100
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Downloading update…")
            .setView(bar)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val apk = runCatching {
                withContext(Dispatchers.IO) {
                    Updater.download(activity, r) { pct ->
                        activity.runOnUiThread { bar.isIndeterminate = false; bar.setProgressCompat(pct, true) }
                    }
                }
            }
            dialog.dismiss()
            apk.onSuccess { Updater.install(activity, it) }
                .onFailure { Toast.makeText(activity, "Update failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
}
