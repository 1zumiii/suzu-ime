package com.osfans.trime.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.preference.PreferenceManager
import com.osfans.trime.suzu.AppUpdater
import com.osfans.trime.suzu.SuzuFeatures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream

internal object SettingsBackup {
    private const val FORMAT_VERSION = 1
    private const val MAX_BYTES = 256 * 1024

    private data class Setting(val default: Any, val intRange: IntRange? = null)

    private fun settings(prefs: SharedPreferences): Map<String, Setting> = buildMap {
        val groups = listOf(
            AppPrefs.General(prefs),
            AppPrefs.Keyboard(prefs),
            AppPrefs.Clipboard(prefs),
            AppPrefs.Advanced(prefs),
        )
        for (group in groups) {
            val ui = group.preferenceDelegatesUi.associateBy { it.key }
            group.preferenceDelegates.forEach { (key, delegate) ->
                val range = when (val preference = ui[key]) {
                    is PreferenceDelegateUi.EditTextInt -> preference.min..preference.max
                    is PreferenceDelegateUi.SeekBarInt -> preference.min..preference.max
                    else -> null
                }
                put(key, Setting(delegate.defaultValue, range))
            }
        }
        // An imported launcher preference must not hide the new installation.
        remove(AppPrefs.Advanced.SHOW_APP_ICON)
        put(AppUpdater.PREF_AUTO_CHECK, Setting(true))
        put(SuzuFeatures.AI_MASTER, Setting(true))
        SuzuFeatures.Feature.entries.forEach { put(it.key, Setting(true)) }
        put(SuzuFeatures.FIX_PAUSE_KEY, Setting(SuzuFeatures.FIX_PAUSE_DEFAULT, SuzuFeatures.FIX_PAUSE_MIN..SuzuFeatures.FIX_PAUSE_MAX))
    }

    fun values(prefs: SharedPreferences, includeDefaults: Boolean = false): Map<String, Any> {
        val stored = prefs.all
        return settings(prefs).mapNotNull { (key, setting) ->
            val value = stored[key]?.toPrimitive()?.let { setting.read(it) }
            val default = setting.default
            (value ?: (if (includeDefaults) (default as? Enum<*>)?.name ?: default else null))
                ?.let { key to it }
        }.toMap()
    }

    fun export(context: Context, uri: Uri) {
        val settings = values(PreferenceManager.getDefaultSharedPreferences(context), includeDefaults = true)
            .mapValues { (_, value) -> requireNotNull(value.toPrimitive()) }
        val backup = JsonObject(mapOf("version" to JsonPrimitive(FORMAT_VERSION), "settings" to JsonObject(settings)))
        val bytes = backup.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
    }

    fun read(context: Context, uri: Uri): Map<String, Any> {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val backup = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Invalid Suzu settings backup")
        val version = backup["version"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == FORMAT_VERSION)
        val values = backup["settings"] as? JsonObject ?: throw IllegalArgumentException("Missing Suzu settings")
        val rules = settings(PreferenceManager.getDefaultSharedPreferences(context))
        return values.mapNotNull { (key, value) ->
            val rule = rules[key] ?: return@mapNotNull null
            key to requireNotNull((value as? JsonPrimitive)?.let { rule.read(it) })
        }.toMap()
    }

    fun apply(context: Context, values: Map<String, Any>, replace: Boolean): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        val rules = settings(prefs)
        for ((key, raw) in values) {
            if (!replace && prefs.contains(key)) continue
            val rule = rules[key] ?: continue
            val value = raw.toPrimitive()?.let { rule.read(it) } ?: continue
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
            }
        }
        if (!editor.commit()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) AppPrefs.defaultInstance().syncToDeviceEncryptedStorage()
        return true
    }

    private fun Any.toPrimitive(): JsonPrimitive? = when (this) {
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        else -> null
    }

    private fun Setting.read(value: JsonPrimitive): Any? = when (default) {
        is Boolean -> value.booleanOrNull?.takeUnless { value.isString }
        is Int -> value.intOrNull?.takeIf { !value.isString && (intRange == null || it in intRange) }
        is Enum<*> -> value.content.takeIf { name ->
            value.isString && default.declaringJavaClass.enumConstants.orEmpty().any { it.name == name }
        }
        is String -> value.content.takeIf { value.isString }
        else -> null
    }
}
