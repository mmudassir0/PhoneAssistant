package com.mudassir.assistant.actions

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mudassir.assistant.App
import com.mudassir.assistant.Settings
import com.mudassir.assistant.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject

/** Reminders set by voice. Saved so they survive a phone restart. */
object Reminders {

    data class Reminder(val id: Int, val at: Long, val text: String)

    fun add(ctx: Context, at: Long, text: String): Reminder {
        val all = load(ctx).toMutableList()
        val r = Reminder((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), at, text)
        all += r
        save(ctx, all)
        schedule(ctx, r)
        return r
    }

    fun upcoming(ctx: Context) = load(ctx).filter { it.at > System.currentTimeMillis() }.sortedBy { it.at }

    /** After a restart: schedule everything still in the future again. */
    fun rescheduleAll(ctx: Context) {
        val future = upcoming(ctx)
        save(ctx, future)
        future.forEach { schedule(ctx, it) }
    }

    internal fun remove(ctx: Context, id: Int) = save(ctx, load(ctx).filterNot { it.id == id })

    private fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, r.id,
            Intent(ctx, Receiver::class.java).putExtra("id", r.id).putExtra("text", r.text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
    }

    private fun load(ctx: Context): List<Reminder> = runCatching {
        val arr = JSONArray(Settings(ctx).remindersJson)
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Reminder(it.getInt("id"), it.getLong("at"), it.getString("text")) }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, list: List<Reminder>) {
        Settings(ctx).remindersJson = JSONArray(list.map {
            JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)
        }).toString()
    }

    /** Fires the reminder notification. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getIntExtra("id", 0)
            val text = intent.getStringExtra("text") ?: "Reminder"
            val open = PendingIntent.getActivity(
                ctx, id, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            ctx.getSystemService(NotificationManager::class.java).notify(
                id,
                NotificationCompat.Builder(ctx, App.CH_REMINDERS)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("Reminder")
                    .setContentText(text.replaceFirstChar { it.uppercase() })
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
            remove(ctx, id)
        }
    }

    /** Re-schedules reminders after the phone restarts. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) rescheduleAll(ctx)
        }
    }
}
