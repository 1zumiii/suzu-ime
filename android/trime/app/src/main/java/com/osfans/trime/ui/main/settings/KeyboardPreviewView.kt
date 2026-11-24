/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.annotation.Keep
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizePx
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.BuiltinTheme
import com.osfans.trime.data.theme.ColorScheme
import com.osfans.trime.ime.keyboard.EnterLabel
import com.osfans.trime.ime.keyboard.KeyState
import com.osfans.trime.ime.keyboard.Pages
import com.osfans.trime.ime.keyboard.cornerLabel
import com.osfans.trime.ime.keyboard.displayLabel
import com.osfans.trime.ime.keyboard.isIconFont
import com.osfans.trime.ime.keyboard.layoutPage
import com.osfans.trime.ime.keyboard.toIconName
import com.osfans.trime.util.isNightMode

/**
 * 虚拟键盘设置页顶部的示意图：用主键盘的真实布局，按当前的配色、按键背景、键盘高度、
 * 垂直间距和按键提示，等比缩小画出按键区。设置改了立即重画，不画工具栏和候选栏。
 */
@SuppressLint("ViewConstructor")
class KeyboardPreviewView(context: Context) : View(context) {
    private val prefs = AppPrefs.defaultInstance().keyboard
    private val page = Pages.get(Pages.DEFAULT)
    private val style = BuiltinTheme.theme.style
    private val state = KeyState(enterLabel = "ic@keyboard_return")

    private val keyTypeface: Typeface = runCatching { BuiltinTheme.theme.fonts.key }.getOrDefault(Typeface.DEFAULT)
    private val cornerTypeface: Typeface = runCatching { BuiltinTheme.theme.fonts.symbol }.getOrDefault(Typeface.DEFAULT)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val icons = HashMap<String, IconicsDrawable>()

    @Keep
    private val onChange = PreferenceDelegate.OnChangeListener<Any> { _, _ ->
        requestLayout()
        invalidate()
    }

    init {
        listOf(prefs.keyboardHeight, prefs.keyVerticalGap, prefs.keyBackground, prefs.colorMode, prefs.hideKeySymbol, prefs.hideKeyHint)
            .forEach { it.registerOnChangeListener(onChange) }
    }

    /** 示意图宽度和屏幕宽度之比，键盘的高度、间距和字号都按它缩小 */
    private val scale: Float
        get() = if (width > 0) width.toFloat() / resources.displayMetrics.widthPixels else 1f

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val ratio = w.toFloat() / resources.displayMetrics.widthPixels
        setMeasuredDimension(w, (dp(prefs.keyboardHeight.getValue().toFloat()) * ratio).toInt())
    }

    private fun scheme(): ColorScheme {
        val dark = when (prefs.colorMode.getValue()) {
            AppPrefs.Keyboard.ColorMode.FOLLOW_SYSTEM -> resources.configuration.isNightMode()
            AppPrefs.Keyboard.ColorMode.LIGHT -> false
            AppPrefs.Keyboard.ColorMode.DARK -> true
        }
        val base = BuiltinTheme.theme.presetColorSchemes.getValue(if (dark) BuiltinTheme.DARK_SCHEME else BuiltinTheme.LIGHT_SCHEME)
        return if (prefs.keyBackground.getValue()) base + BuiltinTheme.keyBackgroundOverlay(dark) else base
    }

    private fun ColorScheme.color(name: String?, fallback: String): Int =
        runCatching { Color.parseColor(this[name] ?: this.getValue(fallback)) }.getOrDefault(Color.TRANSPARENT)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = scheme()
        val k = scale
        val gapX = dp(style.horizontalGap.toFloat()) * k / 2
        val gapY = dp(prefs.keyVerticalGap.getValue().toFloat()) * k / 2
        val radius = dp(style.roundCorner) * k
        // 整块键盘的底色，圆角卡片
        keyPaint.color = s.color("keyboard_back_color", "keyboard_back_color")
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, dp(CARD_RADIUS_DP), dp(CARD_RADIUS_DP), keyPaint)
        // 按键区四周留一点边，靠边的键不被卡片圆角切到
        val inset = dp(CARD_INSET_DP)
        canvas.save()
        canvas.translate(inset, inset)
        for (key in layoutPage(page, (width - 2 * inset).toInt(), (height - 2 * inset).toInt())) {
            val spec = key.spec
            rect.set(key.x + gapX, key.y + gapY, key.x + key.width - gapX, key.y + key.height - gapY)
            keyPaint.color = s.color(spec.tone.back, "key_back_color")
            if (Color.alpha(keyPaint.color) > 0) canvas.drawRoundRect(rect, radius, radius, keyPaint)

            val textColor = s.color(spec.tone.text, "key_text_color")
            val label = spec.displayLabel(state).let { if (it == EnterLabel) state.enterLabel else it }
            val size = sp(
                spec.textSp.takeIf { it > 0 }
                    ?: if (label.length > 1 && !label.isIconFont) style.keyLongTextSize else style.keyTextSize,
            ) * k
            if (label.isIconFont) {
                val icon = icons.getOrPut(label) { IconicsDrawable(context, label.toIconName()) }
                icon.sizePx = size.toInt()
                icon.colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN)
                val half = size.toInt() / 2
                icon.setBounds(rect.centerX().toInt() - half, rect.centerY().toInt() - half, rect.centerX().toInt() + half, rect.centerY().toInt() + half)
                icon.draw(canvas)
            } else if (label.isNotEmpty()) {
                textPaint.color = textColor
                textPaint.textSize = size
                textPaint.typeface = keyTypeface
                val m = textPaint.fontMetrics
                canvas.drawText(label, rect.centerX(), rect.centerY() - (m.ascent + m.descent) / 2f, textPaint)
            }

            val corner = spec.cornerLabel(state)
            if (corner.isNotEmpty() && !prefs.hideKeySymbol.getValue()) {
                cornerPaint.color = s.color("key_symbol_color", "key_text_color")
                cornerPaint.textSize = sp(spec.cornerSp.takeIf { it > 0f } ?: style.symbolTextSize) * k
                cornerPaint.typeface = cornerTypeface
                canvas.drawText(corner, rect.centerX(), rect.top - cornerPaint.fontMetrics.top, cornerPaint)
            }
        }
        canvas.restore()
    }

    private companion object {
        const val CARD_RADIUS_DP = 20f
        const val CARD_INSET_DP = 6f
    }
}
