/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import com.osfans.trime.core.RimeApi
import com.osfans.trime.data.base.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.roundToLong

/** 附加词库的纯文本处理，和文件、网络无关 */
object AddonRules {
    const val DIR = "suzu_addons"

    /** 比词库包的中位权重略低，常用词不会被挤下去，AI 仍可按上文把它们排上来 */
    const val WEIGHT = 10

    private const val MAX_CHARS = 8
    private val CODE = Regex("[a-z]+( [a-z]+)*")
    private val INPUT_WEIGHT = Regex("[0-9]{1,12}(\\.[0-9]{1,8})?")

    /** 单字、过长的条目和“啊啊啊”这类同字重复的条目没有输入价值 */
    fun keepWord(word: String): Boolean {
        val count = word.codePointCount(0, word.length)
        if (count !in 2..MAX_CHARS) return false
        return word.codePoints().distinct().count() > 1
    }

    /** 把上游词表整理成 Rime 词典，按名录保留有效原权重或统一权重 */
    fun convert(id: String, source: Sequence<String>, preserveWeight: Boolean = false): Sequence<String> = sequence {
        yield("# 由 App 从上游词表生成")
        yield("---")
        yield("name: $id")
        yield("version: \"1\"")
        yield("sort: by_weight")
        yield("...")
        var inBody = false
        for (line in source) {
            if (!inBody) {
                inBody = line.startsWith("...")
                continue
            }
            val fields = line.split('\t')
            if (fields.size < 2 || line.startsWith("#")) continue
            val weight = if (preserveWeight) fields.getOrNull(2)?.trim()?.takeIf(INPUT_WEIGHT::matches) ?: continue else WEIGHT.toString()
            if (keepWord(fields[0]) && CODE.matches(fields[1])) yield("${fields[0]}\t${fields[1]}\t$weight")
        }
    }

    /** 写在换算后词库开头的尺度标记，用来判断切换词库包后要不要重写 */
    const val SCALE_PREFIX = "# scale: "

    fun scaleTag(pack: String, map: List<Pair<Double, Double>>?): String =
        if (map == null) pack else "$pack@" + Integer.toHexString(map.hashCode())

    fun scaleTagOf(lines: Sequence<String>): String? =
        lines.takeWhile { it != "---" }.firstOrNull { it.startsWith(SCALE_PREFIX) }?.removePrefix(SCALE_PREFIX)

    /**
     * 基准尺度（白霜）的权重换到当前包：在节点之间按 ln(1+w) 线性插值，
     * 低于第一个节点取第一个节点的值，高于最后一个节点取最后一个节点的值（不放大到超出该包的常见范围）。
     */
    fun scaleWeight(w: Double, map: List<Pair<Double, Double>>?): Double {
        if (map.isNullOrEmpty()) return w
        if (w <= map.first().first) return map.first().second
        if (w >= map.last().first) return map.last().second
        val i = map.indexOfFirst { it.first > w }
        val (x0, y0) = map[i - 1]
        val (x1, y1) = map[i]
        if (w == x0 || y0 == y1) return y0
        val t =(ln1p(w) - ln1p(x0)) / (ln1p(x1) - ln1p(x0))
        return expm1(ln1p(y0) + t * (ln1p(y1) - ln1p(y0)))
    }

    private fun formatWeight(w: Double): String {
        val r = w.roundToLong()
        return (if (w > 0 && r < 1) 1 else r).toString()
    }

    /** 把基准尺度的附加词库原稿改写成当前包的尺度，并在开头写上尺度标记 */
    fun rescale(source: Sequence<String>, map: List<Pair<Double, Double>>?, tag: String): Sequence<String> = sequence {
        var body = false
        var tagged = false
        for (line in source) {
            if (!body) {
                if (line.startsWith(SCALE_PREFIX)) continue
                if (!tagged && line == "---") {
                    yield("$SCALE_PREFIX$tag")
                    tagged = true
                }
                yield(line)
                body = line.startsWith("...")
                continue
            }
            val fields = line.split('\t')
            val w = fields.getOrNull(2)?.trim()?.toDoubleOrNull()
            if (fields.size >= 3 && w != null && !line.startsWith("#")) {
                yield("${fields[0]}\t${fields[1]}\t${if (map == null) fields[2].trim() else formatWeight(scaleWeight(w, map))}")
            } else {
                yield(line)
            }
        }
    }

    /** 把启用的附加词库加进主词典的导入列表 */
    fun composeMainDict(main: String, addons: List<String>): String {
        if (addons.isEmpty()) return main
        val lines = addons.joinToString("") { "  - $DIR/$it\n" }
        check("import_tables:\n" in main) { "词库配置不完整，无法添加附加词库" }
        return main.replaceFirst("import_tables:\n", "import_tables:\n$lines")
    }
}

/** 叠加在词库包之上的词库。文件在就算启用，所有词库包共用 */
object DictAddons {
    private val dir get() = DataManager.userDataDir.resolve(AddonRules.DIR)

    private fun fileOf(addon: DictAddon) = dir.resolve("${addon.id}.dict.yaml")

    /** 关闭时把词库挪到这里（不以 .dict.yaml 结尾，Rime 不会读），再打开时不用重新下载 */
    private fun offOf(addon: DictAddon) = dir.resolve("${addon.id}.dict.yaml.off")

    /** 基准尺度（白霜）的原稿。不以 .dict.yaml 结尾，Rime 不会读；切换词库包时从它重新换算 */
    private fun refOf(id: String) = dir.resolve("$id.ref.yaml")

