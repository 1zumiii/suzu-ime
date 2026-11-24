/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.TextViewCompat
import com.google.android.material.R as MR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/*
 * 底部弹窗与对话框的公共样式：顶部是图标方块和大标题，选项是圆角卡片，选中的用主色容器色。
 */

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Context.text(
    s: CharSequence,
    appearanceAttr: Int,
    colorAttr: Int = MR.attr.colorOnSurface,
) = TextView(this).apply {
    text = s
    val tv = TypedValue()
    if (theme.resolveAttribute(appearanceAttr, tv, true)) TextViewCompat.setTextAppearance(this, tv.resourceId)
    setTextColor(themeColor(colorAttr))
}

private fun Context.rounded(
    color: Int,
    radiusDp: Int,
) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

/** 标题区：图标方块 + 大标题 + 可选说明 */
private fun Context.sheetHeader(
    icon: String,
    title: CharSequence,
    message: CharSequence?,
): List<View> {
    val tile = FrameLayout(this).apply {
        background = rounded(themeColor(MR.attr.colorPrimaryContainer), 16)
        addView(
            ImageView(this@sheetHeader).apply {
                setImageDrawable(mdi(icon, themeColor(MR.attr.colorOnPrimaryContainer)))
            },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
        )
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
    }
    val t = text(title, MR.attr.textAppearanceHeadlineSmall).apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        }
    }
    val views = mutableListOf<View>(tile, t)
    if (!message.isNullOrBlank()) {
        views += text(message, MR.attr.textAppearanceBodyLarge, MR.attr.colorOnSurfaceVariant).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
    }
    return views
}

private fun Context.buttonRow(vararg buttons: MaterialButton) =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        buttons.forEach { b ->
            addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        }
    }

private fun Context.textButton(label: CharSequence) =
    MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply { text = label }

private fun Context.filledButton(label: CharSequence) =
    MaterialButton(this).apply { text = label }

private fun Context.newSheet(content: LinearLayout): BottomSheetDialog {
    val dialog = BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(BottomSheetDragHandleView(this@newSheet))
        content.setPadding(dp(24), 0, dp(24), dp(24))
        addView(content)
    }
    dialog.setContentView(root)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    return dialog
}

class SheetOption(
    val title: CharSequence,
    val subtitle: CharSequence? = null,
)

/**
 * 单选底部弹窗：[initial] 是打开时选中的项，[current] 是正在使用的项（选它时确认按钮不可点）。
 * [confirmLabel] 按选中项给出确认按钮的文字。
 */
fun Context.choiceSheet(
    icon: String,
    title: CharSequence,
    message: CharSequence?,
    options: List<SheetOption>,
    current: Int?,
    initial: Int,
    confirmLabel: (Int) -> CharSequence,
    onConfirm: (Int) -> Unit,
) {
    var selected = initial
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHeader(icon, title, message).forEach(content::addView)
    val confirm = filledButton("")
    val cancel = textButton(getString(android.R.string.cancel))
    val rows = mutableListOf<Pair<LinearLayout, MaterialRadioButton>>()
    fun render() {
        rows.forEachIndexed { i, (row, radio) ->
            val on = i == selected
            row.background = rounded(
                themeColor(if (on) MR.attr.colorPrimaryContainer else MR.attr.colorSurfaceContainerLowest),
                16,
            )
            radio.isChecked = on
        }
        confirm.text = confirmLabel(selected)
        confirm.isEnabled = selected != current
    }
    options.forEachIndexed { i, opt ->
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            isFocusable = false
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(opt.title, MR.attr.textAppearanceTitleMedium))
            opt.subtitle?.let { addView(text(it, MR.attr.textAppearanceBodyMedium, MR.attr.colorOnSurfaceVariant)) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(16), dp(12))
            addView(radio)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            isClickable = true
            setOnClickListener {
                selected = i
                render()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(if (i == 0) 20 else 8)
            }
        }
        rows += row to radio
        content.addView(row)
    }
    content.addView(buttonRow(cancel, confirm))
    val dialog = newSheet(content)
    cancel.setOnClickListener { dialog.dismiss() }
    confirm.setOnClickListener {
        dialog.dismiss()
        onConfirm(selected)
    }
    render()
    dialog.show()
}

class SheetAction(
    val title: CharSequence,
    val subtitle: CharSequence? = null,
    /** 左侧图标（MDI 名），没有就不留位置 */
    val leadingIcon: String? = null,
    /** 右侧图标（MDI 名） */
    val trailingIcon: String? = null,
    /** 只点右侧图标才触发（如删除），否则整行可点 */
    val trailingOnly: Boolean = false,
    val onClick: () -> Unit,
)

