/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 词库页：切换词库包，开关附加词库。方案只有一份，各包只差词库，用户词典共用 */
class DictPackFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        build()
    }

    private fun build() {
        val ctx = requireContext()
        val active = DictPacks.active()
        val installed = DictPacks.installed()
        val names = (PackInstaller.catalog.map { it.name } + installed).distinct()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory("词库包") {
                isIconSpaceReserved = false
                for (name in names) {
                    val entry = PackInstaller.catalog.firstOrNull { it.name == name }
                    val state = when {
                        name == active -> "正在使用"
                        name in installed -> "已下载"
                        entry != null -> "需要下载约 ${(entry.size + 524288) / 1048576} MB"
                        else -> ""
                    }
                    val commit = DictPacks.info(name)["commit"] ?: entry?.commit
                    val summary = listOfNotNull(state, entry?.license, commit?.take(7)).joinToString(" · ")
                    addPreference(PackInstaller.title(name), summary) {
                        if (name != active) confirm(name, entry, name in installed)
                    }
                }
            }
            addCategory("附加词库") {
                isIconSpaceReserved = false
                for (addon in DictAddon.entries) {
                    val on = DictAddons.isEnabled(addon)
                    addPreference(
                        addon.title,
                        (if (on) "已启用 · " else "未启用，需要下载约 ${(addon.remote.size + 524288) / 1048576} MB · ") + addon.source,
                    ) { confirmAddon(addon, !on) }
                }
            }
            addCategory("说明") {
                isIconSpaceReserved = false
                addPreference(
                    "切换词库时需等待编译完成",
                    "编译期间暂时无法打字，耗时取决于词库大小和设备性能。切换会保留已学习的词，已下载的词库包可直接切回，无需重新下载。",
                )
            }
        }
    }

    private fun confirmAddon(addon: DictAddon, enable: Boolean) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(if (enable) "启用${addon.title}词库？" else "关闭${addon.title}词库？")
            .setMessage("需要重新编译词库，期间暂时无法打字。耗时取决于词库大小和设备性能。AI 简拼补全将在下次启动 AI 服务后更新。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val dialog = ProgressDialog(ctx, addon.title)
                lifecycleScope.launch {
                    val result = runCatching {
                        viewModel.rime.runOnReady {
                            DictAddons.setEnabled(this, addon, enable) { text, percent ->
                                lifecycleScope.launch(Dispatchers.Main) { dialog.update(text, percent) }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        result.onFailure {
                            AlertDialog.Builder(ctx)
                                .setTitle("词库更新失败")
                                .setMessage(it.message ?: it.toString())
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                        if (isAdded) build()
                    }
                }
            }.show()
    }

    private fun confirm(name: String, entry: PackInstaller.Entry?, installed: Boolean) {
        val title = PackInstaller.title(name)
        val download = if (installed || entry == null) "" else "需先下载约 ${(entry.size + 524288) / 1048576} MB。"
        AlertDialog.Builder(requireContext())
            .setTitle("切换到$title？")
            .setMessage("${download}切换时需重新编译词库，期间暂时无法打字。耗时取决于词库大小和设备性能。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> switch(name) }
            .show()
    }

    private fun switch(name: String) {
        val ctx = requireContext()
        val dialog = ProgressDialog(ctx, PackInstaller.title(name))
        lifecycleScope.launch {
            val result = runCatching {
                viewModel.rime.runOnReady {
                    PackInstaller.switchTo(this, name) { text, percent ->
                        lifecycleScope.launch(Dispatchers.Main) { dialog.update(text, percent) }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                result
                    .onSuccess { ctx.toast("已切换到${PackInstaller.title(name)}") }
                    .onFailure {
                        AlertDialog.Builder(ctx)
                            .setTitle("词库切换失败")
                            .setMessage(it.message ?: it.toString())
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                if (isAdded) build()
            }
        }
    }
}

/** 词库下载和编译时的弹窗：知道进度时显示百分比，不知道时显示滚动的进度条 */
private class ProgressDialog(context: Context, title: String) {
    private val text = TextView(context)
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        isIndeterminate = true
    }
    private val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (24 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                addView(text)
                addView(bar)
            },
        )
        .setCancelable(false)
        .show()

    init {
        text.text = "准备中"
    }

    fun update(message: String, percent: Int?) {
        text.text = if (percent != null) "$message（$percent%）" else message
        bar.isIndeterminate = percent == null
        if (percent != null) bar.progress = percent
    }

    fun dismiss() = dialog.dismiss()
}
