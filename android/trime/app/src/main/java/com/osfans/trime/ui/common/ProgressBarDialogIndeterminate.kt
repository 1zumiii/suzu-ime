/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.osfans.trime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

suspend fun withLoadingDialog(
    context: Context,
    @StringRes title: Int = R.string.loading,
    threshold: Long = 200L,
    action: suspend () -> Unit,
) {
    coroutineScope {
        var loadingDialog: BottomSheetDialog? = null
        val loadingJob =
            launch {
                delay(threshold)
                withContext(Dispatchers.Main) {
                    loadingDialog = context.settingsSheet(
                        icon = "sync",
                        title = context.getString(title),
                        body = LinearProgressIndicator(context).apply { isIndeterminate = true },
                        cancelable = false,
                    ).apply { show() }
                }
            }
        try {
            action()
        } finally {
            withContext(NonCancellable) {
                loadingJob.cancelAndJoin()
                withContext(Dispatchers.Main) {
                    loadingDialog?.dismiss()
                }
            }
        }
    }
}
