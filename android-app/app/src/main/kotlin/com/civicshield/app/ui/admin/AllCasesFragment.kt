package com.civicshield.app.ui.admin

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
import com.civicshield.app.data.model.CaseStatusUpdate
import com.civicshield.app.databinding.FragmentAllCasesBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AllCasesFragment : Fragment() {

    private var _binding: FragmentAllCasesBinding? = null
    private val binding get() = _binding!!

    private lateinit var api: ApiService
    private lateinit var adapter: CaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = RetrofitClient.create(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAllCasesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = CaseAdapter(onStatusChanged = ::changeStatus)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { refresh(initial = false) }

        refresh(initial = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh(initial: Boolean) {
        if (initial) binding.progress.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    api.listAdminCases(page = 1, limit = 100)
                }
                _binding?.let { b ->
                    adapter.submitList(page.items)
                    b.tvEmpty.isVisible = page.items.isEmpty()
                }
            } catch (e: Exception) {
                showSnack("Failed to load cases: ${e.message}")
            } finally {
                _binding?.let {
                    it.progress.isVisible = false
                    it.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun changeStatus(caseId: String, newStatus: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.updateCaseStatus(caseId, CaseStatusUpdate(newStatus))
                }
                showSnack("Case ${resp.caseId.take(8)} → ${resp.status}")
                refresh(initial = false)
            } catch (e: Exception) {
                showSnack("Update failed: ${e.message}")
                refresh(initial = false) // resync spinner with server state
            }
        }
    }

    private fun showSnack(text: String) {
        val root = _binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_SHORT).show()
    }
}
