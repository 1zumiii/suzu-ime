/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.osfans.trime.R
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

enum class SuzuModel(
    val title: String,
    revision: String,
    filename: String,
    weightsSha: String,
    weightsSize: Long,
    vocabSha: String,
    vocabSize: Long,
    val topGate: String,
) {
    KD500(
        "Suzu-UER6-KD500", "4a8fd6831e482569fd4503cc19ce3022e75cc027",
        "suzu-uer6-kd500.q8_0.gguf",
        "953fa76eca9d1b171cf9e819e9f0474f12c309be1bc7c1aad46db2916d0b3859", 52_811_488,
        "16a9b481d23159418c6455d76f36569a9d97d6ce1d5303f5431ce0c621e4fc7c", 20_573, "1.5",
    ),
    Suzu24(
        "Suzu-2.4", "b166ed00f744a3a8f68f7b1b8980a76c80ac9f25",
        "suzu-2.4.q8_0.gguf",
        "a2ff2c46110c7ba47746871eaf8ff37cc4fab58105ddbe11e0a768e340cdb996", 43_949_568,
        "21f87a1ce1e99597342ed521787a1d7cac308edb6d2eddeb6c2db06c05668e5d", 30_005, "2.5",
    ),
    ;

    private val url = "https://huggingface.co/IzumiThe3rd/$title/resolve/$revision/$filename"
    val files = listOf(
        RemoteFile("suzu.gguf", url, weightsSha, weightsSize),
        RemoteFile("suzu.vocab.txt", "$url.vocab.txt", vocabSha, vocabSize),
    )
    val sizeMb: Long get() = (files.sumOf { it.size } + 500_000) / 1_000_000
}

/**
 * 不打进 APK 的可选资源。文件在就算启用：没有模型时 AI 不启动，没有语法模型时 Rime 不用它，
 * 所以取消下载或删除文件就等于关闭对应的功能。
 */
enum class SuzuAsset(val title: String, val files: List<RemoteFile>) {
    Model("Suzu 模型", SuzuModel.KD500.files),
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

    val sizeMb: Long
        get() = if (this == Model) SuzuModel.KD500.sizeMb else (files.sumOf { it.size } + 524288) / 1048576
}

private const val MIN_GRAMMAR_BYTES = 1_048_576L
private const val MODEL_PATCH = "suzu_model.yaml"
private const val MODEL_DOWNLOADS = ".suzu-model-downloads"

object SuzuAssets {
    sealed interface State {
        data object Idle : State

        data class Downloading(val doneBytes: Long, val totalBytes: Long, val model: SuzuModel? = null) : State {
            private val unit = if (model == null) 1_048_576L else 1_000_000L
            val doneMb: Long get() = doneBytes / unit
            val totalMb: Long get() = (totalBytes + unit / 2) / unit
            val percent: Int get() = if (totalBytes > 0) (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else 0
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
    fun start(asset: SuzuAsset, model: SuzuModel = SuzuModel.KD500) {
        if (jobs[asset]?.isCompleted == false || isInstalled(asset)) return
        val selectedModel = model.takeIf { asset == SuzuAsset.Model }
        val files = selectedModel?.files ?: asset.files
        val total = files.sumOf { it.size }
        set(asset, State.Downloading(0, total, selectedModel))
        jobs[asset] = scope.launch {
            var finished = 0L
            runCatching {
                val dir = selectedModel?.let { userDir.resolve(MODEL_DOWNLOADS).resolve(it.name) } ?: userDir
                dir.mkdirs()
                for (file in files) {
                    coroutineContext.ensureActive()
                    val target = dir.resolve(file.name)
                    if (!target.isFile || selectedModel != null && sha256(target) != file.sha256) {
                        download(file, target) { done ->
                            set(asset, State.Downloading(finished + done, total, selectedModel))
                        }
                    }
                    finished += file.size
                }
                coroutineContext.ensureActive()
                if (selectedModel != null) installModel(selectedModel, dir)
                requestDeploy()
            }.onSuccess {
                set(asset, State.Idle)
            }.onFailure {
                Timber.w(it, "Download of %s failed", asset)
                set(asset, if (it is kotlinx.coroutines.CancellationException) State.Idle else State.Failed(it.message ?: it.toString()))
            }
        }
    }

    private fun installModel(model: SuzuModel, staging: File) {
        staging.resolve("suzu.name").writeText("${model.title}\n")
        staging.resolve(MODEL_PATCH).writeText("patch:\n  llm_rerank/unified/top_gate: ${model.topGate}\n")
        val names = model.files.map { it.name } + listOf("suzu.name", MODEL_PATCH)
        val installed = mutableListOf<String>()
        val backups = staging.resolve("backup")
        backups.mkdirs()
        try {
            for (name in names) {
                val target = userDir.resolve(name)
                if (target.exists()) target.copyTo(backups.resolve(name), overwrite = true)
                check(staging.resolve(name).renameTo(target)) { appContext.getString(R.string.suzu_file_save_failed) }
                installed.add(name)
            }
        } catch (e: Exception) {
            for (name in installed.asReversed()) {
                val backup = backups.resolve(name)
                val target = userDir.resolve(name)
                if (backup.exists()) backup.renameTo(target) else target.delete()
            }
            throw e
        }
        staging.deleteRecursively()
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
                    check(c.moveToFirst()) { appContext.getString(R.string.suzu_file_unreadable) }
                    c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to
                        c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
                } ?: error(appContext.getString(R.string.suzu_file_unreadable))
                check(name.endsWith(".gram")) { appContext.getString(R.string.suzu_grammar_file_required) }
                check(size >= MIN_GRAMMAR_BYTES) { appContext.getString(R.string.suzu_grammar_file_too_small) }
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
                            set(asset, State.Downloading(done, size))
                                reported = done
                            }
                        }
                    }
                }
                check(part.renameTo(target)) { appContext.getString(R.string.suzu_file_save_failed) }
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
        if (asset == SuzuAsset.Model) {
            userDir.resolve("suzu.name").delete()
            userDir.resolve(MODEL_PATCH).delete()
            userDir.resolve(MODEL_DOWNLOADS).deleteRecursively()
        }
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
            check(code == 200 || code == 206 || code == 416) { appContext.getString(R.string.suzu_download_http_error, code) }
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
            error(appContext.getString(R.string.suzu_file_checksum_failed))
        }
        check(part.renameTo(target)) { appContext.getString(R.string.suzu_file_save_failed) }
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
