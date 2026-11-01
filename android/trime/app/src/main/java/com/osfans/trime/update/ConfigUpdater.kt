/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.osfans.trime.TrimeApplication
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.base.DataManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * 从 GitHub 仓库（可换成任何 fork）下载最新配置并覆盖到 Rime 用户目录。
 * 覆盖前把被改动的文件备份一份（只保留上一个版本）；更新后 [VERIFY_MS] 内连续崩溃两次（进程在确认健康前被重启两次）自动回退。
 * 个人数据（词频库、用户词、最近使用记录、模型、用户设置）不会被覆盖。
 */
object ConfigUpdater {
    const val DEFAULT_REPO = "https://github.com/1zumiii/rime-suzu"
    const val DEFAULT_BRANCH = "main"
    private const val VERIFY_MS = 45_000L
    private const val CRASHES_TO_ROLLBACK = 2

    private const val PK_REPO = "cfg_update_repo"
    private const val PK_BRANCH = "cfg_update_branch"
    private const val PK_PENDING = "cfg_update_pending"
    private const val PK_STARTS = "cfg_update_starts"
    private const val PK_NEEDS_DEPLOY = "cfg_update_needs_deploy"
    private const val PK_VERSION = "cfg_update_version"
    private const val PK_BACKUP_VERSION = "cfg_update_backup_version"
    private const val PK_LAST = "cfg_update_last"

    // 不从仓库复制到手机的路径（与 deploy_trime.sh 的排除项一致）
    private val EXCLUDE_PREFIX = listOf(
        ".git/", ".github/", "others/", "windows/", "llm-mac/", "llm-android/", "llm-distill/", "tools/", "build/",
    )
    private val EXCLUDE_NAME = setOf(
        ".gitignore", ".DS_Store", "AGENTS.md", "LICENSE", "install.sh", "recipe.yaml", "squirrel.yaml",
        "squirrel.custom.yaml", "weasel.yaml", "luac.out", "recent_words.tsv", "recent_pairs.tsv",
        "user.yaml", "installation.yaml",
    )
    private val EXCLUDE_SUFFIX = listOf(".gram", ".gguf")

    private val main = Handler(Looper.getMainLooper())
    private val ctx: Context get() = TrimeApplication.getInstance().directBootAwareContext
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(ctx)
    private val rimeDir get() = DataManager.userDataDir
    private val backupDir get() = File(ctx.filesDir, "config_backup")

    var repo: String
        get() = prefs.getString(PK_REPO, DEFAULT_REPO) ?: DEFAULT_REPO
        set(v) = prefs.edit().putString(PK_REPO, v.trim()).apply()
    var branch: String
        get() = prefs.getString(PK_BRANCH, DEFAULT_BRANCH) ?: DEFAULT_BRANCH
        set(v) = prefs.edit().putString(PK_BRANCH, v.trim()).apply()
    val version: String get() = prefs.getString(PK_VERSION, "") ?: ""
    val hasBackup: Boolean get() = File(backupDir, "manifest.json").exists()
    val lastMessage: String get() = prefs.getString(PK_LAST, "") ?: ""

    /** 把用户填的地址变成压缩包地址：支持 GitHub 仓库地址、owner/repo、/tree/分支 形式，或直接给 .zip 链接。 */
    fun zipUrl(repoText: String, branchText: String): String {
        val t = repoText.trim()
        if (t.endsWith(".zip")) return t
        val m = Regex("""^(?:https?://github\.com/)?([\w.-]+)/([\w.-]+?)(?:\.git)?(?:/tree/([^?#]+))?/?$""").find(t)
            ?: throw IllegalArgumentException("无法识别的地址：$t（应为 https://github.com/用户/仓库 或 用户/仓库）")
        val (owner, name, treeBranch) = m.destructured
        val b = branchText.trim().ifEmpty { treeBranch.ifEmpty { "main" } }
        return "https://codeload.github.com/$owner/$name/zip/refs/heads/$b"
    }

    private fun excluded(rel: String): Boolean {
        if (EXCLUDE_PREFIX.any { rel.startsWith(it) }) return true
        val name = rel.substringAfterLast('/')
        if (name in EXCLUDE_NAME || name.startsWith("README")) return true
        if (EXCLUDE_SUFFIX.any { name.endsWith(it) }) return true
        return rel.contains(".userdb/")
    }

