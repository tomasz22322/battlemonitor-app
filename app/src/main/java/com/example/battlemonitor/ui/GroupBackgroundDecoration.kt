package com.example.battlemonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
import com.example.battlemonitor.R
import kotlin.math.max
import kotlin.math.min

class GroupBackgroundDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val cornerRadius = dpToPx(context, 12f)
    private val strokeWidth = dpToPx(context, 1f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface_alt)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface_stroke)
        style = Paint.Style.STROKE
        strokeWidth = this@GroupBackgroundDecoration.strokeWidth
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PlayerAdapter ?: return
        val items = adapter.getItems()
        if (items.isEmpty()) return

        val groupRanges = mutableListOf<Pair<Int, Int>>()
        var start = -1
        items.forEachIndexed { index, item ->
            if (item is PlayerListItem.Header) {
                if (start != -1) {
                    groupRanges.add(start to index - 1)
                }
                start = if (item.isUngrouped) -1 else index
            }
        }
        if (start != -1) {
            groupRanges.add(start to items.lastIndex)
        }

        groupRanges.forEach { (startPos, endPos) ->
            val headerView = parent.findViewHolderForAdapterPosition(startPos)?.itemView ?: return@forEach
            val headerParams = headerView.layoutParams as RecyclerView.LayoutParams
            var left = headerView.left.toFloat() - headerParams.leftMargin
            var right = headerView.right.toFloat() + headerParams.rightMargin
            var top = headerView.top.toFloat() - headerParams.topMargin
            var bottom = headerView.bottom.toFloat() + headerParams.bottomMargin

            val lastView = parent.findViewHolderForAdapterPosition(endPos)?.itemView
            val lastCandidate = lastView ?: findLastVisibleChild(parent, startPos, endPos)
            if (lastCandidate != null) {
                val lastParams = lastCandidate.layoutParams as RecyclerView.LayoutParams
                bottom = lastCandidate.bottom.toFloat() + lastParams.bottomMargin
                left = min(left, lastCandidate.left.toFloat() - lastParams.leftMargin)
                right = max(right, lastCandidate.right.toFloat() + lastParams.rightMargin)
            }

            val rect = RectF(left, top, right, bottom)
            c.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
            c.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        }
    }

    private fun findLastVisibleChild(
        parent: RecyclerView,
        startPos: Int,
        endPos: Int
    ): android.view.View? {
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position in startPos..endPos) {
                return child
            }
        }
        return null
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
