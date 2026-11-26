/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment

class KeyboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().keyboard) {

    override fun headerPreference(screen: PreferenceScreen): Preference = KeyboardPreviewPreference(screen.context)

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<Preference>("custom_sound_effect_name")?.apply {
            setOnPreferenceClickListener {
                SoundEffectPickerDialog.show(viewLifecycleOwner.lifecycleScope, requireContext())
                true
            }
        }
    }
}
