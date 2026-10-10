package com.mudassir.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mudassir.assistant.ChatMessage
import com.mudassir.assistant.databinding.ItemAssistantBinding
import com.mudassir.assistant.databinding.ItemUserBinding

class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    class UserHolder(val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root)
    class AssistantHolder(val b: ItemAssistantBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = if (getItem(position).fromUser) USER else ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == USER) UserHolder(ItemUserBinding.inflate(inflater, parent, false))
        else AssistantHolder(ItemAssistantBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val m = getItem(position)
        val view = when (holder) {
            is UserHolder -> {
                holder.b.text.text = m.text
                holder.b.text
            }
            is AssistantHolder -> {
                val b = holder.b
                val thinking = m.pending && m.text.isEmpty()
                b.text.isVisible = !thinking
                b.text.text = m.text
                b.typing.isVisible = thinking
                b.miniOrb.mode = if (m.pending) OrbView.Mode.THINKING else OrbView.Mode.IDLE
                b.source.isVisible = !m.pending && m.source != null
                b.source.text = when (m.source) {
                    "offline" -> "Offline brain"
                    "online" -> "Gemini"
                    "phone" -> "Done on your phone"
                    else -> ""
                }
                b.root
            }
            else -> return
        }
        view.setOnLongClickListener {
            val ctx = it.context
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", m.text))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            true
        }
    }

    companion object {
        private const val USER = 1
        private const val ASSISTANT = 2
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }
}
