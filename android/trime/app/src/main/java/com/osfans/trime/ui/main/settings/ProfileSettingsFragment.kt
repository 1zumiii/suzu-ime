/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import android.widget.LinearLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.osfans.trime.R
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.mdiIcon
import com.osfans.trime.ui.common.settingsSheet
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.ResourceUtils
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileSettingsFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.synchronization) {
                isIconSpaceReserved = false
                addPreference(R.string.sync_user_data_immediately) {
                    lifecycleScope.launch {
                        withLoadingDialog(ctx) {
                            runCatching {
                                viewModel.rime.runOnReady { syncUserData() }
                            }.onSuccess { success ->
                                ctx.toast(if (success) R.string.sync_user_data_success else R.string.sync_user_data_failure)
                            }.onFailure {
                                ctx.toast(R.string.sync_user_data_failure)
                            }
                        }
                    }
                }
                getPreference(preferenceCount - 1).mdiIcon("sync")
            }
            addCategory(R.string.maintenance) {
                isIconSpaceReserved = false
                addPreference(R.string.reset, R.string.reset_hint) {
                    val items = ctx.assets.list("shared") ?: return@addPreference
                    val choices = items.map { item -> MaterialCheckBox(ctx).apply { text = item } }
                    ctx.settingsSheet(
                        icon = "restore",
                        title = getString(R.string.reset),
                        body = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            choices.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
                        },
                        cancel = getString(android.R.string.cancel),
                        confirm = SheetButton(getString(android.R.string.ok)) {
                            val selected = items.filterIndexed { index, _ -> choices[index].isChecked }
                            lifecycleScope.launch {
                                var res = true
                                withLoadingDialog(ctx) {
                                    withContext(Dispatchers.IO) {
                                        res =
                                            selected
                                                .fold(true) { acc, asset ->
                                                    val destPath =
                                                        DataManager.sharedDataDir.resolve(asset).absolutePath
                                                    ResourceUtils
                                                        .copyFile("shared/$asset", destPath)
                                                        .fold({ acc and true }, { acc and false })
                                                }
                                    }
                                }
                                ctx.toast((if (res) R.string.reset_success else R.string.reset_failure))
                            }
                        },
                    ).show()
                }
                getPreference(preferenceCount - 1).mdiIcon("restore")
            }
        }
    }
}
