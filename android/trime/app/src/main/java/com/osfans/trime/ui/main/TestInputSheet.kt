/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.R as MR
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.settingsSheet
import splitties.dimensions.dp

private class TestInputMode(val label: Int, val hint: Int, val icon: Int, val inputType: Int)

internal fun Context.showTestInputSheet(
    initialMode: Int,
    onModeSelected: (Int) -> Unit,
): BottomSheetDialog {
    val modes = listOf(
        TestInputMode(R.string.text, R.string.type_text, R.drawable.ic_baseline_text_fields_24, InputType.TYPE_CLASS_TEXT),
        TestInputMode(R.string.number, R.string.type_number, R.drawable.ic_baseline_numbers_24, InputType.TYPE_CLASS_NUMBER),
        TestInputMode(
            R.string.password,
            R.string.type_password,
            R.drawable.ic_baseline_lock_24,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ),
    )
    val field = TextInputLayout(this, null, MR.attr.textInputOutlinedStyle).apply {
        endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
    }
    val input = TextInputEditText(field.context).apply {
        maxLines = 1
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
    }
    field.addView(input)
    val buttons = modes.map { mode ->
        MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            setText(mode.label)
        }
    }
    val modesGroup = MaterialButtonToggleGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                val index = buttons.indexOfFirst { it.id == id }
                val mode = modes[index]
                onModeSelected(index)
                input.inputType = mode.inputType
                input.setSelection(input.length())
                field.hint = getString(mode.hint)
                field.setStartIconDrawable(mode.icon)
            }
        }
        check(buttons[initialMode].id)
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(modesGroup)
        addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
    }
    val dialog = settingsSheet(
        icon = "keyboard_outline",
        title = getString(R.string.test_input),
        body = content,
        confirm = SheetButton(getString(R.string.done)),
    ).apply { show() }
    input.requestFocus()
    input.post {
        if (dialog.isShowing) {
            dialog.window?.let { WindowCompat.getInsetsController(it, input).show(WindowInsetsCompat.Type.ime()) }
        }
    }
    return dialog
}
