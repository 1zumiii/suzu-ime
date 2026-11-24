/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import com.osfans.trime.ui.common.themeColor
import com.osfans.trime.ui.common.mdi
import com.osfans.trime.ui.common.fillRadioCards
import com.osfans.trime.ui.common.SheetOption
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.databinding.FragmentSetupBinding
import com.osfans.trime.suzu.SuzuAsset
import com.osfans.trime.suzu.SuzuAssets
import com.osfans.trime.suzu.SuzuModel
import com.osfans.trime.suzu.SuzuModels
import com.osfans.trime.util.serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SetupFragment : Fragment() {
    private lateinit var binding: FragmentSetupBinding

    private val page: SetupPage by lazy { requireArguments().serializable("page")!! }
    private var selectedModel: SuzuModel = SuzuModels.default
    private var models: List<SuzuModel> = emptyList()

    private val pickGrammar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(SuzuAssets::importGrammar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentSetupBinding.inflate(inflater)
        if (page == SetupPage.Model) {
            val downloading = SuzuAssets.state(SuzuAsset.Model) as? SuzuAssets.State.Downloading
            selectedModel = downloading?.model ?: SuzuModels.byId(savedInstanceState?.getString("selected_model")) ?: SuzuModels.default
            // 列表从新到旧排列，第一项是默认模型
            viewLifecycleOwner.lifecycleScope.launch { SuzuModels.list.collect(::showModels) }
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { SuzuModels.refresh() }
        }
        sync()
        viewLifecycleOwner.lifecycleScope.launch {
            SuzuAssets.states.collect {
                sync()
                (activity as? SetupActivity)?.updateButtons()
            }
        }
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected_model", selectedModel.id)
    }

    private fun showModels(list: List<SuzuModel>) {
        models = list
        if (list.none { it.id == selectedModel.id }) list.firstOrNull()?.let { selectedModel = it }
        sync()
    }

    // Called on window focus changed
    fun sync() {
        val ctx = context ?: return
        val done = page.isDone()
        with(binding) {
            iconTile.background = GradientDrawable().apply {
                setColor(ctx.themeColor(com.google.android.material.R.attr.colorPrimaryContainer))
                cornerRadius = 20 * ctx.resources.displayMetrics.density
            }
            stepIcon.setImageDrawable(ctx.mdi(page.icon, ctx.themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer), 32))
            stepText.text = page.getStepText(ctx)
            titleText.text = page.getTitle(ctx)
            hintText.text = page.getHintText(ctx, selectedModel)
            val showModels = page == SetupPage.Model && !done && models.isNotEmpty()
            modelOptions.visibility = if (showModels) View.VISIBLE else View.GONE
            if (showModels) {
                ctx.fillRadioCards(
                    modelOptions,
                    models.map { SheetOption(it.title, "${it.sizeMb} MB") },
                    models.indexOfFirst { it.id == selectedModel.id },
                    enabled = SuzuAssets.state(SuzuAsset.Model) !is SuzuAssets.State.Downloading,
                ) { i ->
                    selectedModel = models[i]
                    sync()
                }
            }
            val showActionButton = !done && page.isActionAvailable()
            actionButton.visibility = if (showActionButton) View.VISIBLE else View.GONE
            actionButton.text = page.getButtonText(ctx)
            actionButton.setOnClickListener { page.getButtonAction(requireActivity(), selectedModel) }
            val percent = page.downloadPercent()
            progressBar.visibility = if (percent != null) View.VISIBLE else View.GONE
            progressBar.progress = percent ?: 0
            secondaryButton.visibility = if (page.canImportFile && !done && SuzuAssets.state(SuzuAsset.Grammar) !is SuzuAssets.State.Downloading) View.VISIBLE else View.GONE
            secondaryButton.text = getString(R.string.suzu_choose_downloaded_file)
            secondaryButton.setOnClickListener { pickGrammar.launch(arrayOf("*/*")) }
            doneRow.visibility = if (done) View.VISIBLE else View.GONE
            doneIcon.setImageDrawable(ctx.mdi("check_circle", ctx.themeColor(androidx.appcompat.R.attr.colorPrimary)))
        }
    }
}
