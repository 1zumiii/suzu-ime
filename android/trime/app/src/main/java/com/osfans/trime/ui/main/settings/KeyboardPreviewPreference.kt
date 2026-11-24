/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.osfans.trime.ui.common.FullBleed

/** 虚拟键盘页顶部的示意键盘：按当前设置画出主键盘的按键区，只做展示，不可点（见 [KeyboardPreviewView]） */
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
        val wrap = LinearLayout(ctx).apply {
            val pad = (16 * d).toInt()
            setPadding(pad, (8 * d).toInt(), pad, (8 * d).toInt())
            addView(KeyboardPreviewView(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(wrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    companion object {
        private val TAG_KEY = com.osfans.trime.R.id.keyboard_preview_built
    }
}
