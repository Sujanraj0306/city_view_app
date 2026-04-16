package com.civicshield.app.ui.common

import android.os.Bundle
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

        binding.swipeRefresh.setOnRefreshListener { load(initial = false) }

        load(initial = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load(initial: Boolean) {
        if (initial) binding.progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val xml = withContext(Dispatchers.IO) {
                    api.getRssFeed().string()
                }
                val items = withContext(Dispatchers.Default) {
                    RssParser.parse(xml)
                }
                _binding?.let {
                    adapter.submitList(items)
                    it.tvEmpty.isVisible = items.isEmpty()
                }
            } catch (e: Exception) {
                showSnack("RSS load failed: ${e.message}")
            } finally {
                _binding?.let {
                    it.progress.isVisible = false
                    it.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun showSnack(text: String) {
        val root = _binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    }
}
