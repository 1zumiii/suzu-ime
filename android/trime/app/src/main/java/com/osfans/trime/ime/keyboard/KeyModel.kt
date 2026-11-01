/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

/** 不经过字符映射、直接发给 Rime 的键，名称是 Rime 的键名 */
enum class Nav(val rimeName: String, val repeats: Boolean = false) {
    Backspace("BackSpace", repeats = true),
    Return("Return"),
    Space("space"),
    Left("Left", repeats = true),
    Right("Right", repeats = true),
    Escape("Escape"),
    Tab("Tab"),
    PageUp("Page_Up"),
    PageDown("Page_Down"),
    Up("Up"),
    Down("Down"),
    Home("Home"),
    End("End");

    /** 移动光标的键：Shift 不会被消耗，可以连续选择文字 */
    val movesCursor: Boolean get() = this == Left || this == Right || this == Up || this == Down || this == Home || this == End
}

enum class EditorOp(val label: String, val letter: Char) {
    SelectAll("全选", 'a'),
    Cut("剪切", 'x'),
    Copy("复制", 'c'),
    Paste("粘贴", 'v'),
    Undo("撤销", 'z'),
}

/** 按键触发的动作。[KeyCommandRunner] 负责执行，这里只描述“做什么” */
sealed interface KeyCommand {
    /** 一个 ASCII 字符，经 Rime 处理 */
    data class Char(val char: kotlin.Char) : KeyCommand

    data class Special(val nav: Nav) : KeyCommand

    /** 一段文字，之后依次按 [after] 里的键（如括号之间的左移） */
    data class Insert(val text: String, val after: List<Nav> = emptyList()) : KeyCommand

    /** 清空当前输入后，把 [input] 作为编码打给 Rime（符号反查） */
    data class Query(val input: String) : KeyCommand

    data class Chord(val key: String, val ctrl: Boolean = false, val shift: Boolean = false, val repeats: Boolean = false) : KeyCommand

    data class Editor(val op: EditorOp) : KeyCommand

    data class ShowPage(val id: String) : KeyCommand

    data class OpenLiquid(val tag: String) : KeyCommand

    data object ExitLiquid : KeyCommand

    data object OpenMenu : KeyCommand

    data object Shift : KeyCommand

    data object ShiftLock : KeyCommand

    data object ToggleAscii : KeyCommand

    data object PasteClipboard : KeyCommand

    data object OpenClipboard : KeyCommand

    data object OpenSettings : KeyCommand

    data object Hide : KeyCommand

    data object ToggleKeyHints : KeyCommand

    data object InsertDateTime : KeyCommand
}

/** 按键配色，值是配色方案里的颜色名；[Plain] 使用默认键色 */
enum class Tone(val back: String?, val text: String?) {
    Plain(null, null),
    Gn("bgn", "tgn"),
    Enter("benter", "tenter"),
    H1("bh1", "th1"),
    H2("bh2", "th2"),
    H3("bh3", "th3"),
    H4("bh4", "th4"),
    Aoe("baoe", "taoe"),
    Bs("bbs", "tbs"),
    Kg("bkg", "tkg"),
    Active("bactive", "tactive"),
}

/** 标签占位符：由输入框的回车动作决定显示什么 */
const val EnterLabel = "\u0000enter"

/** 一个按键，或 [isGap] 时的占位空隙。宽度是占键盘宽度的百分比 */
data class KeySpec(
    val command: KeyCommand?,
    val width: Float,
    val label: String? = null,
    val asciiLabel: String? = null,
    val tone: Tone = Tone.Plain,
    val long: KeyCommand? = null,
    val double: KeyCommand? = null,
    val swipeUp: KeyCommand? = null,
    val swipeDown: KeyCommand? = null,
    val swipeLeft: KeyCommand? = null,
    val swipeRight: KeyCommand? = null,
    val composing: KeyCommand? = null,
    val hint: String? = null,
    val textSp: Float = 0f,
    val cornerSp: Float = 0f,
    val preview: String? = null,
    val functional: Boolean = false,
    val slideCursor: Boolean = false,
) {
    val isGap: Boolean get() = command == null

    val repeats: Boolean
        get() = (command as? KeyCommand.Special)?.nav?.repeats == true || (command as? KeyCommand.Chord)?.repeats == true

    val isShift: Boolean get() = command == KeyCommand.Shift
}

internal fun key(
    command: KeyCommand,
    width: Float,
    label: String? = null,
    asciiLabel: String? = null,
    tone: Tone = Tone.Plain,
    long: KeyCommand? = null,
    double: KeyCommand? = null,
    swipeUp: KeyCommand? = null,
    swipeDown: KeyCommand? = null,
    swipeLeft: KeyCommand? = null,
    swipeRight: KeyCommand? = null,
    composing: KeyCommand? = null,
    hint: String? = null,
    textSp: Float = 0f,
    cornerSp: Float = 0f,
    preview: String? = null,
    functional: Boolean = false,
    slideCursor: Boolean = false,
) = KeySpec(
    command, width, label, asciiLabel, tone, long, double, swipeUp, swipeDown, swipeLeft, swipeRight,
    composing, hint, textSp, cornerSp, preview, functional, slideCursor,
)

internal fun gap(width: Float) = KeySpec(command = null, width = width)

/** [height] 是行高的相对权重，实际行高按键盘总高度等比分配 */
data class Row(val height: Float, val keys: List<KeySpec>)

/** 进入页面时中英文状态怎么变 */
enum class AsciiPolicy { Chinese, Ascii, Follow }

