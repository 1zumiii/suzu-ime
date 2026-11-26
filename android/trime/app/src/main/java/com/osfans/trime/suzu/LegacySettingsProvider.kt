package com.osfans.trime.suzu

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.preference.PreferenceManager
import com.osfans.trime.data.prefs.SettingsBackup

internal const val LEGACY_SUZU_PACKAGE = "com.osfans.trime"
internal const val SUZU_PACKAGE = "izumiii.suzu.ime"
internal const val LEGACY_SETTINGS_AUTHORITY = "$LEGACY_SUZU_PACKAGE.suzu.legacy-settings"
private const val READ_SETTINGS = "read-settings"

/** Enabled only in a separately signed migration update to the legacy package. */
class LegacySettingsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = requireNotNull(context)
        val caller = callingPackage
        if (app.packageName != LEGACY_SUZU_PACKAGE || caller != SUZU_PACKAGE ||
            app.packageManager.checkSignatures(app.packageName, caller) != PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("Legacy settings require the signed Suzu app")
        }
        require(method == READ_SETTINGS)
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val values = SettingsBackup.values(prefs)
        return Bundle().apply {
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                }
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

@Suppress("DEPRECATION")
internal fun Context.importLegacySettings(): Boolean {
    if (packageManager.resolveContentProvider(LEGACY_SETTINGS_AUTHORITY, 0) == null) return false
    if (packageManager.checkSignatures(packageName, LEGACY_SUZU_PACKAGE) != PackageManager.SIGNATURE_MATCH) return false
    val uri = Uri.parse("content://$LEGACY_SETTINGS_AUTHORITY")
    val values = contentResolver.call(uri, READ_SETTINGS, null, null) ?: return false
    val settings = values.keySet().mapNotNull { key -> values[key]?.let { key to it } }.toMap()
    return SettingsBackup.apply(this, settings, replace = false)
}
