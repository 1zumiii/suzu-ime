/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.osfans.trime.R
import com.osfans.trime.util.InputMethodUtils

enum class SetupPage {
    Enable,
    Select,
    ;

    fun getStepText(context: Context) = context.getText(
        when (this) {
            Enable -> R.string.setup__step_one
            Select -> R.string.setup__step_two
        },
    )

    fun getHintText(context: Context) = context.getText(
        when (this) {
            Enable -> R.string.setup__enable_ime_hint
            Select -> R.string.setup__select_ime_hint
        },
    )

    fun getButtonText(context: Context) = context.getText(
        when (this) {
            Enable -> R.string.setup__enable_ime
            Select -> R.string.setup__select_ime
        },
    )

    fun getButtonAction(activity: FragmentActivity) {
        when (this) {
            Enable -> InputMethodUtils.showImeEnablerActivity(activity)
            Select -> InputMethodUtils.showImePicker()
        }
    }

    fun isDone() = when (this) {
        Enable -> InputMethodUtils.checkIsTrimeEnabled()
        Select -> InputMethodUtils.checkIsTrimeSelected()
    }

    companion object {
        fun SetupPage.isLastPage() = this == entries.last()

        fun Int.isLastPage() = this == entries.size - 1

        fun hasUndonePage() = entries.any { !it.isDone() }

        fun firstUndonePage() = entries.firstOrNull { !it.isDone() }
    }
}
