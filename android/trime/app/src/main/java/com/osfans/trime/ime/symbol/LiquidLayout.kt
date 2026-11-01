// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.symbol

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.keyboard.KeyCommand
import com.osfans.trime.ime.keyboard.Nav
import splitties.dimensions.dp

/**
 * 流式键盘的整体布局：一个符号网格 + 一条固定按键条（位置 top/bottom/left/right）。
 * 就是一个线性布局：网格占满剩下的空间，按键条固定厚度、里面的按钮等分。
 */
@SuppressLint("ViewConstructor")
class LiquidLayout(
    context: Context,
    private val scope: ThemeScope,
    private val runCommand: (KeyCommand) -> Unit,
) : LinearLayout(context) {
    private val theme: Theme get() = scope.theme
    private val position = theme.liquidKeyboard.fixedKeyBar.position
    private val barIsVertical =
        position == LiquidKeyboard.KeyBar.Position.LEFT || position == LiquidKeyboard.KeyBar.Position.RIGHT

    // The fixed-key items, kept so their colors can be refreshed on scheme switches.
    private val fixedKeyItems = mutableListOf<LiquidItemUi>()

    val tabsUi = LiquidTabsUi(context, scope)

    val recyclerView = RecyclerView(context)

    private fun buildBar(): LinearLayout? {
        return LinearLayout(context).apply {
            orientation = if (barIsVertical) VERTICAL else HORIZONTAL
            BAR_KEYS.forEach { (label, command) ->
                // 按键条上的文字比格子里的符号小一些、不画按键背景；按钮等分整条
                val ui = LiquidItemUi(context, scope, textScale = BAR_TEXT_SCALE, flat = true)
                fixedKeyItems += ui
                ui.mainText.text = label
                ui.root.apply {
                    isRepeatable = command == KeyCommand.Special(Nav.Backspace)
                    onClick = { runCommand(command) }
                }
                addView(
                    ui.root,
                    if (barIsVertical) LayoutParams(MATCH_PARENT, 0, 1f) else LayoutParams(0, MATCH_PARENT, 1f),
                )
            }
        }
    }

    init {
        orientation = if (barIsVertical) HORIZONTAL else VERTICAL
        val bar = buildBar()
        val barParams =
            if (barIsVertical) LayoutParams(dp(BAR_THICKNESS_DP), MATCH_PARENT) else LayoutParams(MATCH_PARENT, dp(BAR_THICKNESS_DP))
        val gridParams = if (barIsVertical) LayoutParams(0, MATCH_PARENT, 1f) else LayoutParams(MATCH_PARENT, 0, 1f)
        val barFirst = position == LiquidKeyboard.KeyBar.Position.TOP || position == LiquidKeyboard.KeyBar.Position.LEFT
        if (bar != null && barFirst) addView(bar, barParams)
        addView(recyclerView, gridParams)
        if (bar != null && !barFirst) addView(bar, barParams)
    }

    fun refreshColors() {
        fixedKeyItems.forEach { it.refreshColors() }
        tabsUi.refreshColors()
        recyclerView.adapter?.notifyDataSetChanged()
    }

    private companion object {
        val BAR_KEYS = listOf(
            "返回" to KeyCommand.ExitLiquid,
            "空格" to KeyCommand.Special(Nav.Space),
            "退格" to KeyCommand.Special(Nav.Backspace),
            "回车" to KeyCommand.Special(Nav.Return),
            "剪贴" to KeyCommand.OpenClipboard,
            "更多" to KeyCommand.OpenLiquid("更多"),
        )
        const val BAR_TEXT_SCALE = 0.62f
        const val BAR_THICKNESS_DP = 44   // 和表情页底部的导航行差不多高，避免太矮误触
    }
}
