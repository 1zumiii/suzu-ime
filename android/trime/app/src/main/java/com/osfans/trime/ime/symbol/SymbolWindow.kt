/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.annotation.SuppressLint
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Transition
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.SymbolHistory
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.keyboard.KeyCommand
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.SymbolGroup
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

/**
 * 符号面板：工具栏位置是“符号 / 表情 / 颜文字”，下面一行是“最近”和各分类，右端“分类”展开全部分类；
 * 中间是可滚动的网格，底部是各面板共用的底栏。每组记住上次看的分类和最近用过的条目。
 */
class SymbolWindow(di: DI) :
    BoardWindow.BarBoardWindow(di),
    ResidentWindow,
    InputBroadcastReceiver {
    override val showTitle = false

    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val keyboardWindow: KeyboardWindow by instance()

    companion object : ResidentWindow.Key {
        private const val RECENT_CAPACITY = 180
        private const val CATEGORY_COLUMNS = 4
        private const val CELL_GAP_DP = 2

        /** 分类条的第 0 项是“最近” */
        private const val RECENT = 0

        private fun categoriesOf(group: SymbolGroup) = when (group) {
            SymbolGroup.Symbols -> SymbolData.symbols
            SymbolGroup.Emoji -> SymbolData.emoji
            SymbolGroup.Kaomoji -> SymbolData.kaomoji
        }

        private fun historyFile(group: SymbolGroup) = when (group) {
            SymbolGroup.Symbols -> SymbolHistory.FILE_NAME
            SymbolGroup.Emoji -> "emoji_history"
            SymbolGroup.Kaomoji -> "kaomoji_history"
        }

        /**
         * 默认按文字样式显示的表情（☹ ☝ ✌ ❤ 等，Unicode 属性 Emoji_Presentation = No）补上 U+FE0F，
         * 面板里和上屏后都显示成彩色表情。属性查询要 API 28，更老的系统保持原样。
         */
        fun emojiPresentation(s: String): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return s
            val cp = s.codePointAt(0)
            val textDefault = s.length == Character.charCount(cp) &&
                UCharacter.hasBinaryProperty(cp, UProperty.EMOJI) &&
                !UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)
            return if (textDefault) s + "️" else s
        }
    }

    override val key: ResidentWindow.Key get() = SymbolWindow

    // 和键盘页面之间的切换一致：不要从顶部滑入
    override fun enterAnimation(lastWindow: BoardWindow): Transition? = null

    override fun exitAnimation(nextWindow: BoardWindow): Transition? = null

    private var group = SymbolGroup.Symbols
    private val selected = SymbolGroup.entries.associateWith { RECENT + 1 }.toMutableMap()
    private var listingCategories = false
    private val histories = HashMap<SymbolGroup, SymbolHistory>()

    private fun history(g: SymbolGroup) = histories.getOrPut(g) { SymbolHistory(RECENT_CAPACITY, historyFile(g)).apply { load() } }

    /** 网格里的一格：一个符号，或者展开分类时的一个分类名 */
    private class Cell(val text: String, val symbol: Symbol? = null, val category: Int = -1)

    private var cells: List<Cell> = emptyList()

    private val groupTabs by lazy {
        PanelTabsUi(context, scope, textScale = 1f).apply {
            setLabels(SymbolGroup.entries.map { it.label })
            setOnTabClickListener { i -> show(SymbolGroup.entries[i]) }
        }
    }

    private lateinit var chips: PanelTabsUi
    private lateinit var toggle: PanelItemUi
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var bottomBar: PanelBottomBar
    private val gridLayout by lazy { GridLayoutManager(context, 7) }

    private inner class Holder(val ui: PanelItemUi) : RecyclerView.ViewHolder(ui.root)

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = cells.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ui = PanelItemUi(context, scope, gapX = context.dp(CELL_GAP_DP), gapY = context.dp(CELL_GAP_DP))
            ui.root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellHeight())
            return Holder(ui)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val cell = cells[position]
            holder.ui.refreshColors()
            holder.ui.root.layoutParams.height = cellHeight()
            holder.ui.mainText.text = cell.text
            holder.ui.mainText.textSize = scope.theme.style.keyTextSize * textScale()
            holder.ui.root.onClick = { onCellClick(cell) }
        }
    }

    /** 符号和表情的格子矮一些，一屏多放一行；颜文字和分类名较长，格子高一些 */
    private fun cellHeight() = context.dp(if (listingCategories || group == SymbolGroup.Kaomoji) 40 else 32) + 2 * context.dp(CELL_GAP_DP)

    private fun textScale() = when {
        listingCategories -> 0.62f
        group == SymbolGroup.Kaomoji -> 0.62f
        group == SymbolGroup.Symbols -> 0.75f
        else -> 1f
    }

    @SuppressLint("ViewConstructor")
    override fun onCreateView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        chips = PanelTabsUi(context, scope).apply { setOnTabClickListener(::select) }
        toggle = PanelItemUi(context, scope, textScale = PanelBottomBar.TEXT_SCALE, flat = true).apply {
            root.onClick = ::toggleCategories
        }
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chips.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(
                View(context).apply { setBackgroundColor(scope.colors.candidateSeparatorColor) },
                LinearLayout.LayoutParams(dp(1), dp(20)).apply { gravity = Gravity.CENTER_VERTICAL },
            )
            addView(toggle.root, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        grid = RecyclerView(context).apply {
            layoutManager = gridLayout
            adapter = this@SymbolWindow.adapter
            itemAnimator = null
            setPadding(dp(4), 0, dp(4), 0)
            clipToPadding = false
        }
        empty = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = scope.theme.style.candidateTextSize * 0.8f
            setTextColor(scope.colors.commentTextColor)
        }
        addView(
            FrameLayout(context).apply {
                addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        bottomBar = PanelBottomBar(context, scope, keyboardWindow::run)
        addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PanelBottomBar.heightPx(context)))
        render()
    }

    override fun onCreateBarView(): View = groupTabs.root

    /** 打开 [g] 组，停在它上次看的分类 */
    fun show(g: SymbolGroup) {
        group = g
        listingCategories = false
        render()
    }

    private fun select(index: Int) {
        selected[group] = index
        listingCategories = false
        render()
    }

    private fun toggleCategories() {
        listingCategories = !listingCategories
        render()
    }

    private fun onCellClick(cell: Cell) {
        if (cell.category >= 0) {
            select(cell.category)
            return
        }
        val symbol = cell.symbol ?: return
        keyboardWindow.run(KeyCommand.Commit(symbol.text, symbol.after))
        history(group).apply {
            insert(symbol.text)
            save()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun render() {
        if (!::grid.isInitialized) return
        val categories = categoriesOf(group)
        groupTabs.activate(group.ordinal)
        chips.setLabels(listOf("最近") + categories.map { it.name })
        val index = selected.getValue(group).coerceIn(RECENT, categories.size)
        chips.activate(if (listingCategories) -1 else index)
        toggle.mainText.text = if (listingCategories) "收起" else "分类 ▾"

        cells = when {
            listingCategories -> categories.mapIndexed { i, c -> Cell(c.name, category = i + 1) }
            index == RECENT -> history(group).toOrderedList().map { text ->
                // 最近用过的括号也要把光标放回中间：按文字找回原条目
                val original = categories.firstNotNullOfOrNull { c -> c.items.firstOrNull { it.text == text } }
                Cell(text, original ?: Symbol(text))
            }
            else -> categories[index - 1].items.map { s ->
                if (group == SymbolGroup.Emoji) {
                    val shown = emojiPresentation(s.text)
                    Cell(shown, Symbol(shown, s.after))
                } else {
                    Cell(s.text, s)
                }
            }
        }
        gridLayout.spanCount = when {
            listingCategories -> CATEGORY_COLUMNS
            index == RECENT -> categories.first().columns
            else -> categories[index - 1].columns
        }
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
        empty.text = "还没有用过的${group.label}"
        empty.visibility = if (!listingCategories && index == RECENT && cells.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onAttached() {
        bottomBar.setAsciiMode(rime.run { statusCached }.isAsciiMode)
        render()
    }

    override fun onDetached() {}

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        if (value.option == "ascii_mode" && ::bottomBar.isInitialized) bottomBar.setAsciiMode(value.value)
    }

    override fun refreshColors() {
        if (!::grid.isInitialized) return
        groupTabs.refreshColors()
        chips.refreshColors()
        toggle.refreshColors()
        bottomBar.refreshColors()
        empty.setTextColor(scope.colors.commentTextColor)
        render()
    }
}