/** 操作列表底部弹窗：每行一个操作，行间有分隔线，底部一个关闭按钮 */
fun Context.actionSheet(
    icon: String,
    title: CharSequence,
    actions: List<SheetAction>,
    closeLabel: CharSequence,
    emptyText: CharSequence? = null,
) {
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheetHeader(icon, title, null).forEach(content::addView)
    lateinit var dialog: BottomSheetDialog
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        }
    }
    if (actions.isEmpty() && emptyText != null) {
        list.addView(text(emptyText, MR.attr.textAppearanceBodyLarge, MR.attr.colorOnSurfaceVariant).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
    }
    actions.forEachIndexed { i, a ->
        if (i > 0) {
            list.addView(
                View(this).apply { setBackgroundColor(themeColor(MR.attr.colorOutlineVariant)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                },
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(8), dp(8), dp(4), dp(8))
        }
        a.leadingIcon?.let {
            row.addView(
                ImageView(this).apply { setImageDrawable(mdi(it)) },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) },
            )
        }
        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(a.title, MR.attr.textAppearanceTitleMedium))
                a.subtitle?.let { addView(text(it, MR.attr.textAppearanceBodyMedium, MR.attr.colorOnSurfaceVariant)) }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val run = {
            dialog.dismiss()
            a.onClick()
        }
        a.trailingIcon?.let {
            row.addView(
                ImageView(this).apply {
                    setImageDrawable(mdi(it, themeColor(MR.attr.colorOnSurfaceVariant)))
                    scaleType = ImageView.ScaleType.CENTER
                    if (a.trailingOnly) {
                        val tv = TypedValue()
                        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                        setBackgroundResource(tv.resourceId)
                        contentDescription = a.title
                        setOnClickListener { run() }
                    }
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        }
        if (!a.trailingOnly) {
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            row.setBackgroundResource(tv.resourceId)
            row.setOnClickListener { run() }
        }
        list.addView(row)
    }
    content.addView(list)
    val close = textButton(closeLabel)
    content.addView(buttonRow(close))
    dialog = newSheet(content)
    close.setOnClickListener { dialog.dismiss() }
    dialog.show()
}

/** 删除类确认：确认按钮用错误色 */
fun AlertDialog.Builder.showDestructive(): AlertDialog =
    show().also { d ->
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.let { b ->
            val ctx = b.context
            (b as? MaterialButton)?.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(androidx.appcompat.R.attr.colorError))
            b.setTextColor(ctx.themeColor(MR.attr.colorOnError))
        }
    }

/** 对话框里的“标签 + 输入框”，标签在输入框上方 */
class LabeledField(
    context: Context,
    label: CharSequence,
    value: String,
    hint: CharSequence? = null,
) {
    val input: TextInputEditText
    val view: LinearLayout

    init {
        val layout = TextInputLayout(context, null, MR.attr.textInputOutlinedStyle).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            placeholderText = hint
        }
        input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(value)
        }
        layout.addView(input)
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                context.text(label, MR.attr.textAppearanceBodyLarge, MR.attr.colorOnSurfaceVariant).apply {
                    setPadding(0, 0, 0, context.dp(8))
                },
            )
            addView(layout)
        }
    }
}

/** 文本变化时回调，用来随输入启用或禁用保存按钮 */
fun TextInputEditText.onTextChanged(block: () -> Unit) {
    addTextChangedListener(
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) = block()
        },
    )
}

/** 对话框内一行带图标的操作（如“删除这条映射”） */
fun Context.dialogActionRow(
    icon: String,
    label: CharSequence,
    onClick: () -> Unit,
): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(4), 0, 0, 0)
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        addView(ImageView(context).apply { setImageDrawable(mdi(icon)) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        addView(text(label, MR.attr.textAppearanceTitleMedium), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(context).apply { setImageResource(com.osfans.trime.R.drawable.ic_chevron_right_24); imageTintList = ColorStateList.valueOf(themeColor(MR.attr.colorOnSurfaceVariant)) })
        setOnClickListener { onClick() }
    }

/** 在 [container] 里铺一列单选卡片（引导页选模型用），选中的用主色容器色；每次调用都重建 */
fun Context.fillRadioCards(
    container: LinearLayout,
    options: List<SheetOption>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    container.removeAllViews()
    options.forEachIndexed { i, opt ->
        val on = i == selected
        val radio = MaterialRadioButton(this).apply {
            isChecked = on
            isClickable = false
            isFocusable = false
            isEnabled = enabled
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(opt.title, MR.attr.textAppearanceTitleMedium))
            opt.subtitle?.let { addView(text(it, MR.attr.textAppearanceBodyMedium, MR.attr.colorOnSurfaceVariant)) }
        }
        container.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(16), dp(12))
                background = rounded(
                    if (on) themeColor(MR.attr.colorPrimaryContainer) else getColor(com.osfans.trime.R.color.m3_card),
                    16,
                )
                alpha = if (enabled) 1f else 0.6f
                addView(radio)
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (enabled) {
                    isClickable = true
                    setOnClickListener { onSelect(i) }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(if (i == 0) 12 else 8)
            },
        )
    }
}
