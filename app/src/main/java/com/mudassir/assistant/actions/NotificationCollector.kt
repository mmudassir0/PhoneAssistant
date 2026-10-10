package com.mudassir.assistant.actions

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings as AndroidSettings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Keeps the last messages from your notifications (in memory only, never uploaded unless you
 * use online mode for a summary), so "what did I miss?" can be answered.
 */
class NotificationCollector : NotificationListenerService() {

    data class Note(val app: String, val pkg: String, val title: String, val text: String, val time: Long)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        synchronized(notes) {
            // Messaging apps re-post the same message when new ones arrive; keep one copy.
            notes.removeAll { it.pkg == sbn.packageName && it.title == title && it.text == text }
            notes.addLast(Note(app, sbn.packageName, title, text, sbn.postTime))
            while (notes.size > MAX) notes.removeFirst()
        }
    }

    companion object {
        private const val MAX = 150
        private val notes = ArrayDeque<Note>()

        /** Recent notifications, newest last, optionally only from apps whose name contains [app]. */
        fun recent(app: String?, sinceMs: Long): List<Note> = synchronized(notes) {
            notes.filter { it.time >= sinceMs && (app == null || it.app.contains(app, true) || it.pkg.contains(app, true)) }
        }

        fun isEnabled(ctx: Context): Boolean {
            val flat = AndroidSettings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotificationCollector::class.java).flattenToString()
            return flat.split(":").any { it == me }
        }
    }
}
