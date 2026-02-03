package com.example.battlemonitor.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
    private val onToggleGroupNotifications: (String) -> Unit,
    private val onDeleteGroup: (String) -> Unit,
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

    fun getItems(): List<PlayerListItem> = items.toList()

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is PlayerListItem.Header -> 0
            is PlayerListItem.PlayerRow -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_group_header, parent, false)
            HeaderVH(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_player, parent, false)
            PlayerVH(view)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PlayerListItem.Header -> (holder as HeaderVH).bind(
                item.title,
                item.groupName,
                item.notificationsEnabled,
                item.isUngrouped,
                onRenameGroup,
                onToggleGroupNotifications,
                onDeleteGroup,
                onStartDrag
            )
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
        val fromItem = items.getOrNull(fromPosition) ?: return false
        val targetItem = items.getOrNull(toPosition) ?: return false
        return when (fromItem) {
            is PlayerListItem.Header -> moveGroupBlock(fromPosition, toPosition, targetItem)
            is PlayerListItem.PlayerRow -> movePlayerRow(fromPosition, toPosition, targetItem)
        }
    }

    fun onDragFinished() {
        onReorder(items.toList())
    }

    private fun moveGroupBlock(
        fromPosition: Int,
        toPosition: Int,
        targetItem: PlayerListItem
    ): Boolean {
        val fromRange = findGroupRange(fromPosition)
        if (toPosition in fromRange) return false
        val targetHeaderPos = when (targetItem) {
            is PlayerListItem.Header -> toPosition
            is PlayerListItem.PlayerRow -> findHeaderPositionFor(toPosition) ?: return false
        }
        if (targetHeaderPos in fromRange) return false

        val targetRange = findGroupRange(targetHeaderPos)
        val movingDown = targetHeaderPos > fromRange.first
        val rawInsertIndex = when (targetItem) {
            is PlayerListItem.Header -> targetHeaderPos
            is PlayerListItem.PlayerRow ->
                if (movingDown) targetRange.last + 1 else targetRange.first
        }

        val block = items.subList(fromRange.first, fromRange.last + 1).toList()
        repeat(block.size) { items.removeAt(fromRange.first) }
        var insertIndex = rawInsertIndex
        if (rawInsertIndex > fromRange.first) {
            insertIndex -= block.size
        }
        insertIndex = insertIndex.coerceIn(0, items.size)
        items.addAll(insertIndex, block)
        notifyDataSetChanged()
        return true
    }

    private fun movePlayerRow(
        fromPosition: Int,
        toPosition: Int,
        targetItem: PlayerListItem
    ): Boolean {
        val fromItem = items.getOrNull(fromPosition) as? PlayerListItem.PlayerRow ?: return false
        val insertionIndex = when (targetItem) {
            is PlayerListItem.Header -> {
                val movingDown = toPosition > fromPosition
                val headerRange = findGroupRange(toPosition)
                val rawIndex = if (movingDown) headerRange.last + 1 else toPosition + 1
                rawIndex.coerceAtMost(items.size)
            }
            is PlayerListItem.PlayerRow -> toPosition
        }
        if (insertionIndex == fromPosition) return false
        if (targetItem is PlayerListItem.PlayerRow && toPosition == fromPosition + 1) {
            items[fromPosition] = targetItem
            items[toPosition] = fromItem
            notifyItemMoved(fromPosition, toPosition)
            return true
        }
        items.removeAt(fromPosition)
        val adjustedIndex = if (insertionIndex > fromPosition) insertionIndex - 1 else insertionIndex
        if (adjustedIndex == fromPosition) return false
        items.add(adjustedIndex, fromItem)
        notifyItemMoved(fromPosition, adjustedIndex)
        return true
    }

    private fun findGroupRange(headerPosition: Int): IntRange {
        var end = headerPosition
        var index = headerPosition + 1
        while (index < items.size) {
            if (items[index] is PlayerListItem.Header) {
                break
            }
            end = index
            index++
        }
        return headerPosition..end
    }

    private fun findHeaderPositionFor(position: Int): Int? {
        var index = position
        while (index >= 0) {
            if (items[index] is PlayerListItem.Header) {
                return index
            }
            index--
        }
        return null
    }

    class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView.findViewById(R.id.tvGroupTitle)
        private val btnNotify: TextView = itemView.findViewById(R.id.btnGroupNotify)
        private val btnDelete: TextView = itemView.findViewById(R.id.btnGroupDelete)
        private val defaultLayoutParams = RecyclerView.LayoutParams(itemView.layoutParams)

        fun bind(
            title: String,
            groupName: String,
            notificationsEnabled: Boolean,
            isUngrouped: Boolean,
            onRenameGroup: (String) -> Unit,
            onToggleGroupNotifications: (String) -> Unit,
            onDeleteGroup: (String) -> Unit,
            onStartDrag: (RecyclerView.ViewHolder) -> Unit
        ) {
            tv.text = title
            if (isUngrouped) {
                itemView.visibility = View.GONE
                itemView.layoutParams = RecyclerView.LayoutParams(itemView.layoutParams).apply {
                    width = 0
                    height = 0
                    setMargins(0, 0, 0, 0)
                }
                tv.visibility = View.GONE
                tv.setOnClickListener(null)
                btnNotify.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnNotify.setOnClickListener(null)
                btnDelete.setOnClickListener(null)
            } else {
                itemView.visibility = View.VISIBLE
                itemView.layoutParams = RecyclerView.LayoutParams(defaultLayoutParams)
                tv.visibility = View.VISIBLE
                btnNotify.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
                btnNotify.text = if (notificationsEnabled) "🔔" else "🔕"
                btnNotify.setTextColor(
                    if (notificationsEnabled) Color.parseColor("#22D3EE")
                    else Color.parseColor("#7B8AA5")
                )
                btnNotify.setOnClickListener { onToggleGroupNotifications(groupName) }
                btnDelete.setOnClickListener { onDeleteGroup(groupName) }
                tv.setOnClickListener { onRenameGroup(groupName) }
            }
            itemView.setOnLongClickListener {
                onStartDrag(this)
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
            val stayValue = stayEntry
                ?.substringAfter(":", stayEntry)
                ?.trim()
            val metaText = if (item.online) {
                val metaParts = mutableListOf("Online")
                if (!stayValue.isNullOrBlank()) {
                    metaParts.add(stayValue)
                }
                metaParts.joinToString(separator = " • ")
            } else {
                buildOfflineText(item)
            }
            tvMeta.text = metaText
            tvMeta.visibility = if (metaText.isBlank()) View.GONE else View.VISIBLE

            val detailsText = item.details
                .orEmpty()
                .filterNot { entry ->
                    stayEntry != null && entry.trim().equals(stayEntry.trim(), ignoreCase = true)
                }
                .map { entry ->
                    if (entry.trim().startsWith("Czas przebywania", ignoreCase = true)) {
                        entry.substringAfter(":", entry).trim()
                    } else {
                        entry
                    }
                }
                .joinToString(separator = "\n")
                .takeIf { expandedKeys.contains(item.key) }
                .orEmpty()
            tvDetails.text = detailsText
            tvDetails.visibility = if (detailsText.isBlank()) View.GONE else View.VISIBLE

            val statusColor = if (item.online) {
                Color.parseColor("#22C55E")
            } else {
                Color.parseColor("#475569")
            }
            statusBar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(statusColor)
            }

            val notificationsEnabled = item.notificationsEnabled != false
            btnNotify.text = if (notificationsEnabled) "🔔" else "🔕"
            btnNotify.setTextColor(
                if (notificationsEnabled) Color.parseColor("#22D3EE")
                else Color.parseColor("#7B8AA5")
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
            if (originalName.all { it.isDigit() }) return currentName
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

        private fun buildOfflineText(item: WatchedPlayer): String {
            val lastOfflineAt = item.lastOfflineAt ?: return "Offline"
            val elapsedSeconds = ((System.currentTimeMillis() - lastOfflineAt) / 1000L)
                .coerceAtLeast(0)
            val elapsedText = formatOfflineDuration(elapsedSeconds) ?: return "Offline"
            return "Offline od $elapsedText"
        }

        private fun formatOfflineDuration(seconds: Long): String? {
            val safeSeconds = seconds.coerceAtLeast(0)
            val minutes = safeSeconds / 60
            val hours = minutes / 60
            return "${hours}h ${minutes % 60}m"
        }
    }
}
