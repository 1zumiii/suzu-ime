/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.keyboard.KeyCommand
import com.osfans.trime.ime.keyboard.Nav
import com.osfans.trime.ime.keyboard.Pages
import com.osfans.trime.ime.keyboard.isIconFont
import com.osfans.trime.ime.keyboard.toIconName
import splitties.dimensions.dp

/**
 * 符号面板的底栏：左边回字母键盘（显示“拼音”或“ABC”）和数字小键盘，右边空格、退格、回车。
 * 其他面板顶部已有返回键，不带底栏。
 */
class PanelBottomBar(
    context: Context,
    private val scope: ThemeScope,
    private val run: (KeyCommand) -> Unit,
) : LinearLayout(context) {
    private class Item(val ui: PanelItemUi, val weight: Float, val icon: ImageView? = null)

    private val back = item("拼音", KeyCommand.ShowKeyboard(Pages.DEFAULT), 1.2f, bold = true)
    private val items = listOf(
        back,
        item("123", KeyCommand.ShowKeyboard(Pages.NUMPAD), 1.2f),
        null,
        item("空格", KeyCommand.Special(Nav.Space), 1.4f),
        item("ic@backspace_outline", KeyCommand.Special(Nav.Backspace), 1f),
        item("ic@keyboard_return", KeyCommand.Special(Nav.Return), 1f),
    )

    init {
        orientation = HORIZONTAL
        items.forEach { item ->
            if (item == null) {
                addView(android.view.View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 2.2f))
            } else {
                addView(item.ui.root, LayoutParams(0, LayoutParams.MATCH_PARENT, item.weight))
            }
        }
    }

    private fun item(label: String, command: KeyCommand, weight: Float, bold: Boolean = false): Item {
        val ui = PanelItemUi(context, scope, textScale = TEXT_SCALE, flat = true)
        // 图标和键盘上的退格、回车用同一套字体图标，大小一致
        val icon = if (label.isIconFont) {
            ImageView(context).apply {
                setImageDrawable(IconicsDrawable(context, label.toIconName()).apply { sizeDp = ICON_DP })
                imageTintList = ColorStateList.valueOf(scope.colors.keyTextColor)
                ui.root.addView(this, FrameLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP), Gravity.CENTER))
            }
        } else {
            ui.mainText.text = label
            if (bold) ui.mainText.setTypeface(ui.mainText.typeface, Typeface.BOLD)
            null
        }
        ui.root.isRepeatable = command == KeyCommand.Special(Nav.Backspace)
        ui.root.onClick = { run(command) }
        return Item(ui, weight, icon)
    }

    /** 返回键的文字跟随中英文状态 */
    fun setAsciiMode(ascii: Boolean) {
        back.ui.mainText.text = if (ascii) "ABC" else "拼音"
    }

    fun refreshColors() = items.forEach { item ->
        item?.ui?.refreshColors()
        item?.icon?.imageTintList = ColorStateList.valueOf(scope.colors.keyTextColor)
    }

    companion object {
        const val TEXT_SCALE = 0.62f
        private const val ICON_DP = 22

        /** 和键盘最下一行差不多高，避免太矮误触 */
        const val HEIGHT_DP = 44

        fun heightPx(context: Context) = context.dp(HEIGHT_DP)
    }
}
