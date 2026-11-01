/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.osfans.trime.R
import com.osfans.trime.data.base.DataManager
import org.json.JSONObject
import java.io.File

/**
 * 「模块状态」界面：只读，列出 AI 重排、统一排序、各类记录与配置的当前状态。数据来自用户目录里的文件，
 * 不改任何设置；打开期间每 2 秒刷新一次。圆点：绿 = 正常或已开启，黄 = 过渡状态，灰 = 关闭，红 = 异常。
 */
class ModuleStatusActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout

    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(round(PANEL, 24))
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(body) })
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(GRAY)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun row(color: Int, name: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = round(TILE, 12)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(
            TextView(context).apply {
                text = "●"
                textSize = 14f
                setTextColor(color)
                setPadding(0, 0, dp(10), 0)
            },
        )
        addView(
            TextView(context).apply {
                text = name
                textSize = 14f
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            TextView(context).apply {
                text = value
                textSize = 13f
                setTextColor(GRAY)
                gravity = Gravity.END
            },
            LinearLayout.LayoutParams(-2, -2),
        )
    }

    private fun add(v: android.view.View) {
        body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun lines(f: File): Int =
        try {
            if (!f.exists()) {
                0
            } else {
                var n = 0
                f.bufferedReader().use { r -> while (r.readLine() != null) n++ }
                n
            }
        } catch (e: Exception) {
            0
        }

    private fun toggle(name: String, flag: String, data: String?) {
        val dir = DataManager.userDataDir
        val on = dir.resolve(flag).exists()
        val rows = data?.let { lines(dir.resolve(it)) }
        add(row(if (on) GREEN else GRAY, name, (if (on) "已开启" else "关闭") + (rows?.let { "，$it 行" } ?: "")))
    }

    private fun render() {
        body.removeAllViews()
        add(title(getString(R.string.suzu_module_status)))
        val dir = DataManager.userDataDir

        add(section("AI 重排"))
        val j =
            try {
                JSONObject(dir.resolve("ai_status.json").readText())
            } catch (e: Exception) {
                null
            }
        val age = if (j != null) System.currentTimeMillis() / 1000 - j.optLong("ts", 0) else Long.MAX_VALUE
        if (j == null || age > 30) {
            add(row(RED, "AI 服务", if (j == null) "尚未获取到状态" else "状态未更新（${age} 秒前）"))
        } else {
            val loaded = j.optBoolean("model_loaded")
            val enabled = j.optBoolean("enabled", true)
            val color = if (!enabled) GRAY else if (loaded) GREEN else YELLOW
            val value = if (!enabled) "已关闭" else if (loaded) "模型在线" else "模型未加载"
            add(row(color, "AI 服务", value))
            val up = j.optLong("uptime_s")
            add(row(GREEN, "运行时间 / 内存", "${up / 3600} 时 ${up % 3600 / 60} 分 / ${j.optLong("rss_kb") / 1024} MB"))
            val e = j.optJSONObject("engine")
            val c = j.optJSONObject("client")
            if (e != null) {
                val ok = e.optInt("ok")
                val skipped = e.optInt("skipped")
                val total = ok + skipped
                val rate = if (total > 0) "%.1f%%".format(skipped * 100.0 / total) else "-"
                add(row(if (total > 0 && skipped * 100.0 / total > 5) YELLOW else GREEN, "打分 / 跳过", "$ok / $skipped（$rate）"))
                add(row(GREEN, "上文不足（不计入跳过）", "${e.optInt("no_context")}"))
                val w = e.optJSONObject("wait_ms")
                if (w != null) {
                    val p99 = w.optDouble("p99")
                    add(row(if (p99 > 50) YELLOW else GREEN, "等待 p50 / p99", "%.0f / %.0f ms".format(w.optDouble("p50"), p99)))
                }
            }
            if (c != null) {
                val over = c.optInt("wait_over_50ms")
                add(row(if (over > 0) YELLOW else GREEN, "等待超过 50 ms", "$over 次"))
            }
        }
        val sz = when (AiDaemonLauncher.availability()) {
            AiDaemonLauncher.Availability.READY -> GREEN to "已授权"
            AiDaemonLauncher.Availability.NEEDS_PERMISSION -> YELLOW to "运行中，待授权"
            AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING -> GRAY to "未运行"
        }
        add(row(sz.first, "Shizuku（启动 AI 服务）", sz.second))

        add(section("排序"))
        val schema = dir.resolve("build/${DictPacks.SCHEMA}.schema.yaml")
        val ranker =
            try {
                // 方案里没写 ranker 时用代码里的默认值：统一排序
                Regex("""ranker:\s*(\w+)""").find(schema.readText())?.groupValues?.get(1) ?: "unified"
            } catch (e: Exception) {
                "未知"
            }
        add(row(if (ranker == "unified") GREEN else GRAY, "排序方式", if (ranker == "unified") "统一排序" else "旧排序（$ranker）"))
        val gen = j?.optJSONObject("engine")?.optJSONObject("gen")
        if (gen == null) {
            add(row(GRAY, "候选生成", "AI 服务离线或不支持此功能"))
        } else {
            add(row(GREEN, "候选生成", "成功 ${gen.optInt("ok")} 次，超时 ${gen.optInt("timeout")} 次"))
        }
        val gram = dir.resolve("wanxiang-lts-zh-hans.gram")
        add(row(if (gram.exists()) GREEN else GRAY, "语法模型", if (gram.exists()) "${gram.length() / 1048576} MB" else "没有"))
        val weird = lines(dir.resolve("weird_words.tsv"))
        add(row(GRAY, "已降低排序的候选", "$weird 个"))

        add(section("记录（只在本机）"))
        toggle("输入过程记录", "input_log.enabled", "input_log.jsonl")
        toggle("选词样本", "select_corpus.enabled", "select_corpus.tsv")
        toggle("选词统计", "select_stats.enabled", "select_stats.tsv")
        add(row(GRAY, "最近使用词 / 词对", "${lines(dir.resolve("recent_words.tsv"))} / ${lines(dir.resolve("recent_pairs.tsv"))}"))

        add(section("方案与词库"))
        val sources = runCatching { assets.open("suzu_sources.txt").bufferedReader().readLines() }.getOrDefault(emptyList())
        for (line in sources.filter { !it.startsWith("词库包 ") }) {
            val (name, rev) = line.split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            add(row(if (rev.endsWith("-dirty")) YELLOW else GRAY, name, rev.ifEmpty { "未知" }))
        }
        val pack = DictPacks.active()
        add(
            row(
                if (pack != null) GREEN else RED,
                "词库包",
                pack?.let { "${PackInstaller.title(it)} ${DictPacks.info(it)["commit"]?.take(7).orEmpty()}" } ?: "没有启用",
            ),
        )
        val migration = SuzuMigration.state()
        val failed = migration["failed"]
        when {
            failed != null -> {
                add(row(RED, "旧数据迁移", "未完成，仍使用旧配置。原因：$failed"))
                add(
                    Button(this).apply {
                        text = "下次启动时重试"
                        setOnClickListener {
                            SuzuMigration.clearFailure()
                            text = "已安排重试，请重新部署或重启手机"
                        }
                    },
                )
            }
            migration["merged"] == "1" ->
                add(row(GREEN, "旧数据迁移", "已完成，用户词典 ${migration["count.suzu"]} 条"))
            else -> add(row(GRAY, "旧数据迁移", "不需要"))
        }
        // Rime 先读用户目录：这里有同名文件就会盖住 APK 自带的配置
        val shared = DataManager.sharedDataDir
        val overriding = (shared.list().orEmpty().filter { shared.resolve(it).isFile && it != "custom_phrase.txt" } +
            shared.resolve("lua").list().orEmpty().map { "lua/$it" })
            .filter { dir.resolve(it).isFile }
        add(
            row(
                if (overriding.isEmpty()) GREEN else YELLOW,
                "自定义配置覆盖",
                if (overriding.isEmpty()) "没有" else "${overriding.size} 个文件优先于内置配置：${overriding.joinToString("、")}",
            ),
        )
    }

    private companion object {
        val PANEL = Color.rgb(0x3A, 0x3A, 0x3A)
        val TILE = Color.rgb(0x1A, 0x1A, 0x1A)
        val GRAY = Color.rgb(0xA8, 0xA8, 0xA8)
        val GREEN = Color.rgb(0x4C, 0xAF, 0x50)
        val YELLOW = Color.rgb(0xFF, 0xC1, 0x07)
        val RED = Color.rgb(0xF4, 0x43, 0x36)
    }
}
