/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard.v2

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import androidx.core.view.children

/** 一页键盘。尺寸确定后才摆放按键，尺寸变化（如旋转屏幕）时重新摆放 */
@SuppressLint("ViewConstructor")
class KeyPageView(
    context: Context,
    val page: Page,
    private val host: KeyHost,
) : FrameLayout(context) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) post { placeKeys(w, h) }
    }

    private fun placeKeys(w: Int, h: Int) {
        removeAllViews()
        val gapX = host.metrics.horizontalGapPx / 2
        val gapY = host.metrics.verticalGapPx / 2
        layoutPage(page, w, h).forEachIndexed { index, rect ->
            addView(
                KeyCapView(context, rect.spec, host).apply {
                    id = index
                    translationX = rect.x.toFloat()
                    translationY = rect.y.toFloat()
                    setPadding(gapX, gapY, gapX, gapY)
                },
                LayoutParams(rect.width, rect.height),
            )
        }
    }

    fun repaint() = children.forEach { it.invalidate() }
}
