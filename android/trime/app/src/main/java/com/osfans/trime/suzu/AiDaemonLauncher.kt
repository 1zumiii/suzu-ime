/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.osfans.trime.data.base.DataManager
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 通过 Shizuku 在手机上直接拉起 AI 重排守护进程 rimellmd（不依赖电脑）。
 * 二进制随 APK 打包为 librimellmd.so；这里以 shell 身份把它复制到 /data/local/tmp/rimellm/ 再启动。
 * 模型只用 Suzu：Trime 用户目录里的 suzu.gguf 与 suzu.vocab.txt，每次启动复制到 /data/local/tmp/rimellm/；缺任一文件就不启动（不再回退 Qwen）。
 */
object AiDaemonLauncher {
    enum class Availability { SHIZUKU_NOT_RUNNING, NEEDS_PERMISSION, READY }

    sealed class Result {
        data object Started : Result()
        data object AlreadyRunning : Result()
        data class Failed(val reason: String) : Result()
    }

    private const val REQUEST_CODE = 7301
    private const val STUDENT_MODEL = "suzu.gguf"          // 放在 Trime 用户目录里即启用 Suzu 学生模型（需配套词表）
    private const val STUDENT_VOCAB = "suzu.vocab.txt"
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var lastAttemptMs = 0L
        private set

    @Volatile
    var lastResult: String = "尚未启动"
        private set

    private const val TAG = "SuzuAi"

    @Volatile
    private var checked = false

    fun availability(): Availability =
        try {
            when {
                !Shizuku.pingBinder() -> Availability.SHIZUKU_NOT_RUNNING
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Availability.READY
                else -> Availability.NEEDS_PERMISSION
            }
        } catch (e: Throwable) {
            Availability.SHIZUKU_NOT_RUNNING
        }

