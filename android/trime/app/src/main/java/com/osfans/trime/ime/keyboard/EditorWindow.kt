/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.transition.Transition
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.symbol.PanelItemUi
import com.osfans.trime.ime.window.BoardWindow
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

/** 编辑面板：光标移动、选择、剪贴和撤销。“选择”打开后，方向键、行首行尾和按词移动都连带选中文字 */
class EditorWindow(di: DI) : BoardWindow.BarBoardWindow(di) {
    private val scope: ThemeScope by instance()
    private val keyboardWindow: KeyboardWindow by instance()

    override val title = "编辑"

    override fun enterAnimation(lastWindow: BoardWindow): Transition? = null

    override fun exitAnimation(nextWindow: BoardWindow): Transition? = null

    private class Button(val label: String, val command: KeyCommand, val weight: Float = 1f)

    private val rows = listOf(
        listOf(
            Button("全选", KeyCommand.Editor(EditorOp.SelectAll)),
            Button("剪切", KeyCommand.Editor(EditorOp.Cut)),
            Button("复制", KeyCommand.Editor(EditorOp.Copy)),
            Button("粘贴", KeyCommand.Editor(EditorOp.Paste)),
        ),
        listOf(
            Button("撤销", KeyCommand.Editor(EditorOp.Undo)),
            Button("重做", KeyCommand.Chord("z", ctrl = true, shift = true)),
            Button("Tab", KeyCommand.Special(Nav.Tab)),
            Button(SELECT, KeyCommand.Shift),
        ),
        listOf(
            Button("上一词", KeyCommand.Chord("Left", ctrl = true, repeats = true)),
            Button("↑", KeyCommand.Special(Nav.Up)),
            Button("下一词", KeyCommand.Chord("Right", ctrl = true, repeats = true)),
            Button("行首", KeyCommand.Special(Nav.Home)),
        ),
        listOf(
            Button("←", KeyCommand.Special(Nav.Left)),
            Button("↓", KeyCommand.Special(Nav.Down)),
            Button("→", KeyCommand.Special(Nav.Right)),
            Button("行尾", KeyCommand.Special(Nav.End)),
        ),
        listOf(
            Button("空格", KeyCommand.Special(Nav.Space), 2f),
            Button("退格", KeyCommand.Special(Nav.Backspace)),
            Button("回车", KeyCommand.Special(Nav.Return)),
        ),
    )

    private val items = ArrayList<PanelItemUi>()
    private var selectItem: PanelItemUi? = null

    override fun onCreateView(): View {
        val gap = context.dp(2)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            rows.forEach { row ->
                val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { button ->
                    val ui = PanelItemUi(context, scope, textScale = 0.7f, gapX = gap, gapY = gap)
                    ui.mainText.text = button.label
                    ui.root.isRepeatable = button.command.let { (it as? KeyCommand.Special)?.nav?.repeats == true || (it as? KeyCommand.Chord)?.repeats == true }
                    ui.root.onClick = {
                        keyboardWindow.run(button.command)
                        updateSelectLabel()
                    }
                    if (button.label == SELECT) selectItem = ui
                    items += ui
                    line.addView(ui.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, button.weight))
                }
                addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
    }

    private fun updateSelectLabel() {
        selectItem?.mainText?.text = if (keyboardWindow.isShiftActive) "选择中" else SELECT
    }

    override fun onAttached() = updateSelectLabel()

    override fun onDetached() {}

    override fun refreshColors() {
        items.forEach { it.refreshColors() }
    }

    private companion object {
        const val SELECT = "选择"
    }
}
