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
import com.osfans.trime.util.serializable
import kotlinx.coroutines.launch

class SetupFragment : Fragment() {
    private lateinit var binding: FragmentSetupBinding

    private val page: SetupPage by lazy { requireArguments().serializable("page")!! }
    private var selectedModel = SuzuModel.KD500

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
            selectedModel = downloading?.model ?: SuzuModel.entries.firstOrNull {
                it.name == savedInstanceState?.getString("selected_model")
            } ?: selectedModel
            binding.modelPicker.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                SuzuModel.entries.map { model ->
                    if (model == SuzuModel.KD500) {
                        getString(R.string.suzu_setup_model_recommended, model.title)
                    } else {
                        model.title
                    }
                },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            binding.modelPicker.setSelection(selectedModel.ordinal)
            binding.modelPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedModel = SuzuModel.entries[position]
                    sync()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
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
        outState.putString("selected_model", selectedModel.name)
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
