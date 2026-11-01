/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

open class GestureFrame(context: Context) : FrameLayout(context) {

    private var touchId = 0
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var startTime = 0L

    private var isLongPressed = false
    private var slideActivated = false
    private var slideStepped = false
    private var swipeTriggered = false

    private var longPressJob: Job? = null
    private var repeatJob: Job? = null
    private var doubleTapJob: Job? = null

    private var lastTapTime = 0L
    private var lastSwipeBehavior: KeyBehavior = KeyBehavior.CLICK

    private val lifecycleScope by lazy {
        findViewTreeLifecycleOwner()?.lifecycleScope!!
    }

    var onClick: (() -> Unit)? = null
    var onDoubleClick: (() -> Unit)? = null
    var onLazyDoubleClick: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null

    var onSlide: ((delta: Int, x: Float, y: Float) -> Unit)? = null

    var onPress: (() -> Unit)? = null
    var onRelease: ((behavior: KeyBehavior, longPress: Boolean) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onMove: ((x: Float, y: Float, longPress: Boolean) -> Unit)? = null
    var onSwipe: ((behavior: KeyBehavior) -> Unit)? = null

    /** 这个键定义了动作的滑动方向；其他方向的滑动松手时仍算点按 */
    var swipeBehaviors: Set<KeyBehavior> = emptySet()

    var isRepeatable = false

    /** 长按连按开始时调用；返回的函数在每次连按前检查，返回 false 就停止连按 */
    var onRepeatStart: (() -> () -> Boolean)? = null
    var isSlideCursor = false
    var isSlideDelete = false

    var hasLongPress = false
    var hasDouble = false
    var hasLazyDouble = false
    var hasPopup = false

    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
        // avoid gaining focus unexpectedly
        isFocusable = false
        isFocusableInTouchMode = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                touchId = (touchId + 1) and 0xFFFF
                val currentTouchId = touchId
                startX = x
                startY = y
                lastX = startX
                startTime = SystemClock.elapsedRealtime()

                isLongPressed = false
                slideActivated = false
                slideStepped = false
                swipeTriggered = false
                lastSwipeBehavior = KeyBehavior.CLICK

                drawableHotspotChanged(x, y)
                isPressed = true
                if (vibrateOnKeyPress) InputFeedbackManager.keyPressVibrate(this)
                onPress?.invoke()

                if (hasLongPress || isRepeatable || hasPopup) {
                    startLongPressJob(currentTouchId)
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)

                val dx = x - startX
                val dy = y - startY

                onMove?.invoke(x, y, isLongPressed)

                if ((isSlideCursor || isSlideDelete) && onSlide != null && !isLongPressed && swipeTravel > 0) {
                    if (!slideActivated) {
                        if (abs(dx) >= swipeTravelPx) {
                            slideActivated = true
                            lastX = startX
                        }
                    }

                    if (slideActivated) {
                        val step = getNStep(lastX, x, slideStepSize.toFloat())
                        if (step != 0) {
                            onSlide?.invoke(step, x, y)
                            slideStepped = true
                            lastX = x
                        }
                    }
                }

                if (!isLongPressed) {
                    val behavior = detectSwipe(dx, dy)
                    if (behavior != lastSwipeBehavior) {
                        lastSwipeBehavior = behavior
                        if (behavior != KeyBehavior.CLICK) {
                            onSwipe?.invoke(behavior)
                        }
                    }
                }

                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = x - startX
                val dy = y - startY

                isPressed = false
                if (vibrateOnKeyRelease) InputFeedbackManager.keyPressVibrate(this)
                cancelJobs()

                // 滑动真的移动了光标或删了字才吞掉这次点按；只是手指偏移了一点，仍按点按处理
                if (slideActivated && slideStepped) {
                    onSlide?.invoke(0, x, y)
                    onCancel?.invoke()
                    return true
                }

                if (isLongPressed) {
                    dispatchBehavior(KeyBehavior.LONG_CLICK, true)
                    return true
                }

                if (swipeTriggered && lastSwipeBehavior in swipeBehaviors) {
                    dispatchBehavior(lastSwipeBehavior, false)
                    return true
                }

                if (!hasDouble && !hasLazyDouble) {
                    dispatchBehavior(KeyBehavior.CLICK, false)
                    return true
                }

                val now = SystemClock.elapsedRealtime()
                val delta = now - lastTapTime
                if (delta <= doubleTapTimeout) {
                    lastTapTime = 0
                    doubleTapJob?.cancel()
                    if (hasDouble) {
                        dispatchBehavior(KeyBehavior.DOUBLE_CLICK, false)
                    } else {
                        dispatchBehavior(KeyBehavior.LAZY_DOUBLE_CLICK, false)
                    }
                } else {
                    lastTapTime = now
                    if (hasLazyDouble && !hasDouble) {
                        doubleTapJob = lifecycleScope.launch {
                            delay(doubleTapTimeout.toLong())
                            if (lastTapTime == now) {
                                lastTapTime = 0
                                dispatchBehavior(KeyBehavior.CLICK, false)
                            }
                        }
                    } else {
                        dispatchBehavior(KeyBehavior.CLICK, false)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                cancelJobs()

                isLongPressed = false
                slideActivated = false
                swipeTriggered = false

                onCancel?.invoke()
                return true
            }
        }

        return true
    }

