/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.settingsSheet
import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.Logcat

class DeveloperFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        screen.addPreference(
            Preference(context).apply {
                setTitle(R.string.real_time_logs)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    AppUtils.launchLogActivity(context)
                    true
                }
            },
        )
        screen.addPreference(
            Preference(context).apply {
                setTitle(R.string.real_time_logs_clear)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    context.settingsSheet(
                        icon = "delete_outline",
                        title = getString(R.string.real_time_logs_clear),
                        message = getString(R.string.real_time_logs_confirm),
                        confirm = SheetButton(getString(R.string.ok)) {
                            Logcat.Companion.default.clearLog()
                        },
                        cancel = getString(R.string.cancel),
                        destructive = true,
                    ).show()
                    true
                }
            },
        )
        preferenceScreen = screen
    }

    override fun onResume() {
        super.onResume()
        viewModel.disableTopOptionsMenu()
    }
}
