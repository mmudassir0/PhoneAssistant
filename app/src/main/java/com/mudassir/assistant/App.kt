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
        ModelManager.refresh(this)
        Assistant.init(this)
    }

    companion object {
        const val CH_REMINDERS = "reminders"
    }
}
