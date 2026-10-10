package com.mudassir.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** App settings, backed by the same SharedPreferences the Settings screen edits. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    /** Use Google Gemini (free tier) when online and a key is set. */
    val onlineMode get() = p.getBoolean(K_ONLINE, false)
    val geminiKey get() = p.getString(K_GEMINI_KEY, "")?.trim().orEmpty()
    val geminiModel get() = p.getString(K_GEMINI_MODEL, DEFAULT_GEMINI_MODEL)?.trim()?.ifEmpty { null } ?: DEFAULT_GEMINI_MODEL

    /** CPU threads for the offline model. The G99 has 2 fast + 6 slow cores; 4 is usually best. */
    val threads get() = p.getString(K_THREADS, "4")?.toIntOrNull()?.coerceIn(1, 8) ?: 4

    /** "always", "mic" (only when you spoke) or "never". */
    val speakReplies get() = p.getString(K_SPEAK, "mic") ?: "mic"
    val speechLanguage get() = p.getString(K_SPEECH_LANG, "en-US") ?: "en-US"
    val weatherCity get() = p.getString(K_CITY, "Lahore")?.trim()?.ifEmpty { null } ?: "Lahore"
    val autoUpdateCheck get() = p.getBoolean(K_UPDATE_CHECK, true)

    var lastUpdateCheck: Long
        get() = p.getLong("last_update_check", 0)
        set(v) = p.edit().putLong("last_update_check", v).apply()

    /** Saved reminders (JSON), managed by Reminders. */
    var remindersJson: String
        get() = p.getString("reminders", "[]") ?: "[]"
        set(v) = p.edit().putString("reminders", v).apply()

    companion object {
        const val K_ONLINE = "online_mode"
        const val K_GEMINI_KEY = "gemini_key"
        const val K_GEMINI_MODEL = "gemini_model"
        const val K_THREADS = "threads"
        const val K_SPEAK = "speak_replies"
        const val K_SPEECH_LANG = "speech_lang"
        const val K_CITY = "weather_city"
        const val K_UPDATE_CHECK = "update_check"

        /** Google's alias for its newest Flash model. Editable in Settings in case it changes. */
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
    }
}
