package com.example.battlemonitor.ui

import android.graphics.Color
import android.graphics.Typeface
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
    private val onRemove: (WatchedPlayer) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<PlayerListItem>()

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
            is PlayerListItem.Header -> (holder as HeaderVH).bind(item.title)
            is PlayerListItem.PlayerRow -> (holder as PlayerVH).bind(item.player, onRemove)
        }
    }

    private fun createHeaderView(parent: ViewGroup): TextView {
        val tv = TextView(parent.context)
        tv.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val padH = dp(parent, 10)
        tv.setPadding(padH, dp(parent, 10), padH, dp(parent, 6))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setTextColor(Color.parseColor("#BDBDBD"))
        tv.gravity = Gravity.START
        return tv
    }

    private fun dp(parent: ViewGroup, v: Int): Int {
        val d = parent.resources.displayMetrics.density
        return (v * d).toInt()
    }

    class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView as TextView
        fun bind(title: String) {
            tv.text = title
        }
    }

    class PlayerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
        private val btnRemove: TextView = itemView.findViewById(R.id.btnRemove)
        private val statusBar: View = itemView.findViewById(R.id.vStatus)

        fun bind(item: WatchedPlayer, onRemove: (WatchedPlayer) -> Unit) {

            tvName.text = item.resolvedName.ifBlank { item.key }

            val metaParts = mutableListOf<String>()

            if (!item.resolvedId.isNullOrBlank()) {
                metaParts.add("ID: ${item.resolvedId}")
            }

            if (item.online) {
                metaParts.add(item.playTime.ifBlank { "??" })
            }

            tvMeta.text = metaParts.joinToString(" • ")

            statusBar.setBackgroundColor(
                if (item.online) Color.parseColor("#2E7D32")
                else Color.parseColor("#616161")
            )

            btnRemove.setOnClickListener { onRemove(item) }
        }
    }
}
