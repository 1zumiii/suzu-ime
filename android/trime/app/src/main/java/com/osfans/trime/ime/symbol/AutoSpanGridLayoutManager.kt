/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 列数由行宽决定的网格：每格至少 [minCellPx] 宽，一行放得下几格就几列，剩余宽度平均分给每一格。
 * 布局前先算好列数，所以第一次排版就是对的（不会出现先排错、滚动后才恢复的情况）。
 */
class AutoSpanGridLayoutManager(
    context: Context,
    private val minCellPx: Int,
) : GridLayoutManager(context, 1) {
    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val usable = width - paddingLeft - paddingRight
        if (usable > 0 && minCellPx > 0) {
            val span = maxOf(1, usable / minCellPx)
            if (span != spanCount) spanCount = span
        }
        super.onLayoutChildren(recycler, state)
    }
}
