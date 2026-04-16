package com.civicshield.app.ui.user

import android.content.Intent
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
import com.civicshield.app.data.local.AuthStore
import com.civicshield.app.data.model.CaseAdminItem
import com.civicshield.app.databinding.FragmentMyCasesBinding
import com.civicshield.app.ui.login.LoginActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

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
        val b = _binding ?: return
        if (initial) b.topProgress.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.listMyCases(userId = "me") }
            }
            val bind = _binding ?: return@launch
            bind.topProgress.hide()
            bind.swipeRefresh.isRefreshing = false

            result.onSuccess { raw ->
                val cases: List<CaseAdminItem> = raw.orEmpty()
                adapter.submitList(cases)
                bind.emptyGroup.isVisible = cases.isEmpty()
                bind.recycler.isVisible = cases.isNotEmpty()
            }.onFailure { e ->
                Log.w(TAG, "listMyCases failed", e)
                adapter.submitList(emptyList())
                bind.emptyGroup.isVisible = true
                bind.recycler.isVisible = false

                when {
                    e is HttpException && e.code() == 401 -> handleUnauthorized()
                    else -> Snackbar.make(
                        bind.root,
                        "Could not load cases: ${e.message ?: "unknown error"}",
                        Snackbar.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun handleUnauthorized() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            AuthStore(ctx.applicationContext).clear()
            startActivity(
                Intent(ctx, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            activity?.finish()
        }
    }

    companion object {
        private const val TAG = "MyCasesFragment"
    }
}
