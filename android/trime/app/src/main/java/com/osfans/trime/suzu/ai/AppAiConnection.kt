/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu.ai

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.osfans.trime.data.base.DataManager

/**
 * 输入法进程一侧：绑定 [AppAiService]，并替代 rimellmd 的监督进程做看门狗。
 *
 * 看门狗每 3 秒查一次：心跳（B → PONG）连续 3 次失败，或客户端库连续 3 次打分请求等到超时都没有应答
 * （librime_llm.so 的 nativeTimeoutStreak），就对推理进程调用 Process.killProcess。绑定还在，Android 会重建它。
 * 绑定或重建后的 10 秒内不判定（模型加载期间心跳照常应答，留余量给系统拉起进程）。
 */
object AppAiConnection {
    private const val BEAT_MS = 3000L
    private const val GRACE_MS = 10_000L
    private const val MAX_FAILS = 3
    private const val MAX_TIMEOUT_STREAK = 3

    private val thread = HandlerThread("suzu-ai-watchdog").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var appContext: Context? = null
    @Volatile private var bound = false
    @Volatile private var connected = false
    @Volatile private var graceUntil = 0L
    private var pingFails = 0
    private var streakBase = 0

    private val clientLoaded = runCatching { System.loadLibrary("rime_llm") }.isSuccess

    @JvmStatic
    private external fun nativeTimeoutStreak(): Int

    private val conn =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected = true
                graceUntil = SystemClock.uptimeMillis() + GRACE_MS
                appContext?.let { AiEvents.append(it, "connected") }
            }

            // 推理进程死了（被回收、被杀或崩溃）：绑定还在，Android 会重建服务，这里只记一笔
            override fun onServiceDisconnected(name: ComponentName?) {
                connected = false
                appContext?.let { AiEvents.append(it, "disconnected (process died)") }
            }
        }

    fun isBound() = bound

    @Synchronized
    fun bind(context: Context, backend: AiBackend) {
        if (bound) return
        val app = context.applicationContext
        appContext = app
        val intent =
            Intent(app, AppAiService::class.java)
                .putExtra(AppAiService.EXTRA_RIME_DIR, DataManager.userDataDir.absolutePath)
                .putExtra(AppAiService.EXTRA_BOOSTED, backend == AiBackend.APP_SERVICE_BOOSTED)
        var flags = Context.BIND_AUTO_CREATE
        if (backend == AiBackend.APP_SERVICE_BOOSTED) flags = flags or Context.BIND_IMPORTANT
        bound = app.bindService(intent, conn, flags)
        graceUntil = SystemClock.uptimeMillis() + GRACE_MS
        pingFails = 0
        AiEvents.append(app, "bind backend=${backend.id} ok=$bound")
        if (bound) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(::watch, BEAT_MS)
        }
    }

    @Synchronized
    fun unbind() {
        handler.removeCallbacksAndMessages(null)
        if (!bound) return
        bound = false
        connected = false
        appContext?.let {
            runCatching { it.unbindService(conn) }
            AiEvents.append(it, "unbind")
        }
    }

    /** 状态徽标点“启动”时：没绑定就绑定，绑定着却离线就杀掉推理进程让 Android 重建 */
    fun ensureRunning(context: Context, backend: AiBackend) {
        if (!bound) {
            bind(context, backend)
            return
        }
        handler.post {
            if (!PortProbe.alive()) killServiceProcess(context, "manual restart")
        }
    }

    /** 结束推理进程（同一 UID 的进程可以直接杀）。找不到时什么都不做 */
    fun killServiceProcess(context: Context, why: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = context.packageName + AppAiService.PROCESS_SUFFIX
        val pid = am.runningAppProcesses?.firstOrNull { it.processName == name }?.pid ?: return
        AiEvents.append(context, "kill pid=$pid ($why)")
        Process.killProcess(pid)
        graceUntil = SystemClock.uptimeMillis() + GRACE_MS
        pingFails = 0
    }

    private fun watch() {
        if (!bound) return
        val ctx = appContext ?: return
        if (SystemClock.uptimeMillis() >= graceUntil) {
            pingFails = if (PortProbe.alive()) 0 else pingFails + 1
            val streak = if (clientLoaded) runCatching { nativeTimeoutStreak() }.getOrDefault(0) else 0
            if (streak < streakBase) streakBase = 0   // 有过一次正常应答，计数清零了
            when {
                pingFails >= MAX_FAILS -> killServiceProcess(ctx, "watchdog: $pingFails heartbeats failed")
                streak - streakBase >= MAX_TIMEOUT_STREAK -> {
                    streakBase = streak
                    killServiceProcess(ctx, "watchdog: $streak score requests timed out in a row")
                }
            }
        }
        handler.postDelayed(::watch, BEAT_MS)
    }
}
