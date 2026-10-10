package com.mudassir.assistant.actions

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Your lists ("shopping", "grocery", "notes"…) and the things you asked me to remember. Stored on the phone. */
object Notes {

    data class Memory(val text: String, val added: Long)

    private lateinit var file: File
    private val _lists = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val lists: StateFlow<Map<String, List<String>>> = _lists.asStateFlow()
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()
    private val _name = MutableStateFlow<String?>(null)
    val name: StateFlow<String?> = _name.asStateFlow()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "notes.json")
        runCatching {
            val o = JSONObject(file.readText())
            val l = o.optJSONObject("lists") ?: JSONObject()
            _lists.value = l.keys().asSequence().associateWith { k ->
                val arr = l.getJSONArray(k)
                (0 until arr.length()).map { arr.getString(it) }
            }
            val m = o.optJSONArray("memories") ?: JSONArray()
            _memories.value = (0 until m.length()).map { m.getJSONObject(it) }.map { Memory(it.getString("text"), it.optLong("added")) }
            _name.value = o.optString("name").ifEmpty { null }
        }
    }

    /** "groceries" and "grocery" are the same list; "note" is "notes". */
    fun key(list: String): String {
        val k = list.trim().lowercase().removeSuffix(" list")
        return when {
            k == "note" || k == "notes" -> "notes"
            k == "to do" || k == "todo" || k == "to-do" || k == "to dos" || k == "todos" -> "to-do"
            k.endsWith("ies") -> k.dropLast(3) + "y"
            k.endsWith("s") && !k.endsWith("ss") -> k.dropLast(1)
            else -> k
        }
    }

    /** Adds items; "eggs and bread" or "eggs, bread" become separate items (not for notes). */
    fun add(list: String, item: String): List<String> {
        val k = key(list)
        val items = if (k == "notes") listOf(item.trim()) else item.split(Regex(",| and ")).map { it.trim() }.filter { it.isNotEmpty() }
        update { it + (k to ((it[k] ?: emptyList()) + items.map { s -> s.replaceFirstChar { c -> c.uppercase() } })) }
        return items
    }

    /** Removes the item that best matches [item]; returns its text, or null. */
    fun remove(list: String, item: String): String? {
        val k = key(list)
        val current = _lists.value[k] ?: return null
        val hit = current.firstOrNull { it.equals(item, true) } ?: current.firstOrNull { it.contains(item, true) || item.contains(it, true) }
            ?: return null
        update { it + (k to current - hit) }
        return hit
    }

    fun removeAt(list: String, index: Int) {
        val current = _lists.value[list] ?: return
        if (index !in current.indices) return
        update { it + (list to current.filterIndexed { i, _ -> i != index }) }
    }

    fun clear(list: String) = update { it - key(list) }

    fun items(list: String) = _lists.value[key(list)].orEmpty()

    // ---- memory ----

    fun remember(fact: String) {
        val f = fact.trim().replaceFirstChar { it.uppercase() }
        _memories.value = _memories.value.filterNot { it.text.equals(f, true) } + Memory(f, System.currentTimeMillis())
        save()
    }

    /** Best-matching memories for a question like "where did I park" or "car number". */
    fun recall(query: String): List<Memory> {
        val words = significant(query)
        if (words.isEmpty()) return emptyList()
        return _memories.value
            .map { m -> m to significant(m.text).count { w -> words.any { q -> w.startsWith(q.take(4)) || q.startsWith(w.take(4)) } } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }.thenByDescending { it.first.added })
            .map { it.first }
    }

    /** Forgets memories matching [query]; returns how many. */
    fun forget(query: String): Int {
        val hits = recall(query).toSet()
        _memories.value = _memories.value - hits
        save()
        return hits.size
    }

    fun forgetAt(index: Int) {
        _memories.value = _memories.value.filterIndexed { i, _ -> i != index }
        save()
    }

    fun clearName() {
        _name.value = null
        save()
    }

    fun setName(n: String) {
        _name.value = n.trim().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        save()
    }

    /** "I parked on level 2" → "You parked on level 2", for reading back. */
    fun secondPerson(text: String): String {
        val swaps = mapOf("i" to "you", "i'm" to "you're", "im" to "you're", "my" to "your", "me" to "you",
            "mine" to "yours", "am" to "are", "i've" to "you've", "myself" to "yourself", "was" to "was")
        val out = text.split(" ").joinToString(" ") { w -> swaps[w.lowercase()] ?: w }
        return out.replaceFirstChar { it.uppercase() }
    }

    private val STOP = setOf("i", "my", "me", "the", "a", "an", "is", "was", "are", "did", "do", "where", "what", "when",
        "that", "to", "of", "on", "in", "at", "it", "you", "your", "remember", "have", "has", "and", "for")

    private fun significant(s: String) =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.length > 1 && it !in STOP }

    private fun update(change: (Map<String, List<String>>) -> Map<String, List<String>>) {
        _lists.value = change(_lists.value).filterValues { it.isNotEmpty() }
        save()
    }

    private fun save() {
        val lists = JSONObject()
        _lists.value.forEach { (k, v) -> lists.put(k, JSONArray(v)) }
        val mem = JSONArray(_memories.value.map { JSONObject().put("text", it.text).put("added", it.added) })
        val o = JSONObject().put("lists", lists).put("memories", mem).put("name", _name.value ?: "")
        val tmp = File(file.parentFile, "notes.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}
