/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 经 Shizuku 以 shell 身份读 WindowManager 记录的宿主状态栏外观，判断当前应用是深色还是浅色。
 * 输入框字色（TextAppearanceInfo）取不到时用它，WebView、Flutter、自绘输入框的应用也有这个信号。
 * 只读 `dumpsys window displays` 里的焦点窗口和状态栏外观区域，不读应用的内容，不截屏。
 * Shizuku 没在运行或没授权时什么都不做，也不弹授权。
 */
object HostAppearance {
    private const val TAG = "SuzuColor"
    private const val COMMAND = "dumpsys window displays | grep -E 'mFocusedWindow=|AppearanceRegion\\{'"

    /** 单线程，查询排队执行，回调里自己判断结果是否已经过时 */
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "SuzuHostAppearance").apply { isDaemon = true } }

    /**
     * 在后台查一次，[onResult] 在后台线程回调：true 深色，false 浅色，null 判断不出
     * （Shizuku 不可用、焦点窗口不是 [pkg]、输出格式不认识）。
     */
    fun query(pkg: String, onResult: (Boolean?) -> Unit) {
        if (AiDaemonLauncher.availability() != AiDaemonLauncher.Availability.READY) return
        executor.execute {
            val dark =
                runCatching {
                    val method =
                        Shizuku::class.java.getDeclaredMethod(
                            "newProcess",
                            Array<String>::class.java,
                            Array<String>::class.java,
                            String::class.java,
                        )
                    method.isAccessible = true
                    val proc = method.invoke(null, arrayOf("sh", "-c", COMMAND), null, null) as Process
                    val out = proc.inputStream.bufferedReader().readText()
                    if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroy()
                    parse(out, pkg)
                }.onFailure { Log.w(TAG, "读取宿主窗口外观失败：${it.message}") }.getOrNull()
            onResult(dark)
        }
    }

    private val focusedRegex = Regex("""mFocusedWindow=Window\{\S+ u\d+ ([^/\s}]+)""")
    private val regionRegex = Regex("""AppearanceRegion\{([^}]*?)\s*bounds=\[(-?\d+),(-?\d+)]""")

    /**
     * 从 dumpsys 输出判断 [pkg] 的深浅色。只在焦点窗口属于 [pkg] 时给结果。
     * 取顶边从 0 开始的外观区域（状态栏所在），有 LIGHT_STATUS_BARS 就是浅色应用，没有就是深色。
     * 导航栏外观不用：键盘弹出时导航栏归输入法管。
     */
    internal fun parse(dump: String, pkg: String): Boolean? {
        val focused = focusedRegex.find(dump)?.groupValues?.get(1) ?: return null
        if (focused != pkg) return null
        val region =
            regionRegex.findAll(dump).firstOrNull { it.groupValues[3].toIntOrNull() == 0 }
                ?: return null
        val flags = region.groupValues[1].split(' ', '|').filter { it.isNotBlank() }
        return "LIGHT_STATUS_BARS" !in flags
    }
}
