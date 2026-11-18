/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu.ai

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/** 守护进程的心跳（B → PONG，不需要认证），与 rimellmd 的监督进程用的是同一个请求。不能在主线程上调用。 */
object PortProbe {
    const val PORT = 17920

    fun alive(timeoutMs: Int = 1500): Boolean =
        try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("127.0.0.1", PORT), timeoutMs)
                s.soTimeout = timeoutMs
                // 帧：4 字节大端长度 + 内容
                s.getOutputStream().apply { write(byteArrayOf(0, 0, 0, 1, 'B'.code.toByte())); flush() }
                val inp = DataInputStream(s.getInputStream())
                val n = inp.readInt()
                if (n !in 1..64) return false
                val b = ByteArray(n)
                inp.readFully(b)
                String(b, Charsets.UTF_8) == "PONG"
            }
        } catch (e: Exception) {
            false
        }
}
