package com.civicshield.app.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.civicshield.app.R
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.model.CaseAdminItem
import com.civicshield.app.databinding.FragmentAdminCasePlotsBinding
import com.civicshield.app.ui.common.MapPins
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

/**
 * Admin "Case Plots" tab: every case as a teardrop marker on the map,
 * with a filter-chip row across the top. Tapping a marker opens a small
 * info window with a "View Full" button that launches [AdminCaseDetailActivity].
 */
class AdminCasePlotsFragment : Fragment() {

    private var _binding: FragmentAdminCasePlotsBinding? = null
    private val binding get() = _binding!!

    private lateinit var api: ApiService

    private enum class Filter { ALL, HELMET, POTHOLE, COMPLETED }
    private var currentFilter: Filter = Filter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = RetrofitClient.create(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAdminCasePlotsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(13.0)
        binding.mapView.controller.setCenter(COIMBATORE)

        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            currentFilter = when (id) {
                R.id.chipHelmet -> Filter.HELMET
                R.id.chipPothole -> Filter.POTHOLE
                R.id.chipCompleted -> Filter.COMPLETED
                else -> Filter.ALL
            }
            refresh()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        _binding?.mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        _binding?.mapView?.onPause()
    }

    override fun onDestroyView() {
        _binding?.mapView?.onDetach()
        super.onDestroyView()
        _binding = null
    }

    /** Re-fetches cases per spec ("tapping a chip re-fetches and redraws markers"). */
    private fun refresh() {
        val b = _binding ?: return
        b.topProgress.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val typeFilter: String? = when (currentFilter) {
                        Filter.HELMET -> "helmet"
                        Filter.POTHOLE -> "pothole"
                        else -> null
                    }
                    val statusFilter: String? = when (currentFilter) {
                        Filter.COMPLETED -> "completed"
                        else -> null
                    }
                    api.listAdminCases(
                        page = 1,
                        limit = 500,
                        type = typeFilter,
                        status = statusFilter,
                    ).items
                }
            }

            val bind = _binding ?: return@launch
            bind.topProgress.hide()

            result.onSuccess { cases -> renderCases(cases) }
                .onFailure { e ->
                    Snackbar.make(
                        bind.root,
                        getString(R.string.map_load_failed, e.message ?: ""),
                        Snackbar.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun renderCases(cases: List<CaseAdminItem>) {
        val b = _binding ?: return
        val map = b.mapView
        // Clear previous markers (but keep the base tile layer).
        map.overlays.removeAll { it is Marker }

        val ctx = requireContext()
        cases.forEach { c ->
            val color = MapPins.colorFor(c.type, c.status)
            val marker = Marker(map).apply {
                position = GeoPoint(c.latitude, c.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = MapPins.teardrop(ctx, color)
                relatedObject = c
                infoWindow = CaseMarkerInfoWindow(R.layout.marker_info_window, map)
            }
            map.overlays.add(marker)
        }

        if (cases.isNotEmpty()) {
            val first = cases.first()
            map.controller.setCenter(GeoPoint(first.latitude, first.longitude))
        }
        map.invalidate()
    }

    companion object {
        private val COIMBATORE: GeoPoint = GeoPoint(10.9026, 76.9366)
    }
}
