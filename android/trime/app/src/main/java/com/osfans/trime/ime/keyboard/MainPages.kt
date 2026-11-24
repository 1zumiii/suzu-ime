// 键盘页面的布局。符号、表情、颜文字在符号面板里（ime/symbol/SymbolWindow.kt），编辑面板见 EditorWindow.kt

package com.osfans.trime.ime.keyboard

import com.osfans.trime.ime.keyboard.KeyCommand.*

internal object MainPages {
    val builders: Map<String, () -> Page> = mapOf(
        "default" to ::default,
        "number" to ::number,
        "numpad" to ::numpad,
    )

    private fun default() = Page("default", AsciiPolicy.Chinese, listOf(
        Row(48.0f, listOf(
            key(Char('q'), width = 10.0f, tone = Tone.H1, long = Char('1')),
            key(Char('w'), width = 10.0f, tone = Tone.H1, long = Char('2')),
            key(Char('e'), width = 10.0f, tone = Tone.H1, long = Char('3')),
            key(Char('r'), width = 10.0f, tone = Tone.H1, long = Char('4')),
            key(Char('t'), width = 10.0f, tone = Tone.H1, long = Char('5')),
            key(Char('y'), width = 10.0f, tone = Tone.H1, long = Char('6')),
            key(Char('u'), width = 10.0f, tone = Tone.H1, long = Char('7')),
            key(Char('i'), width = 10.0f, tone = Tone.H1, long = Char('8')),
            key(Char('o'), width = 10.0f, tone = Tone.H1, long = Char('9')),
            key(Char('p'), width = 10.0f, tone = Tone.H1, long = Char('0')),
        )),
        Row(48.0f, listOf(
            gap(5.0f),
            key(Char('a'), width = 10.0f, tone = Tone.H2, long = Editor(EditorOp.SelectAll)),
            key(Char('s'), width = 10.0f, tone = Tone.H2, long = Char('@')),
            key(Char('d'), width = 10.0f, tone = Tone.H2, long = Char('#')),
            key(Char('f'), width = 10.0f, tone = Tone.H2, long = Char('$')),
            key(Char('g'), width = 10.0f, tone = Tone.H2, long = Char('%'), swipeDown = InsertDateTime),
            key(Char('h'), width = 10.0f, tone = Tone.H2, long = Char('!'), swipeDown = Char('+')),
            key(Char('j'), width = 10.0f, tone = Tone.H2, long = Char('&'), swipeDown = Char('-')),
            key(Char('k'), width = 10.0f, tone = Tone.H2, long = Char('*'), swipeDown = Char('_')),
            key(Char('l'), width = 10.0f, tone = Tone.H2, long = Insert("()", listOf(Nav.Left)), swipeDown = Char('=')),
            gap(5.0f),
        )),
        Row(48.0f, listOf(
            key(Shift, label = "ic@arrow_up_bold_outline", width = 15.0f, tone = Tone.Bs, long = ShiftLock, double = ShiftLock, composing = Insert("'"), preview = "⇪"),
            key(Char('z'), width = 10.0f, tone = Tone.H3, long = Char('`'), swipeDown = Insert("[]", listOf(Nav.Left))),
            key(Char('x'), width = 10.0f, tone = Tone.H3, long = Editor(EditorOp.Cut), swipeDown = Insert("{}", listOf(Nav.Left))),
            key(Char('c'), width = 10.0f, tone = Tone.H3, long = Editor(EditorOp.Copy), swipeDown = Char(' ')),
            key(Char('v'), width = 10.0f, tone = Tone.H3, long = PasteClipboard, swipeDown = Editor(EditorOp.Paste)),
            key(Char('b'), width = 10.0f, tone = Tone.H3, long = Char(';'), swipeDown = Char('\\')),
            key(Char('n'), width = 10.0f, tone = Tone.H3, long = Char(':')),
            key(Char('m'), width = 10.0f, tone = Tone.H3, long = Char('?'), swipeDown = Char('|')),
            key(Special(Nav.Backspace), label = "ic@backspace_outline", width = 15.0f, tone = Tone.Bs, preview = "⇦"),
        )),
        Row(48.0f, listOf(
            key(ShowPage("number"), label = "?123", width = 15.0f, tone = Tone.Gn, long = ToggleKeyHints),
            key(Char(','), label = "，", width = 10.0f, tone = Tone.H4, long = Insert("<>", listOf(Nav.Left))),
            key(Special(Nav.Space), label = "拼音", asciiLabel = "English", width = 50.0f, tone = Tone.Kg, long = ToggleAscii, swipeUp = Chord("6", ctrl = true, shift = true), swipeLeft = Special(Nav.Left), swipeRight = Special(Nav.Right), textSp = 16.0f, preview = " ", slideCursor = true),
            key(Char('.'), label = "。", width = 10.0f, tone = Tone.H4, long = OpenClipboard),
            key(Special(Nav.Return), label = EnterLabel, width = 15.0f, tone = Tone.Enter, swipeUp = Special(Nav.Escape), composing = Special(Nav.Return), long = OpenSettings, preview = "↩"),
        )),
    ))

