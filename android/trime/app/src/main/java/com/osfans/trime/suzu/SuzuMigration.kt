/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import com.osfans.trime.core.Rime
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.userdict.UserDictManager
import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.File

/**
 * 从“雾凇/白霜各一个方案、配置推到用户目录”的旧版本迁到“方案打包在 APK、只有 suzu”的新版本。
 * 在 Rime 准备好、还没有会话打开用户词典时运行（见 SuzuSetup）。每一步都可以重入，中途被杀下次接着做。
 * 状态写在用户目录的 suzu_migration.txt，只有步骤和条数，没有词条。
 *
 * 1. 备份：旧词典的快照与 custom_phrase.txt 复制到 migration_backup/
 * 2. 合并：旧词典（rime_frost、rime_ice）的快照把 db_name 改成 suzu，用 levers 的 restore 并入 suzu.userdb
 * 3. 核对：合并后的条数不少于来源中最多的一份（允许 2% 的衰减条目解析失败）；不满足就停在这里，继续用旧配置
 * 4. 清理：按固定清单删除用户目录里的旧配置，清单之外的文件一概不动
 * 旧的 *.userdb 保留一个大版本，确认无误后再删。
 */
object SuzuMigration {
    private val SOURCES = listOf("rime_frost", "rime_ice")
    private const val STATE_FILE = "suzu_migration.txt"
    private const val BACKUP_DIR = "migration_backup"
    private const val MIN_RATIO = 0.98

    /** 旧版本推到用户目录的配置。只删这些名字，不按通配符删 */
    private val OLD_FILES = listOf(
        "algebra_extra.yaml", "default.yaml", "default.custom.yaml", "install.ps1",
        "melt_eng.dict.yaml", "melt_eng.schema.yaml", "radical_pinyin.dict.yaml", "radical_pinyin.schema.yaml",
        "rime_frost.custom.yaml", "rime_frost.dict.yaml", "rime_frost.schema.yaml",
        "rime_ice.custom.yaml", "rime_ice.dict.yaml", "rime_ice.schema.yaml",
        "symbols_v.yaml", "symbols_caps_v.yaml", "t9.schema.yaml", "double_pinyin.schema.yaml",
        "double_pinyin_abc.schema.yaml", "double_pinyin_flypy.schema.yaml", "double_pinyin_jiajia.schema.yaml",
        "double_pinyin_mspy.schema.yaml", "double_pinyin_sogou.schema.yaml", "double_pinyin_ziguang.schema.yaml",
        "tongwenfeng.trime.custom.yaml", "trime.custom.yaml",
        // 旧部署脚本留下的备份
        "default.custom.yaml.bak-before-derive", "default.custom.yaml.bak-before-frost",
        "rime_frost.custom.yaml.bak-before-appctx", "rime_frost.custom.yaml.bak-before-derive",
        "rime_frost.custom.yaml.bak-before-gate", "rime_frost.schema.yaml.bak-before-derive",
        "rime_ice.custom.yaml.bak-before-appctx", "rime_ice.custom.yaml.bak-before-gate",
        // 旧方案的编译产物
        "build/rime_frost.schema.yaml", "build/rime_frost.table.bin", "build/rime_frost.prism.bin",
        "build/rime_frost.reverse.bin", "build/rime_ice.schema.yaml", "build/rime_ice.table.bin",
        "build/rime_ice.prism.bin", "build/rime_ice.reverse.bin",
        // Trime 首次运行时编译的朙月拼音、笔画（APK 已不再带这两个方案）
        "build/luna_pinyin.schema.yaml", "build/luna_pinyin.table.bin", "build/luna_pinyin.prism.bin",
        "build/luna_pinyin.reverse.bin", "build/luna_pinyin_simp.schema.yaml", "build/luna_pinyin_simp.prism.bin",
        "build/stroke.schema.yaml", "build/stroke.table.bin", "build/stroke.prism.bin", "build/stroke.reverse.bin",
    )

