/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import com.osfans.trime.core.RimeApi
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * 下载、安装、切换词库包。在线与内置名录由 DictCatalog 管理。
 * 每个包一个内容确定的 zip，带 sha256。下载到临时文件，校验通过才解压；切换失败回到原来的包。
 */
object PackInstaller {
    data class Entry(
        val name: String,
        val title: String,
        val license: String,
        val commit: String,
        val url: String,
        val sha256: String,
        val size: Long,
        val schemaLabel: String,
        val source: String,
        /** 基准尺度（白霜）权重到本包权重的换算节点；为 null 表示与基准相同 */
        val weightMap: List<Pair<Double, Double>>? = null,
    )

    val catalog: List<Entry> get() = DictCatalog.current.packs

    fun title(pack: String) = DictCatalog.pack(pack)?.title ?: DictPacks.info(pack)["title"] ?: pack

    private val userDir get() = DataManager.userDataDir

    /** 下载并安装到用户目录；已装好的同名包会被替换。在 IO 线程调用 */
    suspend fun download(entry: Entry, progress: (done: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
        check(DictCatalog.validId(entry.name))
        val zip = File(appContext.cacheDir, "pack-${entry.name}-${entry.sha256.take(12)}.zip")
        try {
            SuzuAssets.download(RemoteFile(zip.name, entry.url, entry.sha256, entry.size), zip, dictionary = true) {
                progress(it, entry.size)
            }
            install(entry, zip)
        } finally {
            zip.delete()
        }
    }

    /** 解压到临时目录，确认是这个包的目录后再换进用户目录 */
    private fun install(entry: Entry, zip: File) {
        val pack = entry.name
        val prefix = DictPacks.dirName(pack)
        val tmp = userDir.resolve(".pack-$pack.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            ZipFile(zip).use { zf ->
                for (e in zf.entries()) {
                    val name = e.name
                    check(name.startsWith("$prefix/") || name.startsWith("${prefix}_cell/")) { "词库包内容不符，无法安装" }
                    check(!name.contains("..")) { "词库包包含不安全的文件路径，无法安装" }
                    if (e.isDirectory) continue
                    val out = tmp.resolve(name)
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
            }
            check(tmp.resolve(prefix).resolve("pack.main.yaml").isFile) { "词库包缺少必要文件，请重新下载" }
            val lock = tmp.resolve(prefix).resolve("pack.lock")
            val metadata = if (lock.isFile) lock.readLines() else listOf("pack\t$pack", "commit\t${entry.commit}")
            lock.writeText((metadata.filterNot { it.startsWith("title\t") || it.startsWith("schema_label\t") } +
                listOf("title\t${entry.title}", "schema_label\t${entry.schemaLabel}")).joinToString("\n", postfix = "\n"))
            for (dir in tmp.listFiles().orEmpty()) {
                val target = userDir.resolve(dir.name)
                target.deleteRecursively()
                check(dir.renameTo(target)) { "无法保存词库，请检查存储空间后重试" }
            }
        } finally {
            tmp.deleteRecursively()
        }
        Timber.i("Dict pack installed: %s", pack)
    }

    /**
     * 切换到 [pack]：没装就先下载，启用后重新部署（编译词库）。部署失败就启用原来的包再部署一次，并抛出原因。
     * 旧包的文件不删，下次切回来不用再下载。
     */
    suspend fun switchTo(rime: RimeApi, pack: String, stage: (String, Int?) -> Unit) {
        if (pack !in DictPacks.installed()) {
            val entry = catalog.firstOrNull { it.name == pack } ?: error("找不到词库包 $pack 的下载信息")
            stage("正在下载 ${entry.title}", 0)
            download(entry) { done, total ->
                stage("正在下载 ${entry.title}：${done / 1048576} / ${total / 1048576} MB", (done * 100 / total).toInt().coerceIn(0, 100))
            }
        }
        val previous = DictPacks.active()
        withContext(Dispatchers.IO) { DictPacks.activate(pack) }
        stage("正在编译词库，请稍候", null)
        try {
            rime.deploy()
        } catch (e: Exception) {
            Timber.w(e, "Deploy failed after switching to %s", pack)
            if (previous != null && previous != pack) {
                stage("编译失败，正在恢复 ${title(previous)}", null)
                withContext(Dispatchers.IO) { DictPacks.activate(previous) }
                runCatching { rime.deploy() }
            }
            throw IllegalStateException("词库编译失败，请重新选择词库后重试", e)
        }
    }
}