data class Page(val id: String, val ascii: AsciiPolicy, val rows: List<Row>)

object Pages {
    const val DEFAULT = "default"
    const val NUMBER = "number"
    const val LAST = ".last"

    private val cache = HashMap<String, Page>()

    fun get(id: String): Page = cache.getOrPut(id) {
        if (SymbolBoard.owns(id)) SymbolBoard.page(id) else (MainPages.builders[id] ?: MainPages.builders.getValue(DEFAULT))()
    }
}

/** 决定标签和实际动作的全部状态，由键盘控制器从 Rime 读取后传入 */
data class KeyState(
    val asciiMode: Boolean = false,
    val composing: Boolean = false,
    val shifted: Boolean = false,
    val enterLabel: String = "",
)

/** 输入中的动作优先于普通点按，比如 Shift 输入中变成分词符 */
fun KeySpec.activeCommand(state: KeyState): KeyCommand? = if (state.composing && composing != null) composing else command

fun KeyCommand.defaultLabel(state: KeyState): String = when (this) {
    is KeyCommand.Char -> char.toString()
    is KeyCommand.Insert -> text
    is KeyCommand.Editor -> op.label
    is KeyCommand.Query -> input
    is KeyCommand.Special -> when (nav) {
        Nav.Escape -> "取消"
        Nav.Left -> "←"
        Nav.Right -> "→"
        Nav.Tab -> "Tab"
        Nav.PageUp -> "PgUp"
        Nav.PageDown -> "PgDn"
        Nav.Backspace -> "ic@backspace_outline"
        Nav.Return -> "Enter"
        Nav.Space -> ""
        Nav.Up -> "↑"
        Nav.Down -> "↓"
        Nav.Home -> "行首"
        Nav.End -> "行尾"
    }
    KeyCommand.ToggleAscii -> if (state.asciiMode) "西文" else "中文"
    KeyCommand.PasteClipboard -> "粘贴"
    KeyCommand.OpenClipboard -> "剪贴"
    is KeyCommand.OpenLiquid -> "更多"
    else -> ""
}

private fun adjustCase(text: String, state: KeyState) = if (text.length == 1 && state.shifted) text.uppercase() else text

/** 按键中间显示的文字。输入中的动作不同于点按时，显示那个动作自己的标签 */
fun KeySpec.displayLabel(state: KeyState): String {
    val active = activeCommand(state)
    val text = when {
        active !== command -> composingLabel(active, state)
        state.asciiMode -> asciiLabel ?: if (command is KeyCommand.Char) command.defaultLabel(state) else label ?: command?.defaultLabel(state).orEmpty()
        else -> label ?: command?.defaultLabel(state).orEmpty()
    }
    return if (text == EnterLabel) state.enterLabel else adjustCase(text, state)
}

private fun composingLabel(active: KeyCommand?, state: KeyState): String = when {
    active is KeyCommand.Insert && active.text == "'" -> "分词"
    active is KeyCommand.Special && active.nav == Nav.Return -> "Enter"
    else -> active?.defaultLabel(state).orEmpty()
}

/** 右上角的小字：长按动作的标签 */
fun KeySpec.cornerLabel(state: KeyState): String = long?.let { adjustCase(it.defaultLabel(state), state) }.orEmpty()

fun KeySpec.previewText(state: KeyState): String = preview ?: displayLabel(state)

/** Shift 的三种状态：关闭、只影响下一个键、锁定 */
enum class ShiftState {
    Off, Once, Locked;

    val isActive: Boolean get() = this != Off

    fun tapped() = if (this == Off) Once else Off

    fun lockToggled() = if (this == Locked) Off else Locked

    /** 输入了一个非 Shift 键之后 */
    fun afterKey() = if (this == Once) Off else this
}

/**
 * 切换到 [target] 页后的中英文状态。跟随语言的页面（数字、符号）进出都保持当前状态，
 * 否则从符号页回到字母页会丢掉英文模式。
 */
fun targetAsciiMode(target: AsciiPolicy, leavingFollowPage: Boolean, current: Boolean): Boolean = when {
    target == AsciiPolicy.Follow || leavingFollowPage -> current
    target == AsciiPolicy.Ascii -> true
    else -> false
}

data class KeyRect(val spec: KeySpec, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 计算一页里每个键的位置。行高按权重分配到 [height]，最后一行吸收取整误差；
 * 贴近右边缘的键补齐到边缘。[expandGaps] 时占位空隙的触摸区域分给相邻的键。
 */
fun layoutPage(page: Page, width: Int, height: Int): List<KeyRect> {
    val totalWeight = page.rows.sumOf { it.height.toDouble() }.toFloat()
    val result = ArrayList<KeyRect>()
    var y = 0
    page.rows.forEachIndexed { rowIndex, row ->
        val rowHeight = if (rowIndex == page.rows.lastIndex) height - y else (row.height / totalWeight * height).toInt()
        var x = 0
        for (spec in row.keys) {
            val px = (spec.width * width / 100f).toInt()
            if (!spec.isGap) {
                val rightGap = kotlin.math.abs(width - x - px)
                val w = if (rightGap <= width / 100) width - x else px
                result += KeyRect(spec, x, y, w, rowHeight)
                x += w
            } else {
                x += px
            }
        }
        y += rowHeight
    }
    return result
}

private const val ICON_PREFIX = "ic@"

/** 以 ic@ 开头的标签是图标名，不是文字 */
val String.isIconFont: Boolean get() = startsWith(ICON_PREFIX)

fun String.toIconName(): String = replace(ICON_PREFIX, "cmd_")
