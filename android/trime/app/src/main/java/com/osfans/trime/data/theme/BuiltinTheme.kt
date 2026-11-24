// 内置主题：外观取自同文风主题，配色见 lightScheme 与 darkScheme

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.Preedit
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.data.theme.model.ToolBar.Button
import com.osfans.trime.data.theme.model.ToolBar.Button.Background
import com.osfans.trime.data.theme.model.ToolBar.Button.Foreground
import com.osfans.trime.data.theme.model.Window
import com.osfans.trime.ime.keyboard.KeyCommand
import com.osfans.trime.ime.keyboard.SymbolGroup

object BuiltinTheme {
    const val LIGHT_SCHEME = "default"
    const val DARK_SCHEME = "dark"

    val theme by lazy {
        Theme(
            name = "Suzu",
            style = style,
            preedit = preedit,
            window = window,
            presetColorSchemes = mapOf(LIGHT_SCHEME to lightScheme, DARK_SCHEME to darkScheme),
            fallbackColors = mapOf("candidate_text_color" to "text_color"),
            toolBar = toolBar,
        )
    }

    private val style = GeneralStyle(
        autoCaps = false,
        candidateFont = listOf("han.ttf"),
        candidatePadding = 5,
        candidateSpacing = 0.0f,
        candidateTextSize = 18.0f,
        candidateTextVerticalBias = 1.0f,
        candidateViewHeight = 28,
        commentFont = listOf("comment.ttf"),
        commentHeight = 12,
        commentPosition = GeneralStyle.CommentPosition.RIGHT,
        commentVerticalBias = 0.0f,
        commentTextSize = 10.0f,
        hanbFont = listOf("hanb.ttf"),
        horizontalGap = 4,
        keyFont = listOf("symbol.ttf"),
        keyHeight = 48,
        keyLongTextSize = 16.0f,
        keyTextSize = 24.0f,
        keyWidth = 10.0f,
        labelTextSize = 22.0f,
        labelFont = listOf("label.ttf"),
        latinFont = listOf("latin.ttf"),
        keyboardHeight = 245,
        keyboardHeightLand = 180,
        popupFont = listOf("latin.ttf"),
        popupWidth = 38,
        popupHeight = 48,
        popupBottomMargin = 68,
        popupKeyHeight = 48,
        popupTextSize = 23.0f,
        resetAsciiModeOnFocusChange = false,
        roundCorner = 6.0f,
        shadowRadius = 0.0f,
        symbolFont = listOf("symbol.ttf"),
        symbolTextSize = 10.0f,
        textFont = listOf("latin.ttf"),
        verticalGap = 12,
        enterLabelMode = 0,
        enterLabels = GeneralStyle.EnterLabel(go = "前往", done = "完成", next = "下个", pre = "上个", search = "搜索", send = "发送", default = "ic@keyboard_return"),
    )

    private val preedit = Preedit(horizontalPadding = 8, topStartRadius = 4.0f, topEndRadius = 4.0f, foreground = Preedit.Foreground(fontSize = 16.0f))

    private val window = Window(insets = Window.Padding(4, 4), itemPadding = Window.Padding(2, 4), foreground = Window.Foreground(20.0f, 20.0f, 16.0f))

    private val toolBar = ToolBar(
        primaryButton = Button(
            background = Background(type = Background.Type.CIRCLE, highlight = "hilited_candidate_button_color"),
            foreground = Foreground(style = "ic@dots_horizontal", normal = "candidate_text_color", padding = 10),
            command = KeyCommand.OpenMenu,
            longPressCommand = KeyCommand.OpenSettings,
        ),
        buttons = listOf(
            Button(foreground = Foreground(style = "ic@keyboard_close", normal = "candidate_text_color"), command = KeyCommand.Hide),
            Button(foreground = Foreground(style = "ic@emoticon"), command = KeyCommand.OpenSymbols(SymbolGroup.Emoji)),
            Button(foreground = Foreground(style = "ic@clipboard"), command = KeyCommand.OpenClipboard),
            Button(foreground = Foreground(style = "ic@cursor_text"), command = KeyCommand.OpenEditor),
            Button(foreground = Foreground(optionStyles = listOf("中", "En"), padding = 10), command = KeyCommand.ToggleAscii),
        ),
        buttonSpacing = 5,
        buttonFont = listOf("iconfont.ttf"),
        backStyle = "ic@arrow-left",
    )

