package com.mudassir.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.mudassir.assistant.ChatMessage
import com.mudassir.assistant.databinding.ItemMessageBinding

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.Holder>(DIFF) {

    class Holder(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val m = getItem(position)
        val b = holder.b
        val ctx = b.root.context
        b.text.text = if (m.pending && m.text.isEmpty()) "…" else m.text
        (b.bubble.layoutParams as FrameLayout.LayoutParams).gravity = if (m.fromUser) Gravity.END else Gravity.START
        b.bubble.setCardBackgroundColor(
            MaterialColors.getColor(
                b.bubble,
                if (m.fromUser) com.google.android.material.R.attr.colorPrimaryContainer
                else com.google.android.material.R.attr.colorSurfaceContainerHigh
            )
        )
        b.text.setTextColor(
            MaterialColors.getColor(
                b.text,
                if (m.fromUser) com.google.android.material.R.attr.colorOnPrimaryContainer
                else com.google.android.material.R.attr.colorOnSurface
            )
        )
        b.source.isVisible = !m.fromUser && m.source != null
        b.source.text = when (m.source) {
            "offline" -> if (m.pending) "Offline brain · thinking" else "Offline brain"
            "online" -> if (m.pending) "Gemini · thinking" else "Gemini"
            "phone" -> "Phone"
            else -> ""
        }
        b.bubble.setOnLongClickListener {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", m.text))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            true
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }
}
