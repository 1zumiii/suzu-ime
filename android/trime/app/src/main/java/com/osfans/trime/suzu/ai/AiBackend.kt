/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.osfans.trime.suzu.AiDaemonLauncher

/**
 * AI 重排的推理由谁来跑。两种方案说同一套线路协议（127.0.0.1:17920、用户目录里的 llm_token 与 ai_status.json），
 * 客户端库 librime_llm.so 与 Lua 不区分，所以切换不用重装，也不用重新部署。同一时间只让一种方案在跑：
 * 端口只有一个，切换时先停旧的再起新的。
 *
 * 新增方案时：在这里加一项，在 [AiBackends.activate] 与 [AiBackends.deactivate] 里接上启停，
 * 状态徽标按 [isAppService] 等属性分支，不必改动客户端。
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
    private val main = Handler(Looper.getMainLooper())

    fun current(context: Context): AiBackend =
        AiBackend.fromId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** 输入法服务创建时：当前是应用内方案就绑定推理服务（Shizuku 方案由状态徽标首次显示时拉起，保持原样） */
    fun onImeCreate(context: Context) {
        val b = current(context)
        if (b.isAppService) AppAiConnection.bind(context, b)
    }

    fun onImeDestroy() = AppAiConnection.unbind()

    /**
     * 切换方案：记下选择，停掉旧方案，再起新方案。[onDone] 在主线程上收到给用户看的一句话。
     * 选的与当前相同时也重新拉起一遍（可当作“重启推理服务”用）。
     */
    fun switchTo(context: Context, target: AiBackend, onDone: (String) -> Unit) {
        val app = context.applicationContext
        val old = current(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, target.id).apply()
        AiEvents.append(app, "switch ${old.id} -> ${target.id}")
        deactivate(app, old) { stopped ->
            activate(app, target) { msg -> onDone(if (stopped.isEmpty()) msg else "$stopped\n$msg") }
        }
    }

    private fun deactivate(context: Context, b: AiBackend, onDone: (String) -> Unit) {
        // 应用内的推理服务无论如何都先解绑：进程里有模型，留着就占内存与端口
        AppAiConnection.unbind()
        if (b.isAppService) {
            AppAiConnection.killServiceProcess(context, "switch")
            main.postDelayed({ onDone("") }, 500)   // 等端口空出来
            return
        }
        // Shizuku 方案：能连上 Shizuku 就停掉 rimellmd；连不上时它若还在跑，新方案会等端口空出来
        if (AiDaemonLauncher.availability() == AiDaemonLauncher.Availability.READY) {
            AiDaemonLauncher.stop { r -> onDone(if (r is AiDaemonLauncher.Result.Failed) r.reason else "") }
        } else {
            Thread {
                val alive = PortProbe.alive()
                main.post { onDone(if (alive) "Shizuku 未就绪，旧的守护进程停不下来，新方案会等它退出（重启手机即可）" else "") }
            }.start()
        }
    }

    private fun activate(context: Context, b: AiBackend, onDone: (String) -> Unit) {
        if (b.isAppService) {
            AppAiConnection.bind(context, b)
            onDone("已切换到${b.title}")
            return
        }
        if (AiDaemonLauncher.availability() != AiDaemonLauncher.Availability.READY) {
            onDone("已切换到${b.title}，Shizuku 就绪后点按键盘上的 AI 徽标启动")
            return
        }
        AiDaemonLauncher.start(context) { r ->
            onDone(if (r is AiDaemonLauncher.Result.Failed) r.reason else "已切换到${b.title}")
        }
    }
}
