/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.slider.Slider
import com.osfans.trime.R
import com.osfans.trime.ui.common.mdi
import com.osfans.trime.ui.common.themeColor
import java.util.Locale

/**
 * 选错修正提示的开关项。开关开着且可用时，说明文字下方多一行“停顿时间（秒）”，点按展开或收起滑块。
 * 滑块 0.5～5 秒，每步 0.5 秒，拖动时直接保存。
 */
class FixHintPreference(
    context: Context,
) : SwitchPreferenceCompat(context) {
    private var expanded = false

    init {
        layoutResource = R.layout.preference_suzu_fix_hint
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f", ms / 1000.0)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val header = holder.findViewById(R.id.fix_pause_header)
        val label = holder.findViewById(R.id.fix_pause_label) as TextView
        val arrow = holder.findViewById(R.id.fix_pause_arrow) as ImageView
        val slider = holder.findViewById(R.id.fix_pause_slider) as Slider

        val show = isEnabled && isChecked
        header.visibility = if (show) View.VISIBLE else View.GONE
        slider.visibility = if (show && expanded) View.VISIBLE else View.GONE
        if (!show) return

        val ms = SuzuFeatures.fixPauseMs
        label.text = "停顿时间（秒）：${seconds(ms)}"
        arrow.setImageDrawable(context.mdi(if (expanded) "chevron_up" else "chevron_down", context.themeColor(android.R.attr.textColorSecondary), 20))
        header.setOnClickListener {
            expanded = !expanded
            notifyChanged()
        }

        slider.clearOnChangeListeners()
        slider.value = ms / 1000f
        slider.setLabelFormatter { v -> "${seconds((v * 1000).toLong())} 秒" }
        slider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            SuzuFeatures.fixPauseMs = (v * 1000).toLong()
            label.text = "停顿时间（秒）：${seconds(SuzuFeatures.fixPauseMs)}"
        }
    }
}
