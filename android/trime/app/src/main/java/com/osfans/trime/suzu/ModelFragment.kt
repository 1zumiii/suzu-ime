/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 模型页：下载、换用、导入或删除 Suzu 模型和语法模型。文件在就算启用 */
class ModelFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private val pickGrammar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(SuzuAssets::importGrammar)
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        build()
        // 下载进度变化时只更新这两行，不重建页面
        lifecycleScope.launch {
            SuzuAssets.states.drop(1).collect {
                if (isAdded) assetPrefs.forEach { (asset, pref) -> renderAsset(asset, pref) }
            }
        }
        // 先拉最新的模型名录，点开列表时就是新的
        lifecycleScope.launch(Dispatchers.IO) { SuzuModels.refresh() }
    }

    private val assetPrefs = HashMap<SuzuAsset, AssetPreference>()

    private fun renderAsset(asset: SuzuAsset, pref: AssetPreference) {
        val state = SuzuAssets.state(asset)
        pref.percent = (state as? SuzuAssets.State.Downloading)?.percent
        pref.summary = when {
            state is SuzuAssets.State.Downloading ->
                "正在下载${state.model?.let { " ${it.title}" }.orEmpty()} ${state.percent}%（${state.doneMb} / ${state.totalMb} MB），点按取消"
            asset == SuzuAsset.Grammar && SuzuAssets.isInstalled(asset) ->
                getString(R.string.suzu_grammar_installed, SuzuAssets.installedGrammarDescription(requireContext()))
            asset == SuzuAsset.Model && SuzuAssets.isInstalled(asset) ->
                "已安装 ${SuzuAssets.installedModelTitle() ?: "Suzu 模型"}，点按可换用其他模型或删除"
            SuzuAssets.isInstalled(asset) -> "已安装，点按可删除并关闭此功能"
            state is SuzuAssets.State.Failed -> "下载失败：${state.message}\n点按重试"
            asset == SuzuAsset.Grammar -> getString(R.string.suzu_grammar_available, asset.sizeMb)
            else -> "未安装，此功能已关闭。点按选择模型并下载"
        }
    }

    private fun build() {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            for (asset in SuzuAsset.entries) {
                val pref = AssetPreference(ctx).apply {
                    title = asset.title
                    isSingleLineTitle = false
                    setOnPreferenceClickListener {
                        onAssetClick(asset)
                        true
                    }
                }
                addPreference(pref)
                assetPrefs[asset] = pref
                renderAsset(asset, pref)
            }
        }
    }

    private fun onAssetClick(asset: SuzuAsset) {
        when {
            SuzuAssets.state(asset) is SuzuAssets.State.Downloading -> SuzuAssets.cancel(asset)
            asset == SuzuAsset.Model -> chooseModel()
            SuzuAssets.isInstalled(asset) -> confirmDelete(asset)
            asset == SuzuAsset.Grammar ->
                AlertDialog.Builder(requireContext())
                    .setTitle(asset.title)
                    .setItems(arrayOf(getString(R.string.suzu_grammar_download_lts, asset.sizeMb), getString(R.string.suzu_choose_downloaded_file))) { _, which ->
                        if (which == 0) SuzuAssets.start(asset) else pickGrammar.launch(arrayOf("*/*"))
                    }.show()
            else -> SuzuAssets.start(asset)
        }
        if (isAdded) build()
    }

    /** 列出名录里的模型（从新到旧），点按即下载并换用。已装模型时末尾加“删除模型” */
    private fun chooseModel() {
        val models = SuzuModels.list.value
        val installed = SuzuAssets.isInstalled(SuzuAsset.Model)
        val current = SuzuAssets.installedModelTitle()
        val items = models.map { m ->
            if (installed && m.title == current) "${m.title}（当前）" else "${m.title}（约 ${m.sizeMb} MB）"
        } + if (installed) listOf("删除模型") else emptyList()
        AlertDialog.Builder(requireContext())
            .setTitle(if (installed) "换用模型" else "下载模型")
            .setItems(items.toTypedArray()) { _, which ->
                val m = models.getOrNull(which)
                when {
                    m == null -> confirmDelete(SuzuAsset.Model)
                    installed && m.title == current -> Unit
                    else -> SuzuAssets.start(SuzuAsset.Model, m)
                }
                if (isAdded) build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(asset: SuzuAsset) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除${asset.title}？")
            .setMessage(
                if (asset == SuzuAsset.Model) {
                    "删除后关闭 AI 重排，仍可正常打字。之后可在此页重新下载。"
                } else {
                    "删除后，整句输入不再使用语法模型辅助选字，其他功能不受影响。之后可在此页重新下载。"
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (asset == SuzuAsset.Model) {
                    lifecycleScope.launch { viewModel.rime.runOnReady { setRuntimeOption("ai_rerank", false) } }
                }
                SuzuAssets.delete(asset)
                build()
            }.show()
    }
}
