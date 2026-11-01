/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.RimeKeyEvent
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.customFormatDateTime
import com.osfans.trime.util.isAsciiPrintable
import kotlinx.coroutines.launch
import splitties.dimensions.dp
import splitties.systemservices.clipboardManager

/** 面板类动作由窗口层实现，键盘不依赖具体的窗口 */
interface KeyboardPanels {
    fun openClipboard()

    fun openLiquid(tag: String)

    fun exitLiquid()

    fun openMenu()
}

/** 新键盘：管理当前页、Shift 状态，并执行按键动作 */
class SuzuKeyboard(
    private val context: Context,
    private val theme: Theme,
    private val rime: RimeSession,
    private val service: TrimeInputMethodService,
    private val popup: PopupDelegate,
    private val enterKey: EnterKeyDisplayDelegate,
    private val panels: KeyboardPanels,
) : KeyHost {
    private val prefs = AppPrefs.defaultInstance().keyboard
    private val container = FrameLayout(context)
    private val pages = HashMap<String, KeyPageView>()
    private var current: KeyPageView? = null
    private var lastPageId = Pages.DEFAULT
    private var shift = ShiftState.Off
    private var savedAsciiMode: Boolean? = null

    override val palette = KeyPalette()

    override val metrics by lazy {
        val style = theme.style
        KeyMetrics(
            roundCorner = style.roundCorner,
            textSp = style.keyTextSize,
            longTextSp = style.keyLongTextSize.takeIf { it > 0 } ?: style.keyTextSize,
            cornerSp = style.symbolTextSize.takeIf { it > 0 } ?: style.keyTextSize,
            horizontalGapPx = context.dp(style.horizontalGap),
            verticalGapPx = context.dp(style.verticalGap),
            keyTypeface = theme.fonts.key,
            cornerTypeface = theme.fonts.symbol,
            hideCorner = prefs.hideKeySymbol.getValue(),
            hideHint = prefs.hideKeyHint.getValue(),
        )
    }

    fun createView(): ViewGroup {
        show(Pages.DEFAULT)
        return container
    }

    override fun state(): KeyState {
        val status = rime.run { statusCached }
        return KeyState(status.isAsciiMode, status.isComposing, shift.isActive, enterKey.keyLabel)
    }

    override fun composingLength() = rime.run { compositionCached }.preedit?.count { !it.isWhitespace() } ?: 0

    fun show(id: String) {
        val target = if (id == Pages.LAST) lastPageId else id
        val previous = current
        if (previous?.page?.id == target) {
            // 页面没变，但部署结束后 Rime 的状态可能变了
            repaintAfterRime()
            return
        }
        val page = Pages.get(target)
        val current = rime.run { statusCached }.isAsciiMode
        val leavingFollow = previous?.page?.ascii == AsciiPolicy.Follow
        val mode = targetAsciiMode(page.ascii, leavingFollow, current)
        // 中文模式下数字键会选候选，进符号页前先上屏未完成的输入
        if (page.ascii == AsciiPolicy.Follow && !current) service.postRimeJob { commitComposition() }
        if (mode != current) {
            service.postRimeJob {
                commitComposition()
                setRuntimeOption("ascii_mode", mode)
            }
        }
        previous?.let {
            lastPageId = it.page.id
            container.removeView(it)
        }
        val view = pages.getOrPut(target) { KeyPageView(context, page, this) }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        this.current = view
        repaintAfterRime()
    }

    /** Rime 就绪前读到的状态是默认值，排在 Rime 任务之后再画一次，否则标签可能和实际模式不一致 */
    private fun repaintAfterRime() {
        service.postRimeJob { container.post { repaint() } }
    }

    /** 数字类输入框进数字页，密码类输入框留在当前页并临时切到英文，其余回到字母页 */
    fun startInput(wantsNumbers: Boolean, wantsAscii: Boolean) {
        if (!wantsAscii) show(if (wantsNumbers) Pages.NUMBER else Pages.DEFAULT)
        val ascii = rime.run { statusCached }.isAsciiMode
        if (wantsNumbers || wantsAscii) {
            if (savedAsciiMode == null) savedAsciiMode = ascii
            if (!ascii) service.postRimeJob { setRuntimeOption("ascii_mode", true) }
        } else {
            savedAsciiMode?.let { saved ->
                if (ascii != saved) service.postRimeJob { setRuntimeOption("ascii_mode", saved) }
                savedAsciiMode = null
            }
        }
        repaintAfterRime()
    }

    fun onAsciiModeChanged(ascii: Boolean) {
        if (!ascii) shift = ShiftState.Off
        repaint()
    }

    fun repaint() {
        current?.repaint()
    }

    fun dismissPopups() = popup.dismissAll()

    override fun onKeyDown(spec: KeySpec) {
        val code = feedbackCode(spec.command)
        InputFeedbackManager.keyPressSound(code)
        InputFeedbackManager.keyPressSpeak(code)
    }

    override fun showPreview(viewId: Int, content: String, bounds: Rect) {
        if (prefs.popupOnKeyPress.getValue()) {
            popup.listener.onPopupAction(PopupAction.PreviewAction(viewId, content, bounds))
        }
    }

    override fun dismissPreview(viewId: Int) {
        popup.listener.onPopupAction(PopupAction.DismissAction(viewId))
    }

    override fun run(command: KeyCommand) {
        when (command) {
            KeyCommand.Shift -> setShift(shift.tapped())
            KeyCommand.ShiftLock -> setShift(shift.lockToggled())
            is KeyCommand.Char -> typeChar(command.char)
            is KeyCommand.Special -> press(command.nav)
            is KeyCommand.Insert -> insert(command.text, command.after)
            is KeyCommand.Query -> service.postRimeJob { simulateKeySequence("{Escape}" + command.input) }
            is KeyCommand.Chord -> sendChord(command)
            is KeyCommand.Editor -> edit(command.op)
            is KeyCommand.ShowPage -> show(command.id)
            is KeyCommand.OpenLiquid -> panels.openLiquid(command.tag)
            KeyCommand.ExitLiquid -> panels.exitLiquid()
            KeyCommand.OpenMenu -> panels.openMenu()
            KeyCommand.OpenClipboard -> panels.openClipboard()
            KeyCommand.ToggleAscii -> toggleAscii()
            KeyCommand.PasteClipboard -> clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(service)?.let { service.commitText(it.toString()) }
            KeyCommand.OpenSettings -> AppUtils.launchMainActivity(service)
            KeyCommand.Hide -> service.requestHideSelf(0)
            KeyCommand.ToggleKeyHints -> {
                prefs.hideKeySymbol.setValue(!prefs.hideKeySymbol.getValue())
                prefs.hideKeyHint.setValue(!prefs.hideKeyHint.getValue())
            }
            KeyCommand.InsertDateTime -> service.commitText(customFormatDateTime(DATE_TIME_FORMAT))
        }
        if (command != KeyCommand.Shift && command != KeyCommand.ShiftLock && !keepsShift(command)) consumeShift()
    }

    private fun keepsShift(command: KeyCommand) =
        command is KeyCommand.ShowPage || (command as? KeyCommand.Chord)?.repeats == true || (command as? KeyCommand.Special)?.nav?.movesCursor == true

    private fun setShift(value: ShiftState) {
        shift = value
        repaint()
    }

    private fun consumeShift() {
        val next = shift.afterKey()
        if (next != shift) setShift(next)
    }

    private fun typeChar(char: Char) {
        val shifted = shift.isActive
        if (shifted && char in 'a'..'z') {
            val upper = char.uppercaseChar()
            if (rime.run { statusCached }.isAsciiMode) {
                val mods = KeyModifiers.fromMetaState(KeyEvent.META_SHIFT_ON).modifiers
                service.postRimeJob { processKey(upper.code, mods) }
            } else {
                service.postRimeJob { simulateKeySequence(upper.toString()) }
            }
        } else {
            service.postRimeJob { processKey(char.code, 0u) }
        }
    }

    private fun press(nav: Nav) {
        val code = RimeKeyEvent.getKeycodeByName(nav.rimeName)
        val meta = if (shift.isActive) KeyEvent.META_SHIFT_ON else 0
        val mods = KeyModifiers.fromMetaState(meta).modifiers
        service.postRimeJob { processKey(code, mods) }
    }

    private fun insert(text: String, after: List<Nav>) {
        if (!text[0].isAsciiPrintable() && rime.run { statusCached }.isComposing) {
            service.postRimeJob { commitComposition() }
        }
        service.postRimeJob {
            if (text[0].isAsciiPrintable()) {
                simulateKeySequence(text.map { c -> when (c) { '{' -> "{braceleft}"; '}' -> "{braceright}"; else -> c.toString() } }.joinToString(""))
            } else {
                service.commitText(text)
            }
        }
        after.forEach(::press)
    }

    private fun sendChord(chord: KeyCommand.Chord) {
        val keepsSelection = shift.isActive && (chord.key == "Left" || chord.key == "Right")
        val meta = (if (chord.ctrl) KeyEvent.META_CTRL_ON else 0) or (if (chord.shift || keepsSelection) KeyEvent.META_SHIFT_ON else 0)
        val mods = KeyModifiers.fromMetaState(meta).modifiers
        val code = RimeKeyEvent.getKeycodeByName(chord.key)
        service.postRimeJob { processKey(code, mods) }
    }

    private fun edit(op: EditorOp) {
        val androidCode = KeyEvent.KEYCODE_A + (op.letter - 'a')
        val mods = KeyModifiers.fromMetaState(KeyEvent.META_CTRL_ON).modifiers
        service.postRimeJob {
            if (!service.hookKeyboard(androidCode, KeyEvent.META_CTRL_ON)) processKey(op.letter.code, mods)
        }
    }

    private fun toggleAscii() {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                val ascii = api.getRuntimeOption("ascii_mode")
                val composing = api.statusCached.isComposing
                api.setRuntimeOption("ascii_mode", !ascii)
                if (composing) {
                    api.getRawInput().takeIf { it.isNotEmpty() }?.let {
                        service.commitText(it)
                        api.clearComposition()
                    }
                }
            }
        }
    }

    private companion object {
        const val DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm"

        /** 按键音和读屏按 Android 键码区分按键，只在这里换算 */
        fun feedbackCode(command: KeyCommand?): Int = when (command) {
            is KeyCommand.Char -> when (val c = command.char.lowercaseChar()) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
                else -> KeyEvent.KEYCODE_UNKNOWN
            }
            is KeyCommand.Special -> when (command.nav) {
                Nav.Backspace -> KeyEvent.KEYCODE_DEL
                Nav.Return -> KeyEvent.KEYCODE_ENTER
                Nav.Space -> KeyEvent.KEYCODE_SPACE
                else -> KeyEvent.KEYCODE_UNKNOWN
            }
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }
}
