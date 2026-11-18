/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu.ai

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * 应用自己的推理服务：在独立进程 :suzu_ai 里跑与 rimellmd 相同的工作进程（librimellm_service.so，core/daemon/embedded.h）。
 *
 * - 输入法进程以 BIND_AUTO_CREATE 绑定它。进程被系统回收或被看门狗杀掉后，只要绑定还在，Android 会重新创建服务，
 *   不需要 Shizuku，也不需要监督进程。不在应用里 fork 子进程：Android 12 起幽灵进程会被系统清掉。
 * - 线路协议、令牌与状态文件和 rimellmd 相同，客户端库不区分两种方案。
 * - 本进程不跑 TrimeApplication 的初始化（见 TrimeApplication.onCreate），所以路径都由绑定的 Intent 带过来。
 * - 所有绑定都解除时结束整个进程：推理线程停不下来，留着就一直占着模型内存和端口。
 */
class AppAiService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent): IBinder {
        startOnce(intent)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean = false

    override fun onDestroy() {
        AiEvents.append(this, "service destroyed (unbound), exiting process")
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun startOnce(intent: Intent) {
        synchronized(lock) {
            if (started) return
            started = true
        }
        val rimeDir = intent.getStringExtra(EXTRA_RIME_DIR)
        val boosted = intent.getBooleanExtra(EXTRA_BOOSTED, false)
        val ctx = applicationContext
        Thread({
            if (boosted) Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)   // 推理线程都由这条线程派生，继承优先级
            val restarts = bumpRestarts(ctx)
            val lastExit = lastExitReason(ctx)
            AiEvents.append(ctx, "service start pid=${Process.myPid()} restarts=$restarts boosted=$boosted last_exit=$lastExit")
            val args = buildArgs(ctx, rimeDir, restarts, lastExit)
            if (args == null) {
                AiEvents.append(ctx, "service: model files missing, not starting")
                return@Thread
            }
            val code =
                try {
                    System.loadLibrary("rimellm_service")
                    nativeRun(args)
                } catch (e: Throwable) {
                    AiEvents.append(ctx, "service: native failed ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                    -1
                }
            // 正常情况下 nativeRun 不返回。返回了说明启动失败：结束进程，由 Android 按绑定重建（系统自带退避）
            AiEvents.append(ctx, "service: native exited with $code")
            SystemClock.sleep(1000)
            Process.killProcess(Process.myPid())
        }, "suzu-ai-worker").start()
    }

    /** 与 AiDaemonLauncher 的启动脚本相同的参数，只是路径换成本应用自己的目录。模型缺任一文件就返回 null */
    private fun buildArgs(ctx: Context, rimeDir: String?, restarts: Int, lastExit: String): Array<String>? {
        if (rimeDir.isNullOrEmpty()) return null
        val src = File(rimeDir)
        val dst = File(ctx.noBackupFilesDir, "rimellm").also { it.mkdirs() }
        // 模型复制到内部存储：/sdcard 是 FUSE，mmap 读模型比内部存储慢（Shizuku 方案同样复制到 /data/local/tmp）
        val model = syncCopy(File(src, MODEL), File(dst, MODEL)) ?: return null
        val vocab = syncCopy(File(src, VOCAB), File(dst, VOCAB)) ?: return null
        return arrayOf(
            "--model", model.path, "--student-vocab", vocab.path,
            "--n-seq-max", "10", "--gen-beam", "10", "--repack",
            "--rime-dir", rimeDir, "--log-dir", AiEvents.dir(ctx).path,
            "--port", PortProbe.PORT.toString(), "--threads", "2", "--idle-sec", "0",
            "--restarts", restarts.toString(), "--last-exit", lastExit,
        )
    }

    private fun syncCopy(from: File, to: File): File? {
        if (!from.isFile) return null
        if (to.isFile && to.length() == from.length() && to.lastModified() == from.lastModified()) return to
        val tmp = File(to.path + ".tmp")
        return try {
            from.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            if (!tmp.renameTo(to)) return null
            to.setLastModified(from.lastModified())
            to
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    /** 本次开机以来推理服务进程启动的次数减一（第一次启动为 0），与 rimellmd 的 restarts 口径相同 */
    private fun bumpRestarts(ctx: Context): Int {
        val f = File(ctx.noBackupFilesDir, "rimellm/restarts.txt")
        val boot = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60000   // 开机时刻（分钟），换了就是重启过手机
        val parts = runCatching { f.readText().trim().split(' ') }.getOrNull()
        val n = if (parts?.size == 2 && parts[0].toLongOrNull() == boot) (parts[1].toIntOrNull() ?: -1) + 1 else 0
        runCatching { f.writeText("$boot $n") }
        return n
    }

    /** 本进程上一次退出的原因（Android 11 起有 ApplicationExitInfo），如 LOW_MEMORY、SIGNALED(9)、FREEZER */
    private fun lastExitReason(ctx: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "unknown"
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val me = "${ctx.packageName}$PROCESS_SUFFIX"
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 16).firstOrNull { it.processName == me }
                ?: return "none"
            val ageS = (System.currentTimeMillis() - info.timestamp) / 1000
            "${reasonName(info.reason)}(${info.status}) ${info.description?.take(60) ?: ""} ${ageS}s ago".trim()
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun reasonName(r: Int): String =
        when (r) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            else -> "REASON_$r"
        }

    companion object {
        const val PROCESS_SUFFIX = ":suzu_ai"
        const val EXTRA_RIME_DIR = "rime_dir"
        const val EXTRA_BOOSTED = "boosted"
        private const val MODEL = "suzu.gguf"
        private const val VOCAB = "suzu.vocab.txt"
        private val lock = Any()

        @Volatile
        private var started = false

        @JvmStatic
        private external fun nativeRun(args: Array<String>): Int
    }
}
