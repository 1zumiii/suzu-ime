/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

/** 符号板上的一个符号；[after] 是输入后要按的键，如成对括号之间的左移 */
data class Symbol(val text: String, val after: List<Nav> = emptyList())

internal fun plain(symbols: String) = symbols.split(' ').map { Symbol(it) }

internal fun pair(symbols: String) = listOf(Symbol(symbols, listOf(Nav.Left)))

/**
 * 所有符号页共用的布局：表情（顶部分类）、标点（符号网格内翻页）和颜文字（底部分类）。
 * 页面 id 是 sym/<分类>/<页码>，按符号数量自动分页。
 */
object SymbolBoard {
    private const val PREFIX = "sym/"
    private const val NAV_SP = 14f

    private enum class Kind(val columns: Int, val rows: Int) {
        Emoji(10, 4),
        Punct(7, 4),
        Kao(4, 6),
    }

    private class Category(
        val id: String,
        val kind: Kind,
        val label: String,
        val symbols: () -> List<Symbol>,
    )

    private val categories = listOf(
        Category("rw", Kind.Emoji, "🙂") { SymbolData.emoji_rw },
        Category("hc", Kind.Emoji, "🌼") { SymbolData.emoji_hc },
        Category("pt", Kind.Emoji, "🎂") { SymbolData.emoji_pt },
        Category("lx", Kind.Emoji, "🚖") { SymbolData.emoji_lx },
        Category("hd", Kind.Emoji, "🎉") { SymbolData.emoji_hd },
        Category("kj", Kind.Emoji, "🔌") { SymbolData.emoji_kj },
        Category("qt", Kind.Emoji, "🔶") { SymbolData.emoji_qt },
        Category("qz", Kind.Emoji, "🇨🇳") { SymbolData.emoji_qz },
        Category("fh", Kind.Emoji, "#!?") { SymbolData.emoji_fh },
        Category("cn", Kind.Punct, "中文") { SymbolData.punct_cn },
        Category("en", Kind.Punct, "英文") { SymbolData.punct_en },
        Category("h", Kind.Kao, "开心") { SymbolData.kao_h },
        Category("f", Kind.Kao, "喜欢") { SymbolData.kao_f },
        Category("a", Kind.Kao, "惊讶") { SymbolData.kao_a },
        Category("i", Kind.Kao, "无奈") { SymbolData.kao_i },
        Category("s", Kind.Kao, "伤心") { SymbolData.kao_s },
        Category("m", Kind.Kao, "生气") { SymbolData.kao_m },
    )
    private val byId = categories.associateBy { it.id }

    private fun pageId(category: String, index: Int) = "$PREFIX$category/$index"

    val PUNCT_CN = pageId("cn", 0)
    val PUNCT_EN = pageId("en", 0)
    val EMOJI_FIRST = pageId("rw", 0)
    val KAO_FIRST = pageId("h", 0)

    fun emoji(category: String) = pageId(category, 0)

    fun owns(id: String) = id.startsWith(PREFIX)

    /** 标点分多页时，最后一行留 3 格放翻页键 */
    private fun capacity(category: Category): Int {
        val slots = category.kind.columns * category.kind.rows
        return if (category.kind == Kind.Punct && category.symbols().size > slots) slots - 3 else slots
    }

    fun pageCount(categoryId: String): Int {
        val category = byId.getValue(categoryId)
        val cap = capacity(category)
        return (category.symbols().size + cap - 1) / cap
    }

