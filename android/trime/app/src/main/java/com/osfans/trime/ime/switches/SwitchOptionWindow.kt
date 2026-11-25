/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import com.osfans.trime.data.prefs.AppPrefs
import android.app.Dialog
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeConfig
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyCommand
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.SymbolGroup
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager
import com.osfans.trime.suzu.ModuleStatusActivity

class SwitchOptionWindow(di: DI) :
    BoardWindow.BarBoardWindow(di),
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme
    private val keyboardWindow: KeyboardWindow by instance()

    override val title = "菜单"

    /** 常用开关和符号面板入口，排在原有的设置项前面 */
    private val quickEntries get(): Array<SwitchOptionEntry.Static> {
        val ascii = rime.run { statusCached }.isAsciiMode
        val ai = rime.run { getRuntimeOption("ai_rerank") }
        val hints = !AppPrefs.defaultInstance().keyboard.hideKeySymbol.getValue()
        return arrayOf(
            SwitchOptionEntry.Static(if (ascii) "英文 → 中文" else "中文 → 英文", 0, SwitchOptionEntry.Static.Type.AsciiMode, if (ascii) "En" else "中"),
        ) + (
            // 设置页关闭了 AI 重排时不显示 AI 项
            if (com.osfans.trime.suzu.SuzuFeatures.aiMaster) {
                arrayOf(SwitchOptionEntry.Static(if (ai) "AI 重排：开" else "AI 重排：关", 0, SwitchOptionEntry.Static.Type.AiRerank, "AI"))
            } else {
                emptyArray()
            }
        ) + arrayOf(
            SwitchOptionEntry.Static(if (hints) "键帽提示：开" else "键帽提示：关", 0, SwitchOptionEntry.Static.Type.KeyHints, "¹a"),
            SwitchOptionEntry.Static("符号", 0, SwitchOptionEntry.Static.Type.Symbols, "#"),
            SwitchOptionEntry.Static("表情", 0, SwitchOptionEntry.Static.Type.Emoji, "☺"),
            SwitchOptionEntry.Static("颜文字", 0, SwitchOptionEntry.Static.Type.Kaomoji, "^_^"),
        )
    }

    private val staticEntries get() =
        quickEntries + arrayOf(
            SwitchOptionEntry.Static(
                context.getString(R.string.suzu_current_color, context.getString(AppPrefs.defaultInstance().keyboard.colorMode.getValue().stringRes)),
                R.drawable.ic_baseline_color_lens_24,
                SwitchOptionEntry.Static.Type.ColorMode,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.dict_packs),
                R.drawable.ic_round_view_list_24,
                SwitchOptionEntry.Static.Type.DictPacks,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.update_config),
                R.drawable.ic_baseline_sync_24,
                SwitchOptionEntry.Static.Type.UpdateConfig,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.suzu_module_status),
                R.drawable.ic_baseline_list_alt_24,
                SwitchOptionEntry.Static.Type.ModuleStatus,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.virtual_keyboard),
                R.drawable.ic_baseline_keyboard_24,
                SwitchOptionEntry.Static.Type.Keyboard,
            ),
        )

    var popupMenu: PopupMenu? = null

    private val saveOptions by lazy {
        RimeConfig.openConfig("default").use {
            it.getList("switcher/save_options", RimeConfig::getString).toSet()
        }
    }

    private suspend fun RimeApi.applyOption(option: String, value: Boolean) {
        setRuntimeOption(option, value)
        if (option in saveOptions) {
            RimeConfig.openUserConfig("user").use {
                it.setBool("var/option/$option", value)
            }
        }
    }

    private fun showDialog(builder: suspend (RimeApi) -> Dialog) {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                service.showDialog(builder(api))
            }
        }
    }

    private val adapter: SwitchOptionAdapter by lazy {
        object : SwitchOptionAdapter() {
            override val scope: ThemeScope = this@SwitchOptionWindow.scope

            override fun onItemClick(
                view: View,
                entry: SwitchOptionEntry,
            ) {
                when (entry) {
                    is SwitchOptionEntry.Static -> when (entry.type) {
                        SwitchOptionEntry.Static.Type.AsciiMode -> keyboardWindow.run(KeyCommand.ToggleAscii)

                        SwitchOptionEntry.Static.Type.AiRerank -> service.postRimeJob {
                            // 和工具栏徽标的手动切换一样先设 _ai_manual，Lua 侧据此认出是用户操作
                            val target = !getRuntimeOption("ai_rerank")
                            setRuntimeOption("_ai_manual", true)
                            setRuntimeOption("ai_rerank", target)
                        }

                        SwitchOptionEntry.Static.Type.KeyHints -> {
                            keyboardWindow.run(KeyCommand.ToggleKeyHints)
                            updateSchemaOptionEntries()
                        }

                        SwitchOptionEntry.Static.Type.Symbols -> keyboardWindow.run(KeyCommand.OpenSymbols(SymbolGroup.Symbols))

                        SwitchOptionEntry.Static.Type.Emoji -> keyboardWindow.run(KeyCommand.OpenSymbols(SymbolGroup.Emoji))

                        SwitchOptionEntry.Static.Type.Kaomoji -> keyboardWindow.run(KeyCommand.OpenSymbols(SymbolGroup.Kaomoji))

                        SwitchOptionEntry.Static.Type.DictPacks -> AppUtils.launchMainToDictPacks(context)

                        SwitchOptionEntry.Static.Type.UpdateConfig -> rime.launchOnReady { r ->
                            r.updateConfig()
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }

                        SwitchOptionEntry.Static.Type.ModuleStatus -> context.startActivity(
                            android.content.Intent(context, com.osfans.trime.suzu.ModuleStatusActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )

                        SwitchOptionEntry.Static.Type.Keyboard -> AppUtils.launchMainToKeyboard(context)

                        SwitchOptionEntry.Static.Type.ColorMode -> {
                            val pref = AppPrefs.defaultInstance().keyboard.colorMode
                            val modes = AppPrefs.Keyboard.ColorMode.entries
                            pref.setValue(modes[(pref.getValue().ordinal + 1) % modes.size])
                            updateSchemaOptionEntries()
                        }
                    }

                    is SwitchOptionEntry.Custom -> {
                        val options = entry.switch.options
                        if (options.isEmpty()) {
                            rime.launchOnReady {
                                val oldValue = it.getRuntimeOption(entry.switch.name)
                                it.applyOption(entry.switch.name, !oldValue)
                            }
                        } else {
                            val popup = PopupMenu(context, view)
                            val menu = popup.menu
                            entry.switch.states.forEachIndexed { i, state ->
                                menu.add(0, 0, 0, state).apply {
                                    setOnMenuItemClickListener {
                                        rime.launchOnReady {
                                            options.forEachIndexed { j, option ->
                                                it.applyOption(option, i == j)
                                            }
                                        }
                                        true
                                    }
                                }
                            }
                            popupMenu?.dismiss()
                            popupMenu = popup
                            popup.show()
                        }
                    }
                }
            }
        }
    }

    val view by lazy {
        context.recyclerView {
            layoutManager = gridLayoutManager(4)
            adapter = this@SwitchOptionWindow.adapter
        }
    }

    private fun updateSchemaOptionEntries() {
        val switches = rime.run { schemaCached }.switches
        adapter.submitList(
            listOf(
                *staticEntries,
                *switches.mapNotNull { SwitchOptionEntry.fromSwitch(rime, it) }.toTypedArray(),
            ),
        )
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        updateSchemaOptionEntries()
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        updateSchemaOptionEntries()
    }

    private var viewCreated = false

    override fun onCreateView(): View {
        viewCreated = true
        return view
    }

    override fun refreshColors() {
        if (!viewCreated) return
        adapter.refreshColors()
        settingsButton.refreshColors()
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, scope).apply {
            setOnClickListener { AppUtils.launchMainActivity(context) }
        }
    }

    private val barExternalView by lazy {
        context.constraintLayout {
            val size = dp(theme.style.run { candidateViewHeight + commentHeight })
            add(
                settingsButton,
                lParams(size, size) {
                    endOfParent()
                },
            )
        }
    }

    override fun onCreateBarView() = barExternalView

    override fun onAttached() {
        rime.launchOnReady { api ->
            val data = api.currentSchema().switches
            service.lifecycleScope.launch {
                adapter.submitList(
                    listOf(
                        *staticEntries,
                        *data.mapNotNull { SwitchOptionEntry.fromSwitch(rime, it) }.toTypedArray(),
                    ),
                )
            }
        }
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }
}
