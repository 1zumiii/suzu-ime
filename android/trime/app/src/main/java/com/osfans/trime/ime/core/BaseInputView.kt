/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.res.Resources
import android.view.View
import android.view.WindowInsets
import android.widget.PopupMenu
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splitties.dimensions.dp
import splitties.views.dsl.core.withTheme
import kotlin.math.max

abstract class BaseInputView(
    val service: TrimeInputMethodService,
    val rime: RimeSession,
    val scope: ThemeScope,
) : ConstraintLayout(service) {
    /** The theme config; geometry is theme-scoped, colors go through [scope.colors]. */
    val theme: Theme
        get() = scope.theme

    protected abstract fun handleRimeMessage(it: RimeMessage<*>)

    private var messageHandlerJob: Job? = null

    private fun setupRimeMessageHandler() {
        messageHandlerJob =
            service.lifecycleScope.launch {
                rime.run { messageFlow }.collect {
                    handleRimeMessage(it)
                }
            }
    }

    var handleMessages = false
        set(value) {
            field = value
            if (field) {
                if (messageHandlerJob == null) {
                    setupRimeMessageHandler()
                }
            } else {
                messageHandlerJob?.cancel()
                messageHandlerJob = null
            }
        }

    val themedContext = context.withTheme(android.R.style.Theme_DeviceDefault_Settings)

    private var candidateActionMenu: PopupMenu? = null

    /** 怪词表：用户目录的 weird_words.tsv，每行一个词（后面可带制表符和时间戳）。Lua 重排时把表里的词排到同组候选的最后。 */
    private fun weirdFile() = DataManager.userDataDir.resolve("weird_words.tsv")

    private fun weirdWords(): List<String> =
        runCatching { weirdFile().readLines().map { it.substringBefore('\t') }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())

    private fun setWeird(text: String, mark: Boolean) {
        runCatching {
            val f = weirdFile()
            val old = if (f.exists()) f.readLines() else emptyList()
            val out = old.filter { it.substringBefore('\t') != text }.toMutableList()
            if (mark) out += "$text\t${System.currentTimeMillis() / 1000}"
            f.writeText(out.joinToString("\n") + if (out.isEmpty()) "" else "\n")
        }
    }

    fun showCandidateActionMenu(idx: Int, text: String, view: View, global: Boolean, comment: String = "") {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        val highlightColor = scope.colors.hilitedCandidateTextColor
        val title = buildSpannedString {
            bold {
                color(highlightColor) { append(text) }
            }
        }
        service.lifecycleScope.launch {
            InputFeedbackManager.keyPressVibrate(view, longPress = true)
            candidateActionMenu =
                PopupMenu(themedContext, view).apply {
                    menu.add(title).apply {
                        isEnabled = false
                    }
                    menu.add(R.string.forget_this_word).setOnMenuItemClickListener {
                        rime.runIfReady { deleteCandidate(idx, global) }
                        true
                    }
                    // AI 推理出来的候选（注释里带 ◇ 或 ◈）才提供“怪词”标记；已标记的提供取消
                    val marked = text in weirdWords()
                    if (marked || comment.contains("◇") || comment.contains("◈")) {
                        menu.add(if (marked) "取消怪词标记" else "标记为怪词").setOnMenuItemClickListener {
                            setWeird(text, !marked)
                            Toast.makeText(themedContext, if (marked) "已取消怪词标记：$text" else "已标记为怪词：$text", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                    setOnDismissListener {
                        candidateActionMenu = null
                    }
                    show()
                }
        }
    }

    private val navBarBackground by ThemeManager.prefs.navbarBackground

    private val navBarFrameHeight: Int
        get() {
            @SuppressLint("DiscouragedApi")
            val resId = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
            return try {
                resources.getDimensionPixelSize(resId)
            } catch (_: Resources.NotFoundException) {
                dp(FALLBACK_NAVBAR_HEIGHT)
            }
        }

    private val ignoreSystemGestureInsets by AppPrefs.defaultInstance().advanced.ignoreSystemGestureInsets

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navBarBackground != ThemePrefs.NavbarBackground.FULL) {
            return 0
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        val navBars = WindowInsetsCompat.Type.navigationBars()
        val gestures = WindowInsetsCompat.Type.systemGestures() or
            WindowInsetsCompat.Type.mandatorySystemGestures()
        return if (ignoreSystemGestureInsets) {
            // check gestures insets and ignore them when it greater than zero
            val gesturesBottom = insets.getInsets(gestures).bottom
            val navBarsBottom = insets.getInsets(navBars).bottom
            if (gesturesBottom > 0) 0 else navBarsBottom
        } else {
            val insetsBottom = insets.getInsets(navBars or gestures).bottom
            if (insetsBottom > 0) max(insetsBottom, navBarFrameHeight) else insetsBottom
        }
    }

    fun dismissCandidateActionMenu() {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // on API 35+, we must call requestApplyInsets() manually after replacing views,
        // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        handleMessages = false
        dismissCandidateActionMenu()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val FALLBACK_NAVBAR_HEIGHT = 16
    }
}
