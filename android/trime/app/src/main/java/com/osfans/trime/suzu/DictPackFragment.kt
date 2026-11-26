/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.osfans.trime.R
import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.SheetAction
import com.osfans.trime.ui.common.SheetOption
import com.osfans.trime.ui.common.actionSheet
import com.osfans.trime.ui.common.settingsSheet
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DictCatalog.state.collect { if (it != null) build() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { DictCatalog.refresh() }
    }

    private fun mb(bytes: Long) = (bytes + 524288) / 1048576

    private fun build() {
        val ctx = requireContext()
        val active = DictPacks.active()
        val installed = DictPacks.installed()
        val addons = DictAddons.options()
        val names = (PackInstaller.catalog.map { it.name } + installed).distinct()
        val activeEntry = active?.let(DictCatalog::pack)
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
                for (addon in addons) {
                    val on = DictAddons.isEnabled(addon)
                    addPreference(
                        SwitchPreferenceCompat(ctx).apply {
                            isPersistent = false
                            isIconSpaceReserved = false
                            title = addon.title
                            isChecked = on
                            if (!on) {
                                summary = if (DictAddons.isDownloaded(addon)) "已下载"
                                    else addon.remote?.let { "开启需下载约 ${mb(it.size)} MB" }
                            }
                            // 开关只发起确认，真正改完后整页重建
                            setOnPreferenceChangeListener { _, value ->
                                confirmAddon(addon, value as Boolean)
                                false
                            }
                        },
                    )
                }
            }
            addCategory("存储") {
                addPreference(
                    Preference(ctx).apply {
                        title = "管理已下载的词库"
                        val n = installed.size + addons.count(DictAddons::isDownloaded)
                        summary = if (n == 0) "还没有下载词库" else "$n 项"
                        mdiIcon("harddisk")
                        setOnPreferenceClickListener {
                            manageDownloaded()
                            true
                        }
                    },
                )
            }
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
            message = "编译期间暂停输入。已学习的词会保留。",
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

    /** 已下载的词库包和附加词库，正在用的不能删（附加词库要先关掉开关） */
    private fun manageDownloaded() {
        val ctx = requireContext()
        val active = DictPacks.active()
        val actions = buildList {
            for (name in DictPacks.installed()) {
                val inUse = name == active
                add(
                    SheetAction(
                        title = PackInstaller.title(name),
                        subtitle = if (inUse) "正在使用" else "已下载 · ${mb(DictPacks.sizeOf(name))} MB",
                        leadingIcon = "bookshelf",
                        trailingIcon = if (inUse) null else "delete_outline",
                        trailingOnly = true,
                    ) { confirmDelete(PackInstaller.title(name)) { DictPacks.delete(name) } },
                )
            }
            for (addon in DictAddons.options().filter(DictAddons::isDownloaded)) {
                val on = DictAddons.isEnabled(addon)
                add(
                    SheetAction(
                        title = "${addon.title}（附加词库）",
                        subtitle = if (on) "已开启，关掉开关后才能删除" else "已关闭 · ${mb(DictAddons.downloadedBytes(addon))} MB",
                        leadingIcon = "book_open_outline",
                        trailingIcon = if (on) null else "delete_outline",
                        trailingOnly = true,
                    ) { confirmDelete(addon.title) { DictAddons.deleteDownloaded(addon) } },
                )
            }
        }
        ctx.actionSheet("harddisk", "已下载的词库", actions, "完成", emptyText = "还没有下载词库。")
    }

    private fun confirmDelete(
        title: String,
        delete: () -> Boolean,
    ) {
        requireContext().settingsSheet(
            icon = "delete_outline",
            title = "删除$title？",
            message = "再次使用时需重新下载。",
            cancel = getString(android.R.string.cancel),
            destructive = true,
            confirm = SheetButton("删除") {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching(delete).getOrDefault(false) }
                    if (!ok) requireContext().toast("删除失败")
                    if (isAdded) build()
                }
            },
        ).show()
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
        val sources = DictAddons.options().mapNotNull { addon ->
            listOf(addon.source, addon.license).filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
                (listOf(addon.title) + it).joinToString("\n")
            }
        }
        requireContext().settingsSheet(
            icon = "information_outline",
            title = "版本与许可",
            message = (listOf(lines.joinToString("\n").ifEmpty { "没有词库包信息。" }) + sources).joinToString("\n\n"),
            confirm = SheetButton(getString(android.R.string.ok)),
        ).show()
    }

    private fun confirmAddon(addon: DictAddon, enable: Boolean) {
        val ctx = requireContext()
        ctx.settingsSheet(
            icon = "bookshelf",
            title = if (enable) "启用 ${addon.title}？" else "关闭 ${addon.title}？",
            message = "编译期间暂停输入。简拼补全将在 AI 服务下次启动后更新。",
            cancel = getString(android.R.string.cancel),
            confirm = SheetButton(if (enable) "启用" else "关闭") {
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
                            ctx.settingsSheet(
                                icon = "alert_circle_outline",
                                title = "词库更新失败",
                                message = it.message ?: it.toString(),
                                confirm = SheetButton(getString(android.R.string.ok)),
                            ).show()
                        }
                        if (isAdded) build()
                    }
                }
            },
        ).show()
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
                        ctx.settingsSheet(
                            icon = "alert_circle_outline",
                            title = "词库切换失败",
                            message = it.message ?: it.toString(),
                            confirm = SheetButton(getString(android.R.string.ok)),
                        ).show()
                    }
                if (isAdded) build()
            }
        }
    }
}

/** 词库下载和编译时的弹窗：知道进度时显示百分比，不知道时显示滚动的进度条 */
internal class ProgressDialog(context: Context, title: String) {
    private val text = TextView(context).apply { text = "准备中" }
    private val waiting = LinearProgressIndicator(context).apply { isIndeterminate = true }
    private val progress = LinearProgressIndicator(context).apply {
        max = 100
        isVisible = false
    }
    private val dialog = context.settingsSheet(
        icon = "download_outline",
        title = title,
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text)
            addView(waiting)
            addView(progress)
        },
        cancelable = false,
    ).apply { show() }

    fun update(message: String, percent: Int?) {
        text.text = if (percent != null) "$message（$percent%）" else message
        waiting.isVisible = percent == null
        progress.isVisible = percent != null
        if (percent != null) progress.setProgressCompat(percent, false)
    }

    fun dismiss() = dialog.dismiss()
}
