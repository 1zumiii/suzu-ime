// SPDX-FileCopyrightText: 2026 1zumiii
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.suzu

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SuzuAppContextTest : BehaviorSpec({
    Given("光标变化后上文是否作废") {
        Then("输入法自己上屏引起的移动不作废") {
            SuzuAppContext.cursorJumped(3, 5, 10) shouldBe false
        }
        Then("用户点击移动光标作废") {
            SuzuAppContext.cursorJumped(5, 2, 2000) shouldBe true
        }
        Then("回车发出消息后输入框被清空，即使紧跟在自己的按键之后也作废") {
            SuzuAppContext.cursorJumped(12, 0, 30) shouldBe true
        }
        Then("光标没动不作废") {
            SuzuAppContext.cursorJumped(0, 0, 30) shouldBe false
        }
    }
})
