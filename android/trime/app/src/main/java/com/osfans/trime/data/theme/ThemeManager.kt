/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.appContext

/** 主题固定为 [BuiltinTheme]，这里只负责把它接到配色管理上 */
object ThemeManager {
    val activeTheme: Theme get() = BuiltinTheme.theme

    val prefs = AppPrefs.defaultInstance().registerProvider(::ThemePrefs)

    fun init(configuration: Configuration) {
        if (ColorManager.currentScope() == null) ColorManager.attachTheme(activeTheme)
        ColorManager.init(configuration)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.preferenceDelegates.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }
}
