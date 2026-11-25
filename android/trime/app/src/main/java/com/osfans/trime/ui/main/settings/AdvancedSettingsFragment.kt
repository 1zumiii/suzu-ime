/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.suzu.SuzuFeatures
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch

class AdvancedSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().advanced) {

    private val uiMode = AppPrefs.defaultInstance().advanced.uiMode

    private val showAppIcon = AppPrefs.defaultInstance().advanced.showAppIcon

    @Keep
    private val onUiModeChange = PreferenceDelegate.OnChangeListener<AppPrefs.Advanced.UiMode> { _, v ->
        val mode = when (v) {
            AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    @Keep
    private val onShowAppIconChange = PreferenceDelegate.OnChangeListener<Boolean> { _, v ->
        showAppIcon(requireContext(), v)
    }

    private val viewModel: MainViewModel by activityViewModels()

    // 诊断记录：用户目录里的 .enabled 文件。Lua 在部署时读取，所以改完重新部署一次
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addCategory("诊断记录") {
            for (d in SuzuFeatures.Diag.entries) {
                addPreference(
                    SwitchPreferenceCompat(context).apply {
                        isPersistent = false
                        isIconSpaceReserved = false
                        title = d.title
                        summary = d.summary + "。文件只在本机"
                        isChecked = SuzuFeatures.isOn(d)
                        setOnPreferenceChangeListener { pref, v ->
                            if (!SuzuFeatures.set(d, v as Boolean)) {
                                context.toast("无法修改${d.title}的开关文件")
                                return@setOnPreferenceChangeListener false
                            }
                            (pref as SwitchPreferenceCompat).isEnabled = false
                            lifecycleScope.launch {
                                runCatching { viewModel.rime.runOnReady { deploy() } }
                                    .onFailure { context.toast("重新部署失败，重启输入法后生效") }
                                pref.isEnabled = true
                            }
                            true
                        }
                    },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiMode.registerOnChangeListener(onUiModeChange)
        showAppIcon.registerOnChangeListener(onShowAppIconChange)
    }

    override fun onDestroy() {
        uiMode.unregisterOnChangeListener(onUiModeChange)
        showAppIcon.unregisterOnChangeListener(onShowAppIconChange)
        super.onDestroy()
    }

    companion object {
        private const val SETTINGS_ACTIVITY_NAME = "com.osfans.trime.MainLauncherAlias"

        fun showAppIcon(context: Context, enable: Boolean) {
            val state = if (enable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, SETTINGS_ACTIVITY_NAME),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
