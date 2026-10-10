package com.mudassir.assistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mudassir.assistant.actions.Notes
import com.mudassir.assistant.databinding.ActivityNotesBinding
import com.mudassir.assistant.databinding.ItemChatBinding
import com.mudassir.assistant.databinding.ItemHeaderBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Everything the assistant keeps for you: lists, notes and memories. Tap the bin to remove one. */
class NotesActivity : AppCompatActivity() {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val text: String, val meta: String?, val onDelete: () -> Unit) : Row()
    }

    private lateinit var b: ActivityNotesBinding
    private var rows = emptyList<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.backButton.setOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Notes.lists, Notes.memories, Notes.name) { lists, memories, name -> Triple(lists, memories, name) }
                    .collect { (lists, memories, name) -> render(lists, memories, name) }
            }
        }
    }

    private fun render(lists: Map<String, List<String>>, memories: List<Notes.Memory>, name: String?) {
        val out = mutableListOf<Row>()
        val ordered = lists.keys.sortedWith(compareBy({ it != "notes" }, { it }))
        for (list in ordered) {
            out += Row.Header(if (list == "notes") "Notes" else list.replaceFirstChar { it.uppercase() } + " list")
            lists[list]!!.forEachIndexed { i, item -> out += Row.Item(item, null) { Notes.removeAt(list, i) } }
        }
        if (memories.isNotEmpty() || name != null) {
            out += Row.Header("Remembered")
            if (name != null) out += Row.Item("Your name is $name", null) { Notes.clearName() }
            memories.forEachIndexed { i, m ->
                out += Row.Item(m.text, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.added))) { Notes.forgetAt(i) }
            }
        }
        rows = out
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        b.emptyText.isVisible = out.isEmpty()
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            val view = if (viewType == 0) ItemHeaderBinding.inflate(inf, parent, false).root else ItemChatBinding.inflate(inf, parent, false).root
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> ItemHeaderBinding.bind(holder.itemView).header.text = r.title
                is Row.Item -> {
                    val row = ItemChatBinding.bind(holder.itemView)
                    row.title.text = r.text
                    row.meta.isVisible = r.meta != null
                    row.meta.text = r.meta
                    row.deleteButton.contentDescription = "Remove"
                    row.deleteButton.setOnClickListener { r.onDelete() }
                }
            }
        }
    }
}
