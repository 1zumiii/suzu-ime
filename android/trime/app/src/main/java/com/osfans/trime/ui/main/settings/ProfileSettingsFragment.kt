/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.osfans.trime.R
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.prefs.SettingsBackup
import com.osfans.trime.suzu.RimeMigrationArchive
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

    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SettingsBackup.export(ctx, uri) } }
            ctx.toast(if (result.isSuccess) R.string.settings_export_success else R.string.settings_export_failure)
        }
    }

    private val exportRimeData = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            withLoadingDialog(ctx) {
                val result = withContext(Dispatchers.IO) { runCatching { RimeMigrationArchive.export(ctx, uri) } }
                ctx.toast(if (result.isSuccess) R.string.rime_export_success else R.string.rime_export_failure)
            }
        }
    }

    private val importSettings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SettingsBackup.read(ctx, uri) } }
            val settings = result.getOrNull()
            when {
                settings == null -> ctx.toast(R.string.settings_import_invalid)
                settings.isEmpty() -> ctx.toast(R.string.settings_import_empty)
                else -> showImportConfirmation(settings)
            }
        }
    }

    private fun showImportConfirmation(settings: Map<String, Any>) {
        val ctx = requireContext()
        ctx.settingsSheet(
            icon = "import",
            title = getString(R.string.settings_import),
            message = getString(R.string.settings_import_overwrite),
            cancel = getString(android.R.string.cancel),
            confirm = SheetButton(getString(R.string.settings_import_confirm)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        runCatching { SettingsBackup.apply(ctx, settings, replace = true) }.getOrDefault(false)
                    }
                    ctx.toast(if (imported) R.string.settings_import_success else R.string.settings_import_failure)
                    if (imported) activity?.recreate()
                }
            },
        ).show()
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.settings_backup) {
                isIconSpaceReserved = false
                addPreference(R.string.settings_export, R.string.settings_export_summary) {
                    exportSettings.launch("suzu-settings.json")
                }
                getPreference(preferenceCount - 1).mdiIcon("export")
                addPreference(R.string.settings_import) {
                    importSettings.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                getPreference(preferenceCount - 1).mdiIcon("import")
                addPreference(R.string.rime_export, R.string.rime_export_summary) {
                    exportRimeData.launch("suzu-rime-backup.zip")
                }
                getPreference(preferenceCount - 1).mdiIcon("archive_outline")
            }
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
