/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.util.appContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 下载到用户目录的文件：先写 .part，可断点续传，校验 sha256 后才改成正式文件名 */
class RemoteFile(val name: String, val url: String, val sha256: String, val size: Long)

/**
 * 不打进 APK 的可选资源。文件在就算启用：没有模型时 AI 不启动，没有语法模型时 Rime 不用它，
 * 所以取消下载或删除文件就等于关闭对应的功能。
 */
enum class SuzuAsset(val title: String, val files: List<RemoteFile>) {
    Model(
        "Suzu 模型",
        listOf(
            RemoteFile("suzu.gguf", "$MODEL_BASE/suzu-2.4.q8_0.gguf", "a2ff2c46110c7ba47746871eaf8ff37cc4fab58105ddbe11e0a768e340cdb996", 43_949_568),
            RemoteFile("suzu.vocab.txt", "$MODEL_BASE/suzu-2.4.q8_0.gguf.vocab.txt", "21f87a1ce1e99597342ed521787a1d7cac308edb6d2eddeb6c2db06c05668e5d", 30_005),
        ),
    ),
    Grammar(
        "语法模型",
        listOf(
            RemoteFile(
                "wanxiang-lts-zh-hans.gram",
                "https://github.com/amzxyz/rime-wanxiang-data/releases/download/LTS/wanxiang-lts-zh-hans.gram",
                "9eedac1d836f3fdafb61b162035c8937253aedc106be40b2b957d9ab64e9ed89",
                418_078_764,
            ),
        ),
    ),
    ;

    val sizeMb: Long get() = (files.sumOf { it.size } + 524288) / 1048576
}

private const val MIN_GRAMMAR_BYTES = 1_048_576L
private const val MODEL_BASE = "https://huggingface.co/IzumiThe3rd/Suzu-2.4/resolve/b166ed00f744a3a8f68f7b1b8980a76c80ac9f25"

object SuzuAssets {
    sealed interface State {
        data object Idle : State

        data class Downloading(val doneMb: Long, val totalMb: Long) : State {
            val percent: Int get() = if (totalMb > 0) (doneMb * 100 / totalMb).toInt().coerceIn(0, 100) else 0
        }

        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<SuzuAsset, Job>()
    private val _states = MutableStateFlow(SuzuAsset.entries.associateWith<SuzuAsset, State> { State.Idle })
    val states: StateFlow<Map<SuzuAsset, State>> = _states.asStateFlow()

    private val userDir get() = DataManager.userDataDir

    fun isInstalled(asset: SuzuAsset) = asset.files.all { userDir.resolve(it.name).isFile }

    fun state(asset: SuzuAsset): State = _states.value.getValue(asset)

    private fun set(asset: SuzuAsset, state: State) {
        _states.value = _states.value + (asset to state)
    }

    /** 在后台下载，离开页面也继续。重复调用不会重复下载 */
    @Synchronized
    fun start(asset: SuzuAsset) {
        if (jobs[asset]?.isActive == true) return
        jobs[asset] = scope.launch {
            val total = asset.files.sumOf { it.size }
            var finished = 0L
            set(asset, State.Downloading(0, total / 1048576))
            runCatching {
                userDir.mkdirs()
                for (file in asset.files) {
                    val target = userDir.resolve(file.name)
                    if (!target.isFile) {
                        download(file, target) { done ->
                            set(asset, State.Downloading((finished + done) / 1048576, total / 1048576))
                        }
                    }
                    finished += file.size
                }
                if (asset == SuzuAsset.Model) userDir.resolve("suzu.name").writeText("Suzu-2.4\n")
                requestDeploy()
            }.onSuccess {
                set(asset, State.Idle)
            }.onFailure {
                Timber.w(it, "Download of %s failed", asset)
                set(asset, if (it is kotlinx.coroutines.CancellationException) State.Idle else State.Failed(it.message ?: it.toString()))
            }
        }
    }

    /** 用用户自己下载的语法模型：检查扩展名和大小，不校验 sha256，因为可能是作者更新过的版本 */
    @Synchronized
    fun importGrammar(uri: Uri) {
        val asset = SuzuAsset.Grammar
        if (jobs[asset]?.isActive == true) return
        jobs[asset] = scope.launch {
            val resolver = appContext.contentResolver
            val target = userDir.resolve(asset.files.single().name)
            val part = File(target.parentFile, target.name + ".part")
            runCatching {
                val (name, size) = resolver.query(uri, null, null, null, null)?.use { c ->
                    check(c.moveToFirst()) { "读不到这个文件" }
                    c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to
                        c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
                } ?: error("读不到这个文件")
                check(name.endsWith(".gram")) { "请选择 .gram 文件" }
                check(size >= MIN_GRAMMAR_BYTES) { "文件太小，不像语法模型" }
                userDir.mkdirs()
                resolver.openInputStream(uri)!!.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var reported = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - reported >= 1048576) {
                                set(asset, State.Downloading(done / 1048576, size / 1048576))
                                reported = done
                            }
                        }
                    }
                }
                check(part.renameTo(target)) { "无法写入 ${target.name}" }
                requestDeploy()
            }.onSuccess {
                set(asset, State.Idle)
            }.onFailure {
                part.delete()
                set(asset, if (it is kotlinx.coroutines.CancellationException) State.Idle else State.Failed(it.message ?: it.toString()))
            }
        }
    }

    @Synchronized
    fun cancel(asset: SuzuAsset) {
        jobs[asset]?.cancel()
        set(asset, State.Idle)
    }

    /** 删除资源和没下完的部分，对应的功能随之关闭 */
    fun delete(asset: SuzuAsset) {
        cancel(asset)
        for (file in asset.files) {
            userDir.resolve(file.name).delete()
            userDir.resolve(file.name + ".part").delete()
        }
        if (asset == SuzuAsset.Model) userDir.resolve("suzu.name").delete()
        requestDeploy()
    }

    /** 输入法服务没在运行时广播会被忽略，下次启动时 Rime 也会读到新文件 */
    private fun requestDeploy() {
        appContext.sendBroadcast(Intent(RimeIntentReceiver.ACTION_DEPLOY).setPackage(appContext.packageName))
    }

    /** 下载到 [target]。sha256 对不上时删掉临时文件，下次从头下载 */
    suspend fun download(file: RemoteFile, target: File, progress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        var have = if (part.isFile) part.length() else 0L
        val conn = (URL(file.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            // 服务器不支持续传，或临时文件已经完整（416）时从头开始
            if (code == 416 || code == 200) have = 0
            check(code == 200 || code == 206 || code == 416) { "下载失败：HTTP $code" }
            if (code != 416) {
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = have
                        var reported = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - reported >= 131072) {
                                progress(done)
                                reported = done
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (sha256(part) != file.sha256) {
            part.delete()
            error("校验失败：${file.name} 不完整或来源已更新")
        }
        check(part.renameTo(target)) { "无法写入 ${file.name}" }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
