/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 推理服务的事件记录：启动、上次退出的原因、看门狗杀进程、切换方案。A/B 对比时用来数“后台被回收几次”。
 * 文件在应用的外部目录（/sdcard/Android/data/<包名>/files/rimellm/service_events.txt），adb shell 能直接读；
 * 两个进程（输入法与 :suzu_ai）都往里追加短行。只有时间与原因，不含任何输入内容。
 */
object AiEvents {
    private const val TAG = "SuzuAi"
    private const val MAX_BYTES = 256 * 1024

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "rimellm").also { it.mkdirs() }

    fun file(context: Context) = File(dir(context), "service_events.txt")

    @Synchronized
    fun append(context: Context, line: String) {
        Log.i(TAG, "推理服务：$line")
        runCatching {
            val f = file(context)
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "service_events.old.txt"))
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            f.appendText("$ts\t$line\n")
        }
    }
}
