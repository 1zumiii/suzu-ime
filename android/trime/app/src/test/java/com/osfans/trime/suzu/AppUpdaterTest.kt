// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.suzu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe

class AppUpdaterTest :
    StringSpec({
        val json =
            """
            [
              {"tag_name": "android-v0.4.0", "draft": false, "prerelease": false, "body": "## 变化\n- **新功能**\n- 修复 `bug`",
               "html_url": "https://github.com/1zumiii/suzu-ime/releases/tag/android-v0.4.0",
               "assets": [
                 {"name": "suzu-android-v0.4.0-arm64-i8mm.apk", "size": 100, "digest": "sha256:aa", "browser_download_url": "https://x/i8mm.apk"},
                 {"name": "suzu-android-v0.4.0-arm64-i8mm.apk.sha256", "size": 1, "digest": "sha256:bb", "browser_download_url": "https://x/i8mm.sha"},
                 {"name": "suzu-android-v0.4.0-arm64.apk", "size": 200, "digest": "sha256:cc", "browser_download_url": "https://x/plain.apk"}
               ]},
              {"tag_name": "android-v0.5.0-rc1", "draft": false, "prerelease": true, "body": "", "assets": []},
              {"tag_name": "desktop-v0.3.1", "draft": false, "prerelease": false, "body": "", "assets": []},
              {"tag_name": "android-v0.2.0", "draft": false, "prerelease": false, "body": "",
               "assets": [{"name": "suzu-android-v0.2.0-arm64.apk", "size": 1, "digest": "sha256:dd", "browser_download_url": "https://x/old.apk"}]}
            ]
            """.trimIndent()

        "picks the i8mm package on i8mm chips" {
            val r = AppUpdater.parseReleases(json, i8mm = true)
            r.map { it.version } shouldBe listOf("0.4.0")
            r[0].apk.url shouldBe "https://x/i8mm.apk"
            r[0].apk.sha256 shouldBe "aa"
        }

        "picks the universal package otherwise and skips prereleases and desktop tags" {
            val r = AppUpdater.parseReleases(json, i8mm = false)
            r.map { it.version } shouldBe listOf("0.4.0", "0.2.0")
            r[0].apk.url shouldBe "https://x/plain.apk"
            r[0].apk.size shouldBe 200L
        }

        "release without a matching package is ignored" {
            AppUpdater.parseReleases("""[{"tag_name":"android-v1.0.0","assets":[]}]""", i8mm = false).shouldBeEmpty()
        }

        "compares versions numerically" {
            AppUpdater.compareVersions("0.10.0", "0.9.9") shouldBeGreaterThan 0
            AppUpdater.compareVersions("0.3.0", "0.3.1") shouldBeLessThan 0
            AppUpdater.compareVersions("0.3", "0.3.0") shouldBe 0
            AppUpdater.compareVersions("0.3.1-rc1", "0.3.1") shouldBe 0
        }

        "release notes become plain text" {
            AppUpdater.plainNotes("## 变化\n- **新功能**\n\n\n\n- 修复 `bug`") shouldBe "变化\n• 新功能\n\n• 修复 bug"
        }

        "markdown links keep only their text" {
            AppUpdater.plainNotes("默认模型换为 [PG200](https://huggingface.co/x)，见 [models.json](https://github.com/y)") shouldBe
                "默认模型换为 PG200，见 models.json"
        }
    })
