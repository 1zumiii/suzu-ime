/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.content.Context
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.AutoScaleTextView
import com.osfans.trime.ime.keyboard.GestureFrame
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.setPaddingDp

/**
 * 流式键盘里的一个按键（网格里的符号，或底部/顶部按键条里的按钮）。
 *
 * 尺寸完全由外面给：格子的宽高由网格/按键条决定，这里只负责
 * - [gapX]/[gapY]：按键四周留的空隙（像素），空隙算在格子内部，所以相邻格子之间的间距是它的两倍；
 * - [flat]：不画按键背景，只显示文字（按键条用）；
 * - [textScale]：相对主题按键字号的比例。
 */
class LiquidItemUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    textScale: Float = 1f,
    private val flat: Boolean = false,
    gapX: Int = 0,
    gapY: Int = 0,
) : Ui {
    private val theme: Theme get() = scope.theme

    val mainText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        background = null
        textSize = theme.style.keyTextSize * textScale
        typeface = theme.fonts.key
        gravity = gravityCenter
        scaleMode = AutoScaleTextView.Mode.Proportional   // 文字比格子宽时整体缩小
        setPaddingDp(4, 2, 4, 2)
        setTextColor(scope.colors.keyTextColor)
    }

    private fun keyBackground() = if (flat) {
        null
    } else {
        scope.decorDrawable(
            "key_back_color",
            "key_border_color",
            ctx.dp(theme.style.keyBorder),
            ctx.dp(theme.style.roundCorner),
        )
    }

    private val content = constraintLayout {
        background = keyBackground()
        add(mainText, lParams(matchConstraints, wrapContent) { centerInParent() })
    }

    override val root = view(::GestureFrame) {
        setPadding(gapX, gapY, gapX, gapY)
        add(content, lParams(matchParent, matchParent))
    }

    /** Restyles the item after a scheme switch. */
    fun refreshColors() {
        mainText.setTextColor(scope.colors.keyTextColor)
        content.background = keyBackground()
    }
}
