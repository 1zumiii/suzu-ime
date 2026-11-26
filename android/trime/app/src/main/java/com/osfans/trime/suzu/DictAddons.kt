/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import com.osfans.trime.core.RimeApi
import com.osfans.trime.data.base.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    fun isEnabled(addon: DictAddon) = fileOf(addon).isFile

    fun isDownloaded(addon: DictAddon) = fileOf(addon).isFile || offOf(addon).isFile

    fun downloadedBytes(addon: DictAddon): Long = listOf(fileOf(addon), offOf(addon)).filter { it.isFile }.sumOf { it.length() }

    /** 删除已关闭的附加词库的本地文件；开着的不删 */
    fun deleteDownloaded(addon: DictAddon): Boolean = !isEnabled(addon) && offOf(addon).delete()

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
            check(tmp.renameTo(fileOf(addon))) { "无法保存词库，请检查存储空间后重试" }
        } finally {
            source.delete()
            tmp.delete()
        }
    }
}
