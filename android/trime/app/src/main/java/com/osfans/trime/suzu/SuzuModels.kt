/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import com.osfans.trime.BuildConfig
import com.osfans.trime.util.appContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class RemoteFile(val name: String, val url: String, val sha256: String, val size: Long)

/** 一个可下载的 Suzu 模型。权重与词表都放在 Hugging Face 的 IzumiThe3rd 名下，按固定 revision 下载 */
data class SuzuModel(
    val id: String,
    val title: String,
    val repo: String,
    val revision: String,
    val filename: String,
    val weightsSha: String,
    val weightsSize: Long,
    val vocabSha: String,
    val vocabSize: Long,
    val topGate: String,
) {
    private val url get() = "https://huggingface.co/$repo/resolve/$revision/$filename"
    val files: List<RemoteFile>
        get() = listOf(
            RemoteFile("suzu.gguf", url, weightsSha, weightsSize),
            RemoteFile("suzu.vocab.txt", "$url.vocab.txt", vocabSha, vocabSize),
        )
    val sizeMb: Long get() = (weightsSize + vocabSize + 500_000) / 1_000_000
}

/**
 * 模型名录：仓库根目录的 models.json，从新到旧排列，第一项是默认模型。
 * 拉取成功后缓存到应用私有目录，拉不到时用缓存，再没有就用安装包内置的列表。
 * 名录只能指向 huggingface.co/IzumiThe3rd/ 下固定 revision 的文件，且必须带 sha256，
 * 不合格的条目直接跳过，所以名录被改也无法让手机下载别处的文件。
 */
object SuzuModels {
    const val CATALOG_URL = "https://raw.githubusercontent.com/1zumiii/suzu-ime/main/models.json"
    private const val SCHEMA = 1
    private const val CACHE_FILE = "suzu-models.json"
    private const val MAX_CATALOG_BYTES = 256 * 1024
    private const val MAX_MODEL_BYTES = 1_000_000_000L

    val BUILTIN = listOf(
        SuzuModel(
            "PG200", "Suzu-UER6-PG200", "IzumiThe3rd/Suzu-UER6-PG200", "92cf37828600d7254b4cf91124edd8a1a5e5c4d8",
            "suzu-uer6-pg200.q8_0.gguf",
            "28782d8df7edc0b8d2bb6133a941c153a63a5f22d463411fea2aef37c7f2b67a", 52_811_488,
            "16a9b481d23159418c6455d76f36569a9d97d6ce1d5303f5431ce0c621e4fc7c", 20_573, "1.5",
        ),
        SuzuModel(
            "PG100", "Suzu-UER12-PG100", "IzumiThe3rd/Suzu-UER12-PG100", "774e243813ad93a3d7bb503c6de284d4171db936",
            "suzu-uer12-pg100.q8_0.gguf",
            "62017d0b8b9693a558de3f1e89fa445ab2917df1c44a5d196959155fe54d2278", 98_176_640,
            "16a9b481d23159418c6455d76f36569a9d97d6ce1d5303f5431ce0c621e4fc7c", 20_573, "1.5",
        ),
    )

    private val _list = MutableStateFlow(BUILTIN)
    val list: StateFlow<List<SuzuModel>> = _list.asStateFlow()

    val default: SuzuModel get() = _list.value.first()

    fun byId(id: String?): SuzuModel? = id?.let { i -> _list.value.firstOrNull { it.id == i } ?: BUILTIN.firstOrNull { it.id == i } }

    /**
     * 已从名录撤下的模型（有了明显更好的替代）：只用来在徽标上显示名字，不能再下载。
     * 升级前装好的旧模型照常可用，徽标仍显示它的名字，用户可在“设置 → 模型”换成新模型。
     */
    private val RETIRED = mapOf(
        "953fa76eca9d1b171cf9e819e9f0474f12c309be1bc7c1aad46db2916d0b3859" to "Suzu-UER6-KD500",
        "a2ff2c46110c7ba47746871eaf8ff37cc4fab58105ddbe11e0a768e340cdb996" to "Suzu-2.4",
    )

    /** 按权重 sha256 前缀找模型名，找不到返回 null */
    fun titleForHash(hashPrefix: String): String? =
        hashPrefix.takeIf { it.length >= 8 }?.let { h ->
            (_list.value + BUILTIN).firstOrNull { it.weightsSha.startsWith(h) }?.title
                ?: RETIRED.entries.firstOrNull { it.key.startsWith(h) }?.value
        }

    private val ID = Regex("[A-Za-z0-9._-]{1,40}")
    private val REPO = Regex("IzumiThe3rd/[A-Za-z0-9._-]{1,96}")
    private val REVISION = Regex("[0-9a-f]{40}")
    private val FILE = Regex("[A-Za-z0-9._-]{1,128}\\.gguf")
    private val SHA = Regex("[0-9a-f]{64}")
    private val GATE = Regex("\\d{1,2}(\\.\\d{1,3})?")

    /** 解析名录。整体格式不对返回 null，单个条目不合格或要求更新版本的 App 时跳过该条 */
    fun parse(text: String, appVersion: Int = BuildConfig.VERSION_CODE): List<SuzuModel>? {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if (root.num("schema") != SCHEMA.toLong()) return null
        val arr = runCatching { root.getValue("models").jsonArray }.getOrNull() ?: return null
        val out = mutableListOf<SuzuModel>()
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            if ((o.num("min_app") ?: 0) > appVersion) continue
            val m = SuzuModel(
                id = o.str("id"),
                title = o.str("title").trim(),
                repo = o.str("repo"),
                revision = o.str("revision"),
                filename = o.str("file"),
                weightsSha = o.str("sha256"),
                weightsSize = o.num("size") ?: 0,
                vocabSha = o.str("vocab_sha256"),
                vocabSize = o.num("vocab_size") ?: 0,
                topGate = o.str("top_gate"),
            )
            val ok = ID.matches(m.id) && m.title.length in 1..60 && m.title.none { it.isISOControl() } &&
                REPO.matches(m.repo) && !m.repo.contains("..") && REVISION.matches(m.revision) && FILE.matches(m.filename) &&
                SHA.matches(m.weightsSha) && SHA.matches(m.vocabSha) &&
                m.weightsSize in 1..MAX_MODEL_BYTES && m.vocabSize in 1..MAX_MODEL_BYTES &&
                GATE.matches(m.topGate) && out.none { it.id == m.id }
            if (ok) out.add(m) else Timber.w("Skip invalid catalog entry %s", m.id)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.num(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private val cacheFile: File get() = File(appContext.filesDir, CACHE_FILE)

    /** 读缓存的名录（进程里只需一次） */
    @Volatile private var cacheLoaded = false

    fun loadCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        runCatching { cacheFile.takeIf { it.isFile }?.readText()?.let(::parse) }
            .getOrNull()?.let { _list.value = it }
    }

    /** 在后台线程调用：拉取最新名录，成功则更新列表与缓存 */
    fun refresh() {
        loadCache()
        val text = runCatching {
            val conn = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                check(conn.responseCode == HttpURLConnection.HTTP_OK)
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        check(out.size() <= MAX_CATALOG_BYTES)
                    }
                    out.toString("UTF-8")
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Timber.i("Model catalog fetch failed: %s", it.message) }.getOrNull() ?: return
        val parsed = parse(text) ?: return
        _list.value = parsed
        runCatching { cacheFile.writeText(text) }
    }
}
