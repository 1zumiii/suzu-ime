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
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.MainViewModel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 模型页：下载、导入或删除 Suzu 模型和语法模型。文件在就算启用 */
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
    }

    private val assetPrefs = HashMap<SuzuAsset, AssetPreference>()

    private fun renderAsset(asset: SuzuAsset, pref: AssetPreference) {
        val state = SuzuAssets.state(asset)
        pref.percent = (state as? SuzuAssets.State.Downloading)?.percent
        pref.summary = when {
            state is SuzuAssets.State.Downloading -> "正在下载 ${state.percent}%（${state.doneMb} / ${state.totalMb} MB），点按取消"
            SuzuAssets.isInstalled(asset) -> "已安装，点按可删除并关闭此功能"
            state is SuzuAssets.State.Failed -> "下载失败：${state.message}\n点按重试"
            else -> "未安装，此功能已关闭。点按下载（约 ${asset.sizeMb} MB）"
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
            SuzuAssets.isInstalled(asset) ->
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
            asset == SuzuAsset.Grammar ->
                AlertDialog.Builder(requireContext())
                    .setTitle(asset.title)
                    .setItems(arrayOf("下载（约 ${asset.sizeMb} MB）", "选择已下载的文件")) { _, which ->
                        if (which == 0) SuzuAssets.start(asset) else pickGrammar.launch(arrayOf("*/*"))
                    }.show()
            else -> SuzuAssets.start(asset)
        }
        if (isAdded) build()
    }
}
