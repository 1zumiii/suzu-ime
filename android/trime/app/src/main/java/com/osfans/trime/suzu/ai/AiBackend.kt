/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu.ai

import android.app.ActivityManager
import android.content.Context
import com.osfans.trime.TrimeApplication
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.suzu.AiDaemonLauncher
import com.osfans.trime.suzu.SuzuAsset
import com.osfans.trime.suzu.SuzuAssets
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 重排的推理由谁来跑。两种方案说同一套线路协议（127.0.0.1:17920、用户目录里的 llm_token 与 ai_status.json），
 * 客户端库 librime_llm.so 与 Lua 不区分，所以切换不用重装，也不用重新部署。同一时间只让一种方案在跑：
 * 端口只有一个，切换时先停旧的再起新的。
 *
 */
enum class AiBackend(
    val id: String,
    val title: String,
    val summary: String,
) {
    /** 默认：Shizuku 以 shell 身份拉起 rimellmd（监督进程 + 工作进程），手机重启后要重新经 Shizuku 启动 */
    SHIZUKU("shizuku", "Shizuku 守护进程", "默认方案。需要 Shizuku，手机重启后要重新启动"),

    /** 应用自己的推理服务：独立进程 :suzu_ai 里的绑定服务，进程的重启交给 Android */
    APP_SERVICE("app", "应用内推理服务（实验）", "不需要 Shizuku，由系统管理进程"),

    /** 同上，加 BIND_IMPORTANT，推理线程优先级提到 THREAD_PRIORITY_DISPLAY，用于 A/B 对比 */
    APP_SERVICE_BOOSTED("app_boosted", "应用内推理服务·高优先级（实验）", "同上，提高进程重要性与线程优先级"),
    ;

    val isAppService get() = this != SHIZUKU

    companion object {
        val DEFAULT = SHIZUKU

        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

object AiBackends {
    private const val PREFS = "suzu_ai_backend"
    private const val KEY = "backend"
    private val scope = MainScope()
    private var transition: Job? = null
    private var imeActive = false
    val switching get() = transition?.isActive == true
    val locked get() = TrimeApplication.getInstance().isDirectBootMode

    fun current(context: Context): AiBackend {
        if (locked) return AiBackend.DEFAULT
        return AiBackend.fromId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun onImeCreate(context: Context) {
        imeActive = true
        val backend = current(context)
        if (backend.isAppService && !switching) AppAiConnection.bind(context, backend)
    }

    fun onImeDestroy() {
        imeActive = false
        AppAiConnection.unbind()
    }

    /** 所有入口在主线程调用。新选择取消旧切换，已经发出的 shell 命令按提交顺序执行。 */
    fun switchTo(context: Context, target: AiBackend, onDone: (String) -> Unit) {
        if (locked) {
            onDone("请先解锁设备")
            return
        }
        val app = context.applicationContext
        transition?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val message = try {
                change(app, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "切换失败（${e.javaClass.simpleName}）"
            }
            AiEvents.append(app, message)
            transition = null
            onDone(message)
        }
        transition = job
        job.start()
    }

    private suspend fun change(app: Context, target: AiBackend): String {
        if (target.isAppService && !SuzuAssets.isInstalled(SuzuAsset.Model)) return "请先在模型页下载 Suzu 模型"
        val old = current(app)
        AiEvents.append(app, "switch ${old.id} -> ${target.id}")
        AppAiConnection.unbind()
        val exited = withTimeoutOrNull(10_000) {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val name = app.packageName + AppAiService.PROCESS_SUFFIX
            while (withContext(Dispatchers.IO) { am.runningAppProcesses?.any { it.processName == name } == true }) delay(150)
            true
        }
        if (exited == null) return "应用内推理进程尚未退出，请稍后重试"

        val shellReady = AiDaemonLauncher.availability() == AiDaemonLauncher.Availability.READY
        if (shellReady && (target.isAppService || old == AiBackend.SHIZUKU)) {
            val stop = AiDaemonLauncher.stop()
            if (stop is AiDaemonLauncher.Result.Failed) return stop.reason
        }
        if (target.isAppService && withContext(Dispatchers.IO) { PortProbe.occupied() }) {
            return "推理端口仍被占用，请确认 Shizuku 已授权并停止旧服务后重试"
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, target.id).apply()
        if (target.isAppService) {
            if (!imeActive) return "已保存${target.title}，启用输入法后启动"
            if (!AppAiConnection.bind(app, target)) return AppAiConnection.problem ?: "绑定推理服务失败"
        } else {
            if (!shellReady) return "已保存 Shizuku 方案，Shizuku 就绪后点按 AI 徽标启动"
            val start = AiDaemonLauncher.startShell(app)
            if (start is AiDaemonLauncher.Result.Failed) return start.reason
        }

        return withTimeoutOrNull(15_000) {
            while (true) {
                if (target.isAppService) {
                    if (!imeActive) return@withTimeoutOrNull "已保存${target.title}，启用输入法后启动"
                    // 等待期间输入法可能已销毁又重新创建。
                    if (!AppAiConnection.isBound() && AppAiConnection.problem == null) AppAiConnection.bind(app, target)
                    AppAiConnection.problem?.let { return@withTimeoutOrNull it }
                    if (AppAiConnection.readyPid > 0) break
                } else if (withContext(Dispatchers.IO) { PortProbe.owner(DataManager.userDataDir)?.host == "shell" }) break
                delay(200)
            }
            "已切换到${target.title}"
        } ?: "已保存${target.title}，尚未确认服务就绪，请查看 AI 徽标"
    }
}
