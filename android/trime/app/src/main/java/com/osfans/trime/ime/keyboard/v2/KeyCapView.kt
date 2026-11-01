/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard.v2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.util.sp
import splitties.dimensions.dp

/** 单个按键：把手势转成对 [host] 的调用，并按传入的状态绘制 */
@SuppressLint("ViewConstructor")
class KeyCapView(
    context: Context,
    private val spec: KeySpec,
    private val host: KeyHost,
) : GestureFrame(context) {
    private var keyPressed = false

    override fun isPressed() = keyPressed

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var icon: IconicsDrawable? = null
    private var iconName: String? = null
    private val location = intArrayOf(0, 0)
    private val bounds = Rect()

    init {
        setWillNotDraw(false)
        isRepeatable = spec.repeats
        isSlideCursor = spec.slideCursor
        hasLongPress = spec.long != null
        hasDouble = spec.double != null || spec.isShift
        swipeBehaviors = buildSet {
            if (spec.swipeUp != null) add(KeyBehavior.SWIPE_UP)
            if (spec.swipeDown != null) add(KeyBehavior.SWIPE_DOWN)
            if (spec.swipeLeft != null) add(KeyBehavior.SWIPE_LEFT)
            if (spec.swipeRight != null) add(KeyBehavior.SWIPE_RIGHT)
        }
        // 长按退格：开始时候选区有拼音，这一次长按最多删光拼音，不接着删已经上屏的字
        if ((spec.command as? KeyCommand.Special)?.nav == Nav.Backspace) {
            onRepeatStart = {
                // 状态更新比连按慢半拍，只看“是否还在输入”会多删一个上屏的字，所以同时按拼音的字母数限定次数
                val startedComposing = host.state().composing
                var left = host.composingLength()
                val keepRepeating: () -> Boolean = { !startedComposing || (left-- > 0 && host.state().composing) }
                keepRepeating
            }
        }

        onPress = {
            setKeyPressed(true)
            host.onKeyDown(spec)
            showPreview(spec.previewText(host.state()))
        }
        onRelease = { behavior, fromLongPress ->
            if (fromLongPress) {
                if (isRepeatable) spec.activeCommand(host.state())?.let(host::run)
            } else {
                commandFor(behavior)?.let(host::run)
                setKeyPressed(false)
                host.dismissPreview(id)
            }
        }
        onSwipe = { behavior ->
            setKeyPressed(true)
            commandFor(behavior)?.defaultLabel(host.state())?.let(::showPreview)
        }
        onSlide = { delta, _, _ ->
            when {
                delta > 0 -> host.run(KeyCommand.Special(Nav.Right))
                delta < 0 -> host.run(KeyCommand.Special(Nav.Left))
            }
        }
        onLongClick = {
            spec.long?.let(host::run)
            setKeyPressed(false)
            host.dismissPreview(id)
        }
        onCancel = {
            setKeyPressed(false)
            host.dismissPreview(id)
        }
    }

    private fun commandFor(behavior: KeyBehavior): KeyCommand? = when (behavior) {
        KeyBehavior.CLICK -> spec.activeCommand(host.state())
        KeyBehavior.DOUBLE_CLICK -> spec.double ?: if (spec.isShift) KeyCommand.ShiftLock else null
        KeyBehavior.SWIPE_UP -> spec.swipeUp
        KeyBehavior.SWIPE_DOWN -> spec.swipeDown
        KeyBehavior.SWIPE_LEFT -> spec.swipeLeft
        KeyBehavior.SWIPE_RIGHT -> spec.swipeRight
        else -> null
    }

    private fun setKeyPressed(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            invalidate()
        }
    }

    private fun showPreview(text: String) {
        if (text.isEmpty()) return
        getLocationInWindow(location)
        bounds.set(location[0], location[1], location[0] + width, location[1] + height)
        val content = if (text.startsWith(ICON_PREFIX)) text else String(Character.toChars(text.codePointAt(0)))
        host.showPreview(id, content, bounds)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = host.state()
        val look = host.palette.lookFor(spec, keyPressed, spec.isShift && state.shifted)
        look.background?.let {
            if (it is GradientDrawable) it.cornerRadius = dp(host.metrics.roundCorner)
            it.setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
            it.draw(canvas)
        }
        val label = spec.displayLabel(state)
        if (label.isNotEmpty()) drawLabel(canvas, label, look.text)
        val corner = spec.cornerLabel(state)
        if (corner.isNotEmpty() && !host.metrics.hideCorner) drawCorner(canvas, corner, look.corner, isTop = true)
        val hint = spec.hint.orEmpty()
        if (hint.isNotEmpty() && !host.metrics.hideHint) drawCorner(canvas, hint, look.corner, isTop = false)
    }

    private val centerX get() = (width - paddingLeft - paddingRight) / 2f + paddingLeft

    private fun drawLabel(canvas: Canvas, label: String, color: Int) {
        val isIcon = label.startsWith(ICON_PREFIX)
        val size = sp(
            spec.textSp.takeIf { it > 0 }
                ?: if (label.length > 1 && !isIcon) host.metrics.longTextSp else host.metrics.textSp,
        )
        if (isIcon) {
            drawIcon(canvas, label, size.toInt(), color)
            return
        }
        textPaint.color = color
        textPaint.textSize = size
        textPaint.typeface = host.metrics.keyTypeface
        val metrics = textPaint.fontMetrics
        val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
        canvas.drawText(label, centerX, centerY - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    private fun drawIcon(canvas: Canvas, label: String, size: Int, color: Int) {
        val name = label.replace(ICON_PREFIX, "cmd_")
        val drawable = icon.takeIf { iconName == name }
            ?: IconicsDrawable(context, name).apply { sizeDp = size }.also {
                icon = it
                iconName = name
            }
        drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        val half = size / 2
        val cx = centerX.toInt()
        val cy = ((height - paddingTop - paddingBottom) / 2f + paddingTop).toInt()
        drawable.setBounds(cx - half, cy - half, cx + half, cy + half)
        drawable.draw(canvas)
    }

    private fun drawCorner(canvas: Canvas, text: String, color: Int, isTop: Boolean) {
        cornerPaint.color = color
        cornerPaint.textSize = sp(spec.cornerSp.takeIf { it > 0f } ?: host.metrics.cornerSp)
        cornerPaint.typeface = host.metrics.cornerTypeface
        val metrics = cornerPaint.fontMetrics
        val y = if (isTop) paddingTop - metrics.top else height - paddingBottom - metrics.bottom
        canvas.drawText(text, centerX, y, cornerPaint)
    }

    private companion object {
        const val ICON_PREFIX = "ic@"
    }
}