    private val lightScheme: ColorScheme = mapOf(
        "back_color" to "#FFFFFFFF",
        "candidate_separator_color" to "#FFFFFFFF",
        "candidate_text_color" to "#FF000000",
        "comment_text_color" to "#FFE1E3E7",
        "hilited_back_color" to "#FFFFFFFF",
        "hilited_candidate_back_color" to "#FF3266A0",
        "hilited_candidate_text_color" to "#FFFFFFFF",
        "hilited_comment_text_color" to "#FF000000",
        "hilited_key_back_color" to "#FFC4C4C4",
        "hilited_key_symbol_color" to "#FF444444",
        "hilited_key_text_color" to "#FF000000",
        "hilited_off_key_back_color" to "#FFC4C4C4",
        "hilited_off_key_text_color" to "#FF000000",
        "hilited_text_color" to "#FF000000",
        "key_back_color" to "#00000000",
        "key_symbol_color" to "#FF444444",
        "key_text_color" to "#FF000000",
        "keyboard_back_color" to "#FFE2E2E2",
        "label_color" to "#FF3266A0",
        "off_key_back_color" to "#00000000",
        "off_key_text_color" to "#FF000000",
        "preview_back_color" to "#FFACB2C2",
        "preview_text_color" to "#FF3266A0",
        "text_color" to "#FF3266A0",
        "text_back_color" to "#FFFFFFFF",
        "bkg" to "#00000000",
        "tkg" to "#FF000000",
        "benter" to "#00000000",
        "tenter" to "#FF000000",
        "bgn" to "#00000000",
        "tgn" to "#FF000000",
        "bbs" to "#00000000",
        "tbs" to "#FF000000",
        "baoe" to "#00000000",
        "taoe" to "#FF000000",
        "bh1" to "#00000000",
        "th1" to "#FF000000",
        "bh2" to "#00000000",
        "th2" to "#FF000000",
        "bh3" to "#00000000",
        "th3" to "#FF000000",
        "bh4" to "#00000000",
        "th4" to "#FF000000",
        "bactive" to "#FFCDD1D8",
        "tactive" to "#FF000000",
    )

    /**
     * 打开“按键背景”时叠加在配色上的按键底色：字母键一种颜色，功能键稍深。
     * 关闭时两套配色的按键底色都是透明的。
     */
    fun keyBackgroundOverlay(dark: Boolean): ColorScheme {
        val key = if (dark) "#FF3A3B3F" else "#FFFFFFFF"
        val functional = if (dark) "#FF2B2C30" else "#FFCDD1D8"
        return mapOf(
            "key_back_color" to key,
            "bh1" to key, "bh2" to key, "bh3" to key, "bh4" to key, "baoe" to key, "bkg" to key,
            "off_key_back_color" to functional,
            "bgn" to functional, "bbs" to functional, "benter" to functional,
        )
    }

    private val darkScheme: ColorScheme = mapOf(
        "back_color" to "#FF2B2C30",
        "candidate_separator_color" to "#FF2B2C30",
        "candidate_text_color" to "#FFE8EAED",
        "comment_text_color" to "#FF80868B",
        "hilited_back_color" to "#FF2B2C30",
        "hilited_candidate_back_color" to "#FF4A4D52",
        "hilited_candidate_text_color" to "#FFFFFFFF",
        "hilited_comment_text_color" to "#FFE8EAED",
        "hilited_key_back_color" to "#FF4A4D52",
        "hilited_key_symbol_color" to "#FFA8ADB3",
        "hilited_key_text_color" to "#FFE8EAED",
        "hilited_off_key_back_color" to "#FF4A4D52",
        "hilited_off_key_text_color" to "#FFE8EAED",
        "hilited_text_color" to "#FFE8EAED",
        "key_back_color" to "#00000000",
        "key_symbol_color" to "#FFA8ADB3",
        "key_text_color" to "#FFE8EAED",
        "keyboard_back_color" to "#FF1F2023",
        "label_color" to "#FFE8EAED",
        "off_key_back_color" to "#00000000",
        "off_key_text_color" to "#FFE8EAED",
        "preview_back_color" to "#FF4A4D52",
        "preview_text_color" to "#FFFFFFFF",
        "text_color" to "#FFE8EAED",
        "text_back_color" to "#FF3A3B3F",
        "bkg" to "#00000000",
        "tkg" to "#FFE8EAED",
        "benter" to "#00000000",
        "tenter" to "#FFE8EAED",
        "bgn" to "#00000000",
        "tgn" to "#FFE8EAED",
        "bbs" to "#00000000",
        "tbs" to "#FFE8EAED",
        "baoe" to "#00000000",
        "taoe" to "#FFE8EAED",
        "bh1" to "#00000000",
        "th1" to "#FFE8EAED",
        "bh2" to "#00000000",
        "th2" to "#FFE8EAED",
        "bh3" to "#00000000",
        "th3" to "#FFE8EAED",
        "bh4" to "#00000000",
        "th4" to "#FFE8EAED",
        "bactive" to "#FF3C4043",
        "tactive" to "#FFE8EAED",
    )

}
