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

    /** 单字、过长的条目和“啊啊啊”这类同字重复的条目没有输入价值 */
    fun keepWord(word: String): Boolean {
        val count = word.codePointCount(0, word.length)
        if (count !in 2..MAX_CHARS) return false
        return word.codePoints().distinct().count() > 1
    }

    /** 把上游词表整理成 Rime 词典：只保留有效词条，统一权重 */
    fun convert(id: String, source: Sequence<String>): Sequence<String> = sequence {
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
            if (keepWord(fields[0]) && CODE.matches(fields[1])) yield("${fields[0]}\t${fields[1]}\t$WEIGHT")
        }
    }

    /** 把启用的附加词库加进主词典的导入列表 */
    fun composeMainDict(main: String, addons: List<String>): String {
        if (addons.isEmpty()) return main
        val lines = addons.joinToString("") { "  - $DIR/$it\n" }
        check("import_tables:\n" in main) { "主词典没有导入列表" }
        return main.replaceFirst("import_tables:\n", "import_tables:\n$lines")
    }
}

/** 叠加在词库包之上的词库。文件在就算启用，所有词库包共用 */
enum class DictAddon(val id: String, val title: String, val source: String, val remote: RemoteFile) {
    Moegirl(
        "moegirl",
        "萌娘百科",
        "词条来自萌娘百科，由 mw2fcitx 项目整理",
        RemoteFile(
            "moegirl.source.yaml",
            "https://github.com/outloudvi/mw2fcitx/releases/download/20261024/moegirl.dict.yaml",
            "f7ed0e76e6bab529c43ff1fb59252e003267c4a339d83972b5b112d025e1b314",
            4_121_164,
        ),
    ),
}

object DictAddons {
    private val dir get() = DataManager.userDataDir.resolve(AddonRules.DIR)

    private fun fileOf(addon: DictAddon) = dir.resolve("${addon.id}.dict.yaml")

    fun isEnabled(addon: DictAddon) = fileOf(addon).isFile

    /** 启用的附加词库，按导入顺序 */
    fun enabledIds(): List<String> = DictAddon.entries.filter(::isEnabled).map { it.id }

    /** 开关一个附加词库并重新编译；编译失败时恢复原状并抛出原因 */
    suspend fun setEnabled(rime: RimeApi, addon: DictAddon, enabled: Boolean, stage: (String, Int?) -> Unit) {
        val pack = DictPacks.active() ?: error("还没有启用词库包")
        if (enabled) {
            stage("正在下载${addon.title}词库", 0)
            install(addon) { done ->
                stage("正在下载${addon.title}词库", (done * 100 / addon.remote.size).toInt().coerceIn(0, 100))
            }
        } else {
            withContext(Dispatchers.IO) { fileOf(addon).delete() }
        }
        withContext(Dispatchers.IO) { DictPacks.activate(pack) }
        stage("正在编译词库，可能需要一分钟", null)
        try {
            rime.deploy()
        } catch (e: Exception) {
            if (enabled) {
                stage("编译失败，正在恢复", null)
                withContext(Dispatchers.IO) {
                    fileOf(addon).delete()
                    DictPacks.activate(pack)
                }
                runCatching { rime.deploy() }
            }
            throw IllegalStateException("编译失败，词库没有改动", e)
        }
    }

    private suspend fun install(addon: DictAddon, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val source = File(dir, addon.remote.name)
        val tmp = File(dir, "${addon.id}.tmp")
        try {
            SuzuAssets.download(addon.remote, source) { progress(it) }
            source.useLines { lines ->
                tmp.bufferedWriter().use { out ->
                    AddonRules.convert(addon.id, lines).forEach {
                        out.write(it)
                        out.write("\n")
                    }
                }
            }
            check(tmp.renameTo(fileOf(addon))) { "无法写入词库文件" }
        } finally {
            source.delete()
            tmp.delete()
        }
    }
}
