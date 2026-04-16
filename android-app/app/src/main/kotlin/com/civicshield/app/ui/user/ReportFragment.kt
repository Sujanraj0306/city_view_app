package com.civicshield.app.ui.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.civicshield.app.databinding.FragmentReportBinding
import com.civicshield.app.ui.common.LottieUrls
import com.google.android.material.tabs.TabLayoutMediator

class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lottieHero.apply {
            setFailureListener { _ -> /* silently degrade on 404 */ }
            setAnimationFromUrl(LottieUrls.HOME_HERO)
            playAnimation()
        }

        binding.viewPager.adapter = ReportPagerAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = ReportType.entries[position].displayLabel
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ReportPagerAdapter(
        fm: FragmentManager,
        lifecycle: Lifecycle,
    ) : FragmentStateAdapter(fm, lifecycle) {

        override fun getItemCount() = ReportType.entries.size

        override fun createFragment(position: Int): Fragment {
            return ReportFormFragment.newInstance(ReportType.entries[position])
        }
    }
}
