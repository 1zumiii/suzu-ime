/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.os.Build
import android.util.AtomicFile
import com.osfans.trime.BuildConfig
import com.osfans.trime.util.appContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

data class DictAddon(
    val id: String,
    val title: String,
    val source: String,
    val license: String,
    val remote: RemoteFile?,
    val preserveWeight: Boolean = false,
)

object DictCatalog {
    private const val CATALOG_URL = "https://raw.githubusercontent.com/1zumiii/suzu-ime/main/dicts.json"
    private const val FILE = "dicts.json"
    private const val OVERRIDE_FILE = "dicts.override.json"
    private const val MAX_BYTES = 256 * 1024
    private val ID = Regex("[a-z][a-z0-9_-]{0,39}")
    private val SHA = Regex("[0-9a-f]{64}")
    private val HEX_COMMIT = Regex("[0-9a-f]{40}")
    private val RELEASE = Regex("/1zumiii/(rime-suzu|suzu-ime)/releases/download/[A-Za-z0-9_-][A-Za-z0-9._-]*/[A-Za-z0-9_-][A-Za-z0-9._-]*")
    private val MOEGIRL = Regex("/outloudvi/mw2fcitx/releases/download/[0-9]{8}/moegirl\\.dict\\.yaml")

    data class Snapshot(val packs: List<PackInstaller.Entry>, val addons: List<DictAddon>)

    private val empty = Snapshot(emptyList(), emptyList())
    private val builtin by lazy {
        runCatching { appContext.assets.open(FILE).use(::readText).let(::parse) }
            .onFailure { Timber.w(it, "Built-in dictionary catalog unavailable") }.getOrNull() ?: empty
    }
    private val mutable = MutableStateFlow<Snapshot?>(null)
    val state = mutable.asStateFlow()
    val current: Snapshot get() = mutable.value ?: builtin

    fun pack(id: String) = current.packs.firstOrNull { it.name == id } ?: builtin.packs.firstOrNull { it.name == id }

    fun builtinAddon(id: String) = builtin.addons.firstOrNull { it.id == id }

    fun validId(id: String) = ID.matches(id)

