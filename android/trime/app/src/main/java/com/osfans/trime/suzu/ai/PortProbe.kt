/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu.ai

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** 在后台线程核对持有共享端口和当前令牌的工作进程。旧版 shell 的握手没有宿主字段。 */
object PortProbe {
    const val PORT = 17920
    class Owner(val pid: Int, val host: String, val token: String)

    fun occupied(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 500) }
        true
    } catch (_: IOException) {
        false
    }

    fun owner(dir: File): Owner? = try {
        val token = File(dir, "llm_token").readText().trim()
        if (token.isEmpty()) null else Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", PORT), 1000)
            socket.soTimeout = 1500
            val request = "H\u001f$token".toByteArray()
            DataOutputStream(socket.getOutputStream()).apply { writeInt(request.size); write(request) }
            val input = DataInputStream(socket.getInputStream())
            val size = input.readInt()
            if (size !in 1..256) return@use null
            val fields = ByteArray(size).also { input.readFully(it) }.decodeToString().split('\u001f')
            val pid = fields.getOrNull(1)?.toIntOrNull()
            if (fields.first() == "OK" && pid != null && pid > 0 && fields.size >= 3)
                Owner(pid, fields.getOrElse(3) { "shell" }, token)
            else null
        }
    } catch (_: IOException) {
        null
    }
}
