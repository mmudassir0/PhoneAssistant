package com.mudassir.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.mudassir.assistant.ai.ModelManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PRAYER, "Prayer times", NotificationManager.IMPORTANCE_HIGH)
        )
        com.mudassir.assistant.actions.Notes.init(this)
        com.mudassir.assistant.actions.Tracker.init(this)
        com.mudassir.assistant.actions.Routines.init(this)
        ModelManager.refresh(this)
        com.mudassir.assistant.wake.WakeModel.refresh(this)
        Assistant.init(this)
        com.mudassir.assistant.actions.PrayerAlerts.schedule(this)
        runCatching { com.mudassir.assistant.ui.AssistantWidget.refresh(this) }
    }

    companion object {
        const val CH_REMINDERS = "reminders"
        const val CH_PRAYER = "prayer"
    }
}
