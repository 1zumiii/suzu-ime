/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs.Advanced
import com.osfans.trime.data.prefs.AppPrefs.Clipboard
import com.osfans.trime.data.prefs.AppPrefs.General
import com.osfans.trime.data.prefs.AppPrefs.Keyboard

/**
 * 设置页的分组：AppPrefs 只按页面定义偏好，这里把同一页的项按用途分成几组，每组显示成一张卡片。
 * 没列进分组的项放在最后一组（无标题），新加的偏好不会因此消失。
 */
object SettingsSections {
    private class Section(
        @StringRes val title: Int,
        val keys: List<String>,
    )

    private fun sectionsFor(provider: PreferenceDelegateProvider): List<Section> =
        when (provider) {
            is General -> listOf(
                Section(R.string.section_input, listOf(General.INLINE_PREEDIT_MODE, General.ASCII_SWITCH_TIPS, General.INLINE_SUGGESTIONS)),
            )
            is Keyboard -> listOf(
                Section(
                    R.string.section_layout_appearance,
                    listOf(
                        Keyboard.COLOR_MODE,
                        Keyboard.KEY_BACKGROUND,
                        Keyboard.KEYBOARD_HEIGHT,
                        Keyboard.KEY_VERTICAL_GAP,
                        Keyboard.LANDSCAPE_MODE,
                        Keyboard.USE_SOFT_CURSOR,
                        Keyboard.HIDE_INPUT_BAR,
                        Keyboard.HIDE_KEY_SYMBOL,
                        Keyboard.HIDE_KEY_HINT,
                    ),
                ),
                Section(
                    R.string.section_sound_vibration,
                    listOf(
                        Keyboard.SOUND_ON_KEYPRESS,
                        Keyboard.KEY_SOUND_VOLUME,
                        Keyboard.USE_CUSTOM_SOUND_EFFECT,
                        Keyboard.CUSTOM_SOUND_EFFECT,
                        Keyboard.VIBRATE_ON_KEY_PRESS,
                        Keyboard.VIBRATE_ON_KEY_RELEASE,
                        Keyboard.VIBRATE_ON_KEY_REPEAT,
                        Keyboard.VIBRATION_DURATION,
                        Keyboard.VIBRATION_AMPLITUDE,
                    ),
                ),
                Section(
                    R.string.section_key_feedback,
                    listOf(Keyboard.POPUP_ON_KEY_PRESS, Keyboard.SPEAK_ON_KEYPRESS, Keyboard.SPEAK_ON_COMMIT),
                ),
                Section(
                    R.string.section_key_gestures,
                    listOf(
                        Keyboard.LONG_PRESS_TIMEOUT,
                        Keyboard.REPEAT_INTERVAL,
                        Keyboard.DOUBLE_TAP_TIMEOUT,
                        Keyboard.SWIPE_TRAVEL,
                        Keyboard.SWIPE_VELOCITY,
                        Keyboard.SLIDE_STEP_SIZE,
                    ),
                ),
                Section(
                    R.string.section_candidates,
                    listOf(Keyboard.HORIZONTAL_CANDIDATE_MODE, Keyboard.MAX_SPAN_COUNT, Keyboard.MAX_SPAN_COUNT_LANDSCAPE),
                ),
                Section(
                    R.string.section_shortcuts,
                    listOf(Keyboard.HOOK_CTRL_A, Keyboard.HOOK_CTRL_CV, Keyboard.HOOK_CTRL_LR, Keyboard.HOOK_CTRL_ZY),
                ),
            )
            is Clipboard -> listOf(
                Section(
                    R.string.section_records,
                    listOf(
                        Clipboard.CLIPBOARD_LISTENING,
                        Clipboard.CLIPBOARD_LIMIT,
                        Clipboard.CLIPBOARD_COMPARE_RULES,
                        Clipboard.CLIPBOARD_OUTPUT_RULES,
                    ),
                ),
                Section(
                    R.string.section_paste,
                    listOf(Clipboard.CLIPBOARD_SUGGESTION, Clipboard.CLIPBOARD_SUGGESTION_TIMEOUT, Clipboard.CLIPBOARD_RETURN_AFTER_PASTE),
                ),
            )
            is Advanced -> listOf(
                Section(R.string.section_app_appearance, listOf(Advanced.UI_MODE, Advanced.SHOW_APP_ICON)),
                Section(R.string.section_system_gestures, listOf(Advanced.IGNORE_SYSTEM_GESTURE_INSETS)),
            )
            else -> emptyList()
        }

    /** 把 [screen] 上平铺的偏好按分组移进各自的分类；[header] 放在最前面（如键盘预览） */
    fun arrange(
        screen: PreferenceScreen,
        provider: PreferenceDelegateProvider,
        header: Preference? = null,
    ) {
        val sections = sectionsFor(provider)
        val flat = (0 until screen.preferenceCount).map { screen.getPreference(it) }
        if (sections.isEmpty() && header == null) return
        screen.removeAll()
        header?.let(screen::addPreference)
        if (sections.isEmpty()) {
            flat.forEach {
                it.order = Preference.DEFAULT_ORDER
                screen.addPreference(it)
            }
            return
        }
        val byKey = flat.associateBy { it.key }
        val used = mutableSetOf<String>()
        for (section in sections) {
            val items = section.keys.mapNotNull { byKey[it] }
            if (items.isEmpty()) continue
            val category = PreferenceCategory(screen.context).apply { setTitle(section.title) }
            screen.addPreference(category)
            items.forEach {
                // 移进分组前清掉第一次加到页面时分配的序号，否则还按原来的顺序排
                it.order = Preference.DEFAULT_ORDER
                category.addPreference(it)
                used += it.key
            }
        }
        val rest = flat.filter { it.key !in used }
        if (rest.isNotEmpty()) {
            val category = PreferenceCategory(screen.context)
            screen.addPreference(category)
            rest.forEach {
                it.order = Preference.DEFAULT_ORDER
                category.addPreference(it)
            }
        }
    }
}
