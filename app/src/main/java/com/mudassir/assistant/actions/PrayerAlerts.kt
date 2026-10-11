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
import com.mudassir.assistant.commands.PrayerTimes
import com.mudassir.assistant.ui.AssistantWidget
import com.mudassir.assistant.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A notification at each prayer time, for your last known location. One alarm at a time: each schedules the next. */
object PrayerAlerts {

    private val PRAYERS = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    /** The next prayer after [after], using the last known location (Lahore until known). */
    fun next(ctx: Context, after: LocalDateTime = LocalDateTime.now()): Pair<String, LocalDateTime> {
        val s = Settings(ctx)
        val zone = ZoneId.systemDefault()
        for (offset in 0L..1L) {
            val day = after.toLocalDate().plusDays(offset)
            val tz = zone.rules.getOffset(day.atStartOfDay(zone).toInstant()).totalSeconds / 3600.0
            val times = PrayerTimes.pakistan(s.lastLat, s.lastLng, tz).on(day)
            PRAYERS.forEach { name ->
                val t = times[name] ?: return@forEach
                val at = LocalDateTime.of(day, t)
                if (at.isAfter(after)) return name to at
            }
        }
        return "Fajr" to LocalDateTime.of(LocalDate.now().plusDays(1), java.time.LocalTime.of(5, 0))
    }

    private fun pending(ctx: Context, name: String = "") = PendingIntent.getBroadcast(
        ctx, 4100, Intent(ctx, Receiver::class.java).putExtra("name", name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context, after: LocalDateTime = LocalDateTime.now()) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (!Settings(ctx).prayerAlerts) {
            am.cancel(pending(ctx))
            return
        }
        val (name, at) = next(ctx, after)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pending(ctx, name)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val name = intent.getStringExtra("name").orEmpty().ifEmpty { "prayer" }
            if (Settings(ctx).prayerAlerts) {
                ctx.getSystemService(NotificationManager::class.java).notify(
                    4101,
                    NotificationCompat.Builder(ctx, App.CH_PRAYER)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentTitle("It's time for $name")
                        .setContentText("Allahu Akbar")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .setAutoCancel(true)
                        .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                        .build()
                )
            }
            schedule(ctx, LocalDateTime.now().plusMinutes(1))
            AssistantWidget.refresh(ctx)
        }
    }
}
