/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.core.RimeApi
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.util.appContext
import org.json.JSONObject

/**
 * “AI 与输入辅助”设置页的开关。默认值都是开。
 *
 * - Lua 侧的功能（两段、候选生成、个人习惯三项）在每次进入输入框时写成 Rime 运行时选项，见 [applyRuntimeOptions]。
 *   选项名用“关”的写法（suzu_gen_off 等），桌面端没有这些选项，读到 false，功能照常开着。
 * - 选错修正提示和光标换词在 Kotlin 里，[SuzuFix] 直接读。
 * - AI 重排本身不存偏好：开关状态以守护进程为准（ai_status.json），见 [aiState]。
 * - 诊断记录用用户目录里的 .enabled 文件，Lua 在部署时读取，见 [Diag]。
 */
object SuzuFeatures {
    enum class Feature(
        val key: String,
        val title: String,
        val summary: String,
        val needsAi: Boolean,
    ) {
        Stage2("suzu_feature_stage2", "两段推理", "四个字以上的输入，先拼出第一段，再对剩下的拼音打分，拼成整句", true),
        Generate("suzu_feature_generate", "候选生成", "简拼时补充 Rime 没给出的词", true),
        FixHint("suzu_feature_fix_hint", "选错修正提示", "停顿后在工具栏提示“A → B”，点按才替换", true),
        CursorSwap("suzu_feature_cursor_swap", "光标换词", "光标点到文字上时，在候选栏列出同音候选", true),
        Recent("suzu_feature_recent", "最近使用优先", "最近上屏过的词排得更靠前", false),
        Pairs("suzu_feature_pairs", "个人词对", "常用的前后搭配再次出现时优先", false),
        Corr("suzu_feature_corr", "纠错记忆", "在同样的前一个词和拼音下，优先选过的词", false),
    }

    /** 由设置页接管、不在三个点面板里显示的方案开关 */
    val settingsOnlySwitches = setOf("ai_stage2")

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(appContext)

    fun isOn(f: Feature) = prefs.getBoolean(f.key, true)

    // ---- 设置页的 AI 重排开关 ----
    // 关闭时：AI 重排关闭，工具栏不显示 AI 徽标，三个点面板也不显示 AI 项，键盘弹出时不自动启动 AI 服务
    internal const val AI_MASTER = "suzu_ai_master"

    var aiMaster: Boolean
        get() = prefs.getBoolean(AI_MASTER, true)
        set(v) = prefs.edit { putBoolean(AI_MASTER, v) }

    // ---- 选错修正提示的停顿时间（毫秒）----
    const val FIX_PAUSE_KEY = "suzu_fix_pause_ms"
    const val FIX_PAUSE_DEFAULT = 1500
    const val FIX_PAUSE_MIN = 500
    const val FIX_PAUSE_MAX = 5000

    const val FIX_PAUSE_STEP = 500

    /** 取整到 0.5 秒并限制在范围内 */
    var fixPauseMs: Long
        get() = snapPause(prefs.getInt(FIX_PAUSE_KEY, FIX_PAUSE_DEFAULT)).toLong()
        set(v) = prefs.edit { putInt(FIX_PAUSE_KEY, snapPause(v.toInt())) }

    private fun snapPause(ms: Int) = ((ms + FIX_PAUSE_STEP / 2) / FIX_PAUSE_STEP * FIX_PAUSE_STEP).coerceIn(FIX_PAUSE_MIN, FIX_PAUSE_MAX)

    fun set(
        f: Feature,
        on: Boolean,
    ) = prefs.edit { putBoolean(f.key, on) }

    /** 偏好开着，依赖 AI 的还要设置页的 AI 重排开着（不读文件，可以在按键路径上调用） */
    fun active(f: Feature) = isOn(f) && (!f.needsAi || aiMaster)


    /** 进入输入框时调用（Rime 线程）：把开关写成运行时选项 */
    suspend fun applyRuntimeOptions(api: RimeApi) {
        api.setRuntimeOption("ai_stage2", isOn(Feature.Stage2))
        api.setRuntimeOption("suzu_gen_off", !isOn(Feature.Generate))
        api.setRuntimeOption("suzu_recent_off", !isOn(Feature.Recent))
        api.setRuntimeOption("suzu_pairs_off", !isOn(Feature.Pairs))
        api.setRuntimeOption("suzu_corr_off", !isOn(Feature.Corr))
    }

    // ---- AI 重排的实际状态 ----

    enum class AiBlock { NoModel, ShizukuNotRunning, NeedsPermission, DaemonOffline }

    /**
     * [block] 为 null 时守护进程在线：[enabled] 是它的开关，[loaded] 表示模型已在内存里。
     * 守护进程不在时 [block] 说明开启前还缺什么；DaemonOffline 表示条件都满足，开启时会启动它。
     */
    data class AiState(
        val block: AiBlock?,
        val enabled: Boolean = false,
        val loaded: Boolean = false,
    ) {
        val working get() = block == null && enabled
    }

    private const val STALE_S = 15

    fun aiState(): AiState {
        if (!SuzuAssets.isInstalled(SuzuAsset.Model)) return AiState(AiBlock.NoModel)
        val j =
            runCatching { JSONObject(DataManager.userDataDir.resolve("ai_status.json").readText()) }.getOrNull()
        val age = if (j != null) System.currentTimeMillis() / 1000 - j.optLong("ts", 0) else Long.MAX_VALUE
        val online = j != null && age <= STALE_S && j.optString("state") != "stopped"
        if (online) {
            return AiState(null, j!!.optBoolean("enabled", true), j.optBoolean("model_loaded"))
        }
        return when (AiDaemonLauncher.availability()) {
            AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING -> AiState(AiBlock.ShizukuNotRunning)
            AiDaemonLauncher.Availability.NEEDS_PERMISSION -> AiState(AiBlock.NeedsPermission)
            AiDaemonLauncher.Availability.READY -> AiState(AiBlock.DaemonOffline)
        }
    }

    /** 守护进程启动时读这个文件决定开关；没有这个文件时默认开 */
    fun saveAiChoice(on: Boolean) {
        runCatching { DataManager.userDataDir.resolve("ai_enabled.state").writeText(if (on) "1" else "0") }
    }

    // ---- 诊断记录 ----

    enum class Diag(
        val file: String,
        val title: String,
        val summary: String,
    ) {
        SelectStats("select_stats.enabled", "选词统计", "只记数字：选了第几个候选、AI 是否改过首位等，写入 select_stats.tsv"),
        SelectCorpus("select_corpus.enabled", "选词语料", "记录选中的词和上文末尾几个字，写入 select_corpus.tsv，用于以后微调模型"),
        InputLog("input_log.enabled", "输入事件流", "记录选词、上屏与删除，写入 input_log.jsonl，用于校准参数"),
    }

    fun isOn(d: Diag) = DataManager.userDataDir.resolve(d.file).isFile

    /** 返回是否改成功 */
    fun set(
        d: Diag,
        on: Boolean,
    ): Boolean {
        val f = DataManager.userDataDir.resolve(d.file)
        runCatching { if (on) f.createNewFile() else f.delete() }
        return f.isFile == on
    }
}
