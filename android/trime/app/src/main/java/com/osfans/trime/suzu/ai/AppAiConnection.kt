/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import com.osfans.trime.data.base.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 绑定和生命周期回调均在主线程处理。每次 Binder 连接独占一次探测任务。 */
object AppAiConnection {
    private const val BEAT_MS = 3000L
    private const val GRACE_MS = 10_000L
    private val scope = MainScope()
    private val clientLoaded by lazy { runCatching { System.loadLibrary("rime_llm") }.isSuccess }
    private var binding: Binding? = null
    var readyPid = 0
        private set
    var problem: String? = null
        private set

    @JvmStatic
    private external fun nativeTimeoutStreak(token: String): Int

    fun isBound() = binding != null

    fun bind(context: Context, backend: AiBackend): Boolean {
        if (binding != null) return true
        val app = context.applicationContext
        val next = Binding(app, backend)
        val intent = Intent(app, AppAiService::class.java)
            .putExtra(AppAiService.EXTRA_RIME_DIR, DataManager.userDataDir.absolutePath)
            .putExtra(AppAiService.EXTRA_BOOSTED, backend == AiBackend.APP_SERVICE_BOOSTED)
        val flags = Context.BIND_AUTO_CREATE or
            (if (backend == AiBackend.APP_SERVICE_BOOSTED) Context.BIND_IMPORTANT else 0)
        problem = null
        binding = next
        val ok = runCatching { app.bindService(intent, next, flags) }.getOrDefault(false)
        if (!ok) {
            unbind()
            problem = "无法绑定应用内推理服务，请点按重试"
        }
        AiEvents.append(app, "bind backend=${backend.id} ok=$ok")
        return ok
    }

    fun unbind() {
        val old = binding ?: return
        binding = null
        old.disconnect()
        runCatching { old.app.unbindService(old) }
        AiEvents.append(old.app, "unbind")
    }

    private class Binding(val app: Context, val backend: AiBackend) : ServiceConnection {
        private var binder: IBinder? = null
        private var watch: Job? = null

        fun disconnect() {
            watch?.cancel()
            watch = null
            binder = null
            readyPid = 0
            problem = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (binding !== this) return
            disconnect()
            binder = service
            AiEvents.append(app, "connected")
            val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
                if (binding === this && binder === service) {
                    if ((msg.arg2 == 1) != (backend == AiBackend.APP_SERVICE_BOOSTED)) {
                        problem = "进程仍在使用上一次的优先级，请重新切换"
                        return@Handler true
                    }
                    when (msg.what) {
                        AppAiService.SERVING -> if (watch == null) monitor(service, msg.arg1)
                        AppAiService.FAILED -> {
                            watch?.cancel()
                            readyPid = 0
                            problem = msg.data.getString("error")
                        }
                    }
                }
                true
            })
            val subscribe = Message.obtain(null, AppAiService.SUBSCRIBE).apply { replyTo = replies }
            runCatching { Messenger(service).send(subscribe) }
                .onFailure { problem = "无法连接应用内推理服务，请点按重试" }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (binding !== this) return
            disconnect()
            AiEvents.append(app, "disconnected (process died)")
        }

        override fun onBindingDied(name: ComponentName) {
            if (binding !== this) return
            unbind()
            bind(app, backend)
        }

        override fun onNullBinding(name: ComponentName) {
            if (binding !== this) return
            unbind()
            problem = "应用内推理服务拒绝绑定，请点按重试"
        }

        private fun monitor(service: IBinder, pid: Int) {
            val graceUntil = SystemClock.uptimeMillis() + GRACE_MS
            watch = scope.launch {
                var failures = 0
                while (binding === this@Binding && binder === service) {
                    val (owner, timeouts) = withContext(Dispatchers.IO) {
                        val peer = PortProbe.owner(DataManager.userDataDir)
                        val streak = if (peer?.host == "app" && peer.pid == pid && clientLoaded)
                            nativeTimeoutStreak(peer.token) else 0
                        peer to streak
                    }
                    if (binding !== this@Binding || binder !== service || !service.isBinderAlive) return@launch
                    val ours = owner?.host == "app" && owner.pid == pid
                    readyPid = if (ours) pid else 0
                    problem = if (owner != null && !ours) "端口仍由其他推理进程占用，请重新切换" else null
                    if (SystemClock.uptimeMillis() >= graceUntil) {
                        failures = if (owner == null) failures + 1 else 0
                        if (failures >= 3 || timeouts >= 3) {
                            AiEvents.append(app, "watchdog kill pid=$pid probes=$failures timeouts=$timeouts")
                            Process.killProcess(pid)
                            return@launch
                        }
                    }
                    delay(BEAT_MS)
                }
            }
        }
    }
}