    /** 整个目录都是旧配置的副本（雾凇的旧词库 cn_dicts 也在此列；白霜 cn_dicts_frost 现在由 APK 管理） */
    private val OLD_DIRS = listOf(
        "cn_dicts", "cn_dicts_cell", "docs", "en_dicts", "extras", "install", "packs", "llm-distill", "llm-windows",
    )

    /** lua/ 下的脚本。cold_word_drop 的 drop_words、hide_words、reduce_freq_words 是用户数据，不在清单里 */
    private val OLD_LUA = listOf(
        "SUZU_IME_VERSION", "lunar.db", "ai_rerank_app_ctx.lua", "ai_rerank_filter.lua", "ai_rerank_processor.lua",
        "autocap_filter.lua", "calc_translator.lua", "cn_en_spacer.lua", "convert_ar_num_to_zh.lua",
        "corpus_archive.lua", "corrector.lua", "date_translator.lua", "debuger.lua", "digit_sep_cn.lua",
        "emoji_defer_filter.lua", "en_spacer.lua", "force_gc.lua", "input_log.lua", "is_in_user_dict.lua",
        "long_word_filter.lua", "lunar.lua", "model_badge_filter.lua", "number_translator.lua",
        "personal_pairs.lua", "pin_cand_filter.lua", "rank_unified.lua", "recent_boost_filter.lua",
        "reduce_english_filter.lua", "rerank_json.lua", "search.lua", "select_character.lua", "select_info.lua",
        "t9_preedit.lua", "unicode.lua", "uuid.lua", "v_filter.lua",
        "ai_rerank_app_ctx.lua.bak-before-evt", "ai_rerank_app_ctx.lua.bak-before-hanctx",
        "ai_rerank_filter.lua.bak-before-evt", "ai_rerank_filter.lua.bak-before-hanctx",
        "rank_unified.lua.bak-before-evt", "rank_unified.lua.bak-before-hanctx",
        "cold_word_drop/filter.lua", "cold_word_drop/logger.lua", "cold_word_drop/metatable.lua",
        "cold_word_drop/processor.lua", "cold_word_drop/string.lua",
    ).map { "lua/$it" }

    /** 新版本也要有、旧版本可能盖住 APK 配置的文件，供状态页检查 */
    val overridingNames: List<String> get() = OLD_FILES.filter { !it.startsWith("build/") && !it.contains(".bak-") }

    private val userDir get() = DataManager.userDataDir

