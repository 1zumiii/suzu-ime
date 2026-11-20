/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import timber.log.Timber

// AI 重排的光标上文：把输入框里光标前的文字交给客户端库 librime_llm.so，Lua 经 llm_context 读取
// （与 Windows 的 UIA/COM 读法共用 ai_rerank_app_ctx.lua）。只在内存里传递，不写文件、不记日志。
// 客户端库由 Lua 用 package.loadlib("librime_llm.so") 加载；这里用 System.loadLibrary 加载的是同一个实例。
object SuzuAppContext {
    const val MAX_CHARS = 60

    private var last = ""
    private var blocked = false

    private val loaded =
        runCatching { System.loadLibrary("rime_llm") }
            .onFailure { Timber.w("SuzuAppContext: librime_llm.so not loaded") }
            .isSuccess

    @JvmStatic
    private external fun nativeSet(utf8: ByteArray)

    private fun push(s: String) {
        if (!loaded || s == last) return
        last = s
        // 传 UTF-8 字节：JNI 的 GetStringUTFChars 给的是改良 UTF-8，emoji 等字符在 Lua 里会成为非法序列
        runCatching { nativeSet(s.toByteArray(Charsets.UTF_8)) }
    }

    // 进入一个输入框：密码框、数字框、无痕模式等不读上文
    fun start(block: Boolean) {
        blocked = block
        push("")
    }

    fun set(text: CharSequence?) {
        push(if (blocked) "" else (text?.toString() ?: "").takeLast(MAX_CHARS))
    }

    // 上屏后先把上屏的文字接到旧快照后面，等 onUpdateSelection 再换成输入框里的真实文字
    fun append(text: String) {
        if (!blocked) push((last + text).takeLast(MAX_CHARS))
    }

    fun clear() = push("")

    /**
     * 光标变化后，原来的上文（上屏历史、个人词对的“前一个词”）是否作废。
     * 一般情况：光标位置变了，而且不是输入法自己 500 ms 内的编辑引起的（用户点击或拖动了光标）。
     * 光标回到 0 时总是作废：前面已经没有文字，上屏历史不可能是光标前的内容。
     * 例如用键盘的回车发出消息后，应用清空输入框，这一跳离输入法自己的回车不到 500 ms，以前会被当成自己的编辑，
     * 刚发出去的那条消息就留在上屏历史里，成了下一条的上文。
     */
    fun cursorJumped(oldSelStart: Int, newSelStart: Int, msSinceOwnEdit: Long): Boolean =
        oldSelStart != newSelStart && (newSelStart == 0 || msSinceOwnEdit > 500)
}
