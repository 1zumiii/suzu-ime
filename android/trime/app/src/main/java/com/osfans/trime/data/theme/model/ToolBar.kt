/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme.model

import com.osfans.trime.ime.keyboard.KeyCommand

data class ToolBar(
    val primaryButton: Button? = null,
    val buttons: List<Button> = emptyList(),
    val buttonSpacing: Int = 18,
    val buttonFont: List<String> = emptyList(),
    val backStyle: String = "ic@arrow-left",
) {
    data class Button(
        val background: Background = Background(),
        val foreground: Foreground = Foreground(),
        val command: KeyCommand? = null,
        val longPressCommand: KeyCommand? = null,
        val size: List<Int> = emptyList(),
    ) {
        data class Background(
            val type: Type = Type.RECTANGLE,
            val cornerRadius: Float = 10f,
            val normal: String = "",
            val highlight: String = "",
            val verticalInset: Int = 4,
            val horizontalInset: Int = 4,
        ) {
            enum class Type {
                RECTANGLE,
                CIRCLE,
            }
        }
        data class Foreground(
            val style: String = "",
            val optionStyles: List<String> = emptyList(),
            val normal: String = "",
            val highlight: String = "",
            val fontSize: Float = 18f,
            val padding: Int = 4,
        )
    }
}
