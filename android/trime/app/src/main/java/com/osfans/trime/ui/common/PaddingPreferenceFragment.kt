/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.text.InputType
import android.widget.FrameLayout
import com.osfans.trime.ui.main.settings.EditTextIntPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.util.applyNavBarInsetsBottomPadding

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
        listView.applyNavBarInsetsBottomPadding()
        // 卡片样式：去掉默认分隔线，改画卡片内的分隔线，上下留白
        setDivider(null)
        listView.addItemDecoration(CardDividerDecoration(context))
        listView.clipToPadding = false
        listView.setPadding(listView.paddingLeft, (8 * resources.displayMetrics.density).toInt(), listView.paddingRight, listView.paddingBottom)
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        CardPreferenceAdapter(preferenceScreen)

    /** 列表选择和文字输入的对话框改用 Material 3 样式，和其他弹窗一致 */
    override fun onDisplayPreferenceDialog(preference: Preference) {
        val ctx = requireContext()
        when (preference) {
            is ListPreference -> {
                val entries = preference.entries ?: return
                val values = preference.entryValues ?: return
                materialDialog(ctx)
                    .setTitle(preference.dialogTitle ?: preference.title)
                    .setSingleChoiceItems(entries, preference.findIndexOfValue(preference.value)) { d, i ->
                        val v = values[i].toString()
                        if (preference.callChangeListener(v)) preference.value = v
                        d.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            is EditTextPreference -> {
                val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
                val edit = TextInputEditText(layout.context).apply { setText(preference.text) }
                layout.addView(edit)
                if (preference is EditTextIntPreference) {
                    preference.bindEditText(edit)
                } else {
                    // 剪贴板规则等：每行一条，允许多行
                    edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                val pad = (24 * resources.displayMetrics.density).toInt()
                val box = FrameLayout(ctx).apply {
                    setPadding(pad, pad / 3, pad, 0)
                    addView(layout)
                }
                materialDialog(ctx)
                    .setTitle(preference.dialogTitle ?: preference.title)
                    .setMessage(preference.dialogMessage)
                    .setView(box)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val v = edit.text?.toString().orEmpty()
                        if (preference.callChangeListener(v)) preference.text = v
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun setPreferenceScreen(preferenceScreen: PreferenceScreen?) {
        preferenceScreen?.let(::decorateForCards)
        super.setPreferenceScreen(preferenceScreen)
    }
}
