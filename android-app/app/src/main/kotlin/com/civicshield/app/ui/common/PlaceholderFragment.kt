package com.civicshield.app.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.civicshield.app.databinding.FragmentPlaceholderBinding

/**
 * Reused by every bottom-nav tab until each tab is fleshed out with real behaviour.
 * Subclasses provide `title` and optional `subtitle` overrides.
 */
abstract class PlaceholderFragment : Fragment() {

    protected abstract val title: String
    protected open val subtitle: String = "Coming soon."

    private var _binding: FragmentPlaceholderBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        binding.tvTitle.text = title
        binding.tvSubtitle.text = subtitle
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
