/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.resources.MaterialAttributes

/**
 * 设置 App 里的对话框统一用 Material 3 样式。
 * 键盘（输入法服务）的上下文不是 Material 主题，MaterialAlertDialogBuilder 在那里会抛异常，
 * 所以先看主题，不是 Material 3 就退回普通的 AlertDialog。
 */
fun materialDialog(context: Context): AlertDialog.Builder =
    if (MaterialAttributes.resolveBoolean(context, com.google.android.material.R.attr.isMaterial3Theme, false)) {
        MaterialAlertDialogBuilder(context)
    } else {
        AlertDialog.Builder(context)
    }

/**
 * 读主题颜色。Material 3 主题里 colorControlNormal 等属性是颜色列表（ColorStateList），
 * splitties 的 styledColor 只认纯色，遇到会抛 IllegalArgumentException；这里取颜色列表的默认色。
 */
fun Context.themeColor(@androidx.annotation.AttrRes attr: Int): Int {
    val tv = android.util.TypedValue()
    check(theme.resolveAttribute(attr, tv, true)) { "theme attribute $attr not set" }
    return if (tv.resourceId != 0) {
        androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
    } else {
        tv.data
    }
}

fun android.view.View.themeColor(@androidx.annotation.AttrRes attr: Int): Int = context.themeColor(attr)
