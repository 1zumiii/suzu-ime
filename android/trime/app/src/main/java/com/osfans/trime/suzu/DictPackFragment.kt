/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Context
import com.osfans.trime.ui.common.materialDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.ui.common.FooterPreference
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.SheetOption
import com.osfans.trime.ui.common.choiceSheet
import com.osfans.trime.ui.common.mdiIcon
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.addCategory
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

    private fun mb(bytes: Long) = (bytes + 524288) / 1048576

    private fun build() {
        val ctx = requireContext()
        val active = DictPacks.active()
        val installed = DictPacks.installed()
        val names = (PackInstaller.catalog.map { it.name } + installed).distinct()
        val activeEntry = PackInstaller.catalog.firstOrNull { it.name == active }
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory("词库包") {
                addPreference(
                    Preference(ctx).apply {
                        title = active?.let(PackInstaller::title) ?: "未选择词库包"
                        summary = if (active != null) "正在使用" else "点按选择"
                        mdiIcon("bookshelf")
                        widgetLayoutResource = R.layout.preference_widget_unfold
                        setOnPreferenceClickListener {
                            choosePack(names, active, installed)
                            true
                        }
                    },
                )
            }
            addCategory("附加词库") {
                for (addon in DictAddon.entries) {
                    val on = DictAddons.isEnabled(addon)
                    addPreference(
                        SwitchPreferenceCompat(ctx).apply {
                            isPersistent = false
                            isIconSpaceReserved = false
                            title = addon.title
                            isChecked = on
                            if (!on) summary = "开启需下载约 ${mb(addon.remote.size)} MB"
                            // 开关只发起确认，真正改完后整页重建
                            setOnPreferenceChangeListener { _, value ->
                                confirmAddon(addon, value as Boolean)
                                false
                            }
                        },
                    )
                }
            }
            for (addon in DictAddon.entries) addPreference(FooterPreference(ctx, "${addon.source}。"))
            addCategory("词库信息") {
                addPreference(
                    Preference(ctx).apply {
                        title = "版本与许可"
                        summary = activeEntry?.license ?: "未知"
                        mdiIcon("information_outline")
                        setOnPreferenceClickListener {
                            showPackInfo(active, activeEntry)
                            true
                        }
                    },
                )
            }
        }
    }

    private fun choosePack(
        names: List<String>,
        active: String?,
        installed: Collection<String>,
    ) {
        val current = names.indexOf(active).takeIf { it >= 0 }
        val entries = names.map { n -> PackInstaller.catalog.firstOrNull { it.name == n } }
        requireContext().choiceSheet(
            icon = "bookshelf",
            title = "切换词库",
            message = "切换时需重新编译词库，期间暂时无法打字。已学习的词会保留，下载过的词库包可以直接切回。",
            options = names.mapIndexed { i, n ->
                val entry = entries[i]
                SheetOption(
                    PackInstaller.title(n),
                    when {
                        n == active -> "正在使用"
                        n in installed -> "已下载"
                        entry != null -> "需下载 · 约 ${mb(entry.size)} MB"
                        else -> null
                    },
                )
            },
            current = current,
            initial = names.indices.firstOrNull { it != current } ?: 0,
            confirmLabel = { i -> if (names[i] in installed) "切换" else "下载并切换" },
        ) { i -> switch(names[i]) }
    }

    private fun showPackInfo(
        active: String?,
        entry: PackInstaller.Entry?,
    ) {
        val commit = active?.let { DictPacks.info(it)["commit"] } ?: entry?.commit
        val lines = listOfNotNull(
            active?.let { "词库包：${PackInstaller.title(it)}" },
            entry?.license?.let { "许可：$it" },
            commit?.let { "来源提交：${it.take(7)}" },
        )
        materialDialog(requireContext())
            .setTitle("版本与许可")
            .setMessage(lines.joinToString("\n").ifEmpty { "没有词库包信息。" })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmAddon(addon: DictAddon, enable: Boolean) {
        val ctx = requireContext()
        materialDialog(ctx)
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
                            materialDialog(ctx)
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
                        materialDialog(ctx)
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
internal class ProgressDialog(context: Context, title: String) {
    private val text = TextView(context)
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        isIndeterminate = true
    }
    private val dialog = materialDialog(context)
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
