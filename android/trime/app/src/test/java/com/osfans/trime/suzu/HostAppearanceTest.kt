package com.osfans.trime.suzu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HostAppearanceTest : StringSpec({
    val darkWeChat =
        """
          mCurrentFocus=Window{19f4088 u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI}
            mFocusedWindow=Window{19f4088 u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI}
              AppearanceRegion{ bounds=[0,0][1080,2400]}
        """.trimIndent()

    "状态栏不是浅色时判为深色" {
        HostAppearance.parse(darkWeChat, "com.tencent.mm") shouldBe true
    }
    "有 LIGHT_STATUS_BARS 时判为浅色" {
        val dump =
            """
                mFocusedWindow=Window{8ac5ad7 u0 com.example.app/com.example.app.Main}
                  AppearanceRegion{LIGHT_STATUS_BARS | LIGHT_NAVIGATION_BARS bounds=[0,0][1080,2400]}
            """.trimIndent()
        HostAppearance.parse(dump, "com.example.app") shouldBe false
    }
    "只看顶部的区域" {
        val dump =
            """
                mFocusedWindow=Window{1 u0 com.example.app/.Main}
                  AppearanceRegion{ bounds=[0,1200][1080,2400]}
                  AppearanceRegion{LIGHT_STATUS_BARS bounds=[0,0][1080,1200]}
            """.trimIndent()
        HostAppearance.parse(dump, "com.example.app") shouldBe false
    }
    "焦点窗口不是当前应用时不给结果" {
        HostAppearance.parse(darkWeChat, "com.discord") shouldBe null
    }
    "输出不认识时不给结果" {
        HostAppearance.parse("", "com.tencent.mm") shouldBe null
        HostAppearance.parse("    mFocusedWindow=Window{1 u0 com.tencent.mm/.A}", "com.tencent.mm") shouldBe null
    }
})
