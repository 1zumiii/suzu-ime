/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.PreferenceManager
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.ui.common.materialDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 检查 GitHub 上 suzu-ime 的 android-v* Release，有新版本时在应用内下载并调起系统安装器。
 * 只在设置 App 里运行，键盘本身不联网。芯片支持 i8mm 时下 -i8mm 版，否则下通用版。
 * 安装前核对 sha256（GitHub 给出的资产摘要）和签名证书（必须与已装版本相同）。
 */
object AppUpdater {
    private const val RELEASES_API = "https://api.github.com/repos/1zumiii/suzu-ime/releases?per_page=20"
    const val RELEASES_PAGE = "https://github.com/1zumiii/suzu-ime/releases"
    private const val TAG_PREFIX = "android-v"
    private const val MIGRATION_TAG_PREFIX = "suzu-android-v"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    const val PREF_AUTO_CHECK = "suzu_update_auto_check"
    private const val PREF_LAST_CHECK = "suzu_update_last_check"
    private const val PREF_SKIPPED = "suzu_update_skipped_version"

    class Release(val version: String, val notes: String, val page: String, val apk: RemoteFile, val requiresMigration: Boolean)

    sealed interface Result {
        class Available(val release: Release) : Result
        data object UpToDate : Result
        class Failed(val message: String) : Result
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun autoCheckEnabled(context: Context) = prefs(context).getBoolean(PREF_AUTO_CHECK, true)

    /** 读 /proc/cpuinfo 的 Features；i8mm 版在不支持的芯片上会直接崩溃，读不到就当不支持 */
    fun supportsI8mm(): Boolean =
        runCatching {
            File("/proc/cpuinfo").readLines().any { it.startsWith("Features") && " i8mm" in "$it " }
        }.getOrDefault(false)

