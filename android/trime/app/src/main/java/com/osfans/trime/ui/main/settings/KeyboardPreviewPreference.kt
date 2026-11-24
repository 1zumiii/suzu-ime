/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.R as MR
import com.osfans.trime.ui.common.FullBleed
import com.osfans.trime.ui.common.themeColor

/** 虚拟键盘页顶部的示意键盘：主色容器卡片里三排字母键，只做装饰，不可点 */
class KeyboardPreviewPreference(
    context: Context,
) : Preference(context),
    FullBleed {
    init {
        layoutResource = com.osfans.trime.R.layout.preference_empty_frame
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val root = holder.itemView as? ViewGroup ?: return
        if (root.getTag(TAG_KEY) == true) return
        root.setTag(TAG_KEY, true)
        val ctx = root.context
        root.removeAllViews()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ctx.themeColor(MR.attr.colorPrimaryContainer))
                cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        for ((i, row) in ROWS.withIndex()) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (ch in row) {
                line.addView(
                    TextView(ctx).apply {
                        text = ch.toString()
                        gravity = Gravity.CENTER
                        textSize = 15f
                        setTextColor(ctx.themeColor(MR.attr.colorOnSurface))
                        background = GradientDrawable().apply {
                            setColor(ctx.themeColor(MR.attr.colorSurface))
                            cornerRadius = dp(8).toFloat()
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    },
                )
            }
            // 第二、三排左右缩进，像真键盘那样错开
            val inset = when (i) {
                1 -> dp(14)
                2 -> dp(34)
                else -> 0
            }
            card.addView(
                line,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (i == 0) 0 else dp(8)
                    marginStart = inset
                    marginEnd = inset
                },
            )
        }
        val wrap = LinearLayout(ctx).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(wrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    companion object {
        private val ROWS = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
        private val TAG_KEY = com.osfans.trime.R.id.keyboard_preview_built
    }
}
