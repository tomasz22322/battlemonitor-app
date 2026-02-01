package com.example.battlemonitor.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.battlemonitor.R
import com.example.battlemonitor.model.WatchedPlayer

class PlayerAdapter(
    private val onRemove: (WatchedPlayer) -> Unit,
    private val onRenameGroup: (String) -> Unit,
    private val onToggleNotifications: (WatchedPlayer) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onReorder: (List<PlayerListItem>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<PlayerListItem>()
    private val expandedKeys = mutableSetOf<String>()

    fun submitList(newItems: List<PlayerListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is PlayerListItem.Header -> 0
            is PlayerListItem.PlayerRow -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            HeaderVH(createHeaderView(parent))
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_player, parent, false)
            PlayerVH(view)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PlayerListItem.Header -> (holder as HeaderVH).bind(item.title, onRenameGroup)
            is PlayerListItem.PlayerRow -> (holder as PlayerVH).bind(
                item.player,
                onRemove,
                onToggleNotifications,
                onStartDrag
            )
        }
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition == toPosition) return false
        val fromItem = items.getOrNull(fromPosition) as? PlayerListItem.PlayerRow ?: return false
        val targetItem = items.getOrNull(toPosition) ?: return false
        val insertionIndex = when (targetItem) {
            is PlayerListItem.Header -> (toPosition + 1).coerceAtMost(items.size)
            is PlayerListItem.PlayerRow -> toPosition
        }
        if (insertionIndex == fromPosition || insertionIndex == fromPosition + 1) return false
        items.removeAt(fromPosition)
        val adjustedIndex = if (insertionIndex > fromPosition) insertionIndex - 1 else insertionIndex
        items.add(adjustedIndex, fromItem)
        notifyItemMoved(fromPosition, adjustedIndex)
        return true
    }

    fun onDragFinished() {
        onReorder(items.toList())
    }

    private fun createHeaderView(parent: ViewGroup): TextView {
        val tv = TextView(parent.context)
        val params = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(dp(parent, 6), dp(parent, 14), dp(parent, 6), dp(parent, 6))
        tv.layoutParams = params

        val padH = dp(parent, 10)
        tv.setPadding(padH, dp(parent, 8), padH, dp(parent, 8))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setTextColor(Color.parseColor("#BDBDBD"))
        tv.gravity = Gravity.START
        tv.setBackgroundResource(R.drawable.bg_group_header)
        return tv
    }

    private fun dp(parent: ViewGroup, v: Int): Int {
        val d = parent.resources.displayMetrics.density
        return (v * d).toInt()
    }

    class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView as TextView
        fun bind(title: String, onRenameGroup: (String) -> Unit) {
            tv.text = title
            tv.setOnLongClickListener {
                onRenameGroup(title)
                true
            }
        }
    }

    inner class PlayerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
        private val tvDetails: TextView = itemView.findViewById(R.id.tvDetails)
        private val btnNotify: TextView = itemView.findViewById(R.id.btnNotify)
        private val btnRemove: TextView = itemView.findViewById(R.id.btnRemove)
        private val statusBar: View = itemView.findViewById(R.id.vStatus)

        fun bind(
            item: WatchedPlayer,
            onRemove: (WatchedPlayer) -> Unit,
            onToggleNotifications: (WatchedPlayer) -> Unit,
            onStartDrag: (RecyclerView.ViewHolder) -> Unit
        ) {
            tvName.text = buildDisplayName(item)

            val stayEntry = item.details
                ?.firstOrNull { it.trim().startsWith("Czas przebywania", ignoreCase = true) }
            val metaText = if (item.online) {
                val metaParts = mutableListOf("Online")
                if (stayEntry != null) {
                    metaParts.add(stayEntry)
                }
                metaParts.joinToString(separator = " • ")
            } else {
                "Offline"
            }
            tvMeta.text = metaText
            tvMeta.visibility = if (metaText.isBlank()) View.GONE else View.VISIBLE

            val detailsText = item.details
                .orEmpty()
                .filterNot { entry ->
                    stayEntry != null && entry.trim().equals(stayEntry.trim(), ignoreCase = true)
                }
                .joinToString(separator = "\n")
                .takeIf { expandedKeys.contains(item.key) }
                .orEmpty()
            tvDetails.text = detailsText
            tvDetails.visibility = if (detailsText.isBlank()) View.GONE else View.VISIBLE

            statusBar.setBackgroundColor(
                if (item.online) Color.parseColor("#2E7D32")
                else Color.parseColor("#616161")
            )

            val notificationsEnabled = item.notificationsEnabled != false
            btnNotify.text = if (notificationsEnabled) "🔔" else "🔕"
            btnNotify.setTextColor(
                if (notificationsEnabled) Color.parseColor("#38BDF8")
                else Color.parseColor("#94A3B8")
            )
            btnNotify.setOnClickListener { onToggleNotifications(item) }

            btnRemove.setOnClickListener { onRemove(item) }
            itemView.setOnLongClickListener {
                onStartDrag(this)
                true
            }
            itemView.setOnClickListener {
                val key = item.key
                if (expandedKeys.contains(key)) {
                    expandedKeys.remove(key)
                } else {
                    expandedKeys.add(key)
                }
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    notifyItemChanged(position)
                }
            }
        }

        private fun buildDisplayName(item: WatchedPlayer): CharSequence {
            val currentName = item.resolvedName.ifBlank { item.key }
            val originalName = item.originalName?.takeIf { it.isNotBlank() } ?: return currentName
            if (originalName.equals(currentName, ignoreCase = true)) return currentName

            val originalColor = itemView.context.getColor(R.color.text_secondary)
            return SpannableStringBuilder().apply {
                append(currentName)
                append(" (")
                val start = length
                append(originalName)
                setSpan(
                    ForegroundColorSpan(originalColor),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                append(")")
            }
        }
    }
}
