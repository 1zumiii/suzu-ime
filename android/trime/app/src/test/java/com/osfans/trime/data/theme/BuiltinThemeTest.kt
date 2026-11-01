// SPDX-FileCopyrightText: 2026 1zumiii
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.ime.keyboard.Tone
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class BuiltinThemeTest : BehaviorSpec({
    val schemes = BuiltinTheme.theme.presetColorSchemes
    val light = schemes.getValue(BuiltinTheme.LIGHT_SCHEME)
    val dark = schemes.getValue(BuiltinTheme.DARK_SCHEME)

    Given("内置配色") {
        Then("深色和浅色定义同一组颜色") {
            dark.keys shouldBe light.keys
        }
        Then("按键用到的颜色都有定义") {
            val used = Tone.entries.flatMap { listOfNotNull(it.back, it.text) }
            light.keys shouldContainAll used
            dark.keys shouldContainAll used
        }
    }

    Given("液体键盘") {
        val liquid = BuiltinTheme.theme.liquidKeyboard
        Then("表情和特殊符号的分类存在，且有内容") {
            val tags = liquid.getTagList().map { it.label }
            tags shouldContainAll listOf("特殊", "更多", "常用")
            (liquid.getDataByIndex(tags.indexOf("特殊")).size > 50) shouldBe true
        }
    }
})
