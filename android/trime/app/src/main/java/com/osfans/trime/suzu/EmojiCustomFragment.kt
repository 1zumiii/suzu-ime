/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.os.Bundle
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.ui.common.LabeledField
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.dialogActionRow
import com.osfans.trime.ui.common.mdiIcon
import com.osfans.trime.ui.common.onTextChanged
import com.osfans.trime.ui.common.settingsSheet
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast

/**
 * 候选映射：用户目录的 emoji_custom.txt（沿用旧文件名），每行“键<Tab>候选”，由 Lua 的 emoji_custom.lua 读取，改完几秒内生效。
 * 候选可以是表情或任意文字，一个键可以映射多个，用空格隔开。
 * 键全是字母时按拼音处理（输入恰好是这串拼音时，排在首位候选后面）；含其他字符时按词处理（候选里出现这个词时，紧跟在它后面）。
 */
object EmojiCustom {
    data class Entry(val key: String, val emoji: String) {
        val isCode get() = CODE.matches(key)
    }

    private val CODE = Regex("[a-z]+")
    private const val HEADER =
        "# 候选映射：每行“键<Tab>候选”，候选可以是表情或任意文字，多个用空格隔开。键全是小写字母时按拼音匹配输入，否则按词匹配候选。\n"
    private val file get() = DataManager.userDataDir.resolve("emoji_custom.txt")

    fun load(): List<Entry> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) Entry(parts[0].trim(), parts[1].trim()) else null
        }
    }.getOrDefault(emptyList())

    fun save(list: List<Entry>) {
        val tmp = DataManager.userDataDir.resolve("emoji_custom.txt.tmp")
        tmp.writeText(HEADER + list.joinToString("") { "${it.key}\t${it.emoji}\n" })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** 整理用户输入的键：纯字母（可带隔音符、空格）转成小写拼音，其余去掉首尾空白；不合法时返回 null */
    fun normalizeKey(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.contains('\t') || s.contains('\n')) return null
        val letters = s.replace("'", "").replace(" ", "")
        return if (letters.isNotEmpty() && letters.all { it in 'a'..'z' || it in 'A'..'Z' }) letters.lowercase() else s
    }

    fun normalizeEmoji(raw: String): String? {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        return if (s.isEmpty() || s.contains('\t')) null else s
    }
}

class EmojiCustomFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        build()
    }

    private fun build() {
        val ctx = requireContext()
        val list = EmojiCustom.load()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory("自定义候选") {
                for ((i, e) in list.withIndex()) {
                    addPreference("${e.key} → ${e.emoji}") { edit(list, i) }
                }
                addPreference("添加映射") { edit(list, null) }
                getPreference(preferenceCount - 1).mdiIcon("plus")
            }
        }
    }

    private fun edit(list: List<EmojiCustom.Entry>, index: Int?) {
        val ctx = requireContext()
        val old = index?.let { list[it] }
        val key = LabeledField(ctx, "拼音或词", old?.key.orEmpty(), "如 shang 或 尴尬")
        val emoji = LabeledField(ctx, "候选内容", old?.emoji.orEmpty(), helper = "多个候选用空格分隔")
        val pad = (24 * ctx.resources.displayMetrics.density).toInt()
        lateinit var dialog: BottomSheetDialog
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(key.view)
            addView(
                emoji.view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = pad * 2 / 3
                },
            )
            if (index != null) {
                addView(
                    ctx.dialogActionRow("delete_outline", "删除这条映射") {
                        dialog.dismiss()
                        save(list.filterIndexed { i, _ -> i != index })
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = pad / 2
                    },
                )
            }
        }
        fun keyValue() = EmojiCustom.normalizeKey(key.input.text.toString())
        fun candidateValue() = EmojiCustom.normalizeEmoji(emoji.input.text.toString())
        dialog = ctx.settingsSheet(
            icon = "emoticon_outline",
            title = if (old == null) "添加映射" else "编辑映射",
            message = "拼音匹配时插在首选后，词匹配时插在该词后。",
            body = box,
            confirm = SheetButton("保存") {
                val k = keyValue() ?: return@SheetButton
                val e = candidateValue() ?: return@SheetButton
                val next = list.toMutableList()
                val entry = EmojiCustom.Entry(k, e)
                if (index == null) next += entry else next[index] = entry
                save(next)
            },
            cancel = getString(android.R.string.cancel),
            configureConfirm = { button ->
                fun refresh() {
                    button.isEnabled = keyValue() != null && candidateValue() != null
                }
                key.input.onTextChanged(::refresh)
                emoji.input.onTextChanged(::refresh)
                refresh()
            },
        )
        dialog.show()
    }

    private fun save(list: List<EmojiCustom.Entry>) {
        runCatching { EmojiCustom.save(list) }
            .onFailure { requireContext().toast("保存失败：${it.message}") }
        build()
    }
}
