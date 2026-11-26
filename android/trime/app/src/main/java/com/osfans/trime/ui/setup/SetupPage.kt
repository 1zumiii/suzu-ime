/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.osfans.trime.R
import com.osfans.trime.suzu.AiDaemonLauncher
import com.osfans.trime.suzu.SuzuAsset
import com.osfans.trime.suzu.SuzuAssets
import com.osfans.trime.suzu.SuzuModel
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.util.InputMethodUtils
import com.osfans.trime.util.appContext

/** 前两步是必需的；后面都可以跳过，跳过的功能保持关闭，之后可在设置的“模型”页补上 */
enum class SetupPage(val required: Boolean) {
    Enable(true),
    Select(true),
    Model(false),
    Grammar(false),
    Ai(false),
    ;

    private val asset: SuzuAsset?
        get() = when (this) {
            Model -> SuzuAsset.Model
            Grammar -> SuzuAsset.Grammar
            else -> null
        }

    /** 每一步的大标题 */
    fun getTitle(context: Context): CharSequence = context.getText(
        when (this) {
            Enable -> R.string.setup_title_enable
            Select -> R.string.setup_title_select
            Model -> R.string.setup_title_model
            Grammar -> R.string.setup_title_grammar
            Ai -> R.string.setup_title_ai
        },
    )

    /** 标题上方图标方块里的 MDI 图标 */
    val icon: String
        get() = when (this) {
            Enable -> "keyboard_outline"
            Select -> "keyboard_settings_outline"
            Model -> "chip"
            Grammar -> "file_document_outline"
            Ai -> "auto_fix"
        }

    fun getStepText(context: Context): CharSequence = when (this) {
        Enable -> context.getText(R.string.setup__step_one)
        Select -> context.getText(R.string.setup__step_two)
        else -> context.getString(R.string.suzu_setup_optional_step, ordinal + 1)
    }

    fun getHintText(context: Context, model: SuzuModel): CharSequence = when (this) {
        Enable -> context.getText(R.string.setup__enable_ime_hint)
        Select -> context.getText(R.string.setup__select_ime_hint)
        Model -> context.getString(R.string.suzu_setup_model_hint, model.sizeMb) + stateText(context, SuzuAsset.Model)
        Grammar -> context.getString(R.string.suzu_setup_grammar_hint, SuzuAsset.Grammar.sizeMb) + stateText(context, SuzuAsset.Grammar)
        Ai -> when {
            !SuzuAssets.isInstalled(SuzuAsset.Model) -> context.getString(R.string.suzu_setup_ai_needs_model)
            else -> when (AiDaemonLauncher.availability()) {
                AiDaemonLauncher.Availability.READY -> context.getString(R.string.suzu_setup_ai_ready)
                AiDaemonLauncher.Availability.NEEDS_PERMISSION -> context.getString(R.string.suzu_setup_ai_permission)
                AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING -> context.getString(R.string.suzu_setup_ai_shizuku)
            }
        }
    }

    private fun stateText(context: Context, asset: SuzuAsset): String = when (val state = SuzuAssets.state(asset)) {
        is SuzuAssets.State.Downloading -> "\n\n" + context.getString(R.string.suzu_setup_downloading, state.percent, state.doneMb, state.totalMb)
        is SuzuAssets.State.Failed -> "\n\n" + context.getString(R.string.suzu_setup_download_failed, state.message)
        SuzuAssets.State.Idle -> if (SuzuAssets.isInstalled(asset)) "\n\n" + context.getString(R.string.suzu_setup_installed) else ""
    }

    fun getButtonText(context: Context): CharSequence = when (this) {
        Enable -> context.getText(R.string.setup__enable_ime)
        Select -> context.getText(R.string.setup__select_ime)
        Model, Grammar -> context.getText(if (SuzuAssets.state(asset!!) is SuzuAssets.State.Downloading) R.string.suzu_cancel_download else R.string.suzu_download)
        Ai -> when (AiDaemonLauncher.availability()) {
            AiDaemonLauncher.Availability.READY -> context.getText(R.string.suzu_start_ai)
            AiDaemonLauncher.Availability.NEEDS_PERMISSION -> context.getText(R.string.suzu_grant_permission)
            AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING -> context.getText(R.string.suzu_open_shizuku)
        }
    }

    /** 正在下载时的百分比，其他时候为 null */
    fun downloadPercent(): Int? = asset?.let { (SuzuAssets.state(it) as? SuzuAssets.State.Downloading)?.percent }

    /** AI 服务要先有模型 */
    fun isActionAvailable() = this != Ai || SuzuAssets.isInstalled(SuzuAsset.Model)

    /** 语法模型较大，也可以选自己下载好的文件 */
    val canImportFile: Boolean get() = this == Grammar

    fun getButtonAction(activity: FragmentActivity, model: SuzuModel) {
        when (this) {
            Enable -> InputMethodUtils.showImeEnablerActivity(activity)
            Select -> InputMethodUtils.showImePicker()
            Model, Grammar -> {
                val downloadAsset = requireNotNull(asset)
                if (SuzuAssets.state(downloadAsset) is SuzuAssets.State.Downloading) {
                    SuzuAssets.cancel(downloadAsset)
                } else {
                    SuzuAssets.start(downloadAsset, model)
                }
            }
            Ai -> when (AiDaemonLauncher.availability()) {
                AiDaemonLauncher.Availability.READY -> startAi(activity)
                AiDaemonLauncher.Availability.NEEDS_PERMISSION -> AiDaemonLauncher.requestPermission { startAi(activity) }
                AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING -> openShizuku(activity)
            }
        }
    }

    private fun startAi(activity: FragmentActivity) {
        // 用户在引导里主动选择了启动，把 AI 开关记为开启（没有这个文件时守护进程也默认开启，这里写明确的选择）
        DataManager.userDataDir.resolve("ai_enabled.state").writeText("1")
        AiDaemonLauncher.start(activity) { result ->
            aiStarted = true
            (activity as? SetupActivity)?.refreshCurrentFragment()
        }
    }

    private fun openShizuku(activity: FragmentActivity) {
        val intent = activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?: Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri())
        activity.startActivity(intent)
    }

    fun isDone() = when (this) {
        Enable -> InputMethodUtils.checkIsTrimeEnabled()
        Select -> InputMethodUtils.checkIsTrimeSelected()
        Model, Grammar -> SuzuAssets.isInstalled(asset!!)
        Ai -> aiStarted
    }

    companion object {
        private const val DECIDED_KEY = "suzu_setup_decided"
        private val prefs get() = PreferenceManager.getDefaultSharedPreferences(appContext)

        /** 这次进程里已启动过 AI 服务 */
        var aiStarted = false

        fun SetupPage.isLastPage() = this == entries.last()

        fun Int.isLastPage() = this == entries.size - 1

        fun hasUndonePage() = entries.any { it.required && !it.isDone() }

        fun firstUndonePage() = entries.firstOrNull { !it.isDone() }

        /** 可选步骤只问一次：走完或跳过引导后不再弹出，已经有模型的老用户也不问 */
        fun optionalDecided() = prefs.getBoolean(DECIDED_KEY, false) || SuzuAssets.isInstalled(SuzuAsset.Model)

        fun markDecided() = prefs.edit { putBoolean(DECIDED_KEY, true) }
    }
}
