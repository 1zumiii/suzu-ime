/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Context
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
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 下载到用户目录的文件：先写 .part，可断点续传，校验 sha256 后才改成正式文件名 */
/**
 * 不打进 APK 的可选资源。文件在就算启用：没有模型时 AI 不启动，没有语法模型时 Rime 不用它，
 * 所以取消下载或删除文件就等于关闭对应的功能。
 */
enum class SuzuAsset(val title: String, val fileNames: List<String>, val sizeMb: Long) {
    Model("Suzu 模型", SuzuModels.BUILTIN.first().files.map { it.name }, SuzuModels.BUILTIN.first().sizeMb),
    Grammar("语法模型", listOf(GRAMMAR_FILE), 400),
}

private const val MIN_GRAMMAR_BYTES = 1_048_576L
private const val GRAMMAR_FILE = "wanxiang-lts-zh-hans.gram"
private const val GRAMMAR_INFO = "wanxiang.grammar.json"
private const val GRAMMAR_PART_DIGEST = "$GRAMMAR_FILE.part.sha256"
private const val GRAMMAR_REPO = "amzxyz/rime-wanxiang-data"
private const val GRAMMAR_RELEASE_API = "https://api.github.com/repos/$GRAMMAR_REPO/releases/tags/LTS"
private const val GRAMMAR_DOWNLOAD = "https://github.com/$GRAMMAR_REPO/releases/download/LTS/$GRAMMAR_FILE"
private val SHA256_HEX = Regex("[0-9a-f]{64}")
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

    fun isInstalled(asset: SuzuAsset) = asset.fileNames.all { userDir.resolve(it).isFile }

    fun installedGrammarDescription(context: Context): String {
        val target = userDir.resolve(GRAMMAR_FILE)
        val info = runCatching { JSONObject(userDir.resolve(GRAMMAR_INFO).readText()) }.getOrNull()
        if (info == null || info.optLong("size") != target.length() || info.optLong("modified") != target.lastModified()) {
            return GRAMMAR_FILE
        }
        val name = info.optString("name").ifBlank { context.getString(R.string.suzu_grammar_lts_name) }
        val date = info.optString("date")
        val fingerprint = info.optString("sha256").takeIf(SHA256_HEX::matches)?.let { "SHA-256 ${it.take(8)}" }.orEmpty()
        return listOf(name, date, fingerprint).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun state(asset: SuzuAsset): State = _states.value.getValue(asset)

    private fun set(asset: SuzuAsset, state: State) {
        _states.value = _states.value + (asset to state)
    }

    /** 在后台下载，离开页面也继续。重复调用不会重复下载 */
    @Synchronized
    fun start(asset: SuzuAsset, model: SuzuModel = SuzuModels.default) {
        if (jobs[asset]?.isCompleted == false) return
        // 模型已装时只有换成另一个模型才下载
        if (isInstalled(asset) && (asset != SuzuAsset.Model || installedModelTitle() == model.title)) return
        val selectedModel = model.takeIf { asset == SuzuAsset.Model }
        val estimatedTotal = selectedModel?.files?.sumOf { it.size } ?: asset.sizeMb * 1_048_576
        set(asset, State.Downloading(0, estimatedTotal, selectedModel))
        jobs[asset] = scope.launch {
            runCatching {
                if (selectedModel == null) downloadGrammar() else downloadModel(selectedModel, estimatedTotal)
                requestDeploy()
            }.onSuccess {
                set(asset, State.Idle)
            }.onFailure {
                Timber.w(it, "Download of %s failed", asset)
                set(asset, if (it is kotlinx.coroutines.CancellationException) State.Idle else State.Failed(it.message ?: it.toString()))
            }
        }
    }

    private suspend fun downloadModel(model: SuzuModel, total: Long) {
        val dir = userDir.resolve(MODEL_DOWNLOADS).resolve(model.id)
        dir.mkdirs()
        var finished = 0L
        for (file in model.files) {
            coroutineContext.ensureActive()
            val target = dir.resolve(file.name)
            if (!target.isFile || sha256(target) != file.sha256) {
                download(file, target) { done ->
                    set(SuzuAsset.Model, State.Downloading(finished + done, total, model))
                }
            }
            finished += file.size
        }
        coroutineContext.ensureActive()
        installModel(model, dir)
    }

    private suspend fun downloadGrammar() {
        val conn = (URL(GRAMMAR_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Suzu-IME")
        }
        val release = try {
            val code = conn.responseCode
            check(code == HttpURLConnection.HTTP_OK) { appContext.getString(R.string.suzu_download_http_error, code) }
            conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            conn.disconnect()
        }
        val assets = release.optJSONArray("assets")
        val asset = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
            .firstOrNull { it.optString("name") == GRAMMAR_FILE && it.optString("browser_download_url") == GRAMMAR_DOWNLOAD }
        val checksum = asset?.optString("digest").orEmpty()
        val digest = checksum.removePrefix("sha256:")
        val size = asset?.optLong("size") ?: 0
        check(checksum.startsWith("sha256:") && SHA256_HEX.matches(digest) && size >= MIN_GRAMMAR_BYTES) {
            appContext.getString(R.string.suzu_grammar_metadata_unavailable)
        }
        coroutineContext.ensureActive()
        userDir.mkdirs()
        val partDigest = userDir.resolve(GRAMMAR_PART_DIGEST)
        if (!partDigest.isFile || partDigest.readText() != digest) {
            val part = userDir.resolve("$GRAMMAR_FILE.part")
            check(!part.exists() || part.delete()) { appContext.getString(R.string.suzu_file_save_failed) }
            partDigest.writeText(digest)
        }
        val target = userDir.resolve(GRAMMAR_FILE)
        set(SuzuAsset.Grammar, State.Downloading(0, size))
        download(RemoteFile(GRAMMAR_FILE, GRAMMAR_DOWNLOAD, digest, size), target) { done ->
            set(SuzuAsset.Grammar, State.Downloading(done, size))
        }
        writeGrammarInfo(target, "", asset?.optString("updated_at").orEmpty().take(10), digest)
        partDigest.delete()
    }

    private fun writeGrammarInfo(target: File, name: String, date: String, digest: String) {
        runCatching {
            userDir.resolve(GRAMMAR_INFO).writeText(
                JSONObject()
                    .put("name", name)
                    .put("date", date)
                    .put("sha256", digest)
                    .put("size", target.length())
                    .put("modified", target.lastModified())
                    .toString(),
            )
        }.onFailure { Timber.w(it, "Failed to save grammar model information") }
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
        // 换好模型后，其他模型没下完的部分用不上了，一并删掉
        userDir.resolve(MODEL_DOWNLOADS).listFiles()?.forEach { it.deleteRecursively() }
        // AI 服务正在跑时重启它，换上新模型
        AiDaemonLauncher.reloadIfRunning(appContext)
    }

    /** 当前装的模型名（suzu.name 第一行），没有模型时为 null */
    fun installedModelTitle(): String? =
        if (!isInstalled(SuzuAsset.Model)) {
            null
        } else {
            runCatching { userDir.resolve("suzu.name").readLines().firstOrNull()?.trim() }.getOrNull()?.ifBlank { null }
        }

    /** 用用户自己下载的语法模型：检查扩展名和大小，不校验 sha256，因为可能是作者更新过的版本 */
    @Synchronized
    fun importGrammar(uri: Uri) {
        val asset = SuzuAsset.Grammar
        if (jobs[asset]?.isCompleted == false) return
        jobs[asset] = scope.launch {
            val resolver = appContext.contentResolver
            val target = userDir.resolve(GRAMMAR_FILE)
            val part = userDir.resolve("$GRAMMAR_FILE.import")
            runCatching {
                val (name, size) = resolver.query(uri, null, null, null, null)?.use { c ->
                    check(c.moveToFirst()) { appContext.getString(R.string.suzu_file_unreadable) }
                    c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to
                        c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
                } ?: error(appContext.getString(R.string.suzu_file_unreadable))
                check(name.endsWith(".gram")) { appContext.getString(R.string.suzu_grammar_file_required) }
                check(size >= MIN_GRAMMAR_BYTES) { appContext.getString(R.string.suzu_grammar_file_too_small) }
                userDir.mkdirs()
                set(asset, State.Downloading(0, size))
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
                check(part.length() >= MIN_GRAMMAR_BYTES) { appContext.getString(R.string.suzu_grammar_file_too_small) }
                val digest = sha256(part)
                coroutineContext.ensureActive()
                check(part.renameTo(target)) { appContext.getString(R.string.suzu_file_save_failed) }
                writeGrammarInfo(target, name, "", digest)
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

    /** 没下完的文件（中途取消或失败后留下，下次下载同一项时续传）。正在下载的不算 */
    class Leftover(val title: String, val bytes: Long, internal val files: List<File>)

    fun leftovers(): List<Leftover> {
        val out = mutableListOf<Leftover>()
        val downloadingModel = (state(SuzuAsset.Model) as? State.Downloading)?.model?.id
        userDir.resolve(MODEL_DOWNLOADS).listFiles()?.filter { it.isDirectory && it.name != downloadingModel }?.forEach { dir ->
            val bytes = dir.walk().filter { it.isFile }.sumOf { it.length() }
            if (bytes > 0) out += Leftover(SuzuModels.byId(dir.name)?.title ?: dir.name, bytes, listOf(dir))
        }
        if (state(SuzuAsset.Grammar) !is State.Downloading) {
            val parts = listOf(userDir.resolve("$GRAMMAR_FILE.part"), userDir.resolve("$GRAMMAR_FILE.import"), userDir.resolve(GRAMMAR_PART_DIGEST))
                .filter { it.isFile }
            val bytes = parts.sumOf { it.length() }
            if (bytes > 0) out += Leftover("万象语法模型", bytes, parts)
        }
        return out
    }

    fun deleteLeftover(l: Leftover) = l.files.forEach { it.deleteRecursively() }

    /** 删除资源和没下完的部分，对应的功能随之关闭 */
    fun delete(asset: SuzuAsset) {
        cancel(asset)
        for (name in asset.fileNames) {
            userDir.resolve(name).delete()
            userDir.resolve(name + ".part").delete()
        }
        if (asset == SuzuAsset.Model) {
            userDir.resolve("suzu.name").delete()
            userDir.resolve(MODEL_PATCH).delete()
            userDir.resolve(MODEL_DOWNLOADS).deleteRecursively()
            // AI 服务目录里也有一份模型副本：停掉 AI 服务并删掉它，不然手机里还留着约 50～100 MB
            AiDaemonLauncher.stopAndRemoveCopies(appContext)
        } else {
            userDir.resolve(GRAMMAR_INFO).delete()
            userDir.resolve(GRAMMAR_PART_DIGEST).delete()
            userDir.resolve("$GRAMMAR_FILE.import").delete()
        }
        requestDeploy()
    }

    /** 输入法服务没在运行时广播会被忽略，下次启动时 Rime 也会读到新文件 */
    private fun requestDeploy() {
        appContext.sendBroadcast(Intent(RimeIntentReceiver.ACTION_DEPLOY).setPackage(appContext.packageName))
    }

    /** 下载到 [target]。sha256 对不上时删掉临时文件，下次从头下载 */
    suspend fun download(file: RemoteFile, target: File, dictionary: Boolean = false, progress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        var have = if (part.isFile) part.length() else 0L
        val conn = if (dictionary) DictCatalog.openDownload(file.url, have) else (URL(file.url).openConnection() as HttpURLConnection).apply {
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
                            if (dictionary && done + n > file.size) error("词库文件大小与名录不符")
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
        if ((dictionary && part.length() != file.size) || sha256(part) != file.sha256) {
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
