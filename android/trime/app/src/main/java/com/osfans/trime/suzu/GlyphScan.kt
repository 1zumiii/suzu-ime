/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.graphics.Paint
import android.os.Build
import com.osfans.trime.BuildConfig
import com.osfans.trime.data.base.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 本机字体画不出的字：扫描后写成用户目录的 glyph_missing.txt，由 Lua 的 glyph_guard.lua 读取，
 * 含这些字的候选不再显示（否则候选栏里会出现方框或问号）。
 * 第一行记下 App 版本与系统指纹，两者都没变时不重扫；系统升级（字体随之更新）或换 App 版本后自动重扫。
 * 扫描范围与 macos/glyph_scan.swift 一致，十万个码位左右，在后台线程上跑一次约一两秒。
 */
object GlyphScan {
    private const val FILE = "glyph_missing.txt"

    private val ranges = listOf(
        0x2600..0x27BF, 0x2B00..0x2BFF, // 杂项符号、装饰符号、箭头补充
        0x3400..0x4DBF, 0x4E00..0x9FFF, // 扩展 A、基本区
        0xF900..0xFAFF, // 兼容汉字
        0x1F000..0x1FAFF, // 表情与符号
        0x20000..0x323AF, // 扩展 B 到 H、I，兼容汉字补充
    )

    private fun stamp() = "# glyph_scan ${BuildConfig.BUILD_VERSION_NAME} ${Build.FINGERPRINT}"

    suspend fun ensure() = withContext(Dispatchers.Default) {
        runCatching {
            val f = DataManager.userDataDir.resolve(FILE)
            val stamp = stamp()
            if (f.exists() && f.bufferedReader().use { it.readLine() } == stamp) return@runCatching
            val t0 = System.currentTimeMillis()
            val paint = Paint()
            val out = StringBuilder(stamp).append('\n')
            var total = 0
            for (r in ranges) {
                var start = -1
                for (cp in r) {
                    val miss = !paint.hasGlyph(String(Character.toChars(cp)))
                    if (miss) {
                        total++
                        if (start < 0) start = cp
                    }
                    if (start >= 0 && (!miss || cp == r.last)) {
                        val end = if (miss) cp else cp - 1
                        out.append(if (start == end) "%X".format(start) else "%X-%X".format(start, end)).append('\n')
                        start = -1
                    }
                }
            }
            // 先写临时文件再改名，Lua 不会读到写了一半的清单
            val tmp = DataManager.userDataDir.resolve("$FILE.tmp")
            tmp.writeText(out.toString())
            tmp.renameTo(f)
            Timber.i("glyph scan: %d missing code points, %d ms", total, System.currentTimeMillis() - t0)
        }.onFailure { Timber.w(it, "glyph scan failed") }
    }
}
