package com.osfans.trime.suzu

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.osfans.trime.data.prefs.SettingsBackup

internal const val LEGACY_SUZU_PACKAGE = "com.osfans.trime"
internal const val SUZU_PACKAGE = "izumiii.suzu.ime"
internal const val LEGACY_SETTINGS_AUTHORITY = "$LEGACY_SUZU_PACKAGE.suzu.legacy-settings"
private const val READ_SETTINGS = "read-settings"

@Suppress("DEPRECATION")
internal fun Context.importLegacySettings(replace: Boolean = false): Boolean {
    if (packageManager.resolveContentProvider(LEGACY_SETTINGS_AUTHORITY, 0) == null) return false
    if (packageManager.checkSignatures(packageName, LEGACY_SUZU_PACKAGE) != PackageManager.SIGNATURE_MATCH) return false
    val uri = Uri.parse("content://$LEGACY_SETTINGS_AUTHORITY")
    val values = contentResolver.call(uri, READ_SETTINGS, null, null) ?: return false
    val settings = values.keySet().mapNotNull { key -> values[key]?.let { key to it } }.toMap()
    return SettingsBackup.apply(this, settings, replace = replace)
}