    private fun crc(f: File): Long {
        val c = CRC32()
        f.inputStream().buffered().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                c.update(buf, 0, n)
            }
        }
        return c.value
    }

    private fun download(url: String, dst: File, log: (String) -> Unit) {
        log("下载 $url")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        if (conn.responseCode != 200) throw IllegalStateException("下载失败：HTTP ${conn.responseCode}（仓库不存在、分支名不对或仓库不是公开的？）")
        conn.inputStream.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        log("已下载 ${dst.length() / 1024} KB")
    }

    /** 立即更新。在后台线程调用；返回一句话结果。 */
    @Synchronized
    fun update(log: (String) -> Unit): String {
        try {
            val url = zipUrl(repo, branch)
            val zipFile = File(ctx.cacheDir, "config_update.zip")
            download(url, zipFile, log)
            val changed = ArrayList<Pair<String, ByteArray?>>()   // 相对路径 → 内容（null = 从 zip 条目流式写）
            val entries = HashMap<String, java.util.zip.ZipEntry>()
            var commit = ""
            ZipFile(zipFile).use { zf ->
                commit = zf.comment?.take(7) ?: ""
                val phone = HashMap<String, ByteArray>()      // llm-android/trime 下的手机专属文件
                val plan = LinkedHashMap<String, ByteArray?>()
                for (e in zf.entries()) {
                    if (e.isDirectory) continue
                    val rel = e.name.substringAfter('/')       // 去掉压缩包顶层目录
                    if (rel.startsWith("llm-android/trime/") && rel.count { it == '/' } == 2) {
                        phone[rel.substringAfterLast('/')] = zf.getInputStream(e).readBytes()
                        continue
                    }
                    if (excluded(rel)) continue
                    plan[rel] = null
                    entries[rel] = e
                }
                if (plan.isEmpty()) throw IllegalStateException("压缩包里没有可用的配置文件（不是 Rime 配置仓库？）")
                // 手机专属：*.overlay.yaml 追加到同名 .custom.yaml 末尾；*.custom.yaml 直接放进用户目录
                val overlays = phone.filterKeys { it.endsWith(".overlay.yaml") }
                for ((name, bytes) in phone) {
                    if (name.endsWith(".custom.yaml") && !name.endsWith(".overlay.yaml")) plan[name] = bytes
                }
                for ((name, ov) in overlays) {
                    val target = name.removeSuffix(".overlay.yaml") + ".custom.yaml"
                    val base = plan[target]
                        ?: entries[target]?.let { zf.getInputStream(it).readBytes() }
                        ?: continue
                    val sep = if (base.isNotEmpty() && base.last() != '\n'.code.toByte()) "\n".toByteArray() else ByteArray(0)
                    plan[target] = base + sep + ov
                }
                // 找出真正有变化的文件
                for ((rel, bytes) in plan) {
                    val dst = File(rimeDir, rel)
                    val same = if (bytes != null) {
                        dst.exists() && dst.length() == bytes.size.toLong() && dst.readBytes().contentEquals(bytes)
                    } else {
                        val e = entries.getValue(rel)
                        dst.exists() && dst.length() == e.size && crc(dst) == e.crc
                    }
                    if (!same) changed += rel to bytes
                }
                log("共 ${plan.size} 个文件，${changed.size} 个有变化")
                if (changed.isEmpty()) {
                    prefs.edit().putString(PK_LAST, "已是最新").apply()
                    return "已是最新，没有需要更新的文件"
                }
                // 备份：只保留上一个版本
                backupDir.deleteRecursively()
                val files = File(backupDir, "files").also { it.mkdirs() }
                val modified = JSONArray()
                val created = JSONArray()
                for ((rel, _) in changed) {
                    val dst = File(rimeDir, rel)
                    if (dst.exists()) {
                        File(files, rel).also { it.parentFile?.mkdirs() }.let { dst.copyTo(it, overwrite = true) }
                        modified.put(rel)
                    } else {
                        created.put(rel)
                    }
                }
                File(backupDir, "manifest.json").writeText(
                    JSONObject().put("modified", modified).put("created", created).put("version", version).toString(),
                )
                prefs.edit().putString(PK_BACKUP_VERSION, version).apply()
                // 写入
                for ((rel, bytes) in changed) {
                    val dst = File(rimeDir, rel)
                    dst.parentFile?.mkdirs()
                    val tmp = File(dst.path + ".new")
                    if (bytes != null) tmp.writeBytes(bytes)
                    else zf.getInputStream(entries.getValue(rel)).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
                }
            }
            zipFile.delete()
            // 进入「待确认」：45 秒内没有被连续崩溃打断才算更新成功
            prefs.edit().putBoolean(PK_PENDING, true).putInt(PK_STARTS, 0)
                .putString(PK_VERSION, commit.ifEmpty { "未知" }).apply()
            scheduleConfirm()
            deployNow(log)
            val msg = "已更新 ${changed.size} 个文件（版本 ${commit.ifEmpty { "未知" }}），正在重新部署"
            prefs.edit().putString(PK_LAST, msg).apply()
            return msg
        } catch (e: Throwable) {
            val msg = "更新失败：${e.message ?: e.javaClass.simpleName}"
            prefs.edit().putString(PK_LAST, msg).apply()
            return msg
        }
    }

    private fun deployNow(log: (String) -> Unit) {
        val s = RimeDaemon.getFirstSessionOrNull()
        if (s == null) {
            prefs.edit().putBoolean(PK_NEEDS_DEPLOY, true).apply()
            log("输入法服务未运行，下次打开键盘时自动部署")
            return
        }
        s.launchOnReady {
            try {
                it.deploy()
            } catch (e: Throwable) {
                rollback { }     // 部署本身失败：立刻回退
            }
        }
    }

    /** 输入法服务启动时调用：补做因为服务未运行而推迟的部署。 */
    fun runPendingDeploy() {
        if (!prefs.getBoolean(PK_NEEDS_DEPLOY, false)) return
        val s = RimeDaemon.getFirstSessionOrNull() ?: return
        prefs.edit().putBoolean(PK_NEEDS_DEPLOY, false).apply()
        s.launchOnReady { it.deploy() }
    }

    private fun scheduleConfirm() {
        main.postDelayed({ confirmHealthy() }, VERIFY_MS)
    }

    fun confirmHealthy() {
        if (prefs.getBoolean(PK_PENDING, false)) prefs.edit().putBoolean(PK_PENDING, false).putInt(PK_STARTS, 0).apply()
    }

    /** 进程启动时（Rime 初始化之前）调用：更新后还没确认健康就又启动了，说明上次崩溃了。 */
    fun onProcessStart() {
        if (!prefs.getBoolean(PK_PENDING, false)) return
        val n = prefs.getInt(PK_STARTS, 0) + 1
        prefs.edit().putInt(PK_STARTS, n).apply()
        if (n >= CRASHES_TO_ROLLBACK) {
            rollback { }
            prefs.edit().putString(PK_LAST, "更新后连续崩溃，已自动回退到上一个版本").apply()
        } else {
            scheduleConfirm()
        }
    }

    /** 回退到上一个版本：恢复被改动的文件，删掉新增的文件，然后重新部署。 */
    @Synchronized
    fun rollback(log: (String) -> Unit): String {
        val mf = File(backupDir, "manifest.json")
        if (!mf.exists()) return "没有可回退的备份"
        return try {
            val j = JSONObject(mf.readText())
            val files = File(backupDir, "files")
            val modified = j.getJSONArray("modified")
            for (i in 0 until modified.length()) {
                val rel = modified.getString(i)
                val dst = File(rimeDir, rel).also { it.parentFile?.mkdirs() }
                File(files, rel).copyTo(dst, overwrite = true)
            }
            val created = j.getJSONArray("created")
            for (i in 0 until created.length()) File(rimeDir, created.getString(i)).delete()
            backupDir.deleteRecursively()
            File(rimeDir, "build/default.yaml").delete()   // 强制重新编译
            prefs.edit().putBoolean(PK_PENDING, false).putInt(PK_STARTS, 0).putBoolean(PK_NEEDS_DEPLOY, true)
                .putString(PK_VERSION, j.optString("version")).apply()
            log("已恢复 ${modified.length()} 个文件，删除 ${created.length()} 个新增文件")
            runPendingDeploy()
            "已回退到上一个版本"
        } catch (e: Throwable) {
            "回退失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}