    /** 比较 "0.3.1" 这样的版本号，忽略 -rc 之类的后缀 */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    suspend fun check(context: Context): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                prefs(context).edit { putLong(PREF_LAST_CHECK, System.currentTimeMillis()) }
                val latest = fetchLatest(supportsI8mm())
                if (latest != null && compareVersions(latest.version, currentVersion) > 0) {
                    Result.Available(latest)
                } else {
                    Result.UpToDate
                }
            }.getOrElse {
                Timber.w(it, "update check failed")
                Result.Failed(it.message ?: it.javaClass.simpleName)
            }
        }

    private fun fetchLatest(i8mm: Boolean): Release? {
        val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val body = try {
            val code = conn.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return parseReleases(body, i8mm)
            .maxWithOrNull { x, y -> compareVersions(x.version, y.version) }
    }

    /** 旧包名更新可覆盖安装，新包名更新须从发布页迁移。 */
    fun parseReleases(json: String, i8mm: Boolean): List<Release> =
        (Json.parseToJsonElement(json) as JsonArray).mapNotNull { el ->
            val r = el.jsonObject
            val tag = r.str("tag_name") ?: return@mapNotNull null
            val prefix = when {
                tag.startsWith(TAG_PREFIX) -> TAG_PREFIX
                tag.startsWith(MIGRATION_TAG_PREFIX) -> MIGRATION_TAG_PREFIX
                else -> return@mapNotNull null
            }
            if (r["draft"]?.jsonPrimitive?.boolean == true || r["prerelease"]?.jsonPrimitive?.boolean == true) {
                return@mapNotNull null
            }
            val apks = r["assets"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { a ->
                a.str("name")?.endsWith(".apk") == true
            }
            val wanted = apks.firstOrNull { (it.str("name") ?: "").endsWith("-i8mm.apk") == i8mm }
                ?: return@mapNotNull null
            val digest = wanted.str("digest")?.removePrefix("sha256:") ?: return@mapNotNull null
            Release(
                version = tag.removePrefix(prefix),
                notes = r.str("body").orEmpty(),
                page = r.str("html_url") ?: RELEASES_PAGE,
                requiresMigration = prefix == MIGRATION_TAG_PREFIX,
                apk = RemoteFile(
                    name = wanted.str("name")!!,
                    url = wanted.str("browser_download_url") ?: return@mapNotNull null,
                    sha256 = digest,
                    size = wanted["size"]?.jsonPrimitive?.long ?: 0L,
                ),
            )
        }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    /** 打开设置 App 时调用：开着自动检查、距上次超过一天才联网，有新版本且没被跳过才弹窗 */
    fun autoCheck(activity: Activity, scope: LifecycleCoroutineScope) {
        val p = prefs(activity)
        if (!autoCheckEnabled(activity)) return
        if (System.currentTimeMillis() - p.getLong(PREF_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        scope.launch {
            val result = check(activity)
            if (result is Result.Available &&
                p.getString(PREF_SKIPPED, null) != result.release.version &&
                !activity.isFinishing
            ) {
                showAvailable(activity, scope, result.release, allowSkip = true)
            }
        }
    }

    /** 关于页的“检查更新”：结果总要告诉用户 */
    fun manualCheck(activity: Activity, scope: LifecycleCoroutineScope) {
        scope.launch {
            val dialog = ProgressDialog(activity, activity.getString(R.string.suzu_update_checking))
            dialog.update(activity.getString(R.string.suzu_update_checking), null)
            val result = check(activity)
            dialog.dismiss()
            if (activity.isFinishing) return@launch
            when (result) {
                is Result.Available -> showAvailable(activity, scope, result.release, allowSkip = false)
                Result.UpToDate ->
                    materialDialog(activity)
                        .setTitle(R.string.suzu_update_up_to_date)
                        .setMessage(activity.getString(R.string.suzu_update_current_version, currentVersion))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                is Result.Failed ->
                    materialDialog(activity)
                        .setTitle(R.string.suzu_update_check_failed)
                        .setMessage(activity.getString(R.string.suzu_update_check_failed_message, result.message))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.suzu_update_open_page) { _, _ -> openPage(activity, RELEASES_PAGE) }
                        .show()
            }
        }
    }

    private fun showAvailable(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        release: Release,
        allowSkip: Boolean,
    ) {
        if (release.requiresMigration) {
            materialDialog(activity)
                .setTitle(activity.getString(R.string.suzu_update_available, release.version))
                .setMessage(R.string.suzu_update_migration_message)
                .setPositiveButton(R.string.suzu_update_open_page) { _, _ -> openPage(activity, release.page) }
                .setNegativeButton(R.string.suzu_update_later, null)
                .show()
            return
        }
        val sizeMb = "%.1f".format(release.apk.size / 1048576.0)
        val notes = plainNotes(release.notes).let { if (it.length > 1200) it.take(1200) + "…" else it }
        val message = activity.getString(
            R.string.suzu_update_available_message,
            currentVersion,
            release.version,
            release.apk.name,
            sizeMb,
        ) + if (notes.isNotEmpty()) "\n\n$notes" else ""
        materialDialog(activity)
            .setTitle(activity.getString(R.string.suzu_update_available, release.version))
            .setMessage(message)
            .setPositiveButton(R.string.suzu_update_install) { _, _ -> downloadAndInstall(activity, scope, release) }
            .setNegativeButton(R.string.suzu_update_later, null)
            .apply {
                if (allowSkip) {
                    setNeutralButton(R.string.suzu_update_skip) { _, _ ->
                        prefs(activity).edit { putString(PREF_SKIPPED, release.version) }
                    }
                } else {
                    setNeutralButton(R.string.suzu_update_open_page) { _, _ -> openPage(activity, release.page) }
                }
            }.show()
    }

    private fun downloadAndInstall(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        release: Release,
    ) {
        if (!canInstall(activity)) {
            materialDialog(activity)
                .setTitle(R.string.suzu_update_need_permission)
                .setMessage(R.string.suzu_update_need_permission_message)
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                        )
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        // 只留这一次的安装包，旧的删掉
        dir.listFiles()?.filter { it.name != release.apk.name && it.name != release.apk.name + ".part" }?.forEach { it.delete() }
        val target = File(dir, release.apk.name)
        val dialog = ProgressDialog(activity, activity.getString(R.string.suzu_update_downloading, release.version))
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (!target.isFile || sha256(target) != release.apk.sha256) {
                        target.delete()
                        SuzuAssets.download(release.apk, target) { done ->
                            val pct = if (release.apk.size > 0) (done * 100 / release.apk.size).toInt() else null
                            scope.launch(Dispatchers.Main) {
                                dialog.update(activity.getString(R.string.suzu_update_downloading_progress), pct)
                            }
                        }
                    }
                    check(sameSigner(activity, target)) { activity.getString(R.string.suzu_update_signature_mismatch) }
                }
            }
            dialog.dismiss()
            result.onSuccess { install(activity, target) }.onFailure {
                Timber.w(it, "update download failed")
                if (activity.isFinishing) return@onFailure
                materialDialog(activity)
                    .setTitle(R.string.suzu_update_download_failed)
                    .setMessage(it.message ?: it.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.suzu_update_open_page) { _, _ -> openPage(activity, release.page) }
                    .show()
            }
        }
    }

    private fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo?): Set<String> {
        info ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    /** 下载的 APK 必须和已装的用同一把密钥签名，否则系统也会拒绝覆盖安装 */
    @Suppress("DEPRECATION")
    private fun sameSigner(context: Context, apk: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val pm = context.packageManager
        val installed = signatures(pm.getPackageInfo(context.packageName, flags))
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
        if (archive?.packageName != context.packageName) return false
        val downloaded = signatures(archive)
        return installed.isNotEmpty() && downloaded.isNotEmpty() && downloaded.any { it in installed }
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 发布说明是 Markdown，对话框里只显示纯文字：链接只留文字，去掉标题井号、加粗与行内代码记号 */
    fun plainNotes(md: String): String =
        md.lines().joinToString("\n") { line ->
            line.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace("**", "")
                .replace("`", "")
                .replace(Regex("^\\s*[-*]\\s+"), "• ")
        }.replace(Regex("\n{3,}"), "\n\n").trim()

    private fun openPage(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
