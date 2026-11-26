// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.suzu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DictCatalogTest : StringSpec({
    val url = "https://github.com/1zumiii/rime-suzu/releases/download/dict-packs/demo.zip"
    val moegirl = "https://github.com/outloudvi/mw2fcitx/releases/download/20261024/moegirl.dict.yaml"
    val pack = """{"id":"frost","title":"白霜词库","schema_label":"白霜","source":"来源","license":"GPL-3.0",
        "url":"$url","sha256":"${"a".repeat(64)}","size":42,"commit":"${"a".repeat(40)}"}"""
    val addon = """{"id":"moegirl","title":"萌娘百科","source":"来源","license":"CC-BY-NC-SA-3.0",
        "url":"$moegirl","sha256":"${"b".repeat(64)}","size":100,"weight":"preserve"}"""
    fun document(addons: String = addon) = """{"schema":1,"packs":[$pack],"addons":[$addons]}"""

    "词库包保留原 ID 和方案标签，附加词库可保留权重" {
        val parsed = checkNotNull(DictCatalog.parse(document()))
        parsed.packs.single().name shouldBe "frost"
        parsed.packs.single().schemaLabel shouldBe "白霜"
        parsed.addons.single().id shouldBe "moegirl"
        parsed.addons.single().preserveWeight shouldBe true
    }
    "名录地址白名单排除其他账号、伪造主机、路径穿越与非 HTTPS" {
        DictCatalog.allowedDownload(url) shouldBe true
        DictCatalog.allowedDownload(moegirl) shouldBe true
        for (bad in listOf(url.replace("1zumiii", "other"), url.replace("github.com", "github.com.evil.example"),
            url.replace("dict-packs/", "../"), url.replace("https:", "http:"), "$url?redirect=1",
            moegirl.replace("moegirl.dict.yaml", "other.yaml"))) {
            DictCatalog.allowedDownload(bad) shouldBe false
        }
    }
    "拒绝不支持的版本与没有有效词库包的名录" {
        DictCatalog.parse(document().replace("\"schema\":1", "\"schema\":2")) shouldBe null
        DictCatalog.parse(document().replace("\"size\":42", "\"size\":0")) shouldBe null
    }
    "跳过不合格及重复的附加词库" {
        val entries = checkNotNull(DictCatalog.parse(document("$addon,$addon,${addon.replace("\"preserve\"", "\"invalid\"")}")))
        entries.addons.size shouldBe 1
        checkNotNull(DictCatalog.parse(document(addon.replace("\"moegirl\"", "\"../bad\"")))).addons.size shouldBe 0
    }
})
