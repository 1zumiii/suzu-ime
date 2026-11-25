/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.suzu.SuzuFeatures.AiBlock
import com.osfans.trime.suzu.SuzuFeatures.Feature
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch

/**
 * “AI 与输入辅助”页。开关只显示实际生效的状态：条件不满足时开关是关的、不可点，摘要写明缺什么。
 *
 * AI 重排开关（[SuzuFeatures.aiMaster]）：关闭时 AI 重排关闭，工具栏不显示 AI 徽标。
 * 开着时，工具栏徽标仍可临时暂停或恢复 AI。开关只在 AI 服务在线时显示为开，点了以后等守护进程真正切换完成，页面每秒刷新一次。
 */
class SuzuFeatureFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var aiSwitch: SwitchPreferenceCompat
    private val featureSwitches = mutableMapOf<Feature, SwitchPreferenceCompat>()

    // 点了 AI 开关后等待守护进程切换：目标状态与截止时间
    private var aiTarget: Boolean? = null
    private var aiTargetUntil = 0L

    private val tick =
        object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 1000)
            }
        }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(ctx).apply {
                addCategory("AI 重排") {
                    aiSwitch =
                        SwitchPreferenceCompat(ctx).apply {
                            isPersistent = false
                            isIconSpaceReserved = false
                            title = "AI 重排"
                            setOnPreferenceChangeListener { _, v ->
                                toggleAi(v as Boolean)
                                false // 以守护进程的实际状态为准，由 refresh() 更新
                            }
                        }
                    addPreference(aiSwitch)
                    addFeatures(Feature.Stage2, Feature.Generate)
                }
                addCategory("修正") { addFeatures(Feature.FixHint, Feature.CursorSwap) }
                addCategory("个人习惯") { addFeatures(Feature.Recent, Feature.Pairs, Feature.Corr) }
            }
        refresh()
    }

    private fun PreferenceCategory.addFeatures(vararg fs: Feature) {
        for (f in fs) {
            // 选错修正提示的说明文字下方带停顿时间的折叠滑块
            val sw =
                (if (f == Feature.FixHint) FixHintPreference(context) else SwitchPreferenceCompat(context)).apply {
                    isPersistent = false
                    isIconSpaceReserved = false
                    title = f.title
                    setOnPreferenceChangeListener { _, v ->
                        SuzuFeatures.set(f, v as Boolean)
                        refresh()
                        true
                    }
                }
            featureSwitches[f] = sw
            addPreference(sw)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        if (!::aiSwitch.isInitialized) return
        val master = SuzuFeatures.aiMaster
        val st = SuzuFeatures.aiState()
        val online = st.block == null
        val waiting =
            aiTarget?.let { t ->
                val reached = if (t) st.working else !(online && st.enabled)
                if (System.currentTimeMillis() < aiTargetUntil && !reached) t else null
            }
        if (waiting == null) aiTarget = null
        val working = master && st.working
        aiSwitch.isChecked = master && online
        aiSwitch.isEnabled = waiting == null && st.block != AiBlock.NoModel && st.block != AiBlock.ShizukuNotRunning
        aiSwitch.summary =
            when {
                waiting == true -> if (online) "正在开启…" else "正在启动 AI 服务…"
                waiting == false -> "正在关闭…"
                st.block == AiBlock.NoModel -> "需要先在“模型”页下载 Suzu 模型"
                st.block == AiBlock.ShizukuNotRunning -> "AI 服务未运行，需要先启动 Shizuku"
                !master -> "已关闭，工具栏不显示 AI 徽标"
                st.block == AiBlock.NeedsPermission -> "AI 服务未运行。开启时会请求 Shizuku 授权"
                st.block == AiBlock.DaemonOffline -> "AI 服务未运行。开启时启动"
                !st.enabled -> "已暂停。点按工具栏的 AI 徽标可恢复"
                st.loaded -> "已开启"
                else -> "已开启，模型在打字时加载"
            }
        for ((f, sw) in featureSwitches) {
            if (f.needsAi && !working) {
                sw.isEnabled = false
                sw.isChecked = false
                sw.summary = "需要先开启 AI 重排"
            } else {
                sw.isEnabled = true
                sw.isChecked = SuzuFeatures.isOn(f)
                sw.summary = f.summary
            }
        }
    }

    private fun toggleAi(on: Boolean) {
        val st = SuzuFeatures.aiState()
        if (st.block == AiBlock.NoModel || st.block == AiBlock.ShizukuNotRunning) {
            refresh()
            return
        }
        SuzuFeatures.aiMaster = on
        if (!on) SuzuFeatures.saveAiChoice(false)
        when (st.block) {
            null -> {
                // 守护进程在线：经 Rime 会话切换，Lua 把它同步给守护进程（与工具栏徽标相同）
                lifecycleScope.launch {
                    viewModel.rime.runOnReady {
                        setRuntimeOption("_ai_manual", true)
                        setRuntimeOption("ai_rerank", on)
                    }
                }
                await(on)
            }
            AiBlock.DaemonOffline -> if (on) startDaemon() else refresh()
            AiBlock.NeedsPermission -> if (on) AiDaemonLauncher.requestPermission { startDaemon() } else refresh()
            else -> refresh()
        }
    }

    private fun startDaemon() {
        SuzuFeatures.saveAiChoice(true)
        await(true, START_MS)
        AiDaemonLauncher.start(requireContext().applicationContext) { r ->
            if (r is AiDaemonLauncher.Result.Failed) {
                aiTarget = null
                context?.toast(r.reason)
            }
            if (isAdded) refresh()
        }
    }

    private fun await(
        target: Boolean,
        ms: Long = 8000,
    ) {
        aiTarget = target
        aiTargetUntil = System.currentTimeMillis() + ms
        refresh()
    }

    companion object {
        private const val START_MS = 20000L
    }
}
