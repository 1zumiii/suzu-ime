// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.transition.Transition
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.core.StatusProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ime.window.ResidentWindow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

class KeyboardWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow,
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by instance()
    private val theme: Theme by instance()
    private val rime: RimeSession by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()
    private val windowManager: BoardWindowManager by instance()
    private val liquidWindow: LiquidWindow by instance()

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    companion object : ResidentWindow.Key

    // 和键盘页面之间的切换一致：不要从顶部滑入
    override fun enterAnimation(lastWindow: BoardWindow): Transition? = null

    override fun exitAnimation(nextWindow: BoardWindow): Transition? = null

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val keyboard by lazy {
        SuzuKeyboard(
            context, theme, rime, service, popup, enterKeyDisplay,
            object : KeyboardPanels {
                override fun openClipboard() = windowManager.attachWindow(ClipboardWindow(di))

                override fun openLiquid(tag: String) {
                    val index = theme.liquidKeyboard.getTagList().indexOfFirst { item ->
                        item.label == tag || runCatching { LiquidKeyboard.DataType.valueOf(tag.uppercase()) }.getOrNull() == item.type
                    }
                    if (index >= 0) {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(index)
                    }
                }

                override fun exitLiquid() = windowManager.attachWindow(KeyboardWindow)

                override fun openMenu() = windowManager.attachWindow(SwitchOptionWindow(di))
            },
        )
    }

    /** 工具栏、液体键盘的按键条也走这里，和键盘共用一套动作 */
    fun run(command: KeyCommand) = keyboard.run(command)

    override fun onCreateView(): View {
        val style = theme.style
        val height = if (context.isLandscapeMode() && style.keyboardHeightLand > 0) style.keyboardHeightLand else style.keyboardHeight
        _currentKeyboardHeight.tryEmit(context.dp(height))
        return keyboard.createView()
    }

    /** Repaints the keyboard after a color-scheme switch; keys re-resolve their colors. */
    override fun refreshColors() = keyboard.repaint()

    override fun onStartInput(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val numbers = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val forcedAscii = info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII == EditorInfo.IME_FLAG_FORCE_ASCII ||
            (
                cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                )
                )
        keyboard.startInput(numbers && !forcedAscii, forcedAscii)
    }

    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) = keyboard.repaint()

    override fun onInputStatusUpdate(value: StatusProto) = keyboard.repaint()

    override fun onRimeSchemaUpdated(schema: SchemaItem) = keyboard.show(Pages.DEFAULT)

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        if (value.option == "ascii_mode") keyboard.onAsciiModeChanged(value.value) else keyboard.repaint()
    }

    override fun onAttached() {
    }

    override fun onDetached() = keyboard.dismissPopups()
}
