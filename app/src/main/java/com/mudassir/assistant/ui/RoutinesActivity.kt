package com.mudassir.assistant.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mudassir.assistant.actions.Routines
import com.mudassir.assistant.commands.CommandParser
import com.mudassir.assistant.databinding.ActivityRoutinesBinding
import com.mudassir.assistant.databinding.ItemChatBinding
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Your routines: a phrase like "good night" that runs several commands. */
class RoutinesActivity : AppCompatActivity() {

    private lateinit var b: ActivityRoutinesBinding
    private var routines = emptyList<Routines.Routine>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRoutinesBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.backButton.setOnClickListener { finish() }
        b.addButton.setOnClickListener { edit(null) }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Routines.all.collect { all ->
                    routines = all.sortedBy { it.phrase }
                    @Suppress("NotifyDataSetChanged")
                    adapter.notifyDataSetChanged()
                    b.emptyText.isVisible = routines.isEmpty()
                }
            }
        }
    }

    private fun field(hint: String, text: String, multiline: Boolean) = EditText(this).apply {
        this.hint = hint
        setText(text)
        inputType = InputType.TYPE_CLASS_TEXT or (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (multiline) minLines = 3
    }

    private fun edit(existing: Routines.Routine?) {
        val phrase = field("When I say… (e.g. good night)", existing?.phrase.orEmpty(), false)
        val steps = field("Do these, one per line:\nput phone on silent\nset an alarm for 7 am", existing?.steps?.joinToString("\n").orEmpty(), true)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(phrase)
            addView(steps)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New routine" else "Edit routine")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Delete") { _, _ -> Routines.delete(existing.phrase) } }
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p = phrase.text.toString().trim()
            val list = steps.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val unknown = list.filter { CommandParser.parse(it, LocalDateTime.now()) == null }
            when {
                p.isEmpty() -> toast("Add the phrase that starts it.")
                list.isEmpty() -> toast("Add at least one command.")
                unknown.isNotEmpty() -> toast("I don't understand: \"${unknown.first()}\". Try it in the chat first.")
                else -> {
                    Routines.save(Routines.Routine(p, list), replacing = existing?.phrase)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = routines.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = routines[position]
            val row = ItemChatBinding.bind(holder.itemView)
            row.title.text = "“${r.phrase.replaceFirstChar { it.uppercase() }}”"
            row.meta.text = r.steps.joinToString(", then ")
            row.root.setOnClickListener { edit(r) }
            row.deleteButton.contentDescription = "Delete routine"
            row.deleteButton.setOnClickListener { Routines.delete(r.phrase) }
        }
    }
}
