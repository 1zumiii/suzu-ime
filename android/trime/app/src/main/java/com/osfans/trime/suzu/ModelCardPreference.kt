/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.R as MR
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.osfans.trime.R
import com.osfans.trime.ui.common.FullBleed
import com.osfans.trime.ui.common.mdi
import com.osfans.trime.ui.common.themeColor

/** 模型页顶部的大卡片：标签（状态）、模型名、下载进度和“选择模型”入口 */
class ModelCardPreference(
    context: Context,
    private val onCardClick: () -> Unit,
) : Preference(context),
    FullBleed {
    var label: CharSequence = ""
    var name: CharSequence = ""
    var action: CharSequence = ""
    var percent: Int? = null

    init {
        layoutResource = R.layout.preference_model_card
        isSelectable = false
        isPersistent = false
    }

    fun update() = notifyChanged()

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val ctx = holder.itemView.context
        val fg = ctx.themeColor(MR.attr.colorOnPrimaryContainer)
        val card = holder.findViewById(R.id.model_card)
        val radius = 24 * ctx.resources.displayMetrics.density
        val shape = GradientDrawable().apply {
            setColor(ctx.themeColor(MR.attr.colorPrimaryContainer))
            cornerRadius = radius
        }
        card.background = RippleDrawable(ColorStateList.valueOf(ctx.themeColor(android.R.attr.colorControlHighlight)), shape, null)
        card.setOnClickListener { onCardClick() }
        (holder.findViewById(R.id.model_card_icon) as ImageView).setImageDrawable(ctx.mdi("chip", fg, 28))
        (holder.findViewById(R.id.model_card_label) as TextView).text = label
        (holder.findViewById(R.id.model_card_name) as TextView).text = name
        (holder.findViewById(R.id.model_card_action) as TextView).apply {
            text = action
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, ctx.mdi("chevron_right", fg, 20), null)
        }
        (holder.findViewById(R.id.model_card_progress) as LinearProgressIndicator).apply {
            visibility = if (percent != null) View.VISIBLE else View.GONE
            progress = percent ?: 0
        }
    }
}
