/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.LiquidKeyboard
import splitties.dimensions.dp

class LiquidAdapter(
    private val scope: ThemeScope,
    private val onItemClick: LiquidKeyboard.KeyItem.(Int) -> Unit,
) : BaseQuickAdapter<LiquidKeyboard.KeyItem, LiquidAdapter.ViewHolder>() {
    inner class ViewHolder(
        val ui: LiquidItemUi,
    ) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val cfg = scope.theme.liquidKeyboard
        // 格子的宽度由网格按列数等分（见 AutoSpanGridLayoutManager），高度是 key_height；
        // 按键四周留 margin_x 的空隙（算在格子内部），所以行间距和列间距一样是 margin_x 的两倍。
        val gap = context.dp(cfg.marginX.toInt())
        val ui = LiquidItemUi(context, scope, gapX = gap, gapY = gap)
        ui.root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(cfg.keyHeight) + 2 * gap,
        )
        return ViewHolder(ui)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        item: LiquidKeyboard.KeyItem?,
    ) {
        item ?: return
        holder.ui.refreshColors()
        holder.ui.mainText.text = item.text
        holder.ui.root.setOnClickListener {
            onItemClick.invoke(item, position)
        }
    }
}
