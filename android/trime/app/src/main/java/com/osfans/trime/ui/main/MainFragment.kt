/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.ui.common.FullBleed
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.mdiIcon
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
    }

    private fun PreferenceGroup.addDestinationPreference(
        @StringRes title: Int,
        icon: String,
        route: NavigationRoute,
    ) {
        addPreference(title) {
            findNavController().navigateWithAnim(route)
        }
        getPreference(preferenceCount - 1).mdiIcon(icon)
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addPreference(
                object : Preference(ctx), FullBleed {}.apply {
                    layoutResource = R.layout.preference_app_header
                    title = getString(R.string.trime_app_name)
                    summary = getString(R.string.trime_app_slogan)
                    isSelectable = false
                },
            )
            addCategory(R.string.main_section_resources) {
                addDestinationPreference(R.string.dict_packs, "bookshelf", NavigationRoute.DictPacks)
                addDestinationPreference(R.string.models, "chip", NavigationRoute.Models)
                addDestinationPreference(R.string.user_dictionary, "account_box_outline", NavigationRoute.UserDict)
                addDestinationPreference(R.string.emoji_custom, "emoticon_outline", NavigationRoute.EmojiCustom)
                addDestinationPreference(R.string.profile, "folder_cog_outline", NavigationRoute.Profile)
            }
            addCategory(R.string.main_section_preferences) {
                addDestinationPreference(R.string.suzu_features, "robot_outline", NavigationRoute.Features)
                addDestinationPreference(R.string.general, "tune_variant", NavigationRoute.General)
                addDestinationPreference(R.string.virtual_keyboard, "keyboard_outline", NavigationRoute.VirtualKeyboard)
                addDestinationPreference(R.string.clipboard, "clipboard_text_outline", NavigationRoute.Clipboard)
                addDestinationPreference(R.string.advanced, "cog_outline", NavigationRoute.Advanced)
            }
        }
    }
}
