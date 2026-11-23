/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
            binding.modelPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    models.getOrNull(position)?.let { selectedModel = it }
                    sync()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            // 名录从新到旧排列，第一项是默认模型
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
        binding.modelPicker.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            list.map { it.title },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val index = list.indexOfFirst { it.id == selectedModel.id }
        if (index < 0) selectedModel = list.first()
        binding.modelPicker.setSelection(index.coerceAtLeast(0))
        sync()
    }

    // Called on window focus changed
    fun sync() {
        val done = page.isDone()
        with(binding) {
            stepText.text = page.getStepText(requireContext())
            hintText.text = page.getHintText(requireContext(), selectedModel)
            modelPicker.visibility = if (page == SetupPage.Model && !done) View.VISIBLE else View.GONE
            modelPicker.isEnabled = SuzuAssets.state(SuzuAsset.Model) !is SuzuAssets.State.Downloading
            val showActionButton = !done && page.isActionAvailable()
            actionButton.visibility = if (showActionButton) View.VISIBLE else View.GONE
            actionButton.text = page.getButtonText(requireContext())
            actionButton.setOnClickListener { page.getButtonAction(requireActivity(), selectedModel) }
            val percent = page.downloadPercent()
            progressBar.visibility = if (percent != null) View.VISIBLE else View.GONE
            progressBar.progress = percent ?: 0
            secondaryButton.visibility = if (page.canImportFile && !done && SuzuAssets.state(SuzuAsset.Grammar) !is SuzuAssets.State.Downloading) View.VISIBLE else View.GONE
            secondaryButton.text = getString(R.string.suzu_choose_downloaded_file)
            secondaryButton.setOnClickListener { pickGrammar.launch(arrayOf("*/*")) }
            doneText.visibility = if (done) View.VISIBLE else View.GONE
            doneIcon.visibility = if (done) View.VISIBLE else View.GONE
        }
    }
}
