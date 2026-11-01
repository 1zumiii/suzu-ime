/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Context
import android.view.View
import android.widget.ProgressBar
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.osfans.trime.R

/** 右侧带进度条的设置项；[percent] 为 null 时不显示进度条 */
class AssetPreference(context: Context) : Preference(context) {
    var percent: Int? = null
        set(value) {
            if (field != value) {
                field = value
                notifyChanged()
            }
        }

    init {
        widgetLayoutResource = R.layout.widget_asset_progress
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val bar = holder.findViewById(R.id.asset_progress) as ProgressBar
        bar.visibility = if (percent != null) View.VISIBLE else View.GONE
        bar.progress = percent ?: 0
    }
}
