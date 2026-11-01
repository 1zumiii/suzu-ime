/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.databinding.FragmentSetupBinding
import com.osfans.trime.suzu.SuzuAsset
import com.osfans.trime.suzu.SuzuAssets
import com.osfans.trime.util.serializable
import kotlinx.coroutines.launch

class SetupFragment : Fragment() {
    private lateinit var binding: FragmentSetupBinding

    private val page: SetupPage by lazy { requireArguments().serializable("page")!! }

    private val pickGrammar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(SuzuAssets::importGrammar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentSetupBinding.inflate(inflater)
        sync()
        viewLifecycleOwner.lifecycleScope.launch {
            SuzuAssets.states.collect {
                sync()
                (activity as? SetupActivity)?.updateButtons()
            }
        }
        return binding.root
    }

    // Called on window focus changed
    fun sync() {
        val done = page.isDone()
        with(binding) {
            stepText.text = page.getStepText(requireContext())
            hintText.text = page.getHintText(requireContext())
            val showActionButton = !done && page.isActionAvailable()
            actionButton.visibility = if (showActionButton) View.VISIBLE else View.GONE
            actionButton.text = page.getButtonText(requireContext())
            actionButton.setOnClickListener { page.getButtonAction(requireActivity()) }
            secondaryButton.visibility = if (page.canImportFile && !done && SuzuAssets.state(SuzuAsset.Grammar) !is SuzuAssets.State.Downloading) View.VISIBLE else View.GONE
            secondaryButton.text = "选择已下载的文件"
            secondaryButton.setOnClickListener { pickGrammar.launch(arrayOf("*/*")) }
            doneText.visibility = if (done) View.VISIBLE else View.GONE
            doneIcon.visibility = if (done) View.VISIBLE else View.GONE
        }
    }
}
