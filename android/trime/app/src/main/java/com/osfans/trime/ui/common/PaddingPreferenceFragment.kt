/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.ui.main.settings.EditTextIntPreference

/**
 * A fragment template that apply navigation bar window insets bottom padding
 *
 * Taken from fcitx5-android project.
 * Source: https://github.com/fcitx5-android/fcitx5-android/blob/dedfc18/app/src/main/java/org/fcitx/fcitx5/android/ui/common/PaddingPreferenceFragment.kt
 */
abstract class PaddingPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        // 底部留出导航栏高度再加 24dp，长页面滚到底时最后一张卡片不贴边
        val extra = (24 * resources.displayMetrics.density).toInt()
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + extra)
            insets
        }
        listView.setPadding(listView.paddingLeft, listView.paddingTop, listView.paddingRight, extra)
        // 卡片样式：去掉默认分隔线，改画卡片内的分隔线，上下留白
        setDivider(null)
        listView.addItemDecoration(CardDividerDecoration(context))
        listView.clipToPadding = false
        listView.setPadding(listView.paddingLeft, (8 * resources.displayMetrics.density).toInt(), listView.paddingRight, listView.paddingBottom)
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        CardPreferenceAdapter(preferenceScreen)

    override fun onDisplayPreferenceDialog(preference: Preference) {
        val ctx = requireContext()
        val title = preference.title ?: ""
        val cancel = getString(android.R.string.cancel)
        when (preference) {
            is ListPreference -> {
                val entries = preference.entries ?: return
                val values = preference.entryValues ?: return
                ctx.pickSheet("format_list_bulleted", preference.dialogTitle ?: title, entries.toList(), preference.findIndexOfValue(preference.value)) { i ->
                    val value = values[i].toString()
                    if (preference.callChangeListener(value)) preference.value = value
                }
            }
            is EditTextPreference -> {
                val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
                val edit = TextInputEditText(layout.context)
                layout.addView(edit)
                if (preference is EditTextIntPreference) {
                    preference.bindEditText(edit)
                } else {
                    // 剪贴板规则等：每行一条，允许多行
                    edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    edit.maxLines = 6
                    edit.setText(preference.text)
                }
                ctx.settingsSheet(
                    icon = "pencil_outline",
                    title = preference.dialogTitle ?: title,
                    message = preference.dialogMessage,
                    body = layout,
                    confirm = SheetButton(getString(android.R.string.ok)) {
                        val v = edit.text?.toString().orEmpty()
                        if (preference.callChangeListener(v)) preference.text = v
                    },
                    cancel = cancel,
                ).show()
            }
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun setPreferenceScreen(preferenceScreen: PreferenceScreen?) {
        preferenceScreen?.let(::decorateForCards)
        super.setPreferenceScreen(preferenceScreen)
    }
}
