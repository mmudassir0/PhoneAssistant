package com.mudassir.assistant.actions

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** News headlines (Google News RSS) and weather (Open-Meteo). Both free, no API key. */
object OnlineInfo {

    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private fun get(url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
        it.body!!.string()
    }

    suspend fun headlines(count: Int = 5): List<String> = withContext(Dispatchers.IO) {
        val xml = get("https://news.google.com/rss?hl=en-PK&gl=PK&ceid=PK:en")
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val titles = mutableListOf<String>()
        var inItem = false
        while (parser.next() != XmlPullParser.END_DOCUMENT && titles.size < count) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name == "item") inItem = true
                else if (inItem && parser.name == "title") {
                    // "Headline - Source": keep the headline.
                    titles += parser.nextText().substringBeforeLast(" - ").trim()
                    inItem = false
                }
            }
        }
        titles
    }

    suspend fun weather(city: String): String = withContext(Dispatchers.IO) {
        val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8")))
        val place = geo.optJSONArray("results")?.optJSONObject(0)
            ?: return@withContext "I couldn't find \"$city\". Change the weather city in Settings."
        val lat = place.getDouble("latitude")
        val lon = place.getDouble("longitude")
        val f = JSONObject(get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&forecast_days=1" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
        ))
        val cur = f.getJSONObject("current")
        val daily = f.getJSONObject("daily")
        val temp = cur.getDouble("temperature_2m").roundToInt()
        val feels = cur.getDouble("apparent_temperature").roundToInt()
        val hi = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
        val lo = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
        val rain = daily.getJSONArray("precipitation_probability_max").optInt(0, -1)
        buildString {
            append("${place.getString("name")}: ${describe(cur.getInt("weather_code"))}, $temp°C")
            if (feels != temp) append(" (feels like $feels°)")
            append(". Today $lo° to $hi°")
            if (rain >= 0) append(", ${rain}% chance of rain")
            append(".")
        }
    }

    /** WMO weather codes used by Open-Meteo. */
    private fun describe(code: Int) = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 66 -> "rain"
        65, 67 -> "heavy rain"
        71, 73, 75, 77 -> "snow"
        80, 81 -> "rain showers"
        82 -> "heavy showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "weather code $code"
    }
}
