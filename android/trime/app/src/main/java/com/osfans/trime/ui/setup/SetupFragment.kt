/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.databinding.FragmentSetupBinding
import com.osfans.trime.util.serializable

class SetupFragment : Fragment() {
    private lateinit var binding: FragmentSetupBinding

    private val page: SetupPage by lazy { requireArguments().serializable("page")!! }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentSetupBinding.inflate(inflater)
        sync()
        return binding.root
    }

    // Called on window focus changed
    fun sync() {
        val done = page.isDone()
        with(binding) {
            stepText.text = page.getStepText(requireContext())
            hintText.text = page.getHintText(requireContext())
            val showActionButton = !done
            actionButton.visibility = if (showActionButton) View.VISIBLE else View.GONE
            actionButton.text = page.getButtonText(requireContext())
            actionButton.setOnClickListener { page.getButtonAction(requireActivity()) }
            doneText.visibility = if (done) View.VISIBLE else View.GONE
            doneIcon.visibility = if (done) View.VISIBLE else View.GONE
        }
    }
}
