/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import com.osfans.trime.core.RimeApi
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * 下载、安装、切换词库包。清单 suzu_packs.tsv 由构建脚本按 rime-suzu 的 packs.tsv 生成，打在 APK 里：
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
    )

    val catalog: List<Entry> by lazy {
        runCatching {
            appContext.assets.open("suzu_packs.tsv").bufferedReader().readLines()
                .drop(1)
                .mapNotNull { line ->
                    val f = line.split('\t')
                    if (f.size < 7) null else Entry(f[0], f[1], f[2], f[3], f[4], f[5], f[6].toLongOrNull() ?: 0)
                }
        }.getOrDefault(emptyList())
    }

    fun title(pack: String) = catalog.firstOrNull { it.name == pack }?.title ?: pack

    private val userDir get() = DataManager.userDataDir

    /** 下载并安装到用户目录；已装好的同名包会被替换。在 IO 线程调用 */
    suspend fun download(entry: Entry, progress: (done: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
        val zip = File(appContext.cacheDir, "pack-${entry.name}.zip")
        try {
            fetch(entry, zip, progress)
            val digest = sha256(zip)
            check(digest == entry.sha256) { "校验失败：下载的文件不完整或被改动" }
            install(entry.name, zip)
        } finally {
            zip.delete()
        }
    }

    private suspend fun fetch(entry: Entry, dst: File, progress: (Long, Long) -> Unit) {
        val conn = (URL(entry.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            check(conn.responseCode == 200) { "下载失败：HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: entry.size
            conn.inputStream.use { input ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
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

    /** 解压到临时目录，确认是这个包的目录后再换进用户目录 */
    private fun install(pack: String, zip: File) {
        val prefix = DictPacks.dirName(pack)
        val tmp = userDir.resolve(".pack-$pack.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            ZipFile(zip).use { zf ->
                for (e in zf.entries()) {
                    val name = e.name
                    check(name.startsWith("$prefix/") || name.startsWith("${prefix}_")) { "压缩包里有不属于这个词库包的文件" }
                    check(!name.contains("..")) { "压缩包里有非法路径" }
                    if (e.isDirectory) continue
                    val out = tmp.resolve(name)
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
            }
            check(tmp.resolve(prefix).resolve("pack.main.yaml").isFile) { "压缩包里没有主词典" }
            for (dir in tmp.listFiles().orEmpty()) {
                val target = userDir.resolve(dir.name)
                target.deleteRecursively()
                check(dir.renameTo(target)) { "无法放入 ${dir.name}" }
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
            val entry = catalog.firstOrNull { it.name == pack } ?: error("清单里没有词库包 $pack")
            stage("正在下载 ${entry.title}", 0)
            download(entry) { done, total ->
                stage("正在下载 ${entry.title}：${done / 1048576} / ${total / 1048576} MB", (done * 100 / total).toInt().coerceIn(0, 100))
            }
        }
        val previous = DictPacks.active()
        withContext(Dispatchers.IO) { DictPacks.activate(pack) }
        stage("正在编译词库，约 15 秒", null)
        try {
            rime.deploy()
        } catch (e: Exception) {
            Timber.w(e, "Deploy failed after switching to %s", pack)
            if (previous != null && previous != pack) {
                stage("编译失败，正在回到 ${title(previous)}", null)
                withContext(Dispatchers.IO) { DictPacks.activate(previous) }
                runCatching { rime.deploy() }
            }
            throw IllegalStateException("编译失败，已回到原来的词库", e)
        }
    }
}
