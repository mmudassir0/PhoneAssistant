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
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.KeyEvent
import com.mudassir.assistant.commands.Calculator
import com.mudassir.assistant.commands.PsxSymbols
import com.mudassir.assistant.psx.Psx
import com.mudassir.assistant.commands.PrayerTimes
import com.mudassir.assistant.commands.UnitConverter
import java.time.LocalDate
import java.time.LocalTime
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
        /** A prompt for the AI to answer (summaries, translations); its answer is shown after [reply]. */
        val aiPrompt: String? = null,
        /** A question was asked ("what time?"): the next answer is read as this prefix + answer. */
        val answerPrefix: String? = null,
        /** Clear the conversation. */
        val newChat: Boolean = false,
        /** Don't read the reply aloud (e.g. "play music": speaking would talk over it). */
        val quiet: Boolean = false,
        /** Take a photo and ask Gemini about it: "describe", "read" or "translate:<language>". */
        val vision: String? = null
    )

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US)

    /**
     * @param routed true when the AI picked this command from free speech: anything that would act
     * without a tap (a phone call) only prepares it instead, so a misunderstanding can't call anyone.
     */
    suspend fun run(ctx: Context, c: Command, online: Boolean, routed: Boolean = false): Result = when (c.type) {
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

        Type.CALL -> call(ctx, c.text.orEmpty(), routed)
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

        Type.CALC -> try {
            Result("${Calculator.format(Calculator.eval(c.text!!))}")
        } catch (e: IllegalArgumentException) {
            Result(e.message ?: "I couldn't work that out.")
        }

        Type.CONVERT -> convert(c, online)
        Type.CALENDAR -> calendar(ctx, c.text ?: "today")
        Type.CALENDAR_ADD -> calendarAdd(c)
        Type.NOTE_ADD -> {
            val added = Notes.add(c.text!!, c.message!!)
            val list = Notes.key(c.text!!)
            Result(if (list == "notes") "Noted." else "Added ${added.joinToString(" and ")} to your $list list.")
        }
        Type.NOTE_READ -> {
            val list = Notes.key(c.text!!)
            val items = Notes.items(list)
            Result(
                when {
                    items.isEmpty() -> "Your $list list is empty."
                    list == "notes" -> "Your notes:\n" + items.joinToString("\n") { "• $it" }
                    else -> "Your $list list has ${items.size} item${if (items.size == 1) "" else "s"}:\n" + items.joinToString("\n") { "• $it" }
                }
            )
        }
        Type.NOTE_REMOVE -> {
            val gone = Notes.remove(c.text!!, c.message!!)
            Result(if (gone != null) "Removed $gone." else "I couldn't find \"${c.message}\" on your ${Notes.key(c.text!!)} list.")
        }
        Type.NOTE_CLEAR -> {
            Notes.clear(c.text!!)
            Result("Cleared your ${Notes.key(c.text!!)} list.")
        }
        Type.REMEMBER -> {
            Notes.remember(c.message!!)
            Result("Got it, I'll remember that.")
        }
        Type.RECALL -> recall(c.text)
        Type.FORGET -> {
            val n = Notes.forget(c.text!!)
            Result(if (n == 0) "I don't have anything saved about that." else "Forgotten.")
        }
        Type.SET_NAME -> {
            Notes.setName(c.text!!)
            Result("Nice to meet you, ${Notes.name.value}.")
        }
        Type.MEDIA -> media(ctx, c.text ?: "play")
        Type.NAVIGATE -> {
            val dest = c.text.orEmpty()
            val saved = Tracker.place(dest)
            val target = if (saved != null) "${saved.lat},${saved.lng}" else dest
            Result(
                "Starting directions to $dest.",
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=" + Uri.encode(target)))
            )
        }
        Type.WHERE_AM_I -> whereAmI(ctx)
        Type.SHARE_LOCATION -> shareLocation(ctx, c.text.orEmpty())
        Type.CAMERA -> camera(c.text ?: "photo")
        Type.TRANSLATE -> Result(
            "",
            aiPrompt = "Translate into ${c.text!!.replaceFirstChar { it.uppercase() }}: \"${c.message}\". Reply with only the translation. " +
                "If it isn't written in Latin letters, add the pronunciation in Latin letters in brackets."
        )
        Type.PRAYER -> prayer(ctx, c.text ?: "all")
        Type.BRIEFING -> briefing(ctx, online)
        Type.CONTACT_INFO -> {
            val who = c.text.orEmpty()
            if (!granted(ctx, Manifest.permission.READ_CONTACTS)) {
                Result("I need access to your contacts to look up $who.", permissions = listOf(Manifest.permission.READ_CONTACTS))
            } else {
                val match = findContact(ctx, who)
                Result(if (match == null) "I couldn't find \"$who\" in your contacts." else "${match.first}'s number is ${match.second}.")
            }
        }

        Type.EXPENSE_ADD -> {
            Tracker.addExpense(c.value, c.text!!)
            val month = Tracker.expensesIn("this month").sumOf { it.amount }
            Result("Logged ${rs(c.value)} for ${c.text}. This month so far: ${rs(month)}.")
        }
        Type.EXPENSE_REPORT -> expenseReport(c.text ?: "this month", c.message)
        Type.STOCK_QUOTE -> stockQuote(c, online)
        Type.STOCK_INDEX -> stockIndex(c.text ?: "KSE100", online)
        Type.PORTFOLIO_ADD -> portfolioAdd(c, online)
        Type.PORTFOLIO_SELL -> {
            val left = Tracker.sell(c.text!!, c.value)
            Result(
                when {
                    left == null -> "You don't have ${c.text} in your portfolio."
                    left == 0.0 -> "Removed ${c.text} from your portfolio."
                    else -> "Done. You now hold ${Calculator.format(left)} ${c.text}."
                }
            )
        }
        Type.PORTFOLIO -> portfolio(online)
        Type.VISION -> Result("", vision = c.text)
        Type.SAVE_PLACE -> savePlace(ctx, c.text!!)
        Type.REMIND_AT_PLACE -> remindAtPlace(ctx, c.text!!, c.message!!)

        Type.HELP -> Result(
            "Here's what I can do.\n" +
                "• Phone: alarms, timers, reminders, calls, WhatsApp and SMS, apps and websites, camera, music, volume, brightness, silent mode, flashlight.\n" +
                "• Organize: your calendar, shopping and other lists, notes, and things to remember (\"remember I parked on level 2\").\n" +
                "• Info: maths and conversions, prayer times, weather, news, PSX prices and your portfolio, directions, where you are, contact numbers, translations.\n" +
                "• Money: \"spent 500 on petrol\", \"how much did I spend this month?\"\n" +
                "• Camera: \"what is this?\", \"read this\" (online mode).\n" +
                "• Urdu works too: \"6 baje alarm laga do\", \"Ammi ko call karo\". Routines like \"good night\" run several steps.\n" +
                "• Say \"good morning\" for your daily briefing, or just ask me anything."
        )

        Type.UNSUPPORTED -> Result(
            "I can't do that on your phone yet. I can: set alarms, timers and reminders; call or message contacts " +
                "on WhatsApp or SMS; open apps and websites; search Google or YouTube; control volume, brightness, " +
                "silent mode and the flashlight; open Wi-Fi, Bluetooth and data switches; and tell the time, date, " +
                "battery, weather and news."
        )
    }

    // ---- calls ----

    private fun call(ctx: Context, who: String, routed: Boolean = false): Result {
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
        if (routed) return Result("Here's $name's number. Tap call.", Intent(Intent.ACTION_DIAL, uri))
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
            aiPrompt = "Summarize these phone notifications in a few short sentences. Group by person or app, " +
                "mention anything that needs a reply. Notifications:\n$text"
        )
    }

    // ---- conversions ----

    private suspend fun convert(c: Command, online: Boolean): Result {
        val from = c.text!!
        val to = c.message!!
        if (UnitConverter.currency(from) != null) {
            if (!online) return Result("I need an internet connection for exchange rates.")
            val rate = ExchangeRates.rate(from, to) ?: return Result("I couldn't get the exchange rate right now.")
            return Result("${Calculator.format(c.value)} $from is about ${Calculator.format(c.value * rate)} $to.")
        }
        val r = UnitConverter.convert(c.value, from, to) ?: return Result("I can't convert ${UnitConverter.name(from)} to ${UnitConverter.name(to)}.")
        val note = if (from.startsWith("marla") || to.startsWith("marla") || from.startsWith("kanal") || to.startsWith("kanal"))
            " (using 1 marla = 225 sq ft, common in Lahore)" else ""
        return Result("${Calculator.format(c.value)} ${UnitConverter.name(from)} is ${Calculator.format(r)} ${UnitConverter.name(to)}$note.")
    }

    // ---- calendar ----

    private fun calendar(ctx: Context, range: String): Result {
        if (!CalendarEvents.allowed(ctx)) {
            return Result("I need access to your calendar to check that.", permissions = listOf(Manifest.permission.READ_CALENDAR))
        }
        val today = LocalDate.now()
        val (from, days, label) = when (range) {
            "tomorrow" -> Triple(today.plusDays(1), 1L, "tomorrow")
            "week" -> Triple(today, 7L, "this week")
            else -> Triple(today, 1L, "today")
        }
        val now = LocalDateTime.now()
        val events = CalendarEvents.events(ctx, from, days).filter { range != "today" || it.allDay || it.start.isAfter(now.minusHours(1)) }
        if (events.isEmpty()) return Result("Nothing on your calendar $label.")
        val lines = events.take(8).joinToString("\n") { e ->
            val time = if (e.allDay) "All day" else e.start.format(TIME)
            val day = if (days > 1) e.start.format(DateTimeFormatter.ofPattern("EEE ", Locale.US)) else ""
            "• $day$time: ${e.title}" + (e.location?.let { " ($it)" } ?: "")
        }
        val count = if (events.size == 1) "1 event" else "${events.size} events"
        return Result("You have $count $label:\n$lines")
    }

    private fun calendarAdd(c: Command): Result {
        val title = c.text.orEmpty().replaceFirstChar { it.uppercase() }
        val at = c.at ?: return Result("When is $title?", answerPrefix = "add $title to my calendar ")
        val start = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Result(
            "Here's $title on ${at.format(DAY)} at ${at.format(TIME)}. Tap Save to add it.",
            Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3600_000)
        )
    }

    // ---- memory ----

    private fun recall(query: String?): Result {
        val name = Notes.name.value
        if (query != null && query.matches(Regex("name"))) {
            return Result(if (name != null) "Your name is $name." else "You haven't told me your name yet. Say \"my name is …\".")
        }
        if (query == null) {
            val all = Notes.memories.value
            if (all.isEmpty() && name == null) return Result("You haven't asked me to remember anything yet. Try \"remember I parked on level 2\".")
            val lines = (listOfNotNull(name?.let { "Your name is $it" }) + all.takeLast(10).map { Notes.secondPerson(it.text) })
                .joinToString("\n") { "• $it" }
            return Result("Here's what I remember:\n$lines")
        }
        val hits = Notes.recall(query)
        if (hits.isEmpty()) return Result("I don't have anything saved about $query.")
        return Result(hits.take(3).joinToString("\n") { Notes.secondPerson(it.text) + "." }.replace("..", "."))
    }

    // ---- music ----

    private fun media(ctx: Context, action: String): Result {
        val am = ctx.getSystemService(AudioManager::class.java)
        val code = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        val reply = when (action) { "pause" -> "Paused."; "next" -> "Next."; "previous" -> "Previous."; else -> "Playing." }
        return Result(reply, quiet = true)
    }

    // ---- location ----

    private suspend fun whereAmI(ctx: Context): Result {
        if (!Locator.allowed(ctx)) {
            return Result("I need location access to tell you where you are.", permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
        val loc = Locator.current(ctx) ?: return Result("I couldn't get your location. Check that location is turned on.")
        val place = Locator.address(ctx, loc)
        return Result(
            if (place != null) "You're near $place." else "You're at %.5f, %.5f.".format(Locale.US, loc.latitude, loc.longitude),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}"))
                .takeIf { place == null }
        )
    }

    private suspend fun shareLocation(ctx: Context, who: String): Result {
        if (!Locator.allowed(ctx)) {
            return Result("I need location access to share where you are.", permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
        val loc = Locator.current(ctx) ?: return Result("I couldn't get your location. Check that location is turned on.")
        val link = "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, loc.latitude, loc.longitude)
        val msg = Command(Type.WHATSAPP).also { it.text = who; it.message = "I'm here: $link" }
        return message(ctx, msg)
    }

    // ---- camera ----

    private fun camera(kind: String): Result = when (kind) {
        "video" -> Result("Opening the video camera.", Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))
        "selfie" -> Result(
            "Opening the front camera.",
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .putExtra("android.intent.extras.CAMERA_FACING", 1)
                .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        )
        else -> Result("Opening the camera.", Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    }

    // ---- prayer times ----

    /** Prayer times for where you are (or Lahore if location isn't allowed). */
    suspend fun prayerTimes(ctx: Context, date: LocalDate): Pair<Map<String, LocalTime>, String?> {
        val loc = if (Locator.allowed(ctx)) Locator.current(ctx) else null
        val lat = loc?.latitude ?: 31.5204
        val lng = loc?.longitude ?: 74.3587
        val offset = ZoneId.systemDefault().rules.getOffset(date.atStartOfDay(ZoneId.systemDefault()).toInstant()).totalSeconds / 3600.0
        if (loc != null) Settings(ctx).run { lastLat = loc.latitude; lastLng = loc.longitude }
        val note = if (loc == null) "for Lahore (allow location for your city)" else null
        return PrayerTimes.pakistan(lat, lng, offset).on(date) to note
    }

    private suspend fun prayer(ctx: Context, which: String): Result {
        val now = LocalDateTime.now()
        val (today, note) = prayerTimes(ctx, now.toLocalDate())
        val suffix = note?.let { " ($it)" } ?: ""
        return when (which) {
            "all" -> Result(
                "Prayer times today$suffix:\n" + today.filterKeys { it != "Sunrise" }.entries.joinToString("\n") { (k, v) -> "• $k ${v?.format(TIME) ?: "-"}" }
            )
            "next" -> {
                val upcoming = today.entries.filter { it.key != "Sunrise" && it.value != null && it.value!!.isAfter(now.toLocalTime()) }.firstOrNull()
                if (upcoming != null) {
                    val mins = Duration.between(now.toLocalTime(), upcoming.value).toMinutes()
                    Result("Next is ${upcoming.key} at ${upcoming.value!!.format(TIME)}, in ${CommandParser.describe(Duration.ofMinutes(mins))}$suffix.")
                } else {
                    val fajr = prayerTimes(ctx, now.toLocalDate().plusDays(1)).first["Fajr"]
                    Result("Next is Fajr tomorrow at ${fajr?.format(TIME)}$suffix.")
                }
            }
            else -> {
                val t = today[which]
                val passed = t != null && t.isBefore(now.toLocalTime())
                if (passed) {
                    val tomorrow = prayerTimes(ctx, now.toLocalDate().plusDays(1)).first[which]
                    Result("$which was at ${t!!.format(TIME)} today. Tomorrow it's at ${tomorrow?.format(TIME)}$suffix.")
                } else Result("$which is at ${t?.format(TIME) ?: "-"} today$suffix.")
            }
        }
    }

    // ---- briefing ----

    private suspend fun briefing(ctx: Context, online: Boolean): Result {
        val now = LocalDateTime.now()
        val name = Notes.name.value?.let { ", $it" } ?: ""
        val hello = when (now.hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello" }
        val parts = mutableListOf("$hello$name. It's ${now.format(TIME)} on ${now.format(DAY)}.")
        if (online) runCatching { OnlineInfo.weather(Settings(ctx).weatherCity) }.getOrNull()?.let { parts += it }
        prayer(ctx, "next").reply.let { parts += it }
        if (CalendarEvents.allowed(ctx)) {
            val events = CalendarEvents.events(ctx, now.toLocalDate(), 1).filter { it.allDay || it.start.isAfter(now) }
            parts += if (events.isEmpty()) "Your calendar is clear today."
            else "First up: ${events.first().title}" + (if (events.first().allDay) "" else " at ${events.first().start.format(TIME)}") +
                (if (events.size > 1) ", plus ${events.size - 1} more." else ".")
        }
        val reminders = Reminders.upcoming(ctx).filter {
            java.time.Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalDate() == now.toLocalDate()
        }
        if (reminders.isNotEmpty()) parts += "Reminders today: " + reminders.joinToString(", ") { it.text } + "."
        if (online) runCatching { OnlineInfo.headlines(3) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            parts += "In the news: " + it.joinToString("; ") + "."
        }
        return Result(parts.joinToString("\n\n"))
    }

    // ---- money ----

    private fun rs(v: Double) = "Rs " + Calculator.format(v)

    private fun expenseReport(range: String, category: String?): Result {
        val list = Tracker.expensesIn(range, category)
        val label = if (range == "all") "in total" else range
        if (list.isEmpty()) {
            return Result(
                if (category != null) "No spending on $category $label."
                else "No expenses logged $label. Say \"spent 500 on petrol\" to add one."
            )
        }
        val total = list.sumOf { it.amount }
        if (category != null) {
            return Result("You spent ${rs(total)} on $category $label (${list.size} time${if (list.size == 1) "" else "s"}).")
        }
        val lines = list.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } }.entries
            .sortedByDescending { it.value }.take(6)
            .joinToString("\n") { "• ${it.key.replaceFirstChar { c -> c.uppercase() }}: ${rs(it.value)}" }
        return Result("You spent ${rs(total)} $label:\n$lines")
    }

    // ---- stocks ----

    private fun move(change: Double?, pct: Double?): String = when {
        change == null -> "no change data"
        change > 0 -> "up ${Calculator.format(change)}" + (pct?.let { " (${Calculator.format(kotlin.math.abs(it))}%)" } ?: "")
        change < 0 -> "down ${Calculator.format(-change)}" + (pct?.let { " (${Calculator.format(kotlin.math.abs(it))}%)" } ?: "")
        else -> "unchanged"
    }

    private suspend fun stockQuote(c: Command, online: Boolean): Result {
        val sym = c.text!!
        if (!online) return Result("I need internet for live PSX prices.")
        val q = try {
            Psx.quote(sym)
        } catch (e: Exception) {
            return Result("I couldn't reach PSX right now.")
        }
        if (q == null) {
            // "Gold price" isn't a PSX stock: answer it as a normal question instead.
            return if (!PsxSymbols.isKnown(sym)) Result("", aiPrompt = c.message) else Result("I couldn't find $sym on PSX.")
        }
        val name = q.name?.let { "$it ($sym)" } ?: sym
        return Result("$name is at ${rs(q.close!!)}, ${move(q.change, q.changePct)} today." + (q.asOf?.let { "\n$it" } ?: ""))
    }

    private suspend fun stockIndex(code: String, online: Boolean): Result {
        if (!online) return Result("I need internet for live PSX data.")
        val all = try {
            Psx.indices()
        } catch (e: Exception) {
            return Result("I couldn't reach PSX right now.")
        }
        val codes = if (code == "KSE100") listOf("KSE100", "KMI30") else listOf(code)
        val lines = codes.mapNotNull { k ->
            val i = all[k] ?: return@mapNotNull null
            val ch = i.change
            val pct = i.changePct?.let { " (${Calculator.format(kotlin.math.abs(it))}%)" } ?: ""
            val moved = when {
                ch == null -> "no change data"
                ch > 0 -> "up ${Calculator.format(ch)} points$pct"
                ch < 0 -> "down ${Calculator.format(-ch)} points$pct"
                else -> "unchanged"
            }
            "${Psx.INDEX_NAMES[k] ?: k} is at ${Calculator.format(i.current ?: 0.0)}, $moved today."
        }
        return Result(if (lines.isEmpty()) "I couldn't find that index on PSX." else lines.joinToString("\n"))
    }

    private suspend fun portfolioAdd(c: Command, online: Boolean): Result {
        val sym = c.text!!
        val shares = c.value
        val price = c.message?.toDoubleOrNull()
            ?: (if (online) runCatching { Psx.quote(sym)?.close }.getOrNull() else null)
            ?: return Result(
                "At what price did you buy $sym?",
                answerPrefix = "i bought ${Calculator.format(shares).replace(",", "")} shares of ${sym.lowercase()} at "
            )
        if (c.message == null && online) {
            // No price given: use today's price, and say so.
            Tracker.buy(sym, shares, price)
            return Result("Added ${Calculator.format(shares)} $sym at today's price, ${rs(price)}. Say \"I bought … at …\" next time to use your own price.")
        }
        Tracker.buy(sym, shares, price)
        val h = Tracker.holdings.value.first { it.symbol == sym }
        return Result("Added ${Calculator.format(shares)} $sym at ${rs(price)}. You now hold ${Calculator.format(h.shares)}, average ${rs(h.avgPrice)}.")
    }

    private suspend fun portfolio(online: Boolean): Result {
        val holdings = Tracker.holdings.value
        if (holdings.isEmpty()) return Result("Your portfolio is empty. Say \"I bought 100 shares of OGDC at 120\" to add a stock.")
        if (!online) {
            return Result("You hold:\n" + holdings.joinToString("\n") { "• ${it.symbol}: ${Calculator.format(it.shares)} at ${rs(it.avgPrice)}" } +
                "\nConnect to the internet for live prices.")
        }
        var value = 0.0
        var cost = 0.0
        var today = 0.0
        val lines = holdings.take(15).map { h ->
            val q = runCatching { Psx.quote(h.symbol) }.getOrNull()
            val price = q?.close
            cost += h.shares * h.avgPrice
            if (price == null) return@map "• ${h.symbol}: price unavailable"
            value += h.shares * price
            today += h.shares * (q.change ?: 0.0)
            val pl = h.shares * (price - h.avgPrice)
            val sign = if (pl >= 0) "+" else "-"
            "• ${h.symbol}: ${Calculator.format(h.shares)} × ${rs(price)} = ${rs(h.shares * price)} ($sign${rs(kotlin.math.abs(pl))})"
        }
        val pl = value - cost
        val pct = if (cost > 0) pl / cost * 100 else 0.0
        val summary = "Your portfolio is worth ${rs(value)}, " +
            (if (pl >= 0) "up ${rs(pl)}" else "down ${rs(-pl)}") + " (${Calculator.format(kotlin.math.abs(pct))}%) overall" +
            (if (today != 0.0) ", " + (if (today > 0) "up ${rs(today)}" else "down ${rs(-today)}") + " today." else ".")
        return Result(summary + "\n" + lines.joinToString("\n"))
    }

    // ---- places ----

    private suspend fun savePlace(ctx: Context, name: String): Result {
        if (!Locator.allowed(ctx)) {
            return Result("I need location access to save this place.", permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
        val loc = Locator.current(ctx) ?: return Result("I couldn't get your location. Check that location is turned on.")
        Tracker.savePlace(name, loc.latitude, loc.longitude)
        return Result("Saved this spot as $name. Now you can say \"take me to $name\" or \"remind me when I reach $name to …\".")
    }

    private fun remindAtPlace(ctx: Context, name: String, task: String): Result {
        val place = Tracker.place(name)
            ?: return Result("I don't know where your $name is yet. When you're there, say \"save this place as $name\", then ask again.")
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return Result("I need precise location to know when you arrive.", permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            return Result(
                "To remind you when you arrive, even with the app closed, choose \"Allow all the time\" for location on the next screen.",
                permissions = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            )
        }
        val r = Tracker.PlaceReminder(System.currentTimeMillis().toString(), place.name, task)
        Tracker.addPlaceReminder(r)
        Geofences.add(ctx, r, place)
        return Result("Okay, I'll remind you to $task when you reach ${place.name}.")
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
