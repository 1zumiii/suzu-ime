/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.osfans.trime.data.theme.ColorManager

/** 按键视图依赖的全部外部能力，按键本身不接触 Rime 和窗口 */
interface KeyHost {
    val palette: KeyPalette
    val metrics: KeyMetrics

    fun state(): KeyState

    fun composingLength(): Int

    fun run(command: KeyCommand)

    fun onKeyDown(spec: KeySpec)

    fun showPreview(viewId: Int, content: String, bounds: Rect)

    fun dismissPreview(viewId: Int)
}

class KeyMetrics(
    val roundCorner: Float,
    val textSp: Float,
    val longTextSp: Float,
    val cornerSp: Float,
    val horizontalGapPx: Int,
    val verticalGapPx: Int,
    val keyTypeface: Typeface,
    val cornerTypeface: Typeface,
    val hideCorner: Boolean,
    val hideHint: Boolean,
)

class KeyLook(val background: Drawable?, val text: Int, val corner: Int)

/** 按键配色，按颜色名取自当前的配色方案（BuiltinTheme 内置浅色和深色两套） */
class KeyPalette {
    private fun color(name: String?, fallback: () -> Int): Int =
        name?.let { runCatching { ColorManager.getColor(it) }.getOrNull() } ?: fallback()

    private fun drawable(name: String?): Drawable? = name?.let { ColorManager.getDrawable(it) }

    fun lookFor(spec: KeySpec, pressed: Boolean, on: Boolean): KeyLook {
        val baseBack = drawable(spec.tone.back) ?: drawable("key_back_color")
        val pressedBack = drawable("hilited_key_back_color")
        val baseText = color(spec.tone.text) { ColorManager.getColor("key_text_color") }
        val pressedText = color("hilited_key_text_color") { baseText }
        val baseCorner = color("key_symbol_color") { baseText }
        val pressedCorner = color("hilited_key_symbol_color") { baseCorner }
        return when {
            on -> if (pressed) {
                KeyLook(drawable("hilited_on_key_back_color") ?: pressedBack, color("hilited_on_key_text_color") { pressedText }, color("hilited_on_key_symbol_color") { pressedCorner })
            } else {
                KeyLook(drawable("on_key_back_color") ?: baseBack, color("on_key_text_color") { baseText }, color("on_key_symbol_color") { baseCorner })
            }
            spec.functional -> if (pressed) {
                KeyLook(drawable("hilited_off_key_back_color") ?: pressedBack, color("hilited_off_key_text_color") { pressedText }, color("hilited_off_key_symbol_color") { pressedCorner })
            } else {
                KeyLook(
                    if (spec.tone.back != null) baseBack else drawable("off_key_back_color") ?: baseBack,
                    color(spec.tone.text) { color("off_key_text_color") { baseText } },
                    color("off_key_symbol_color") { baseCorner },
                )
            }
            pressed -> KeyLook(pressedBack, pressedText, pressedCorner)
            else -> KeyLook(baseBack, baseText, baseCorner)
        }
    }
}
