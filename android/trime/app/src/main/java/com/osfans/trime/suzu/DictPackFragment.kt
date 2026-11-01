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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 词库页：切换词库包，开关附加词库，管理模型下载。方案只有一份，各包只差词库，用户词典共用 */
class DictPackFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private val pickGrammar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(SuzuAssets::importGrammar)
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        build()
        // 下载进度变化时刷新
        lifecycleScope.launch {
            SuzuAssets.states.drop(1).collect {
                // 只更新模型那两行，不重建页面，否则滚动位置会跳回顶部
                if (isAdded) assetPrefs.forEach { (asset, pref) -> renderAsset(asset, pref) }
            }
        }
    }

    private val assetPrefs = HashMap<SuzuAsset, AssetPreference>()

    private fun renderAsset(asset: SuzuAsset, pref: AssetPreference) {
        val state = SuzuAssets.state(asset)
        pref.percent = (state as? SuzuAssets.State.Downloading)?.percent
        pref.summary = when {
            state is SuzuAssets.State.Downloading -> "${state.percent}%（${state.doneMb} / ${state.totalMb} MB），点按取消"
            SuzuAssets.isInstalled(asset) -> "已安装，点按删除并关闭这项功能"
            state is SuzuAssets.State.Failed -> "下载失败：${state.message}。点按重试"
            else -> "未安装，这项功能已关闭。点按下载约 ${asset.sizeMb} MB"
        }
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
            addCategory("模型") {
                isIconSpaceReserved = false
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
            addCategory("说明") {
                isIconSpaceReserved = false
                addPreference(
                    "切换后会重新编译词库",
                    "手机上约 15 秒。所有词库包共用一份用户词典，切换不会丢失已学习的词。下载的包会留在手机上，切回来不用再下载。",
                )
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
                            "AI 重排会关闭，打字不受影响。之后可以在这里重新下载。"
                        } else {
                            "整句输入的同音字选择会变差，其他功能不受影响。之后可以在这里重新下载。"
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

    private fun confirmAddon(addon: DictAddon, enable: Boolean) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(if (enable) "启用${addon.title}词库？" else "关闭${addon.title}词库？")
            .setMessage("需要重新编译词库，可能需要一分钟，期间键盘暂时不能打字。AI 的简拼补全要等下次启动 AI 服务后才会更新。")
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
                                .setTitle("没有成功")
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
        val download = if (installed || entry == null) "" else "需要先下载约 ${(entry.size + 524288) / 1048576} MB，"
        AlertDialog.Builder(requireContext())
            .setTitle("切换到$title？")
            .setMessage("${download}然后重新编译词库，约 15 秒。期间键盘暂时不能打字。")
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
                            .setTitle("没有切换成功")
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
