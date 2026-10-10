package com.mudassir.assistant.actions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** The phone's location (only when you've allowed it). */
object Locator {

    fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fresh fix if possible (up to 8 s), else the last known one, else null. */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!allowed(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000) return last
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && providers.isNotEmpty()) {
            val fresh = withTimeoutOrNull(8000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(providers.first(), signal, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                }
            }
            if (fresh != null) return fresh
        }
        return last
    }

    /** "Gulberg III, Lahore" for a location, or null (needs internet on most phones). */
    @Suppress("DEPRECATION")
    suspend fun address(ctx: Context, loc: Location): String? = withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(ctx, Locale.US).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.thoroughfare?.let { t -> listOfNotNull(a.subThoroughfare, t).joinToString(" ") }, a.subLocality, a.locality ?: a.subAdminArea)
                    .distinct().joinToString(", ").ifEmpty { a.getAddressLine(0) }
            }
        }.getOrNull()
    }
}

/** Reads events from the phone's calendars (Google Calendar etc.). */
object CalendarEvents {

    data class Event(val title: String, val start: LocalDateTime, val allDay: Boolean, val location: String?)

    fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun events(ctx: Context, from: LocalDate, days: Long): List<Event> {
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = from.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start)
            android.content.ContentUris.appendId(it, end)
        }.build()
        val out = mutableListOf<Event>()
        ctx.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(2) == 1
                // All-day events are stored in UTC.
                val begin = Instant.ofEpochMilli(c.getLong(1)).atZone(if (allDay) ZoneId.of("UTC") else zone).toLocalDateTime()
                out += Event(c.getString(0) ?: "(No title)", begin, allDay, c.getString(3)?.ifBlank { null })
            }
        }
        return out
    }
}

/** Live exchange rates (open.er-api.com, free, no key). */
object ExchangeRates {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val cache = mutableMapOf<String, Pair<Long, JSONObject>>()

    suspend fun rate(from: String, to: String): Double? = withContext(Dispatchers.IO) {
        val rates = cache[from]?.takeIf { System.currentTimeMillis() - it.first < 3600_000 }?.second ?: runCatching {
            client.newCall(Request.Builder().url("https://open.er-api.com/v6/latest/$from").build()).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                JSONObject(res.body!!.string()).optJSONObject("rates")
            }
        }.getOrNull()?.also { cache[from] = System.currentTimeMillis() to it }
        rates?.optDouble(to)?.takeIf { !it.isNaN() }
    }
}
