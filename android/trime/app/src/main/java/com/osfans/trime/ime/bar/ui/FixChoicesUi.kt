/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.candidates.CandidateItemUi
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view

// 光标换词：占用候选栏，候选（已按整句分排好）的样子与普通候选相同，第一个高亮；右侧是关闭按钮
class FixChoicesUi(
    override val ctx: Context,
    private val scope: ThemeScope,
) : Ui {
    var onChoose: ((Int) -> Unit)? = null

    private var options: List<String> = emptyList()

    private val row = horizontalLayout { }

    private val scroll = view(::HorizontalScrollView) {
        isHorizontalScrollBarEnabled = false
        addView(row, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    val dismiss = ToolButton(ctx, R.drawable.ic_outline_cancel_24, scope)

    override val root = constraintLayout {
        add(
            scroll,
            lParams(matchConstraints, matchParent) {
                startOfParent()
                before(dismiss)
                centerVertically()
            },
        )
        add(
            dismiss,
            lParams(dp(40), matchParent) {
                endOfParent()
                centerVertically()
            },
        )
    }

    fun show(options: List<String>) {
        this.options = options
        row.removeAllViews()
        options.forEachIndexed { i, w ->
            val item = CandidateItemUi(ctx, scope)
            item.update(CandidateProto(w, "", ""), i == 0)
            item.root.setOnClickListener { onChoose?.invoke(i) }
            row.addView(
                item.root,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT),
            )
        }
        scroll.scrollTo(0, 0)
    }

    /** Re-applies the colors that were set once at construction. */
    fun refreshColors() {
        dismiss.refreshColors()
        if (options.isNotEmpty()) show(options)
    }
}
