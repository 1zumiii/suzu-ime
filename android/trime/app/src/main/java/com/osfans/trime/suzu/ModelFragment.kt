/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.os.Bundle
import com.osfans.trime.ui.common.materialDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import androidx.preference.Preference
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.SheetAction
import com.osfans.trime.ui.common.SheetOption
import com.osfans.trime.ui.common.actionSheet
import com.osfans.trime.ui.common.choiceSheet
import com.osfans.trime.ui.common.mdiIcon
import com.osfans.trime.ui.common.showDestructive
import com.osfans.trime.util.addCategory
import com.osfans.trime.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 模型页：顶部是当前 Suzu 模型的卡片，下面是语法模型和已下载模型的管理。文件在就算启用 */
class ModelFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private val pickGrammar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(SuzuAssets::importGrammar)
    }

    private var card: ModelCardPreference? = null
    private var grammarPref: AssetPreference? = null
    private var storagePref: Preference? = null

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        build()
        // 下载进度变化时只刷新这几项，不重建页面
        lifecycleScope.launch {
            SuzuAssets.states.drop(1).collect {
                if (isAdded) render()
            }
        }
        // 先拉最新的模型列表，打开选择面板时就是新的
        lifecycleScope.launch(Dispatchers.IO) { SuzuModels.refresh() }
    }

    private fun build() {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            card = ModelCardPreference(ctx) { onModelCardClick() }.also(::addPreference)
            addCategory("语法模型") {
                grammarPref = AssetPreference(ctx).apply {
                    title = "万象语法模型"
                    isSingleLineTitle = false
                    mdiIcon("file_document_outline")
                    setOnPreferenceClickListener {
                        onGrammarClick()
                        true
                    }
                }.also(::addPreference)
            }
            addCategory("存储") {
                storagePref = Preference(ctx).apply {
                    title = "管理已下载的模型"
                    mdiIcon("harddisk")
                    setOnPreferenceClickListener {
                        manageDownloaded()
                        true
                    }
                }.also(::addPreference)
            }
        }
        render()
    }

    private fun render() {
        val state = SuzuAssets.state(SuzuAsset.Model)
        card?.apply {
            percent = (state as? SuzuAssets.State.Downloading)?.percent
            when {
                state is SuzuAssets.State.Downloading -> {
                    label = "Suzu 模型 · 正在下载 ${state.percent}%（${state.doneMb} / ${state.totalMb} MB）"
                    name = state.model?.title ?: "Suzu 模型"
                    action = "取消下载"
                }
                SuzuAssets.isInstalled(SuzuAsset.Model) -> {
                    label = "Suzu 模型 · 正在使用"
                    name = SuzuAssets.installedModelTitle() ?: "Suzu 模型"
                    action = "选择模型"
                }
                state is SuzuAssets.State.Failed -> {
                    label = "Suzu 模型 · 下载失败"
                    name = state.message
                    action = "选择模型重新下载"
                }
                else -> {
                    label = "Suzu 模型 · 未安装，AI 重排已关闭"
                    name = "尚未选择模型"
                    action = "选择模型并下载"
                }
            }
            update()
        }
        grammarPref?.apply {
            val g = SuzuAssets.state(SuzuAsset.Grammar)
            percent = (g as? SuzuAssets.State.Downloading)?.percent
            summary = when {
                g is SuzuAssets.State.Downloading -> "正在下载 ${g.percent}%（${g.doneMb} / ${g.totalMb} MB），点按取消"
                SuzuAssets.isInstalled(SuzuAsset.Grammar) -> SuzuAssets.installedGrammarDescription(requireContext())
                g is SuzuAssets.State.Failed -> "下载失败：${g.message}\n点按重试"
                else -> getString(R.string.suzu_grammar_available, SuzuAsset.Grammar.sizeMb)
            }
        }
        storagePref?.summary = SuzuAsset.entries.count(SuzuAssets::isInstalled).let { n ->
            if (n == 0) "还没有下载模型" else "$n 个模型"
        }
    }

    private fun onModelCardClick() {
        if (SuzuAssets.state(SuzuAsset.Model) is SuzuAssets.State.Downloading) {
            SuzuAssets.cancel(SuzuAsset.Model)
            render()
        } else {
            chooseModel()
        }
    }

    private fun onGrammarClick() {
        val asset = SuzuAsset.Grammar
        when {
            SuzuAssets.state(asset) is SuzuAssets.State.Downloading -> SuzuAssets.cancel(asset)
            SuzuAssets.isInstalled(asset) -> confirmDelete(asset)
            else ->
                materialDialog(requireContext())
                    .setTitle("万象语法模型")
                    .setItems(arrayOf(getString(R.string.suzu_grammar_download_lts, asset.sizeMb), getString(R.string.suzu_choose_downloaded_file))) { _, which ->
                        if (which == 0) SuzuAssets.start(asset) else pickGrammar.launch(arrayOf("*/*"))
                        render()
                    }.show()
        }
        render()
    }

    /** 从模型列表（从新到旧）里选一个，下载并换用 */
    private fun chooseModel() {
        val models = SuzuModels.list.value
        if (models.isEmpty()) return
        val installed = SuzuAssets.isInstalled(SuzuAsset.Model)
        val currentTitle = SuzuAssets.installedModelTitle()
        val current = models.indexOfFirst { installed && it.title == currentTitle }.takeIf { it >= 0 }
        requireContext().choiceSheet(
            icon = "chip",
            title = "选择 Suzu 模型",
            message = null,
            options = models.mapIndexed { i, m ->
                SheetOption(m.title, if (i == current) "正在使用" else "需下载 · 约 ${m.sizeMb} MB")
            },
            current = current,
            initial = models.indices.firstOrNull { it != current } ?: 0,
            confirmLabel = { "下载并使用" },
        ) { which ->
            SuzuAssets.start(SuzuAsset.Model, models[which])
            if (isAdded) render()
        }
    }

    private fun manageDownloaded() {
        val ctx = requireContext()
        val actions = buildList {
            if (SuzuAssets.isInstalled(SuzuAsset.Model)) {
                add(
                    SheetAction(
                        title = SuzuAssets.installedModelTitle() ?: "Suzu 模型",
                        subtitle = "正在使用",
                        leadingIcon = "chip",
                        trailingIcon = "delete_outline",
                        trailingOnly = true,
                    ) { confirmDelete(SuzuAsset.Model) },
                )
            }
            if (SuzuAssets.isInstalled(SuzuAsset.Grammar)) {
                add(
                    SheetAction(
                        title = "万象语法模型",
                        subtitle = SuzuAssets.installedGrammarDescription(ctx),
                        leadingIcon = "file_document_outline",
                        trailingIcon = "delete_outline",
                        trailingOnly = true,
                    ) { confirmDelete(SuzuAsset.Grammar) },
                )
            }
        }
        ctx.actionSheet("harddisk", "已下载的模型", actions, "完成", emptyText = "还没有下载模型。")
    }

    private fun confirmDelete(asset: SuzuAsset) {
        val ctx = requireContext()
        val name = if (asset == SuzuAsset.Model) SuzuAssets.installedModelTitle() ?: asset.title else "万象语法模型"
        materialDialog(ctx)
            .setTitle("删除$name？")
            .setMessage(
                if (asset == SuzuAsset.Model) {
                    "删除后关闭 AI 重排，仍可正常打字。之后可在此页重新下载。"
                } else {
                    "删除后，整句输入不再使用语法模型辅助选字，其他功能不受影响。之后可在此页重新下载。"
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("删除") { _, _ ->
                if (asset == SuzuAsset.Model) {
                    lifecycleScope.launch { viewModel.rime.runOnReady { setRuntimeOption("ai_rerank", false) } }
                }
                SuzuAssets.delete(asset)
                if (isAdded) render()
            }.showDestructive()
    }
}
