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
import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.settingsSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * 检查 GitHub 上 suzu-ime 的 Suzu-ime-v* Release，有新版本时在应用内下载并调起系统安装器。
 * 只在设置 App 里运行，键盘本身不联网。芯片支持 i8mm 时下 -i8mm 版，否则下通用版。
 * 安装前核对 sha256（GitHub 给出的资产摘要）和签名证书（必须与已装版本相同）。
 */
object AppUpdater {
    private const val RELEASES_API = "https://api.github.com/repos/1zumiii/suzu-ime/releases?per_page=20"
    const val RELEASES_PAGE = "https://github.com/1zumiii/suzu-ime/releases"
    private const val TAG_PREFIX = "Suzu-ime-v"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    const val PREF_AUTO_CHECK = "suzu_update_auto_check"
    private const val PREF_LAST_CHECK = "suzu_update_last_check"
    private const val PREF_SKIPPED = "suzu_update_skipped_version"
    private const val PREF_AVAILABLE = "suzu_update_available_version"
    private val checkLock = Mutex()
    private var latestRelease: Release? = null
    private var manualCheckInProgress = false

    class Release(val version: String, val notes: String, val page: String, val apk: RemoteFile)

    sealed interface Result {
        class Available(val release: Release) : Result
        data object UpToDate : Result
        class Failed(val message: String) : Result
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun autoCheckEnabled(context: Context) = prefs(context).getBoolean(PREF_AUTO_CHECK, true)

    fun availableVersion(context: Context): String? = prefs(context).run {
        getString(PREF_AVAILABLE, null)?.takeIf {
            compareVersions(it, currentVersion) > 0 && it != getString(PREF_SKIPPED, null)
        }
    }

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
                if (it is CancellationException) throw it
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

    /** 从 Releases API 选择新包名的正式版和适合本机的 APK。 */
    fun parseReleases(json: String, i8mm: Boolean): List<Release> =
        (Json.parseToJsonElement(json) as JsonArray).mapNotNull { el ->
            val r = el.jsonObject
            val tag = r.str("tag_name") ?: return@mapNotNull null
            if (!tag.startsWith(TAG_PREFIX, ignoreCase = true)) return@mapNotNull null
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
                version = tag.substring(TAG_PREFIX.length),
                notes = r.str("body").orEmpty(),
                page = r.str("html_url") ?: RELEASES_PAGE,
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

    private suspend fun refresh(context: Context): Result = check(context).also { result ->
        when (result) {
            is Result.Available -> {
                latestRelease = result.release
                prefs(context).edit { putString(PREF_AVAILABLE, result.release.version) }
            }
            Result.UpToDate -> {
                latestRelease = null
                prefs(context).edit { remove(PREF_AVAILABLE) }
            }
            is Result.Failed -> Unit
        }
    }

    /** 自动检查只记录结果，主页工具栏据此显示红点。 */
    suspend fun autoCheck(context: Context) = checkLock.withLock {
        if (autoCheckEnabled(context) &&
            System.currentTimeMillis() - prefs(context).getLong(PREF_LAST_CHECK, 0L) >= CHECK_INTERVAL_MS
        ) {
            refresh(context)
        }
    }

    fun manualCheck(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        allowSkip: Boolean,
        onStatusChanged: () -> Unit,
    ) {
        if (manualCheckInProgress) return
        val cached = latestRelease
        if (allowSkip && cached != null && availableVersion(activity) == cached.version) {
            showAvailable(activity, scope, cached, true, onStatusChanged)
            return
        }
        scope.launch {
            manualCheckInProgress = true
            val dialog = ProgressDialog(activity, activity.getString(R.string.suzu_update_checking))
            val result = try {
                checkLock.withLock { refresh(activity) }
            } finally {
                dialog.dismiss()
                manualCheckInProgress = false
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            onStatusChanged()
            val ok = SheetButton(activity.getString(android.R.string.ok))
            when (result) {
                is Result.Available -> showAvailable(activity, scope, result.release, allowSkip, onStatusChanged)
                Result.UpToDate ->
                    activity.settingsSheet(
                        icon = "check_circle_outline",
                        title = activity.getString(R.string.suzu_update_up_to_date),
                        message = activity.getString(R.string.suzu_update_current_version, currentVersion),
                        confirm = ok,
                    ).show()
                is Result.Failed ->
                    activity.settingsSheet(
                        icon = "alert_circle_outline",
                        title = activity.getString(R.string.suzu_update_check_failed),
                        message = activity.getString(R.string.suzu_update_check_failed_message, result.message),
                        confirm = ok,
                        extra = SheetButton(activity.getString(R.string.suzu_update_open_page)) { openPage(activity, RELEASES_PAGE) },
                    ).show()
            }
        }
    }

    private fun showAvailable(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        release: Release,
        allowSkip: Boolean,
        onStatusChanged: () -> Unit,
    ) {
        val sizeMb = "%.1f".format(release.apk.size / 1048576.0)
        val notes = plainNotes(release.notes).let { if (it.length > 1200) it.take(1200) + "…" else it }
        val message = activity.getString(
            R.string.suzu_update_available_message,
            currentVersion,
            sizeMb,
        ) + if (notes.isNotEmpty()) "\n\n$notes" else ""
        val extra = if (allowSkip && availableVersion(activity) == release.version) {
            SheetButton(activity.getString(R.string.suzu_update_skip)) {
                prefs(activity).edit { putString(PREF_SKIPPED, release.version) }
                onStatusChanged()
            }
        } else {
            SheetButton(activity.getString(R.string.suzu_update_open_page)) { openPage(activity, release.page) }
        }
        activity.settingsSheet(
            icon = "download_outline",
            title = activity.getString(R.string.suzu_update_available, release.version),
            message = message,
            confirm = SheetButton(activity.getString(R.string.suzu_update_install)) { downloadAndInstall(activity, scope, release) },
            cancel = activity.getString(R.string.suzu_update_later),
            extra = extra,
        ).show()
    }

    private fun downloadAndInstall(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        release: Release,
    ) {
        if (!canInstall(activity)) {
            activity.settingsSheet(
                icon = "shield_outline",
                title = activity.getString(R.string.suzu_update_need_permission),
                message = activity.getString(R.string.suzu_update_need_permission_message),
                confirm = SheetButton(activity.getString(R.string.grant_permission)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                        )
                    }
                },
                cancel = activity.getString(android.R.string.cancel),
            ).show()
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
                    val archive = packageArchiveInfo(activity, target)
                    check(archive != null && archive.packageName == activity.packageName) {
                        activity.getString(R.string.suzu_update_package_mismatch)
                    }
                    check(sameSigner(activity, archive)) { activity.getString(R.string.suzu_update_signature_mismatch) }
                }
            }
            dialog.dismiss()
            result.onSuccess { install(activity, target) }.onFailure {
                Timber.w(it, "update download failed")
                if (activity.isFinishing || activity.isDestroyed) return@onFailure
                activity.settingsSheet(
                    icon = "alert_circle_outline",
                    title = activity.getString(R.string.suzu_update_download_failed),
                    message = it.message ?: it.javaClass.simpleName,
                    confirm = SheetButton(activity.getString(android.R.string.ok)),
                    extra = SheetButton(activity.getString(R.string.suzu_update_open_page)) { openPage(activity, release.page) },
                ).show()
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

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun packageArchiveInfo(context: Context, apk: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(apk.path, signatureFlags())

    @Suppress("DEPRECATION")
    private fun sameSigner(context: Context, archive: PackageInfo): Boolean {
        val installed = signatures(context.packageManager.getPackageInfo(context.packageName, signatureFlags()))
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
