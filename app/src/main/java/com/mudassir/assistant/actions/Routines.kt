package com.mudassir.assistant.actions

import android.content.Context
import com.mudassir.assistant.commands.CommandParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Routines: one phrase ("good night") runs several commands. Editable in Settings → Routines. */
object Routines {

    data class Routine(val phrase: String, val steps: List<String>)

    private lateinit var file: File
    private val _all = MutableStateFlow<List<Routine>>(emptyList())
    val all: StateFlow<List<Routine>> = _all.asStateFlow()

    private val DEFAULTS = listOf(
        Routine("good night", listOf("put phone on silent", "set brightness to 10%", "set an alarm for 7 am")),
        Routine("meeting mode", listOf("put phone on vibrate", "set brightness to 40%")),
        Routine("driving mode", listOf("set volume to 70%", "turn on bluetooth", "open google maps")),
    )

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "routines.json")
        _all.value = runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val s = o.getJSONArray("steps")
                Routine(o.getString("phrase"), (0 until s.length()).map { s.getString(it) })
            }
        }.getOrElse { DEFAULTS }
    }

    /** The routine [input] asks for: "good night", "start driving mode", "run meeting mode routine". */
    fun match(input: String): Routine? {
        val s = CommandParser.normalize(input)
            .replaceFirst(Regex("^(run|start|turn on|activate|begin|do|switch to|enable) (the |my )?"), "")
            .replaceFirst(Regex(" routine$"), "")
        return _all.value.firstOrNull { it.phrase == s }
    }

    fun save(r: Routine, replacing: String? = null) {
        val phrase = CommandParser.normalize(r.phrase)
        _all.value = _all.value.filterNot { it.phrase == phrase || it.phrase == replacing } + r.copy(phrase = phrase)
        write()
    }

    fun delete(phrase: String) {
        _all.value = _all.value.filterNot { it.phrase == phrase }
        write()
    }

    private fun write() {
        file.writeText(JSONArray(_all.value.map { JSONObject().put("phrase", it.phrase).put("steps", JSONArray(it.steps)) }).toString())
    }
}
