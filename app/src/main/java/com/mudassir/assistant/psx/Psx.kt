package com.mudassir.assistant.psx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Live PSX data from the public data portal (the same source as the psx-mobile app). */
object Psx {

    private const val BASE = "https://dps.psx.com.pk"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private var indexCache: Pair<Long, Map<String, PsxParse.IndexQuote>>? = null

    val INDEX_NAMES = mapOf("KSE100" to "KSE-100", "KSE30" to "KSE-30", "KMI30" to "KMI-30", "ALLSHR" to "All Share", "KMIALLSHR" to "KMI All Share")

    private fun get(path: String): String = client.newCall(
        Request.Builder().url(BASE + path).header("User-Agent", UA).header("Accept-Language", "en-US,en;q=0.9").build()
    ).execute().use { res ->
        if (!res.isSuccessful) throw IOException("PSX returned ${res.code}")
        res.body!!.string()
    }

    /** Price of one stock, or null if PSX doesn't list that symbol. */
    suspend fun quote(symbol: String): PsxParse.Quote? = withContext(Dispatchers.IO) {
        try {
            PsxParse.company(symbol, get("/company/$symbol"))
        } catch (e: IOException) {
            if (e.message?.contains("404") == true) null else throw e
        }
    }

    suspend fun indices(): Map<String, PsxParse.IndexQuote> = withContext(Dispatchers.IO) {
        indexCache?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.second
            ?: PsxParse.indices(get("/indices")).also { indexCache = System.currentTimeMillis() to it }
    }
}
