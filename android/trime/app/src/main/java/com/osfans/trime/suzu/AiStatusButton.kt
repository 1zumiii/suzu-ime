/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.TextView
import android.widget.Toast
import com.osfans.trime.data.base.DataManager
import org.json.JSONObject

object AiStatusButtonAction {
    const val TOGGLE = "ai_toggle"
}

/**
 * AI 重排守护进程（rimellmd）的状态指示和手动启停。读用户目录里的 ai_status.json：
 * 绿 = 模型在线；黄 = 加载中/重启中；灰 = 已关闭或空闲已卸载；红 = 守护进程没有响应。
 * 点一下：开/关（立刻有触感和动画，开启时黄色闪烁直到模型加载完成）；长按：看详情。
 * 从没部署过守护进程（文件不存在）时不显示。
 */
/** [onToggle] 执行切换并返回切换后的目标状态（以输入法方案开关的真实值为准，不看徽标当前显示什么）。 */
class AiStatusButton(context: Context, private val onToggle: () -> Boolean) : TextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var detail = ""

    // 点击后等待守护进程真正切换完成：目标状态 + 截止时间
    private var pendingTarget: Boolean? = null
    private var pendingUntil = 0L
    private var blinkOn = false

    // 守护进程离线时，点一下 = 经 Shizuku 启动它；启动期间显示"启动中"
    private var offlineNow = false
    private var startingUntil = 0L

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, if (pendingTarget != null) FAST_MS else POLL_MS)
        }
    }

    init {
        text = "● AI"
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dpToPx(6), 0, dpToPx(6), 0)
        visibility = GONE
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (offlineNow) {
                startDaemon(userInitiated = true)
                return@setOnClickListener
            }
            val target = onToggle()
            pendingTarget = target
            pendingUntil = SystemClock.uptimeMillis() + PENDING_MS
            pop()
            if (target) show(YELLOW, "AI 重排：开启中，正在加载模型…", "◐ AI") else show(GRAY, "AI 重排：关闭中…", "○ AI")
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, FAST_MS)
        }
        setOnLongClickListener {
            refresh()
            Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
            true
        }
    }

    /** 当前学生模型的显示名：用户目录里 suzu.name 的第一行（如 Suzu-2），没有就显示 Suzu */
    private fun modelName(): String =
        try {
            DataManager.userDataDir.resolve("suzu.name").readText().lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "Suzu" }
        } catch (e: Exception) {
            "Suzu"
        }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /** 弹一下：放大再回落，作为切换完成/开始的视觉反馈 */
    private fun pop() {
        animate().cancel()
        animate().scaleX(1.35f).scaleY(1.35f).setDuration(110).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        animate().cancel()
        alpha = 1f
        super.onDetachedFromWindow()
    }

    private fun show(color: Int, detailText: String, glyph: String = "● AI") {
        text = glyph
        setTextColor(color)
        detail = detailText
        visibility = VISIBLE
    }

    /** 经 Shizuku 启动守护进程。userInitiated = 用户点的（可以弹授权/提示）；否则是静默的自动重试。 */
    private fun startDaemon(userInitiated: Boolean) {
        when (AiDaemonLauncher.availability()) {
            AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING ->
                if (userInitiated) Toast.makeText(context, "Shizuku 没有运行：先启动 Shizuku", Toast.LENGTH_LONG).show()
            AiDaemonLauncher.Availability.NEEDS_PERMISSION ->
                if (userInitiated) AiDaemonLauncher.requestPermission { startDaemon(true) }   // 弹出 Shizuku 的授权请求
            AiDaemonLauncher.Availability.READY -> {
                startingUntil = SystemClock.uptimeMillis() + START_MS
                show(YELLOW, "AI 重排：正在启动守护进程…", "◐ AI")
                if (userInitiated) pop()
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, FAST_MS)
                AiDaemonLauncher.start(context) { r ->
                    if (r is AiDaemonLauncher.Result.Failed) {
                        startingUntil = 0
                        if (userInitiated) Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // 完成：弹一下并轻震一下确认；不弹提示（徽标颜色已经说明了状态）。超时放弃等待时安静收尾
    private fun finishPending(quiet: Boolean = false) {
        pendingTarget = null
        alpha = 1f
        if (!quiet) {
            pop()
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    private fun refresh() {
        val f = DataManager.userDataDir.resolve("ai_status.json")
        // 从没部署过守护进程：只有 Shizuku 在运行时才显示（红色，点一下就能启动）；否则隐藏
        if (!f.exists() && AiDaemonLauncher.availability() == AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING) {
            visibility = GONE
            return
        }
        val j =
            try {
                JSONObject(f.readText())
            } catch (e: Exception) {
                null
            }
        val age = if (j != null) System.currentTimeMillis() / 1000 - j.optLong("ts", 0) else Long.MAX_VALUE
        val fresh = j != null && age <= STALE_S
        val enabled = j?.optBoolean("enabled", true) ?: true
        val loaded = j?.optBoolean("model_loaded") ?: false

        // 点击之后：状态文件还没追上之前，显示"进行中"，追上了就收尾；超时则放弃等待，按真实状态显示
        pendingTarget?.let { target ->
            val done = fresh && enabled == target && (!target || loaded)
            when {
                done -> finishPending()
                SystemClock.uptimeMillis() > pendingUntil -> finishPending(quiet = true)
                else -> {
                    if (target) {
                        blinkOn = !blinkOn
                        alpha = if (blinkOn) 1f else 0.35f          // 加载中：闪烁
                        show(YELLOW, "AI 重排：开启中，正在加载模型…", "◐ AI")
                    } else {
                        show(GRAY, "AI 重排：关闭中…", "○ AI")
                    }
                    return
                }
            }
        }

        offlineNow = !fresh
        if (!fresh) {
            val now = SystemClock.uptimeMillis()
            if (now < startingUntil) {                    // 刚经 Shizuku 启动，等它写出第一份状态
                blinkOn = !blinkOn
                alpha = if (blinkOn) 1f else 0.35f
                show(YELLOW, "AI 重排：正在启动守护进程…", "◐ AI")
                return
            }
            // 键盘弹出时发现守护进程不在：每分钟最多自动经 Shizuku 拉起一次（授权过的情况下）
            if (System.currentTimeMillis() - AiDaemonLauncher.lastAttemptMs > AUTO_RETRY_MS &&
                AiDaemonLauncher.availability() == AiDaemonLauncher.Availability.READY
            ) {
                startDaemon(userInitiated = false)
            }
        } else {
            alpha = 1f
        }

        val restarts = j?.optInt("restarts", 0) ?: 0
        val lastExit = j?.optString("last_exit", "none") ?: "none"
        // 长按详情用 Toast 显示，系统最多显示 2 行、每行约 24 个汉字，超出会被截断；所以每种状态都写成 2 行以内
        val history = if (restarts > 0) "·重启$restarts" else ""
        when {
            fresh && !enabled ->
                show(GRAY, "AI 重排：已关闭\n点一下开启，模型已卸载", "○ AI")
            !fresh ->
                show(
                    RED,
                    "AI 重排：离线$history\n点一下经 Shizuku 启动",
                )
            j!!.optString("role") == "supervisor" ->
                show(YELLOW, "AI 重排：重启中\n上次退出 $lastExit，共 $restarts 次")
            loaded ->
                show(
                    GREEN,
                    "AI 重排：在线（${modelName()}）\n${j.optLong("rss_kb") / 1024} MB·均${"%.0f".format(j.optDouble("avg_score_ms"))} ms·" +
                        "跳${j.optLong("skipped")}错${j.optLong("errors")}$history",
                )
            else ->
                show(GRAY, "AI 重排：待机$history\n打字时自动加载模型")
        }
    }

    companion object {
        private const val POLL_MS = 5000L
        private const val FAST_MS = 400L
        private const val PENDING_MS = 15000L
        private const val START_MS = 20000L
        private const val AUTO_RETRY_MS = 60000L
        private const val STALE_S = 15
        private val GREEN = Color.rgb(0x2E, 0xA0, 0x43)
        private val GRAY = Color.rgb(0x8A, 0x8A, 0x8A)
        private val YELLOW = Color.rgb(0xD9, 0xA4, 0x06)
        private val RED = Color.rgb(0xD1, 0x24, 0x2F)
    }
}
