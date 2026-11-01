/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Parcelable
import androidx.navigation.NavController
import androidx.navigation.createGraph
import androidx.navigation.fragment.fragment
import com.osfans.trime.R
import com.osfans.trime.ui.main.settings.AdvancedSettingsFragment
import com.osfans.trime.ui.main.settings.ClipboardSettingsFragment
import com.osfans.trime.ui.main.settings.GeneralSettingsFragment
import com.osfans.trime.ui.main.settings.KeyboardSettingsFragment
import com.osfans.trime.ui.main.settings.ProfileSettingsFragment
import com.osfans.trime.suzu.DictPackFragment
import com.osfans.trime.suzu.ModelFragment
import com.osfans.trime.ui.main.settings.userdict.UserDictionaryFragment
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
sealed class NavigationRoute : Parcelable {

    @Serializable
    data object Main : NavigationRoute()

    @Serializable
    data object DictPacks : NavigationRoute()

    @Serializable
    data object Models : NavigationRoute()

    @Serializable
    data object UserDict : NavigationRoute()

    @Serializable
    data object Profile : NavigationRoute()

    @Serializable
    data object General : NavigationRoute()

    @Serializable
    data object VirtualKeyboard : NavigationRoute()

    @Serializable
    data object Clipboard : NavigationRoute()

    @Serializable
    data object Advanced : NavigationRoute()

    @Serializable
    data object Developer : NavigationRoute()

    @Serializable
    data object About : NavigationRoute()

    @Serializable
    data object License : NavigationRoute()

    companion object {
        fun createGraph(controller: NavController) = controller.createGraph(Main) {
            val ctx = controller.context

            fragment<MainFragment, Main> {
                label = ctx.getString(R.string.trime_app_name)
            }

            fragment<DictPackFragment, DictPacks> {
                label = ctx.getString(R.string.dict_packs)
            }
            fragment<ModelFragment, Models> {
                label = ctx.getString(R.string.models)
            }
            fragment<UserDictionaryFragment, UserDict> {
                label = ctx.getString(R.string.user_dictionary)
            }
            fragment<ProfileSettingsFragment, Profile> {
                label = ctx.getString(R.string.profile)
            }

            fragment<GeneralSettingsFragment, General> {
                label = ctx.getString(R.string.general)
            }
            fragment<KeyboardSettingsFragment, VirtualKeyboard> {
                label = ctx.getString(R.string.virtual_keyboard)
            }
            fragment<ClipboardSettingsFragment, Clipboard> {
                label = ctx.getString(R.string.clipboard)
            }
            fragment<AdvancedSettingsFragment, Advanced> {
                label = ctx.getString(R.string.advanced)
            }
            fragment<DeveloperFragment, Developer> {
                label = ctx.getString(R.string.developer)
            }
            fragment<AboutFragment, About> {
                label = ctx.getString(R.string.about)
            }
            fragment<LicenseFragment, License> {
                label = ctx.getString(R.string.license)
            }
        }
    }
}