    fun page(id: String): Page {
        val parts = id.removePrefix(PREFIX).split('/')
        val category = byId[parts.getOrNull(0)] ?: byId.getValue("rw")
        val last = pageCount(category.id) - 1
        val index = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, last)
        val cap = capacity(category)
        val symbols = category.symbols().drop(index * cap).take(cap)
        return when (category.kind) {
            Kind.Emoji -> emojiPage(id, category, index, last, symbols)
            Kind.Punct -> punctPage(id, category, index, last, symbols)
            Kind.Kao -> kaoPage(id, category, symbols)
        }
    }

    private fun symbolKey(symbol: Symbol, width: Float, textSp: Float = 0f) =
        if (symbol.after.isEmpty() && symbol.text.length == 1 && symbol.text[0].code < 128) {
            key(KeyCommand.Char(symbol.text[0]), width, textSp = textSp)
        } else {
            key(KeyCommand.Insert(symbol.text, symbol.after), width, label = symbol.text, textSp = textSp)
        }

    private fun grid(symbols: List<Symbol>, kind: Kind, width: Float, rowHeight: Float, textSp: Float = 0f) =
        symbols.chunked(kind.columns).map { row -> Row(rowHeight, row.map { symbolKey(it, width, textSp) }) }

    private fun navKey(target: String, label: String, width: Float, tone: Tone = Tone.Gn) =
        key(KeyCommand.ShowPage(target), width, label = label, tone = tone, textSp = NAV_SP)

    private fun backKey(width: Float, textSp: Float) =
        key(KeyCommand.ShowPage(Pages.DEFAULT), width, label = "返回", tone = Tone.Enter, textSp = textSp)

    private fun deleteKey(width: Float, textSp: Float) = key(
        KeyCommand.Special(Nav.Backspace), width, label = "ic@backspace_outline",
        tone = Tone.Enter, textSp = textSp, preview = "⇦",
    )

    /** 表情和标点共用的底部行：当前所在的类别高亮 */
    private fun sharedBottom(category: Category): Row {
        fun tab(label: String, target: String, active: Boolean) =
            navKey(target, label, 16.5f, if (active) Tone.Active else Tone.Gn)
        return Row(
            30f,
            listOf(
                backKey(17f, NAV_SP),
                tab("中文", PUNCT_CN, category.id == "cn"),
                tab("英文", PUNCT_EN, category.id == "en"),
                tab("表情", EMOJI_FIRST, category.kind == Kind.Emoji),
                tab("颜文", KAO_FIRST, false),
                deleteKey(17f, NAV_SP),
            ),
        )
    }

    private fun emojiPage(id: String, category: Category, index: Int, last: Int, symbols: List<Symbol>): Page {
        val tabs = categories.filter { it.kind == Kind.Emoji }
        fun tab(c: Category) = navKey(pageId(c.id, 0), c.label, 10f, if (c === category) Tone.Active else Tone.Gn).copy(textSp = 0f)
        val top = tabs.take(8).map(::tab) + listOf(
            if (index > 0) navKey(pageId(category.id, index - 1), "上页", 10f, Tone.Plain) else tab(tabs[8]),
            if (index < last) navKey(pageId(category.id, index + 1), "下页", 10f, Tone.Plain) else navKey("symbols", "更多", 10f, Tone.Plain),
        )
        return Page(
            id,
            AsciiPolicy.Chinese,
            listOf(Row(26f, top)) + grid(symbols, Kind.Emoji, 10f, 30f) + sharedBottom(category),
        )
    }

    private fun punctPage(id: String, category: Category, index: Int, last: Int, symbols: List<Symbol>): Page {
        val width = 14.28f
        val rows = grid(symbols, Kind.Punct, width, 30f).toMutableList()
        if (last > 0) {
            val filled = symbols.size % Kind.Punct.columns
            val lastRow = if (filled == 0) Row(30f, emptyList()) else rows.removeAt(rows.lastIndex)
            val nav = listOf(
                if (index > 0) navKey(pageId(category.id, index - 1), "上页", width) else gap(width),
                if (index < last) navKey(pageId(category.id, index + 1), "下页", width) else gap(width),
                navKey("symbols", "更多", width),
            )
            val spacer = List(Kind.Punct.columns - lastRow.keys.size - nav.size) { gap(width) }
            rows += lastRow.copy(keys = lastRow.keys + spacer + nav)
        }
        return Page(id, AsciiPolicy.Chinese, rows + sharedBottom(category))
    }

    private fun kaoPage(id: String, category: Category, symbols: List<Symbol>): Page {
        val width = 12.5f
        val textSp = 16f
        val tabs = categories.filter { it.kind == Kind.Kao }.map {
            navKey(pageId(it.id, 0), it.label, width, if (it === category) Tone.Active else Tone.Gn).copy(textSp = textSp)
        }
        val bottom = Row(24f, listOf(backKey(width, textSp)) + tabs + deleteKey(width, textSp))
        return Page(id, AsciiPolicy.Ascii, grid(symbols, Kind.Kao, 25f, 24f, 14f) + bottom)
    }
}
