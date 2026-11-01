/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.os.Bundle
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

/** 词库页：列出可用的词库包，点一下切换。方案只有一份，各包只差词库，用户词典共用 */
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
            addCategory("说明") {
                isIconSpaceReserved = false
                addPreference(
                    "切换后会重新编译词库",
                    "手机上约 15 秒。所有词库包共用一份用户词典，切换不会丢失已学习的词。下载的包会留在手机上，切回来不用再下载。",
                )
            }
        }
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
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(PackInstaller.title(name))
            .setMessage("准备中")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = runCatching {
                viewModel.rime.runOnReady {
                    PackInstaller.switchTo(this, name) { text ->
                        lifecycleScope.launch(Dispatchers.Main) { dialog.setMessage(text) }
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
