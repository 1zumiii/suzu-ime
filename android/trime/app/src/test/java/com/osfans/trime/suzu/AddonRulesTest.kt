// SPDX-FileCopyrightText: 2026 1zumiii
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.suzu

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as stringShouldContain

class AddonRulesTest : BehaviorSpec({
    Given("词条过滤") {
        Then("保留 2 到 8 字的词") {
            AddonRules.keepWord("阿拜多斯") shouldBe true
            AddonRules.keepWord("八个字的一个词条呀") shouldBe false
        }
        Then("去掉单字和同字重复") {
            AddonRules.keepWord("啊") shouldBe false
            AddonRules.keepWord("啊啊啊啊") shouldBe false
        }
    }
    Given("上游词表") {
        val source = listOf("---", "name: moegirl", "...", "阿拜多斯\ta bai duo si", "啊啊\ta a", "坏编码\tA1", "只有词")
        val out = AddonRules.convert("moegirl", source.asSequence()).toList()
        Then("只输出有效词条，并带统一权重") {
            out shouldContain "阿拜多斯\ta bai duo si\t${AddonRules.WEIGHT}"
            out shouldNotContain "啊啊\ta a\t${AddonRules.WEIGHT}"
            out.count { '\t' in it } shouldBe 1
            out shouldContain "name: moegirl"
        }
    }
    Given("主词典") {
        val main = "---\nname: suzu\nimport_tables:\n  - cn_dicts_frost/base\n"
        Then("没有附加词库时不变") {
            AddonRules.composeMainDict(main, emptyList()) shouldBe main
        }
        Then("启用后加进导入列表") {
            AddonRules.composeMainDict(main, listOf("moegirl")) stringShouldContain "import_tables:\n  - suzu_addons/moegirl\n  - cn_dicts_frost/base"
        }
    }
})
