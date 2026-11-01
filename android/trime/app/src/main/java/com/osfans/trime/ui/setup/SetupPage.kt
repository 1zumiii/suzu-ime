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
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.util.InputMethodUtils
import com.osfans.trime.util.appContext

/** 前两步是必需的；后面都可以跳过，跳过的功能保持关闭，之后可在设置的“词库”页补上 */
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

    fun getStepText(context: Context): CharSequence = when (this) {
        Enable -> context.getText(R.string.setup__step_one)
        Select -> context.getText(R.string.setup__step_two)
        else -> "第 ${ordinal + 1} 步（可选）"
    }

    fun getHintText(context: Context): CharSequence = when (this) {
        Enable -> context.getText(R.string.setup__enable_ime_hint)
        Select -> context.getText(R.string.setup__select_ime_hint)
        Model -> "下载 Suzu 模型（约 ${SuzuAsset.Model.sizeMb} MB），按上文调整候选顺序。" + stateText(SuzuAsset.Model, "不下载时 AI 重排保持关闭，打字不受影响。")
        Grammar -> "下载语法模型（约 ${SuzuAsset.Grammar.sizeMb} MB，建议连接 Wi-Fi），改善整句输入的同音字选择。" + stateText(SuzuAsset.Grammar, "不下载时不使用语法模型，其他功能不受影响。")
        Ai -> when {
            !SuzuAssets.isInstalled(SuzuAsset.Model) -> "AI 服务需要先下载 Suzu 模型，可以跳过这一步。"
            else -> when (AiDaemonLauncher.availability()) {
                AiDaemonLauncher.Availability.READY -> "Shizuku 已就绪，点按即可启动 AI 服务。手机重启后要先启动 Shizuku。"
                AiDaemonLauncher.Availability.NEEDS_PERMISSION -> "AI 服务要经 Shizuku 启动，请授权。"
                AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING -> "AI 服务要经 Shizuku 启动。请安装并启动 Shizuku，再回到这里。不用 Shizuku 时 AI 重排保持关闭，打字不受影响。"
            }
        }
    }

    private fun stateText(asset: SuzuAsset, idle: String): String = when (val state = SuzuAssets.state(asset)) {
        is SuzuAssets.State.Downloading -> "\n\n正在下载：${state.doneMb} / ${state.totalMb} MB。离开这一页也会继续。"
        is SuzuAssets.State.Failed -> "\n\n下载失败：${state.message}"
        SuzuAssets.State.Idle -> "\n\n" + if (SuzuAssets.isInstalled(asset)) "已安装。" else idle
    }

    fun getButtonText(context: Context): CharSequence = when (this) {
        Enable -> context.getText(R.string.setup__enable_ime)
        Select -> context.getText(R.string.setup__select_ime)
        Model, Grammar -> if (SuzuAssets.state(asset!!) is SuzuAssets.State.Downloading) "取消下载" else "下载"
        Ai -> when (AiDaemonLauncher.availability()) {
            AiDaemonLauncher.Availability.READY -> "启动 AI 服务"
            AiDaemonLauncher.Availability.NEEDS_PERMISSION -> "授权"
            AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING -> "打开 Shizuku"
        }
    }

    /** AI 服务要先有模型 */
    fun isActionAvailable() = this != Ai || SuzuAssets.isInstalled(SuzuAsset.Model)

    /** 语法模型较大，也可以选自己下载好的文件 */
    val canImportFile: Boolean get() = this == Grammar

    fun getButtonAction(activity: FragmentActivity) {
        when (this) {
            Enable -> InputMethodUtils.showImeEnablerActivity(activity)
            Select -> InputMethodUtils.showImePicker()
            Model, Grammar -> if (SuzuAssets.state(asset!!) is SuzuAssets.State.Downloading) SuzuAssets.cancel(asset!!) else SuzuAssets.start(asset!!)
            Ai -> when (AiDaemonLauncher.availability()) {
                AiDaemonLauncher.Availability.READY -> startAi(activity)
                AiDaemonLauncher.Availability.NEEDS_PERMISSION -> AiDaemonLauncher.requestPermission { startAi(activity) }
                AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING -> openShizuku(activity)
            }
        }
    }

    private fun startAi(activity: FragmentActivity) {
        // 用户在引导里主动选择了启动，把 AI 开关记为开启（新装时默认关闭）
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
