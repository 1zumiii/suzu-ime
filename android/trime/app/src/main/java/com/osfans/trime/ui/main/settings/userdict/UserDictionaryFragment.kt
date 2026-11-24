/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.userdict

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import com.osfans.trime.R
import com.osfans.trime.data.userdict.UserDictManager
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.SheetAction
import com.osfans.trime.ui.common.actionSheet
import com.osfans.trime.ui.common.mdiIcon
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.importErrorDialog
import com.osfans.trime.util.toast
import io.github.whiredplanck.storageaccess.StorageAccess
import io.github.whiredplanck.storageaccess.StorageDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用户词典：列出本机的用户词典，点按从底部弹出备份、导入、导出；“添加词典”从备份文件恢复一份词典 */
class UserDictionaryFragment : PaddingPreferenceFragment() {
    private val storageAccess = StorageAccess(this)

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        build()
    }

    private fun build() {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.user_dict_local) {
                for (name in UserDictManager.getUserDictList()) {
                    addPreference(
                        Preference(ctx).apply {
                            title = name
                            mdiIcon("book_open_outline")
                            widgetLayoutResource = R.layout.preference_widget_more
                            setOnPreferenceClickListener {
                                showActions(name)
                                true
                            }
                        },
                    )
                }
                addPreference(
                    Preference(ctx).apply {
                        setTitle(R.string.user_dict_add)
                        mdiIcon("plus")
                        setOnPreferenceClickListener {
                            lifecycleScope.launch {
                                val picked = storageAccess.pickFile(mimeTypes = arrayOf("text/plain")) ?: return@launch
                                importFromDoc(picked, null)
                            }
                            true
                        }
                    },
                )
            }
        }
    }

    private fun showSnackBar(text: String) {
        view?.let { Snackbar.make(it, text, Snackbar.LENGTH_SHORT).show() } ?: context?.toast(text)
    }

    private fun showActions(dictName: String) {
        val ctx = requireContext()
        ctx.actionSheet(
            icon = "book_open_outline",
            title = dictName,
            actions = listOf(
                SheetAction(getString(R.string.backup), trailingIcon = "archive_outline") { backup(dictName) },
                SheetAction(getString(R.string.import_), trailingIcon = "file_import_outline") {
                    lifecycleScope.launch {
                        val picked = storageAccess.pickFile(mimeTypes = arrayOf("text/plain")) ?: return@launch
                        importFromDoc(picked, dictName)
                    }
                },
                SheetAction(getString(R.string.export), trailingIcon = "file_export_outline") { export(dictName) },
            ),
            closeLabel = getString(R.string.user_dict_close),
        )
    }

    private fun backup(dictName: String) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) { UserDictManager.backupUserDict(dictName) }
            if (success) showSnackBar(getString(R.string.backed_up_x_to_sync_dir, dictName))
        }
    }

    private fun export(dictName: String) {
        val ctx = requireContext()
        lifecycleScope.launch {
            try {
                val doc = storageAccess.createFile(filename = "$dictName.txt", mimeType = "text/plain") ?: return@launch
                val count = withContext(Dispatchers.IO) {
                    StorageAccess.writeFile(doc.uri) {
                        it.buffered().use { outs -> UserDictManager.exportUserDict(outs, dictName, doc.name) }
                    }
                }.getOrThrow()
                showSnackBar(ctx.getString(R.string.exported_n_entries, count))
            } catch (e: Throwable) {
                ctx.toast(e)
            }
        }
    }

    /** [dictName] 为 null 时把文件当作备份恢复成一份词典（添加词典），否则导入到这份词典 */
    private fun importFromDoc(
        doc: StorageDocument,
        dictName: String?,
    ) {
        val ctx = requireContext()
        lifecycleScope.launch {
            try {
                if (dictName == null) {
                    val result = StorageAccess.readFile(doc.uri) { ins -> UserDictManager.restoreUserDict(ins, doc.name) }
                    if (result.isSuccess) {
                        showSnackBar(ctx.getString(R.string.restored_from_x, doc.name))
                        if (isAdded) build()
                    }
                } else {
                    val count = StorageAccess.readFile(doc.uri) { ins ->
                        UserDictManager.importUserDict(ins, dictName, doc.name).getOrThrow()
                    }
                    showSnackBar(ctx.getString(R.string.import_n_entries, count))
                }
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            }
        }
    }
}
