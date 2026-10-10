package com.mudassir.assistant.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mudassir.assistant.Assistant
import com.mudassir.assistant.ChatStore
import com.mudassir.assistant.databinding.ActivityHistoryBinding
import com.mudassir.assistant.databinding.ItemChatBinding

/** Saved chats: tap to continue one, or delete. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityHistoryBinding
    private var chats = emptyList<ChatStore.Summary>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.backButton.setOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.clearButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete all chats?")
                .setMessage("This removes every saved conversation from the phone.")
                .setPositiveButton("Delete all") { _, _ ->
                    ChatStore.deleteAll()
                    Assistant.newChat(save = false)
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        refresh()
    }

    private fun refresh() {
        chats = ChatStore.list()
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        b.emptyText.isVisible = chats.isEmpty()
        b.clearButton.isVisible = chats.isNotEmpty()
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = chats.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val c = chats[position]
            val row = ItemChatBinding.bind(holder.itemView)
            row.title.text = c.title
            val messages = if (c.count == 1) "1 message" else "${c.count} messages"
            row.meta.text = "${DateUtils.getRelativeTimeSpanString(c.updated)}, $messages"
            row.root.setOnClickListener {
                Assistant.open(c.id)
                finish()
            }
            row.deleteButton.setOnClickListener {
                ChatStore.delete(c.id)
                if (Assistant.conversationId == c.id) Assistant.newChat(save = false)
                refresh()
            }
        }
    }
}