    fun isEnabled(addon: DictAddon) = fileOf(addon).isFile

    fun isDownloaded(addon: DictAddon) = fileOf(addon).isFile || offOf(addon).isFile

    fun downloadedBytes(addon: DictAddon): Long =
        listOf(fileOf(addon), offOf(addon), refOf(addon.id)).filter { it.isFile }.sumOf { it.length() }

    /** 删除已关闭的附加词库的本地文件；开着的不删 */
    fun deleteDownloaded(addon: DictAddon): Boolean =
        !isEnabled(addon) && offOf(addon).delete().also { if (it) refOf(addon.id).delete() }

    /**
     * 按当前词库包的权重尺度重写已启用的附加词库，在主词典生成前调用。
     * 尺度标记与当前包一致时不动。旧版装的附加词库没有原稿，它的权重本来就是基准尺度，直接拿来当原稿。
     * 出错只记日志，保留原文件。返回是否重写了文件。
     */
    fun rescaleEnabled(pack: String): Boolean {
        val map = DictCatalog.pack(pack)?.weightMap
        val tag = AddonRules.scaleTag(pack, map)
        var changed = false
        for (id in enabledIds()) {
            runCatching {
                val target = dir.resolve("$id.dict.yaml")
                val ref = refOf(id)
                if (!ref.isFile) target.copyTo(ref)
                if (target.useLines { AddonRules.scaleTagOf(it) } == tag) return@runCatching
                val tmp = File(dir, "$id.scale.tmp")
                try {
                    ref.useLines { lines ->
                        tmp.bufferedWriter().use { out ->
                            AddonRules.rescale(lines, map, tag).forEach {
                                out.write(it)
                                out.write("\n")
                            }
                        }
                    }
                    check(tmp.renameTo(target)) { "无法保存词库" }
                    changed = true
                } finally {
                    tmp.delete()
                }
            }.onFailure { Timber.w(it, "Rescaling dictionary addon %s failed", id) }
        }
        return changed
    }

    private fun moveFile(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun localIds(suffix: String) = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(suffix) }
        .map { it.name.removeSuffix(suffix) }
        .filter(DictCatalog::validId)
        .sorted()

    /** 启用状态以文件为准，名录下架、断网和升级都不会取消导入。 */
    fun enabledIds(): List<String> = localIds(".dict.yaml")

    fun options(): List<DictAddon> {
        val entries = DictCatalog.current.addons
        val local = (enabledIds() + localIds(".dict.yaml.off")).distinct().filter { id -> entries.none { it.id == id } }
        return entries + local.map { id ->
            DictCatalog.builtinAddon(id)?.copy(remote = null) ?: DictAddon(id, id, "", "", null)
        }
    }

    /** 开关一个附加词库并重新编译；编译失败时恢复原状并抛出原因 */
    suspend fun setEnabled(rime: RimeApi, addon: DictAddon, enabled: Boolean, stage: (String, Int?) -> Unit) {
        val pack = DictPacks.active() ?: error("请先选择一个词库包，再启用附加词库")
        if (enabled == isEnabled(addon)) return
        if (enabled) {
            if (offOf(addon).isFile) {
                withContext(Dispatchers.IO) { moveFile(offOf(addon), fileOf(addon)) }
            } else {
                val remote = addon.remote ?: error("找不到该附加词库的下载信息")
                stage("正在下载${addon.title}词库", 0)
                install(addon, remote) { done ->
                    stage("正在下载${addon.title}词库", (done * 100 / remote.size).toInt().coerceIn(0, 100))
                }
            }
        } else {
            withContext(Dispatchers.IO) { if (fileOf(addon).isFile) moveFile(fileOf(addon), offOf(addon)) }
        }
        try {
            withContext(Dispatchers.IO) { DictPacks.activate(pack) }
            stage("正在编译词库，请稍候", null)
            rime.deploy()
        } catch (e: Exception) {
            stage("编译失败，正在恢复", null)
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                if (enabled) {
                    if (fileOf(addon).isFile) moveFile(fileOf(addon), offOf(addon))
                } else {
                    if (offOf(addon).isFile) moveFile(offOf(addon), fileOf(addon))
                }
                DictPacks.activate(pack)
                runCatching { rime.deploy() }
            }
            throw IllegalStateException("词库编译失败，请检查词库状态后重试", e)
        }
    }

    private suspend fun install(addon: DictAddon, remote: RemoteFile, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val source = File(dir, "${addon.id}-${remote.sha256.take(12)}.source")
        val tmp = File(dir, "${addon.id}.tmp")
        try {
            SuzuAssets.download(remote, source, dictionary = true) { progress(it) }
            var hasEntries = false
            source.useLines { lines ->
                tmp.bufferedWriter().use { out ->
                    AddonRules.convert(addon.id, lines, addon.preserveWeight).forEach {
                        hasEntries = hasEntries || '\t' in it
                        out.write(it)
                        out.write("\n")
                    }
                }
            }
            check(hasEntries) { "附加词库没有可用词条" }
            // 先存基准尺度的原稿，再复制一份启用；随后的 DictPacks.activate 会按当前包换算
            check(tmp.renameTo(refOf(addon.id))) { "无法保存词库，请检查存储空间后重试" }
            refOf(addon.id).copyTo(fileOf(addon), overwrite = true)
        } finally {
            source.delete()
            tmp.delete()
        }
    }
}
