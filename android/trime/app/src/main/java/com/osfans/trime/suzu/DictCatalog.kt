/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.util.AtomicFile
import com.osfans.trime.util.appContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

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
                remote.sha256, remote.size, label, o.string("source"))
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

    @Synchronized
    fun loadCache() {
        if (mutable.value != null) return
        mutable.value = runCatching { cache.openRead().use(::readText).let(::parse) }.getOrNull() ?: builtin
    }

    /** 与模型名录相同，先用缓存显示，在线成功后再更新。只从后台线程调用。 */
    @Synchronized
    fun refresh() {
        loadCache()
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
            val file = cache
            val stream = file.startWrite()
            try {
                stream.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                Timber.w(e, "Could not cache dictionary catalog")
            }
            mutable.value = parsed
        }.onFailure { Timber.i("Dictionary catalog fetch failed: %s", it.message) }
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
