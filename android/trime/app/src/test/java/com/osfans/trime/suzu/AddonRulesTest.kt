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
    Given("按词库包换算权重") {
        val map = listOf(0.0 to 100.0, 13.0 to 100.0, 100.0 to 2448.0, 1000.0 to 24000.0)
        Then("没有换算节点时原样返回") {
            AddonRules.scaleWeight(42.0, null) shouldBe 42.0
        }
        Then("节点上取节点值，两端截断") {
            AddonRules.scaleWeight(100.0, map) shouldBe 2448.0
            AddonRules.scaleWeight(0.0, map) shouldBe 100.0
            AddonRules.scaleWeight(5.0, map) shouldBe 100.0
            AddonRules.scaleWeight(1e9, map) shouldBe 24000.0
        }
        Then("节点之间单调") {
            val ys = listOf(14.0, 30.0, 60.0, 99.0, 101.0, 500.0).map { AddonRules.scaleWeight(it, map) }
            ys.zipWithNext().all { (a, b) -> a < b } shouldBe true
            (ys.first() > 100.0 && ys.last() < 24000.0) shouldBe true
        }
        val ref = sequenceOf("# 由 App 从上游词表生成", "---", "name: moegirl", "...", "阿拜多斯\ta bai duo si\t10", "不沟通\tbu gou tong\t100")
        Then("换算后写尺度标记，只改权重列") {
            val out = AddonRules.rescale(ref, map, "ice@1").toList()
            AddonRules.scaleTagOf(out.asSequence()) shouldBe "ice@1"
            out shouldContain "不沟通\tbu gou tong\t2448"
            out shouldContain "name: moegirl"
            out.count { '\t' in it } shouldBe 2
        }
        Then("基准包原样保留权重，重写时替换旧标记") {
            val once = AddonRules.rescale(ref, map, "ice@1").toList()
            val back = AddonRules.rescale(once.asSequence(), null, "frost").toList()
            AddonRules.scaleTagOf(back.asSequence()) shouldBe "frost"
            back.count { it.startsWith(AddonRules.SCALE_PREFIX) } shouldBe 1
            AddonRules.rescale(ref, null, "frost").toList() shouldContain "阿拜多斯\ta bai duo si\t10"
        }
        Then("没有标记的旧文件读出 null") {
            AddonRules.scaleTagOf(ref) shouldBe null
        }
    }
    Given("保留原权重的附加词库") {
        Then("保留合法权重，继续过滤单字、同字重复和无效权重") {
            val source = sequenceOf("...", "不沟通\tbu gou tong\t215", "没通知\tmei tong zhi\t12.5",
                "啊啊\ta a\t100", "无效\twu xiao\tNaN", "不处理\tbu chu li")
            AddonRules.convert("fixture", source, preserveWeight = true).filter { '\t' in it }.toList() shouldBe
                listOf("不沟通\tbu gou tong\t215", "没通知\tmei tong zhi\t12.5")
        }
    }
})
