/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import com.osfans.trime.data.base.DataManager
import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.File

/**
 * 词库包：方案只有一份（suzu），各包只差词库。每个包是用户目录下的 cn_dicts_<名字>/，
 * 包目录里带主词典 pack.main.yaml 与说明 pack.lock。启用一个包就是把这两个文件放到用户目录的
 * suzu.dict.yaml、dict_pack.lock，并写 suzu_pack.yaml（方案补丁经 __patch 读入，告诉守护进程词库目录）。
 * 默认包随 APK 由 Trime 同步到用户目录，其他包下载后放进来。
 */
object DictPacks {
    const val SCHEMA = "suzu"
    const val MAIN_DICT = "$SCHEMA.dict.yaml"
    const val LOCK = "dict_pack.lock"
    const val PATCH = "suzu_pack.yaml"
    private const val PACK_MAIN = "pack.main.yaml"
    private const val PACK_LOCK = "pack.lock"
    private const val DIR_PREFIX = "cn_dicts_"

    private val userDir get() = DataManager.userDataDir

    /** APK 自带的默认包（构建时写在 suzu_sources.txt 的“词库包”一行） */
    val defaultPack: String by lazy {
        runCatching {
            appContext.assets.open("suzu_sources.txt").bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("词库包 ") }?.substringAfter(' ')?.trim()
            }
        }.getOrNull() ?: "frost"
    }

    fun dirName(pack: String) = "$DIR_PREFIX$pack"

    fun dirOf(pack: String) = userDir.resolve(dirName(pack))

    /** 用户目录里装好的包（有主词典的包目录） */
    fun installed(): List<String> = userDir
        .listFiles { f -> f.isDirectory && f.name.startsWith(DIR_PREFIX) && f.resolve(PACK_MAIN).isFile }
        ?.map { it.name.removePrefix(DIR_PREFIX) }
        ?.sorted()
        .orEmpty()

    /** 当前启用的包；没有启用或文件不全时为 null */
    fun active(): String? {
        if (!userDir.resolve(MAIN_DICT).isFile || !userDir.resolve(PATCH).isFile) return null
        return readLock(userDir.resolve(LOCK))["pack"]
    }

    /** 包说明（pack、commit、license 等），读不到时为空 */
    fun info(pack: String): Map<String, String> = readLock(dirOf(pack).resolve(PACK_LOCK))

    private fun readLock(file: File): Map<String, String> = runCatching {
        file.readLines().mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i > 0) line.substring(0, i) to line.substring(i + 1).trim() else null
        }.toMap()
    }.getOrDefault(emptyMap())

    /** 启用一个已装好的包：只写用户目录的三个文件，词库重新编译由调用方触发部署 */
    fun activate(pack: String) {
        val dir = dirOf(pack)
        require(dir.resolve(PACK_MAIN).isFile) { "词库包 $pack 没有装好" }
        writeAtomically(userDir.resolve(MAIN_DICT), AddonRules.composeMainDict(dir.resolve(PACK_MAIN).readText(), DictAddons.enabledIds()))
        writeAtomically(userDir.resolve(LOCK), dir.resolve(PACK_LOCK).takeIf { it.isFile }?.readText() ?: "pack\t$pack\n")
        writeAtomically(userDir.resolve(PATCH), "patch:\n  llm_rerank/lexicon_dir: ${dirName(pack)}\n")
        Timber.i("Dict pack activated: %s", pack)
    }

    /** 还没有启用任何包时启用默认包；返回是否改了文件 */
    fun ensureActive(): Boolean {
        if (active() != null) return false
        val pack = defaultPack.takeIf { it in installed() } ?: installed().firstOrNull() ?: return false
        activate(pack)
        return true
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
