package com.civicshield.app.ui.user

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
import com.civicshield.app.databinding.FragmentMyCasesBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyCasesFragment : Fragment() {

    private var _binding: FragmentMyCasesBinding? = null
    private val binding get() = _binding!!

    private lateinit var api: ApiService
    private val adapter = UserCaseAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = RetrofitClient.create(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMyCasesBinding.inflate(inflater, container, false)
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
                val cases = withContext(Dispatchers.IO) { api.listMyCases(userId = "me") }
                _binding?.let {
                    adapter.submitList(cases)
                    it.tvEmpty.isVisible = cases.isEmpty()
                }
            } catch (e: Exception) {
                val root = _binding?.root ?: return@launch
                Snackbar.make(root, "Load failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                _binding?.let {
                    it.progress.isVisible = false
                    it.swipeRefresh.isRefreshing = false
                }
            }
        }
    }
}
