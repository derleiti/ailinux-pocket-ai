package me.ailinux.pocket

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ChatMessage(val isUser: Boolean, var content: String)

class ChatAdapter(private val items: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: LinearLayout = view as LinearLayout
        val role: TextView = view.findViewById(R.id.messageRole)
        val message: TextView = view.findViewById(R.id.messageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val value = items[position]
        holder.row.gravity = if (value.isUser) Gravity.END else Gravity.START
        holder.role.text = if (value.isUser) "DU" else "POCKET AI"
        holder.role.setTextColor(
            holder.itemView.context.getColor(if (value.isUser) R.color.primary else R.color.secondary)
        )
        holder.message.setBackgroundResource(if (value.isUser) R.drawable.bg_user else R.drawable.bg_assistant)
        holder.message.text = value.content.ifEmpty { "…" }
    }
}
