package com.mudassir.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves conversations as small JSON files so chats survive closing the app. */
object ChatStore {

    data class Summary(val id: String, val title: String, val updated: Long, val count: Int)

    private lateinit var dir: File

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "chats").apply { mkdirs() }
    }

    fun save(id: String, messages: List<ChatMessage>) {
        val done = messages.filter { !it.pending }
        val file = File(dir, "$id.json")
        if (done.none { it.fromUser }) {
            file.delete() // nothing worth keeping
            return
        }
        val arr = JSONArray()
        done.forEach {
            arr.put(JSONObject().put("id", it.id).put("user", it.fromUser).put("text", it.text).put("source", it.source ?: ""))
        }
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(JSONObject().put("updated", System.currentTimeMillis()).put("messages", arr).toString())
        tmp.renameTo(file)
    }

    fun load(id: String): List<ChatMessage> = runCatching {
        val arr = JSONObject(File(dir, "$id.json").readText()).getJSONArray("messages")
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            ChatMessage(it.getLong("id"), it.getBoolean("user"), it.getString("text"), it.optString("source").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    /** Newest first. */
    fun list(): List<Summary> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("messages")
            val firstUser = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getBoolean("user") }
            Summary(f.nameWithoutExtension, firstUser?.getString("text")?.take(80) ?: "Chat", o.optLong("updated", f.lastModified()), arr.length())
        }.getOrNull()
    }.sortedByDescending { it.updated }

    fun delete(id: String) = File(dir, "$id.json").delete()

    fun deleteAll() = dir.listFiles()?.forEach { it.delete() }
}