    private fun number() = Page("number", AsciiPolicy.Follow, listOf(
        Row(48.0f, listOf(
            key(Char('1'), width = 10.0f),
            key(Char('2'), width = 10.0f),
            key(Char('3'), width = 10.0f),
            key(Char('4'), width = 10.0f),
            key(Char('5'), width = 10.0f),
            key(Char('6'), width = 10.0f),
            key(Char('7'), width = 10.0f),
            key(Char('8'), width = 10.0f),
            key(Char('9'), width = 10.0f),
            key(Char('0'), width = 10.0f),
        )),
        Row(48.0f, listOf(
            key(Char('@'), width = 10.0f),
            key(Char('#'), width = 10.0f),
            key(Char('$'), label = "¥", width = 10.0f),
            key(Char('_'), label = "——", width = 10.0f),
            key(Char('&'), width = 10.0f),
            key(Char('-'), width = 10.0f),
            key(Char('+'), width = 10.0f),
            key(Char('('), label = "（", width = 10.0f),
            key(Char(')'), label = "）", width = 10.0f),
            key(Char('/'), width = 10.0f),
        )),
        Row(48.0f, listOf(
            key(OpenSymbols(SymbolGroup.Symbols), label = "符号", width = 15.0f, tone = Tone.Gn, textSp = 14.0f),
            key(Insert("、"), width = 10.0f),
            key(Insert("“"), width = 10.0f),
            key(Insert("”"), width = 10.0f),
            key(Char(':'), label = "：", width = 10.0f),
            key(Char(';'), label = "；", width = 10.0f),
            key(Char('!'), label = "！", width = 10.0f),
            key(Char('?'), label = "？", width = 10.0f),
            key(Special(Nav.Backspace), label = "ic@backspace_outline", width = 15.0f, tone = Tone.Bs, preview = "⇦"),
        )),
        Row(48.0f, listOf(
            key(ShowPage(Pages.DEFAULT), label = "ABC", width = 15.0f, tone = Tone.Gn),
            key(Char(','), label = "，", width = 10.0f, tone = Tone.Gn, long = Char('<')),
            key(ShowPage(Pages.NUMPAD), label = "123", width = 15.0f, tone = Tone.Gn),
            key(Special(Nav.Space), label = "拼音", asciiLabel = "English", width = 35.0f, tone = Tone.Kg, long = ToggleAscii, textSp = 16.0f, preview = " ", slideCursor = true),
            key(Char('.'), label = "。", width = 10.0f, tone = Tone.Gn, long = Char('>')),
            key(Special(Nav.Return), label = EnterLabel, width = 15.0f, tone = Tone.Enter, preview = "↩"),
        )),
    ))

    private fun numpad() = Page(Pages.NUMPAD, AsciiPolicy.Follow, listOf(
        Row(48.0f, listOf(
            key(Char('+'), width = 16.6f, tone = Tone.Gn, long = Char('#')),
            key(Char('-'), width = 16.6f, tone = Tone.Gn, long = Char('_')),
            key(Char('1'), width = 16.6f),
            key(Char('2'), width = 16.6f),
            key(Char('3'), width = 16.6f),
            key(Special(Nav.Backspace), label = "ic@backspace_outline", width = 16.6f, tone = Tone.Bs, preview = "⇦"),
        )),
        Row(48.0f, listOf(
            key(Char('*'), width = 16.6f, tone = Tone.Gn, long = Insert("×")),
            key(Char('/'), width = 16.6f, tone = Tone.Gn, long = Insert("÷")),
            key(Char('4'), width = 16.6f),
            key(Char('5'), width = 16.6f),
            key(Char('6'), width = 16.6f),
            key(Special(Nav.Space), label = "空格", width = 16.6f, tone = Tone.Gn),
        )),
        Row(48.0f, listOf(
            key(Insert("()", listOf(Nav.Left)), width = 16.6f, tone = Tone.Gn, long = Insert("[]", listOf(Nav.Left))),
            key(Char('%'), width = 16.6f, tone = Tone.Gn, long = Char('@')),
            key(Char('7'), width = 16.6f, long = Editor(EditorOp.SelectAll)),
            key(Char('8'), width = 16.6f, long = Editor(EditorOp.Copy)),
            key(Char('9'), width = 16.6f, long = PasteClipboard, swipeDown = Editor(EditorOp.Paste)),
            key(OpenSymbols(SymbolGroup.Symbols), label = "符号", width = 16.6f, tone = Tone.Gn, textSp = 14.0f),
        )),
        Row(48.0f, listOf(
            key(ShowPage(Pages.DEFAULT), label = "ABC", width = 16.6f, tone = Tone.Gn),
            key(ShowPage(Pages.NUMBER), label = "123", width = 16.6f, tone = Tone.Gn),
            key(Char('.'), width = 16.6f, long = Char(':')),
            key(Char('0'), width = 16.6f, long = Editor(EditorOp.Cut)),
            key(Char('='), width = 16.6f, long = Insert("≠")),
            key(Special(Nav.Return), label = EnterLabel, width = 16.6f, tone = Tone.Enter, preview = "↩"),
        )),
    ))

}
