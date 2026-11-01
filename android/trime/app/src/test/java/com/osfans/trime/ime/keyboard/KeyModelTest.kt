// SPDX-FileCopyrightText: 2026 1zumiii
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class KeyModelTest : BehaviorSpec({
    val chinese = KeyState()
    val english = KeyState(asciiMode = true)
    val default = Pages.get(Pages.DEFAULT)
    fun find(pred: (KeySpec) -> Boolean) = default.rows.flatMap { it.keys }.first(pred)

    Given("字母页") {
        val comma = find { it.command == KeyCommand.Char(',') }
        val space = find { it.command == KeyCommand.Special(Nav.Space) }
        val shift = find { it.isShift }
        val letter = find { it.command == KeyCommand.Char('q') }

        Then("标签只看 ascii_mode") {
            comma.displayLabel(chinese) shouldBe "，"
            comma.displayLabel(english) shouldBe ","
            space.displayLabel(chinese) shouldBe "拼音"
            space.displayLabel(english) shouldBe "English"
        }
        Then("Shift 只影响单字符标签") {
            letter.displayLabel(chinese.copy(shifted = true)) shouldBe "Q"
            space.displayLabel(chinese.copy(shifted = true)) shouldBe "拼音"
        }
        Then("输入中 Shift 变成分词符") {
            val composing = chinese.copy(composing = true)
            shift.activeCommand(composing) shouldBe KeyCommand.Insert("'")
            shift.displayLabel(composing) shouldBe "分词"
        }
        Then("回车标签来自输入框") {
            val enter = find { it.command == KeyCommand.Special(Nav.Return) }
            enter.displayLabel(chinese.copy(enterLabel = "发送")) shouldBe "发送"
            enter.displayLabel(chinese.copy(enterLabel = "发送", composing = true)) shouldBe "Enter"
        }
    }

    Given("Shift 状态") {
        Then("点按、锁定与输入后的变化") {
            ShiftState.Off.tapped() shouldBe ShiftState.Once
            ShiftState.Once.tapped() shouldBe ShiftState.Off
            ShiftState.Once.lockToggled() shouldBe ShiftState.Locked
            ShiftState.Locked.lockToggled() shouldBe ShiftState.Off
            ShiftState.Once.afterKey() shouldBe ShiftState.Off
            ShiftState.Locked.afterKey() shouldBe ShiftState.Locked
        }
    }

    Given("进出页面的中英文状态") {
        Then("跟随语言的页面进出都保持") {
            targetAsciiMode(AsciiPolicy.Follow, false, true) shouldBe true
            targetAsciiMode(AsciiPolicy.Chinese, true, true) shouldBe true
            targetAsciiMode(AsciiPolicy.Chinese, false, true) shouldBe false
            targetAsciiMode(AsciiPolicy.Ascii, false, false) shouldBe true
        }
    }

    Given("每个可达页面的布局") {
        fun targets(page: Page) = page.rows.flatMap { it.keys }.flatMap {
            listOfNotNull(it.command, it.long, it.double, it.swipeUp, it.swipeDown, it.swipeLeft, it.swipeRight, it.composing)
        }.filterIsInstance<KeyCommand.ShowPage>().map { it.id }

        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(Pages.DEFAULT, Pages.NUMBER, "letter", "editor"))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == Pages.LAST || !seen.add(id)) continue
            queue += targets(Pages.get(id))
        }

        Then("没有超出键盘范围的键，也没有重叠") {
            for (id in seen) {
                val keys = layoutPage(Pages.get(id), 1080, 618)
                for (k in keys) {
                    (k.x >= 0 && k.x + k.width <= 1080 && k.y + k.height <= 618) shouldBe true
                }
                keys.groupBy { it.y }.values.forEach { row ->
                    row.sortedBy { it.x }.zipWithNext { a, b -> (a.x + a.width <= b.x) shouldBe true }
                }
            }
        }
        Then("页面总高度等于键盘高度") {
            for (id in seen) layoutPage(Pages.get(id), 1080, 618).maxOf { it.y + it.height } shouldBe 618
        }
        Then("所有分类都能到达") {
            seen shouldContainAll listOf(SymbolBoard.PUNCT_CN, SymbolBoard.PUNCT_EN, SymbolBoard.EMOJI_FIRST, SymbolBoard.KAO_FIRST, "numpad", "symbols")
        }
    }

    Given("符号板分页") {
        fun symbolsOf(category: String) = (0 until SymbolBoard.pageCount(category)).flatMap { n ->
            Pages.get("sym/$category/$n").rows.flatMap { it.keys }.mapNotNull { it.command }.filter {
                it is KeyCommand.Insert || it is KeyCommand.Char
            }
        }

        Then("分页不丢符号") {
            symbolsOf("rw").size shouldBe SymbolData.emoji_rw.size
            symbolsOf("cn").size shouldBe SymbolData.punct_cn.size
            symbolsOf("en").size shouldBe SymbolData.punct_en.size
            symbolsOf("h").size shouldBe SymbolData.kao_h.size
        }
        Then("每行键宽合计不超过键盘宽度") {
            for (category in listOf("rw", "fh", "cn", "en", "h", "m")) {
                for (n in 0 until SymbolBoard.pageCount(category)) {
                    Pages.get("sym/$category/$n").rows.forEach { row ->
                        (row.keys.sumOf { it.width.toDouble() } <= 100.5) shouldBe true
                    }
                }
            }
        }
    }
})
