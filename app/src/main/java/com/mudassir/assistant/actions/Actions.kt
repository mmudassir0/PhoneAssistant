package com.mudassir.assistant.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import com.mudassir.assistant.Settings
import com.mudassir.assistant.commands.CommandParser
import com.mudassir.assistant.commands.CommandParser.Command
import com.mudassir.assistant.commands.CommandParser.Type
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the assistant does for a recognised command.
 *
 * Actions never start screens themselves: they return an [Intent] for the chat screen to
 * open, or the permissions it should ask for before trying again.
 */
object Actions {

    data class Result(
        /** What to say/show. */
        val reply: String,
        /** A screen to open (alarm, timer, call, app, settings). */
        val intent: Intent? = null,
        /** Ask for these first, then run the same command again. If refused, [intent] (if any) opens instead. */
        val permissions: List<String> = emptyList(),
        /** Text the AI should summarise (notifications), with this instruction. */
        val summarize: String? = null,
        /** A question was asked ("what time?"): the next answer is read as this prefix + answer. */
        val answerPrefix: String? = null,
        /** Clear the conversation. */
        val newChat: Boolean = false
    )

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US)

    suspend fun run(ctx: Context, c: Command, online: Boolean): Result = when (c.type) {
        Type.ALARM -> {
            val t = c.time
            if (t == null) Result("What time should I set the alarm for?", answerPrefix = "set an alarm for ")
            else Result(
                "Alarm set for ${t.format(TIME)}.",
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, t.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            )
        }

        Type.TIMER -> Result(
            "Timer set for ${CommandParser.describe(Duration.ofSeconds(c.seconds))}.",
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, c.seconds.toInt())
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )

        Type.REMINDER -> {
            val at = c.at
            if (at == null) {
                Result("When should I remind you to ${c.text}?", answerPrefix = "remind me to ${c.text} ")
            } else {
                val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                Reminders.add(ctx, ms, c.text)
                val today = at.toLocalDate() == LocalDateTime.now().toLocalDate()
                val tomorrow = at.toLocalDate() == LocalDateTime.now().toLocalDate().plusDays(1)
                val day = when { today -> "today"; tomorrow -> "tomorrow"; else -> at.format(DAY) }
                Result("Okay, I'll remind you to ${c.text} $day at ${at.format(TIME)}.")
            }
        }

        Type.CALL -> call(ctx, c.text.orEmpty())
        Type.OPEN_APP -> openApp(ctx, c.text.orEmpty())

        Type.TIME -> Result("It's ${LocalDateTime.now().format(TIME)}.")
        Type.DATE -> Result("Today is ${LocalDateTime.now().format(DAY)}.")

        Type.BATTERY -> {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            Result("Battery is at $pct%" + if (bm.isCharging) " and charging." else ".")
        }

        Type.FLASHLIGHT_ON, Type.FLASHLIGHT_OFF -> {
            val on = c.type == Type.FLASHLIGHT_ON
            val cam = ctx.getSystemService(CameraManager::class.java)
            val id = cam.cameraIdList.firstOrNull {
                cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) Result("This phone doesn't have a flashlight I can use.")
            else {
                cam.setTorchMode(id, on)
                Result(if (on) "Flashlight on." else "Flashlight off.")
            }
        }

        Type.NOTIFICATIONS -> notifications(ctx, c.text)

        Type.NEWS -> if (!online) Result("I need an internet connection for the news.") else {
            val titles = runCatching { OnlineInfo.headlines() }.getOrNull()
            if (titles.isNullOrEmpty()) Result("I couldn't load the news right now.")
            else Result("Top headlines:\n" + titles.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"))
        }

        Type.WEATHER -> if (!online) Result("I need an internet connection for the weather.") else {
            Result(runCatching { OnlineInfo.weather(Settings(ctx).weatherCity) }.getOrElse { "I couldn't get the weather right now." })
        }

        Type.NEW_CHAT -> Result("Okay, starting fresh.", newChat = true)

        Type.OPEN_URL -> {
            val raw = c.text.orEmpty()
            val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            Result("Opening ${Uri.parse(url).host ?: raw}.", Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        Type.WEB_SEARCH -> Result(
            "Searching for \"${c.text}\".",
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(c.text)))
        )

        Type.YOUTUBE_SEARCH -> Result(
            "Searching YouTube for \"${c.text}\".",
            // Opens in the YouTube app if installed, otherwise the browser.
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(c.text)))
        )

        Type.WHATSAPP, Type.SMS -> message(ctx, c)
        Type.VOLUME -> volume(ctx, c.text ?: "status")
        Type.BRIGHTNESS -> brightness(ctx, c.text ?: "status")
        Type.RINGER -> ringer(ctx, c.text ?: "silent")
        Type.SETTING -> setting(c.text ?: "wifi")

        Type.HELP -> Result(
            "Here's what I can do. Phone: alarms, timers, reminders, calls, WhatsApp and SMS messages, opening apps " +
                "and websites, volume, brightness, silent mode, flashlight, and Wi-Fi or Bluetooth switches. " +
                "Info: time, date, battery, weather and news. And you can ask me questions or chat, offline."
        )

        Type.UNSUPPORTED -> Result(
            "I can't do that on your phone yet. I can: set alarms, timers and reminders; call or message contacts " +
                "on WhatsApp or SMS; open apps and websites; search Google or YouTube; control volume, brightness, " +
                "silent mode and the flashlight; open Wi-Fi, Bluetooth and data switches; and tell the time, date, " +
                "battery, weather and news."
        )
    }

    // ---- calls ----

    private fun call(ctx: Context, who: String): Result {
        val digits = who.replace(Regex("[\\s-]"), "")
        val number: String
        val name: String
        if (digits.matches(Regex("\\+?\\d{3,15}"))) {
            number = digits
            name = who
        } else {
            if (!granted(ctx, Manifest.permission.READ_CONTACTS)) {
                return Result("I need access to your contacts to call $who.", permissions = listOf(Manifest.permission.READ_CONTACTS))
            }
            val match = findContact(ctx, who) ?: return Result("I couldn't find \"$who\" in your contacts.")
            name = match.first
            number = match.second
        }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (granted(ctx, Manifest.permission.CALL_PHONE)) Result("Calling $name.", Intent(Intent.ACTION_CALL, uri))
        // Without the call permission, the dialer opens with the number ready.
        else Result("Calling $name.", Intent(Intent.ACTION_DIAL, uri), permissions = listOf(Manifest.permission.CALL_PHONE))
    }

    /** Best contact for a spoken name: exact name, then first name, then contains. */
    private fun findContact(ctx: Context, who: String): Pair<String, String>? {
        val q = who.trim().lowercase(Locale.US)
        val rows = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${q.split(" ").first()}%"), null
        )?.use { c ->
            while (c.moveToNext()) rows += (c.getString(0).orEmpty() to c.getString(1).orEmpty())
        }
        val byName = rows.filter { it.second.isNotBlank() }
        return byName.firstOrNull { it.first.lowercase(Locale.US) == q }
            ?: byName.firstOrNull { it.first.lowercase(Locale.US).split(" ").first() == q }
            ?: byName.firstOrNull { it.first.lowercase(Locale.US).startsWith(q) }
            ?: byName.firstOrNull { it.first.lowercase(Locale.US).contains(q) }
    }

    // ---- apps ----

    private fun openApp(ctx: Context, name: String): Result {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val q = name.lowercase(Locale.US).replace(" ", "")
        fun norm(s: String) = s.lowercase(Locale.US).replace(" ", "")
        val hit = apps.firstOrNull { norm(it.first) == q }
            ?: apps.firstOrNull { norm(it.first).startsWith(q) }
            ?: apps.firstOrNull { norm(it.first).contains(q) }
            ?: apps.minByOrNull { distance(norm(it.first), q) }?.takeIf { distance(norm(it.first), q) <= 2 }
            ?: return Result("I couldn't find an app called \"$name\".")
        val launch = pm.getLaunchIntentForPackage(hit.second) ?: return Result("I can't open ${hit.first}.")
        return Result("Opening ${hit.first}.", launch)
    }

    private fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    // ---- notifications ----

    private fun notifications(ctx: Context, app: String?): Result {
        if (!NotificationCollector.isEnabled(ctx)) {
            return Result(
                "To read your messages I need notification access. Turn on Phone Assistant on the next screen, then ask again.",
                Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            )
        }
        val since = System.currentTimeMillis() - 24 * 3600_000L
        val notes = NotificationCollector.recent(app, since).takeLast(40)
        if (notes.isEmpty()) {
            return Result(if (app != null) "No new $app notifications since I started listening." else "No new notifications since I started listening.")
        }
        val text = notes.joinToString("\n") { "[${it.app}] ${it.title}: ${it.text}".take(300) }
        return Result(
            "Here's what you missed:",
            summarize = "Summarize these phone notifications in a few short sentences. Group by person or app, " +
                "mention anything that needs a reply. Notifications:\n$text"
        )
    }

    // ---- messages ----

    private fun message(ctx: Context, c: Command): Result {
        val who = c.text.orEmpty()
        val whatsapp = c.type == Type.WHATSAPP
        val verb = if (whatsapp) "whatsapp" else "text"
        val body = c.message ?: return Result("What should the message to $who say?", answerPrefix = "$verb $who saying ")
        val digits = who.replace(Regex("[\\s-]"), "")
        val (name, number) = if (digits.matches(Regex("\\+?\\d{3,15}"))) who to digits else {
            if (!granted(ctx, Manifest.permission.READ_CONTACTS)) {
                return Result("I need access to your contacts to message $who.", permissions = listOf(Manifest.permission.READ_CONTACTS))
            }
            findContact(ctx, who) ?: return Result("I couldn't find \"$who\" in your contacts.")
        }
        val text = body.replaceFirstChar { it.uppercase() }
        if (!whatsapp) {
            return Result(
                "Your text to $name is ready. Tap send.",
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text)
            )
        }
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${international(number)}&text=" + Uri.encode(text))
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(ctx, it) }
        return Result(
            if (pkg != null) "WhatsApp is open with your message to $name. Tap send." else "WhatsApp isn't installed, so I opened it in the browser.",
            Intent(Intent.ACTION_VIEW, uri).apply { if (pkg != null) setPackage(pkg) }
        )
    }

    /** 0300 1234567 → 923001234567 (WhatsApp needs the country code, without +). */
    internal fun international(number: String): String {
        val d = number.filter { it.isDigit() || it == '+' }
        return when {
            d.startsWith("+") -> d.drop(1)
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") && d.length == 11 -> "92" + d.drop(1)
            d.length == 10 && d.startsWith("3") -> "92$d"
            else -> d
        }
    }

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    // ---- volume / brightness / ringer ----

    private fun volume(ctx: Context, level: String): Result {
        val am = ctx.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        fun pct() = am.getStreamVolume(stream) * 100 / max
        val flags = AudioManager.FLAG_SHOW_UI
        when (level) {
            "up" -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags) }
            "down" -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags) }
            "max" -> am.setStreamVolume(stream, max, flags)
            "min" -> am.setStreamVolume(stream, 1, flags)
            "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
            "status" -> return Result("Media volume is at ${pct()}%.")
            else -> am.setStreamVolume(stream, (level.toInt() * max + 50) / 100, flags)
        }
        return Result(if (level == "mute") "Media muted." else "Media volume ${pct()}%.")
    }

    private fun brightness(ctx: Context, level: String): Result {
        val cr = ctx.contentResolver
        val current = AndroidSettings.System.getInt(cr, AndroidSettings.System.SCREEN_BRIGHTNESS, 128)
        if (level == "status") return Result("Brightness is at ${current * 100 / 255}%.")
        if (!AndroidSettings.System.canWrite(ctx)) {
            return Result(
                "To change brightness I need the \"Modify system settings\" permission. Turn it on, then ask again.",
                Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            )
        }
        val target = when (level) {
            "up" -> current + 64
            "down" -> current - 64
            "max" -> 255
            "min", "mute" -> 10
            "unmute" -> current
            else -> level.toInt() * 255 / 100
        }.coerceIn(5, 255)
        AndroidSettings.System.putInt(cr, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        AndroidSettings.System.putInt(cr, AndroidSettings.System.SCREEN_BRIGHTNESS, target)
        return Result("Brightness ${target * 100 / 255}%.")
    }

    private fun ringer(ctx: Context, mode: String): Result {
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Switching to or from silent needs Do Not Disturb access on Android 7+.
        if ((mode == "silent" || am.ringerMode == AudioManager.RINGER_MODE_SILENT) && !nm.isNotificationPolicyAccessGranted) {
            return Result(
                "To change silent mode I need Do Not Disturb access. Turn on Phone Assistant on the next screen, then ask again.",
                Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            )
        }
        return try {
            am.ringerMode = when (mode) {
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "normal" -> AudioManager.RINGER_MODE_NORMAL
                else -> AudioManager.RINGER_MODE_SILENT
            }
            Result(when (mode) { "vibrate" -> "Phone is on vibrate."; "normal" -> "Ringer is back on."; else -> "Phone is on silent." })
        } catch (e: SecurityException) {
            Result("Android didn't let me change the sound mode.", Intent(AndroidSettings.ACTION_SOUND_SETTINGS))
        }
    }

    /** Android 10+ doesn't let apps flip these switches, so open the right panel instead. */
    private fun setting(which: String): Result {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return when (which) {
            "wifi" -> Result(
                "Android only lets you switch Wi-Fi yourself, so here's the Wi-Fi switch.",
                Intent(if (q) AndroidSettings.Panel.ACTION_WIFI else AndroidSettings.ACTION_WIFI_SETTINGS)
            )
            "bluetooth" -> Result("Here's Bluetooth: tap the switch.", Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
            "data" -> Result(
                "Here are your mobile data and Wi-Fi switches.",
                Intent(if (q) AndroidSettings.Panel.ACTION_INTERNET_CONNECTIVITY else AndroidSettings.ACTION_DATA_ROAMING_SETTINGS)
            )
            "airplane" -> Result("Here's airplane mode.", Intent(AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS))
            "hotspot" -> Result("Here are the hotspot settings.", Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS))
            else -> Result("Here are the location settings.", Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}