    private fun startLongPressJob(currentTouchId: Int) {
        longPressJob = lifecycleScope.launch {
            delay(longPressTimeout.toLong())
            if (touchId != currentTouchId) return@launch
            if (swipeTriggered || slideActivated) return@launch
            isLongPressed = true

            if (vibrateOnKeyPress) InputFeedbackManager.keyPressVibrate(this@GestureFrame, true)

            if (isRepeatable) {
                startRepeatJob()
            } else {
                performLongClick()
            }
        }
    }

    private fun startRepeatJob() {
        val keepRepeating = onRepeatStart?.invoke()
        repeatJob = lifecycleScope.launch {
            try {
                while (keepRepeating?.invoke() != false) {
                    if (vibrateOnKeyRepeat) InputFeedbackManager.keyPressVibrate(this@GestureFrame)
                    dispatchBehavior(KeyBehavior.CLICK, true)
                    delay(repeatInterval.toLong())
                }
            } finally {
                onCancel?.invoke()
            }
        }
    }

    private fun detectSwipe(dx: Float, dy: Float): KeyBehavior {
        val absDx = abs(dx)
        val absDy = abs(dy)

        val distance = if (absDx > absDy) absDx else absDy
        val elapsed = SystemClock.elapsedRealtime() - startTime

        val velocity = if (elapsed > 0) {
            (distance / elapsed) * 1000f
        } else {
            0f
        }

        val isSwipe =
            (swipeTravel > 0 && distance >= swipeTravelPx) ||
                (swipeVelocity > 0 && velocity >= swipeVelocity * resources.displayMetrics.density)
        swipeTriggered = isSwipe

        if (!isSwipe) return KeyBehavior.CLICK
        return if (absDx > absDy) {
            if (dx > 0) KeyBehavior.SWIPE_RIGHT else KeyBehavior.SWIPE_LEFT
        } else {
            if (dy > 0) KeyBehavior.SWIPE_DOWN else KeyBehavior.SWIPE_UP
        }
    }

    private fun dispatchBehavior(
        behavior: KeyBehavior,
        longPress: Boolean,
    ) {
        onRelease?.invoke(behavior, longPress)
        when (behavior) {
            KeyBehavior.CLICK -> performClick()
            KeyBehavior.DOUBLE_CLICK -> onDoubleClick?.invoke()
            KeyBehavior.LAZY_DOUBLE_CLICK -> onLazyDoubleClick?.invoke()
            KeyBehavior.SWIPE_LEFT -> onSwipeLeft?.invoke()
            KeyBehavior.SWIPE_RIGHT -> onSwipeRight?.invoke()
            KeyBehavior.SWIPE_UP -> onSwipeUp?.invoke()
            KeyBehavior.SWIPE_DOWN -> onSwipeDown?.invoke()
            else -> {}
        }
    }

    private fun cancelJobs() {
        longPressJob?.cancel()
        repeatJob?.cancel()
        doubleTapJob?.cancel()
    }

    fun getNStep(start: Float, end: Float, step: Float): Int = (if (start < end) 1 else -1) *
        floor(abs(end - start) / step).toInt()

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        hasLongPress = l != null
        super.setOnLongClickListener(l)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onClick?.invoke()
        return true
    }

    override fun performLongClick(): Boolean {
        val handled = super.performLongClick()
        onLongClick?.invoke()
        return handled || onLongClick != null
    }

    // 设置里滑动距离与速度的单位是 dp，触摸坐标是像素
    private val swipeTravelPx get() = swipeTravel * resources.displayMetrics.density

    companion object {
        private val swipeTravel by AppPrefs.defaultInstance().keyboard.swipeTravel
        private val swipeVelocity by AppPrefs.defaultInstance().keyboard.swipeVelocity
        private val longPressTimeout by AppPrefs.defaultInstance().keyboard.longPressTimeout
        private val repeatInterval by AppPrefs.defaultInstance().keyboard.repeatInterval
        private val doubleTapTimeout by AppPrefs.defaultInstance().keyboard.doubleTapTimeout
        private val slideStepSize by AppPrefs.defaultInstance().keyboard.slideStepSize
        private val vibrateOnKeyPress by AppPrefs.defaultInstance().keyboard.vibrateOnKeyPress
        private val vibrateOnKeyRelease by AppPrefs.defaultInstance().keyboard.vibrateOnKeyRelease
        private val vibrateOnKeyRepeat by AppPrefs.defaultInstance().keyboard.vibrateOnKeyRepeat
    }
}
