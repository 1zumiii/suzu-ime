/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.data.theme.model.Preedit
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.data.theme.model.Window
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** 主题和样式配置  */
@Serializable
data class Theme(
    val name: String,
    val style: GeneralStyle = GeneralStyle(),
    val preedit: Preedit = Preedit(),
    val window: Window = Window(),
    val liquidKeyboard: LiquidKeyboard = LiquidKeyboard(),
    val presetKeys: Map<String, PresetKey> = emptyMap(),
    val presetKeyboards: Map<String, TextKeyboard> = emptyMap(),
    val presetColorSchemes: PresetColorSchemes = emptyMap(),
    val fallbackColors: Map<String, String> = emptyMap(),
    val toolBar: ToolBar = ToolBar(),
) {
    val fonts by lazy { ThemeFonts(this) }

    companion object {
        /**
         * Top-level keys a theme may declare: the sections reads plus
         * the metadata librime and the theme picker use. The theme linter
         * reports anything else, since the runtime ignores it.
         */
        internal val TOP_LEVEL_KEYS: Set<String> =
            setOf(
                "config_version",
                "name",
                "author",
                "description",
                "version",
                "style",
                "preedit",
                "window",
                "liquid_keyboard",
                "tool_bar",
                "preset_keys",
                "preset_keyboards",
                "preset_color_schemes",
                "fallback_colors",
            )
    }
}
