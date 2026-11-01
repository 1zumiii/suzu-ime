/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.update

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 「从仓库更新」界面：显示当前版本，可改仓库地址与分支，立即更新或回退到上一个版本。配色与键盘面板一致（深色、圆角）。 */
class ConfigUpdateActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var update: TextView
    private lateinit var rollback: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun field(value: String, hintText: String, uri: Boolean) = EditText(this).apply {
        setText(value)
        hint = hintText
        setHintTextColor(GRAY)
        setTextColor(Color.WHITE)
        textSize = 14f
        setSingleLine()
        if (uri) inputType = InputType.TYPE_TEXT_VARIATION_URI
        background = round(TILE, 12)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun button(text: String, fill: Int, textColor: Int) = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        background = round(fill, 14)
        setPadding(0, dp(13), 0, dp(13))
    }

    private fun LinearLayout.addSpaced(v: android.view.View, top: Int) {
        addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(round(PANEL, 24))
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        val repoEdit = field(ConfigUpdater.repo, "仓库地址，例如 https://github.com/用户/仓库", true)
        val branchEdit = field(ConfigUpdater.branch, "分支（默认 ${ConfigUpdater.DEFAULT_BRANCH}）", false)
        status = label("", 13f, GRAY)
        update = button("立即更新", Color.WHITE, TILE)
        rollback = button("回退到上一个版本", TILE, Color.WHITE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            addView(label("从仓库更新", 20f, Color.WHITE, bold = true))
            addSpaced(label("从下面的仓库下载最新配置并覆盖到输入法。更新前自动备份上一个版本，更新后连续崩溃会自动回退。词频、用户词、最近使用记录和模型不会被改动。", 13f, GRAY), 8)
            addSpaced(repoEdit, 16)
            addSpaced(branchEdit, 10)
            addSpaced(update, 18)
            addSpaced(rollback, 10)
            addSpaced(status, 16)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
        update.setOnClickListener {
            if (!update.isEnabled) return@setOnClickListener
            ConfigUpdater.repo = repoEdit.text.toString()
            ConfigUpdater.branch = branchEdit.text.toString()
            run("正在更新…") { ConfigUpdater.update { m -> ui.post { append(m) } } }
        }
        rollback.setOnClickListener {
            if (!rollback.isEnabled) return@setOnClickListener
            run("正在回退…") { ConfigUpdater.rollback { m -> ui.post { append(m) } } }
        }
    }

    private fun setEnabledLook(v: TextView, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.4f
    }

    private fun refresh() {
        val v = ConfigUpdater.version.ifEmpty { "未通过本功能更新过" }
        status.text = "当前版本：$v\n上一版备份：${if (ConfigUpdater.hasBackup) "有" else "无"}" +
            ConfigUpdater.lastMessage.let { if (it.isEmpty()) "" else "\n$it" }
        setEnabledLook(update, true)
        setEnabledLook(rollback, ConfigUpdater.hasBackup)
    }

    private fun append(m: String) {
        status.append("\n$m")
    }

    private fun run(start: String, job: () -> String) {
        setEnabledLook(update, false)
        setEnabledLook(rollback, false)
        status.text = start
        Thread {
            val r = job()
            ui.post {
                refresh()
                append(r)
            }
        }.start()
    }

    private companion object {
        val PANEL = Color.rgb(0x3A, 0x3A, 0x3A)     // 与键盘区底色一致
        val TILE = Color.rgb(0x1A, 0x1A, 0x1A)      // 与面板里的图标底色一致
        val GRAY = Color.rgb(0xA8, 0xA8, 0xA8)
    }
}
