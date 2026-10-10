package com.mudassir.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The optional online brain: Google Gemini's free tier, streamed with server-sent events.
 * Your own free API key from Google AI Studio is entered once in Settings.
 */
object GeminiBrain {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun reply(apiKey: String, model: String, turns: List<Turn>, onText: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val system = turns.firstOrNull { it.role == Turn.SYSTEM }?.text
            val contents = JSONArray()
            turns.filter { it.role != Turn.SYSTEM }.forEach { t ->
                contents.put(
                    JSONObject()
                        .put("role", if (t.role == Turn.ASSISTANT) "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", t.text)))
                )
            }
            val body = JSONObject().put("contents", contents)
            if (system != null) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            body.put("generationConfig", JSONObject().put("maxOutputTokens", 1024).put("temperature", 0.7))

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val out = StringBuilder()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw BrainException(errorMessage(res.code, res.body?.string()))
                val source = res.body!!.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                    val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        if (p.optBoolean("thought", false)) continue // skip "thinking" text
                        val text = p.optString("text", "")
                        if (text.isNotEmpty()) {
                            out.append(text)
                            onText(text)
                        }
                    }
                }
            }
            if (out.isEmpty()) throw BrainException("Gemini sent an empty reply.")
            out.toString().trim()
        }

    private fun errorMessage(code: Int, body: String?): String {
        val msg = runCatching { JSONObject(body ?: "").getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            400, 403 -> "Gemini rejected the API key. Check it in Settings."
            404 -> "Gemini model not found. Check the model name in Settings."
            429 -> "Gemini's free limit is used up for now. Using the offline brain."
            else -> "Gemini error $code" + (msg?.let { ": $it" } ?: "")
        }
    }
}
