// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.R
import com.osfans.trime.data.soundeffect.SoundEffectManager
import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.pickSheet
import com.osfans.trime.ui.common.settingsSheet
import kotlinx.coroutines.launch

object SoundEffectPickerDialog {
    fun show(
        scope: LifecycleCoroutineScope,
        context: Context,
    ) {
        val all = SoundEffectManager.getAllSoundEffects().map { it.name }
        val current = SoundEffectManager.activeSoundEffect?.name ?: ""
        val currentIndex = all.indexOfFirst { it == current }
        with(context) {
            val title = getString(R.string.custom_sound_effect_name)
            if (all.isEmpty()) {
                settingsSheet(
                    icon = "volume_high",
                    title = title,
                    message = getString(R.string.no_effect_to_select),
                    confirm = SheetButton(getString(android.R.string.ok)),
                ).show()
            } else {
                pickSheet("volume_high", title, all, currentIndex) { which ->
                    if (which != currentIndex) scope.launch { SoundEffectManager.switchEffect(all[which]) }
                }
            }
        }
    }
}
