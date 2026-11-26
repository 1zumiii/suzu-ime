/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.content.Context
import com.osfans.trime.R
import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.settingsSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Context.importErrorDialog(message: String) {
    withContext(Dispatchers.Main.immediate) {
        settingsSheet(
            icon = "alert_circle_outline",
            title = getString(R.string.import_error),
            message = message,
            confirm = SheetButton(getString(android.R.string.ok)),
        ).show()
    }
}

suspend fun Context.importErrorDialog(t: Throwable) {
    importErrorDialog(t.localizedMessage ?: t.stackTraceToString())
}
