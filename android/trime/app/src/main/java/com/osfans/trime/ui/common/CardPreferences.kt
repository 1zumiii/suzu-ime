/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.preference.DialogPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MR
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.paddingDp
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.R

/**
 * 设置页的卡片样式：同一分组里的项连成一张圆角卡片，项与项之间画内缩的分隔线。
 * 分组标题（PreferenceCategory）和实现了 [FullBleed] 的项（主页大标题、模型卡片、说明文字等）不套卡片。
 */
interface FullBleed

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** MDI 图标（community-material，名字不带 cmd_ 前缀），默认用主色着色 */
fun Context.mdi(
    name: String,
    color: Int = themeColor(androidx.appcompat.R.attr.colorPrimary),
    size: Int = 24,
): Drawable =
    IconicsDrawable(this, "cmd_$name").apply {
        sizeDp = size
        // MDI 图形铺满整个方框，Material 图标四周留约 1/12 空白；补上同样的内边距，大小才一致
        paddingDp = size / 12
        colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

fun Preference.mdiIcon(name: String) {
    icon = context.mdi(name)
}

class CardPreferenceAdapter(
    group: PreferenceGroup,
) : PreferenceGroupAdapter(group) {
    private fun isCard(p: Preference?) = p != null && p !is PreferenceCategory && p !is FullBleed

    fun isFirstInCard(position: Int) = !isCard(itemAt(position - 1))

    fun isLastInCard(position: Int) = !isCard(itemAt(position + 1))

    fun isCardAt(position: Int) = isCard(itemAt(position))

    private fun itemAt(position: Int): Preference? = if (position in 0 until itemCount) getItem(position) else null

    override fun onBindViewHolder(
        holder: PreferenceViewHolder,
        position: Int,
    ) {
        super.onBindViewHolder(holder, position)
        val view = holder.itemView
        val ctx = view.context
        val lp = view.layoutParams as? RecyclerView.LayoutParams ?: return
        if (!isCardAt(position)) {
            lp.marginStart = 0
            lp.marginEnd = 0
            view.layoutParams = lp
            return
        }
        val r = ctx.dp(24).toFloat()
        val shape = ShapeAppearanceModel.builder().apply {
            if (isFirstInCard(position)) setTopLeftCornerSize(r).setTopRightCornerSize(r)
            if (isLastInCard(position)) setBottomLeftCornerSize(r).setBottomRightCornerSize(r)
        }.build()
        val bg = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(ctx.getColor(R.color.m3_card))
        }
        val mask = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(0xFF000000.toInt()) }
        view.background = RippleDrawable(
            ColorStateList.valueOf(ctx.themeColor(android.R.attr.colorControlHighlight)),
            bg,
            mask,
        )
        lp.marginStart = ctx.dp(16)
        lp.marginEnd = ctx.dp(16)
        view.layoutParams = lp
    }
}

/** 卡片内相邻两项之间的分隔线，从文字起点画到卡片右侧留白处 */
class CardDividerDecoration(
    context: Context,
) : RecyclerView.ItemDecoration() {
    private val paint = Paint().apply {
        color = context.themeColor(MR.attr.colorOutlineVariant)
        strokeWidth = context.resources.displayMetrics.density
    }
    private val textInset = context.dp(16)
    private val iconInset = context.dp(72)
    private val endInset = context.dp(16)

    override fun onDrawOver(
        c: Canvas,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val adapter = parent.adapter as? CardPreferenceAdapter ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || !adapter.isCardAt(pos) || adapter.isLastInCard(pos)) continue
            val hasIcon = child.findViewById<View>(android.R.id.icon)?.let { it.visibility == View.VISIBLE } == true
            val y = child.bottom + child.translationY
            c.drawLine(
                child.left + (if (hasIcon) iconInset else textInset).toFloat(),
                y,
                child.right - endInset.toFloat(),
                y,
                paint,
            )
        }
    }
}

/**
 * 给可点击进入下一级、或会弹出对话框的项加右侧箭头，图标统一用主色。
 * 开关、分组和已有右侧控件的项不动。
 */
fun decorateForCards(group: PreferenceGroup) {
    for (i in 0 until group.preferenceCount) {
        val p = group.getPreference(i)
        p.icon?.let { icon ->
            icon.mutate()
            icon.setTint(p.context.themeColor(androidx.appcompat.R.attr.colorPrimary))
        }
        if (p is PreferenceGroup) {
            decorateForCards(p)
            continue
        }
        if (p is FullBleed || p is TwoStatePreference || p.widgetLayoutResource != 0 || !p.isSelectable) continue
        val navigates = p.onPreferenceClickListener != null || p is DialogPreference || p is ListPreference ||
            p.intent != null || p.fragment != null
        if (navigates) p.widgetLayoutResource = R.layout.preference_widget_chevron
    }
}

/** 卡片下方的灰色说明文字 */
class FooterPreference(
    context: Context,
    text: CharSequence,
) : Preference(context),
    FullBleed {
    init {
        layoutResource = R.layout.preference_footer
        summary = text
        isSelectable = false
        isIconSpaceReserved = false
    }
}
