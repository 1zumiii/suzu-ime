// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.symbol

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.util.roundedRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import splitties.views.horizontalPadding
import splitties.views.recyclerview.horizontalLayoutManager

/**
 * 可横向滑动的一排标签，当前项高亮。符号面板顶部的“符号 / 表情 / 颜文字”和下面的分类条都用它。
 * [textScale] 相对候选字号。
 */
class PanelTabsUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val textScale: Float = 0.85f,
) : Ui {
    private val theme: Theme get() = scope.theme

    private inner class TabUi : Ui {
        override val ctx = this@PanelTabsUi.ctx
        private val cornerRadius = ctx.dp(theme.style.candidateCornerRadius)

        val text = textView {
            textSize = theme.style.candidateTextSize * textScale
            typeface = theme.fonts.candidate
            setTextColor(scope.colors.candidateTextColor)
        }

        override val root = view(::GestureFrame) {
            minimumWidth = dp(40)
            add(text, lParams { gravity = gravityCenter; horizontalPadding = dp(theme.style.candidatePadding) })
        }

        fun bind(label: String, active: Boolean) {
            text.text = label
            text.setTextColor(if (active) scope.colors.hilitedCandidateTextColor else scope.colors.candidateTextColor)
            root.background = roundedRippleDrawable(
                scope.colors.hilitedCandidateBackColor,
                cornerRadius,
                if (active) scope.colors.hilitedCandidateBackColor else Color.TRANSPARENT,
            )
        }
    }

    private class Holder(val ui: TabUi) : RecyclerView.ViewHolder(ui.root)

    private var labels: List<String> = emptyList()
    private var selected = -1
    private var onTabClick: ((Int) -> Unit)? = null

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = labels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TabUi()).apply {
            ui.root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.ui.bind(labels[position], position == selected)
            holder.ui.root.onClick = { onTabClick?.invoke(holder.bindingAdapterPosition) }
        }
    }

    override val root = recyclerView {
        layoutManager = horizontalLayoutManager()
        adapter = this@PanelTabsUi.adapter
        itemAnimator = null
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    @Suppress("NotifyDataSetChanged")
    fun setLabels(list: List<String>) {
        labels = list
        selected = -1
        adapter.notifyDataSetChanged()
    }

    /** 高亮第 [index] 项并滚到可见处，-1 表示都不高亮 */
    fun activate(index: Int) {
        if (index == selected) return
        val old = selected
        selected = index
        if (old in labels.indices) adapter.notifyItemChanged(old)
        if (index in labels.indices) {
            adapter.notifyItemChanged(index)
            root.post { root.scrollToPosition(index) }
        }
    }

    fun setOnTabClickListener(listener: ((Int) -> Unit)?) {
        onTabClick = listener
    }

    @Suppress("NotifyDataSetChanged")
    fun refreshColors() = adapter.notifyDataSetChanged()
}