    /** 弹出 Shizuku 的授权请求；授权后回调 [onGranted]。 */
    fun requestPermission(onGranted: () -> Unit) {
        val listener =
            object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (grantResult == PackageManager.PERMISSION_GRANTED) main.post(onGranted)
                }
            }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    private fun shellScript(libDir: String, rimeDir: String, onlyIfRunning: Boolean = false): String =
        """
        D=/data/local/tmp/rimellm
        mkdir -p ${'$'}D || exit 11
        # --repack：CPU 权重重排。手机离线回放 5671 条选词，首位只变 3 条，打分快约 45%，常驻内存多约 41 MiB
        ARGS="--model ${'$'}D/suzu.gguf --student-vocab ${'$'}D/suzu.vocab.txt --n-seq-max 11 --gen-beam 10 --repack"
        TAIL="--rime-dir $rimeDir --log-dir ${'$'}D --port 17920 --threads 2 --idle-sec 0"
        ${if (onlyIfRunning) "pidof rimellmd >/dev/null 2>&1 || { echo NOT_RUNNING; exit 0; }" else ""}
        if pidof rimellmd >/dev/null 2>&1; then
          cmp -s "$libDir/librimellmd.so" ${'$'}D/rimellmd && cmp -s "$rimeDir/$STUDENT_MODEL" ${'$'}D/suzu.gguf &&
            cmp -s "$rimeDir/$STUDENT_VOCAB" ${'$'}D/suzu.vocab.txt &&
            [ "${'$'}(cat ${'$'}D/args 2>/dev/null)" = "${'$'}ARGS ${'$'}TAIL" ] && { echo RUNNING; exit 0; }
          # 程序、模型或启动参数与这次的不同（APK 升级、换了模型、改了参数，或早先用 adb 推的）：
          # 停掉所有 rimellmd（监督进程和工作进程，包括父进程已不在的），确认都退出了再换上新的
          kill ${'$'}(pidof rimellmd) 2>/dev/null
          i=0; while pidof rimellmd >/dev/null 2>&1 && [ ${'$'}i -lt 50 ]; do sleep 0.1; i=${'$'}((i+1)); done
          # 5 秒还没退出（卡死、不响应 SIGTERM）：强制结束
          pidof rimellmd >/dev/null 2>&1 && { kill -9 ${'$'}(pidof rimellmd) 2>/dev/null; sleep 0.5; }
          pidof rimellmd >/dev/null 2>&1 && { echo KILL_FAIL; exit 16; }
          echo REPLACED
        fi
        cp "$libDir/librimellmd.so" ${'$'}D/rimellmd.new && chmod 755 ${'$'}D/rimellmd.new && mv -f ${'$'}D/rimellmd.new ${'$'}D/rimellmd || { echo COPY_FAIL; exit 12; }
        [ -f "$rimeDir/$STUDENT_MODEL" ] && [ -f "$rimeDir/$STUDENT_VOCAB" ] || { echo NO_MODEL; exit 13; }
        cp "$rimeDir/$STUDENT_MODEL" ${'$'}D/suzu.gguf && cp "$rimeDir/$STUDENT_VOCAB" ${'$'}D/suzu.vocab.txt || { echo MODEL_COPY_FAIL; exit 14; }
        rm -f ${'$'}D/model.gguf                              # 旧版回退用的 Qwen 副本，不再使用
        cd ${'$'}D
        echo "${'$'}ARGS ${'$'}TAIL" > ${'$'}D/args
        ( setsid nohup ./rimellmd --supervise ${'$'}ARGS --rime-dir "$rimeDir" --log-dir ${'$'}D \
            --port 17920 --threads 2 --idle-sec 0 >> ${'$'}D/daemon.log 2>&1 < /dev/null & ) > /dev/null 2>&1 < /dev/null
        sleep 1
        pidof rimellmd >/dev/null 2>&1 && echo STARTED || { echo START_FAIL; exit 15; }
        """.trimIndent()

    /**
     * 删除模型后调用：经 Shizuku 停掉 AI 服务，删掉复制到 /data/local/tmp/rimellm 的程序、模型、词表和参数记录。
     * 只删这几个文件，同目录里的其他文件不动。Shizuku 不可用时什么都不做（下次启动时会按缺模型处理）
     */
    fun stopAndRemoveCopies(context: Context) {
        if (availability() != Availability.READY) return
        Thread {
            runCatching {
                val method =
                    Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                method.isAccessible = true
                val script =
                    """
                    D=/data/local/tmp/rimellm
                    kill ${'$'}(pidof rimellmd) 2>/dev/null
                    i=0; while pidof rimellmd >/dev/null 2>&1 && [ ${'$'}i -lt 50 ]; do sleep 0.1; i=${'$'}((i+1)); done
                    pidof rimellmd >/dev/null 2>&1 && kill -9 ${'$'}(pidof rimellmd) 2>/dev/null
                    rm -f ${'$'}D/rimellmd ${'$'}D/suzu.gguf ${'$'}D/suzu.vocab.txt ${'$'}D/args
                    """.trimIndent()
                val proc = method.invoke(null, arrayOf("sh", "-c", script), null, null) as Process
                proc.waitFor()
                Log.i(TAG, "已停止 AI 服务并删除模型副本")
            }.onFailure { Log.w(TAG, "删除 AI 服务的模型副本失败：${it.message}") }
        }.start()
    }

    /** 在后台线程里经 Shizuku 以 shell 身份启动守护进程，结果回到主线程。 */
    fun start(context: Context, onResult: (Result) -> Unit) = execute(context, false, onResult)

    /** 换模型后调用：AI 服务在跑就重启它以载入新模型，没在跑则什么都不做 */
    fun reloadIfRunning(context: Context) {
        if (availability() != Availability.READY) return
        execute(context, true) {}
    }

    private fun execute(context: Context, onlyIfRunning: Boolean, onResult: (Result) -> Unit) {
        lastAttemptMs = System.currentTimeMillis()
        Thread {
            var replaced = false
            var notRunning = false
            val result =
                try {
                    val libDir = context.applicationInfo.nativeLibraryDir
                    val rimeDir = DataManager.userDataDir.absolutePath
                    if (!File(libDir, "librimellmd.so").exists()) {
                        Result.Failed("安装包缺少 AI 服务组件，请重新安装完整版本")
                    } else {
                        val method =
                            Shizuku::class.java.getDeclaredMethod(
                                "newProcess",
                                Array<String>::class.java,
                                Array<String>::class.java,
                                String::class.java,
                            )
                        method.isAccessible = true
                        val proc =
                            method.invoke(null, arrayOf("sh", "-c", shellScript(libDir, rimeDir, onlyIfRunning)), null, null) as Process
                        val out = proc.inputStream.bufferedReader().readText().trim()
                        val err = proc.errorStream.bufferedReader().readText().trim()
                        val code = proc.waitFor()
                        replaced = out.contains("REPLACED")
                        notRunning = out.contains("NOT_RUNNING")
                        when {
                            notRunning -> Result.AlreadyRunning
                            out.contains("RUNNING") -> Result.AlreadyRunning
                            out.contains("STARTED") -> Result.Started
                            out.contains("KILL_FAIL") ->
                                Result.Failed("旧版 AI 服务停不下来，请重启手机后再试")
                            out.contains("NO_MODEL") ->
                                Result.Failed("模型文件不完整，请到“设置 → 模型”重新下载 Suzu 模型")
                            else -> Result.Failed("AI 服务启动失败（错误码 $code）：${(out + " " + err).trim().take(120)}")
                        }
                    }
                } catch (e: Throwable) {
                    Result.Failed("${e.javaClass.simpleName}: ${e.message?.take(100)}")
                }
            if (notRunning) return@Thread
            lastResult =
                when (result) {
                    Result.Started -> if (replaced) "已更新并重新启动" else "已启动"
                    Result.AlreadyRunning -> "已在运行"
                    is Result.Failed -> "启动失败：${result.reason}"
                }
            if (result is Result.Failed) Log.w(TAG, lastResult) else Log.i(TAG, lastResult)
            main.post { onResult(result) }
        }.start()
    }

    /**
     * 本进程第一次显示键盘时要静默启动一次（返回 true 仅一次）：守护进程是旧版本就换掉，没在跑就拉起，
     * 已是安装包里的版本则什么都不做。APK 升级后输入法进程会重启，正好走到这里。
     */
    fun takeFirstCheck(): Boolean {
        if (checked || availability() != Availability.READY) return false
        checked = true
        Log.i(TAG, "首次检查守护进程版本")
        return true
    }

    /** 记一条日志（logcat 标签 SuzuAi），给启动器以外的调用方用，例如 Shizuku 未就绪时的点击 */
    fun log(msg: String) = Log.w(TAG, msg)

    fun info(msg: String) = Log.i(TAG, msg)
}
