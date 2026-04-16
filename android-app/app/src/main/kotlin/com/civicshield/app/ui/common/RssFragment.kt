package com.civicshield.app.ui.common

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.databinding.FragmentRssBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssFragment : Fragment() {

    private var _binding: FragmentRssBinding? = null
    private val binding get() = _binding!!

    private lateinit var api: ApiService
    private val adapter = RssAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = RetrofitClient.create(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRssBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.lottieEmpty.apply {
            setFailureListener { _ -> /* silently degrade on 404 */ }
            setAnimationFromUrl(LottieUrls.EMPTY_FEED)
        }

        binding.swipeRefresh.setOnRefreshListener { load(initial = false) }

        load(initial = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.lottieEmpty?.cancelAnimation()
        _binding = null
    }

    private fun load(initial: Boolean) {
        val b = _binding ?: return
        if (initial) b.topProgress.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val xml = withContext(Dispatchers.IO) { api.getRssFeed().string() }
                withContext(Dispatchers.Default) { RssParser.parse(xml) }
            }

            val bind = _binding ?: return@launch
            bind.topProgress.hide()
            bind.swipeRefresh.isRefreshing = false

            result.onSuccess { items ->
                adapter.submitList(items)
                val empty = items.isEmpty()
                bind.emptyGroup.isVisible = empty
                bind.recycler.isVisible = !empty
                if (empty) bind.lottieEmpty.playAnimation()
                else bind.lottieEmpty.pauseAnimation()
            }.onFailure { e ->
                Log.w(TAG, "RSS load failed", e)
                Snackbar.make(
                    bind.root,
                    "RSS load failed: ${e.message ?: "unknown"}",
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    companion object {
        private const val TAG = "RssFragment"
    }
}
