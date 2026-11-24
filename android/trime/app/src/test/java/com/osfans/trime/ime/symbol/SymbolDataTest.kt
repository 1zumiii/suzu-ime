// SPDX-FileCopyrightText: 2026 1zumiii
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.symbol

import com.osfans.trime.ime.keyboard.Nav
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class SymbolDataTest : BehaviorSpec({
    val groups = mapOf("符号" to SymbolData.symbols, "表情" to SymbolData.emoji, "颜文字" to SymbolData.kaomoji)

    Given("符号面板的分类") {
        Then("分类名不重复，每类都有内容且条目不重复") {
            for ((_, categories) in groups) {
                categories.map { it.name }.distinct().size shouldBe categories.size
                for (c in categories) {
                    c.items.isNotEmpty() shouldBe true
                    c.items.map { it.text }.distinct().size shouldBe c.items.size
                    c.items.none { it.text.isBlank() } shouldBe true
                    (c.columns > 0) shouldBe true
                }
            }
        }
        Then("符号按原型分 22 类") {
            SymbolData.symbols.map { it.name } shouldBe listOf(
                "中文", "括号引号", "英文", "数学", "单位", "箭头", "货币", "序号", "上下标", "几何星形", "拼音注音",
                "希腊字母", "假名", "俄语", "韩文", "拉丁音标", "花体字母", "构字部件", "竖排标点", "音乐天气", "棋盘牌类", "特殊",
            )
        }
        Then("常用符号都在对应的分类里") {
            fun texts(name: String) = SymbolData.symbols.first { it.name == name }.items.map { it.text }
            texts("中文") shouldContainAll listOf("，", "。", "、", "……", "——")
            texts("括号引号") shouldContainAll listOf("《》", "“”", "（）", "【】")
            texts("英文") shouldContainAll listOf(",", ".", "\\", "/", "()")
            texts("数学") shouldContainAll listOf("±", "×", "÷", "≠", "≈", "∞", "√")
            texts("箭头") shouldContainAll listOf("←", "→", "↑", "↓")
        }
        Then("成对的括号上屏后光标回到中间") {
            val pairs = SymbolData.symbols.first { it.name == "括号引号" }.items.filter { it.text.length == 2 }
            pairs.isNotEmpty() shouldBe true
            pairs.all { it.after == listOf(Nav.Left) } shouldBe true
        }
        Then("中文页不放括号和半角符号") {
            SymbolData.symbols.first().items.none { it.after.isNotEmpty() || it.text.all { c -> c.code < 128 } } shouldBe true
        }
        Then("表情按 Unicode 分组") {
            SymbolData.emoji.map { it.name } shouldBe listOf("笑脸", "人物", "自然", "饮食", "旅行", "活动", "科技", "标记", "旗帜")
            SymbolData.emoji.first { it.name == "旗帜" }.items.map { it.text } shouldContainAll listOf("🇨🇳", "🏁")
        }
        Then("颜文字的分类") {
            SymbolData.kaomoji.map { it.name } shouldBe listOf("开心", "喜欢", "惊讶", "无奈", "伤心", "生气")
        }
    }
})