    private fun uri(value: String) = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.userInfo == null && it.port == -1 && it.rawFragment == null && it.host != null
    }

    /** 名录只能指向两个项目的 GitHub Release，或现有萌娘百科的上游附件。 */
    fun allowedDownload(value: String): Boolean {
        val u = uri(value) ?: return false
        return u.host.equals("github.com", ignoreCase = true) && u.rawQuery == null &&
            u.rawPath == u.normalize().rawPath && (RELEASE.matches(u.rawPath) || MOEGIRL.matches(u.rawPath))
    }

    /** GitHub Release 会转到官方附件域。每次跳转都检查，目标文件仍须通过 SHA-256 校验。 */
    fun openDownload(value: String, have: Long): HttpURLConnection {
        check(allowedDownload(value)) { "词库下载地址不受支持" }
        var target = URI(value)
        repeat(5) {
            val conn = connection(target, 30_000)
            try {
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                if (conn.responseCode !in listOf(301, 302, 303, 307, 308)) return conn
                val next = uri(target.resolve(requireNotNull(conn.getHeaderField("Location"))).toString())
                check(next != null && (allowedDownload(next.toString()) ||
                    next.host.equals("release-assets.githubusercontent.com", ignoreCase = true))) {
                    "词库下载跳转地址不受支持"
                }
                target = next
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
            conn.disconnect()
        }
        error("词库下载跳转次数过多")
    }

    private fun connection(target: URI, timeout: Int) = (target.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = timeout
        instanceFollowRedirects = false
    }

    fun parse(text: String): Snapshot? {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        if (root.number("schema") != 1L) return null
        val packs = root["packs"] as? JsonArray ?: return null
        val addons = root["addons"] as? JsonArray ?: return null
        val packEntries = packs.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.string("id")
            val remote = remote(o, "pack-$id.zip") ?: return@mapNotNull null
            val commit = o.string("commit").takeIf(HEX_COMMIT::matches) ?: return@mapNotNull null
            val label = o.display("schema_label", 60) ?: o.display("title", 60) ?: return@mapNotNull null
            PackInstaller.Entry(id, o.string("title"), o.string("license"), commit, remote.url,
                remote.sha256, remote.size, label, o.string("source"), weightMap(o["weight_map"]))
        }.distinctBy { it.name }
        val addonEntries = addons.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.string("id")
            val remote = remote(o, "$id.source.yaml") ?: return@mapNotNull null
            val weight = o.string("weight")
            if (weight !in listOf("uniform", "preserve")) return@mapNotNull null
            DictAddon(id, o.string("title"), o.string("source"), o.string("license"), remote, weight == "preserve")
        }.distinctBy { it.id }
        return Snapshot(packEntries, addonEntries).takeIf { packEntries.isNotEmpty() }
    }

    /**
     * 附加词库的权重按基准尺度（白霜）写。其他词库包用 weight_map 给出换算节点 [[基准权重, 本包权重], ...]：
     * 基准权重严格递增，本包权重不减，2～64 个点。格式不对时忽略这一项，按与基准相同处理。
     */
    fun weightMap(element: kotlinx.serialization.json.JsonElement?): List<Pair<Double, Double>>? {
        val arr = element as? JsonArray ?: return null
        if (arr.size !in 2..64) return null
        val points = arr.map { p ->
            val xy = p as? JsonArray ?: return null
            if (xy.size != 2) return null
            val x = (xy[0] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
            val y = (xy[1] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
            if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0) return null
            x to y
        }
        if (points.zipWithNext().any { (a, b) -> b.first <= a.first || b.second < a.second }) return null
        return points
    }

    private fun remote(o: JsonObject, file: String): RemoteFile? {
        if (!validId(o.string("id")) || o.display("title", 60) == null ||
            o.display("source", 300) == null || o.display("license", 100) == null) return null
        val url = o.string("url").takeIf(::allowedDownload) ?: return null
        val sha = o.string("sha256").takeIf(SHA::matches) ?: return null
        val size = o.number("size")?.takeIf { it in 1L..500_000_000L } ?: return null
        return RemoteFile(file, url, sha, size)
    }

    private fun JsonObject.string(key: String) =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.number(key: String) =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.display(key: String, max: Int) =
        string(key).takeIf { it.isNotBlank() && it.length <= max && it.none(Char::isISOControl) }

    private val cache get() = AtomicFile(File(appContext.filesDir, "suzu-dicts.json"))
    private val refreshing = AtomicBoolean(false)

    /**
     * 测试用的本地名录：放在应用专属外部目录（adb push 到 Android/data/<包名>/files/dicts.override.json），
     * 存在时代替在线名录，删掉即恢复。条目照常经过地址白名单与 SHA-256 校验，也不写入缓存。
     * Android 10 及以下其他应用可能写得进这个目录，所以只在 debug 构建或 Android 11 起读取。
     */
    private fun loadOverride(): Snapshot? {
        if (!BuildConfig.DEBUG && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val file = appContext.getExternalFilesDir(null)?.resolve(OVERRIDE_FILE)?.takeIf { it.isFile } ?: return null
        return runCatching { file.inputStream().use(::readText).let(::parse) }.getOrNull()
            .also { if (it == null) Timber.w("Dictionary catalog override is invalid, ignored") }
    }

    /** 只读本地文件，不碰网络，冷启动时 Rime 线程也会调用 */
    @Synchronized
    fun loadCache() {
        if (mutable.value != null) return
        mutable.value = loadOverride()
            ?: runCatching { cache.openRead().use(::readText).let(::parse) }.getOrNull()
            ?: builtin
    }

    /**
     * 与模型名录相同，先用缓存显示，在线成功后再更新。只从后台线程调用。
     * 网络请求不持有锁，避免和冷启动时的 loadCache 互相等待；同一时间只跑一次刷新。
     */
    fun refresh() {
        loadCache()
        if (!refreshing.compareAndSet(false, true)) return
        try {
            loadOverride()?.let {
                mutable.value = it
                return
            }
            runCatching {
                val text = connection(URI(CATALOG_URL), 10_000).let { conn ->
                    try {
                        check(conn.responseCode == HttpURLConnection.HTTP_OK)
                        conn.inputStream.use(::readText)
                    } finally {
                        conn.disconnect()
                    }
                }
                val parsed = parse(text) ?: return
                synchronized(this) {
                    val file = cache
                    val stream = file.startWrite()
                    try {
                        stream.write(text.toByteArray(Charsets.UTF_8))
                        file.finishWrite(stream)
                    } catch (e: Exception) {
                        file.failWrite(stream)
                        Timber.w(e, "Could not cache dictionary catalog")
                    }
                }
                mutable.value = parsed
            }.onFailure { Timber.i("Dictionary catalog fetch failed: %s", it.message) }
        } finally {
            refreshing.set(false)
        }
    }

    private fun readText(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            check(bytes.size() + n <= MAX_BYTES)
            bytes.write(buffer, 0, n)
        }
        return bytes.toString("UTF-8")
    }
}