    /** 上次迁移的结果（状态页显示）；没迁移过时为空 */
    fun state(): Map<String, String> = runCatching {
        userDir.resolve(STATE_FILE).readLines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun save(state: Map<String, String>) {
        userDir.resolve(STATE_FILE).writeText(state.entries.joinToString("") { "${it.key}=${it.value}\n" })
    }

    private fun hasOldConfig() = OLD_FILES.any { userDir.resolve(it).exists() } || OLD_DIRS.any { userDir.resolve(it).exists() }

    /** 运行迁移；返回是否改动了用户目录（需要完整部署） */
    fun run(): Boolean {
        val state = state().toMutableMap()
        if (state["done"] == "1") return false
        if (state["failed"] != null) return false   // 核对没通过：停在旧配置，等状态页里手动重试
        val sources = SOURCES.filter { userDir.resolve("$it.userdb").isDirectory }
        if (sources.isEmpty() && !hasOldConfig()) {
            state["done"] = "1"
            save(state)
            return false
        }
        Timber.i("Suzu migration: sources=%s", sources)
        val backup = userDir.resolve(BACKUP_DIR).apply { mkdirs() }
        userDir.resolve("custom_phrase.txt").takeIf { it.isFile && !backup.resolve(it.name).exists() }
            ?.copyTo(backup.resolve("custom_phrase.txt"))

        if (state["merged"] != "1" && sources.isNotEmpty()) {
            // 原生层出错会直接结束进程：连续两次没走完就不再自动尝试，免得每次启动都崩
            val attempts = (state["attempts"]?.toIntOrNull() ?: 0) + 1
            if (attempts > 2) return fail(state, "数据合并连续 2 次中断，已暂停自动重试")
            state["attempts"] = attempts.toString()
            save(state)
            // levers 的 backup 要用同步目录与 installation_id，它们由 installation_update 设置。
            // 没有 installation.yaml 时它只新建文件、不设同步目录（留下相对路径 sync），所以再跑一次
            val fresh = !userDir.resolve("installation.yaml").isFile
            if (!Rime.runRimeTask("installation_update") || (fresh && !Rime.runRimeTask("installation_update"))) {
                return fail(state, "无法准备旧数据，请在功能状态页安排重试")
            }
            val counts = sources.associateWith { count(it) }
            for (src in sources) {
                val snapshot = snapshotOf(src)
                if (snapshot == null) {
                    return fail(state, "无法备份旧词库，请检查存储空间后重试")
                }
                snapshot.copyTo(backup.resolve(snapshot.name), overwrite = true)
                val renamed = backup.resolve("$src.as-suzu.userdb.txt")
                renamed.writeText(
                    snapshot.readLines().joinToString("\n", postfix = "\n") {
                        if (it.startsWith("#@/db_name\t")) "#@/db_name\t${DictPacks.SCHEMA}" else it
                    },
                )
                if (!UserDictManager.restoreUserDict(renamed.absolutePath)) {
                    return fail(state, "旧词库合并失败，已保留原数据")
                }
            }
            val merged = count(DictPacks.SCHEMA)
            val need = counts.values.maxOrNull() ?: 0
            counts.forEach { (k, v) -> state["count.$k"] = v.toString() }
            state["count.suzu"] = merged.toString()
            if (merged < need * MIN_RATIO) {
                return fail(state, "合并后有 $merged 条记录，与原词库最多的 $need 条相比未通过核对")
            }
            state["merged"] = "1"
            state.remove("failed")
            save(state)
        }

        val removed = (OLD_FILES + OLD_LUA).count { userDir.resolve(it).let { f -> f.isFile && f.delete() } } +
            OLD_DIRS.count { userDir.resolve(it).let { d -> d.isDirectory && d.deleteRecursively() } }
        state["removed"] = removed.toString()
        selectSuzuSchema()
        state["done"] = "1"
        save(state)
        Timber.i("Suzu migration done: removed %d old entries", removed)
        return true
    }

    /** 状态页的“重试迁移”：清掉失败记录，下次启动 Rime 时重新做 */
    fun clearFailure() {
        save(state() - "failed" - "attempts")
    }

    private fun fail(state: MutableMap<String, String>, reason: String): Boolean {
        Timber.w("Suzu migration stopped: %s", reason)
        state["failed"] = reason
        save(state)
        return false
    }

    /** 条数：导出成文本只为计数，临时文件随即删除 */
    private fun count(dict: String): Int {
        val tmp = File(appContext.cacheDir, "suzu-count-$dict.txt")
        return try {
            UserDictManager.exportUserDict(dict, tmp.absolutePath)
        } finally {
            tmp.delete()
        }
    }

    /** 用 levers 的 backup 生成快照：<sync_dir>/<installation_id>/<词典>.userdb.txt */
    private fun snapshotOf(dict: String): File? {
        if (!UserDictManager.backupUserDict(dict)) return null
        val installation = runCatching { userDir.resolve("installation.yaml").readLines() }.getOrDefault(emptyList())
        fun value(key: String) = installation.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        val syncDir = value("sync_dir")?.let(::File) ?: userDir.resolve("sync")
        val id = value("installation_id") ?: return null
        return syncDir.resolve(id).resolve("$dict.userdb.txt").takeIf { it.isFile }
    }

    /** user.yaml 记着上次选的方案，旧方案已经不在列表里了 */
    private fun selectSuzuSchema() {
        val file = userDir.resolve("user.yaml")
        if (!file.isFile) return
        val text = file.readText()
        val updated = text.replace(Regex("(previously_selected_schema:\\s*)\\S+"), "$1${DictPacks.SCHEMA}")
        if (updated != text) file.writeText(updated)
    }
}
