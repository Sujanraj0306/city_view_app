package com.civicshield.app.ui.common

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.civicshield.app.R
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.databinding.FragmentAdminZoneHeatmapBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polygon
import kotlin.math.round

/**
 * Shared zone-heatmap map. Re-aggregates /analytics/zones by rounding each
 * feature's centroid to 1 decimal place and draws a colored square Polygon
 * per zone (green=low, amber=medium, red=high).
 *
 * Used by both admin and user map screens. Users get no individual case
 * details here — zones only — per spec.
 */
class ZoneHeatmapFragment : Fragment() {

    private var _binding: FragmentAdminZoneHeatmapBinding? = null
    private val binding get() = _binding!!

    private lateinit var api: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = RetrofitClient.create(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAdminZoneHeatmapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(11.0)
        binding.mapView.controller.setCenter(COIMBATORE)

        loadZones()
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

    private fun loadZones() {
        val b = _binding ?: return
        b.topProgress.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.getAnalyticsZones().features }
            }
            val bind = _binding ?: return@launch
            bind.topProgress.hide()

            result.onSuccess { features ->
                val buckets = reaggregate(features)
                bind.tvEmpty.isVisible = buckets.isEmpty()
                renderPolygons(buckets)
                if (buckets.isNotEmpty()) {
                    val hot = buckets.maxBy { it.total }
                    bind.mapView.controller.setCenter(GeoPoint(hot.lat, hot.lng))
                }
                bind.mapView.invalidate()
            }.onFailure { e ->
                Snackbar.make(
                    bind.root,
                    getString(R.string.map_load_failed, e.message ?: ""),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    private data class Bucket(val lat: Double, val lng: Double, val total: Int)

    private fun reaggregate(
        features: List<com.civicshield.app.data.model.ZoneFeatureDto>,
    ): List<Bucket> {
        val totals = mutableMapOf<Pair<Double, Double>, Int>()
        features.forEach { f ->
            val lng = f.geometry.coordinates.getOrNull(0) ?: return@forEach
            val lat = f.geometry.coordinates.getOrNull(1) ?: return@forEach
            val key = roundTo1(lat) to roundTo1(lng)
            totals[key] = (totals[key] ?: 0) + f.properties.total
        }
        return totals.map { (k, total) -> Bucket(lat = k.first, lng = k.second, total = total) }
    }

    private fun roundTo1(v: Double): Double = round(v * 10.0) / 10.0

    private fun colorFor(total: Int): Int = when {
        total >= 7 -> COLOR_HIGH
        total >= 3 -> COLOR_MEDIUM
        else -> COLOR_LOW
    }

    private fun renderPolygons(buckets: List<Bucket>) {
        val map = _binding?.mapView ?: return
        map.overlays.removeAll { it is Polygon }

        val half = 0.05
        buckets.forEach { b ->
            val color = colorFor(b.total)
            val corners = listOf(
                GeoPoint(b.lat - half, b.lng - half),
                GeoPoint(b.lat - half, b.lng + half),
                GeoPoint(b.lat + half, b.lng + half),
                GeoPoint(b.lat + half, b.lng - half),
                GeoPoint(b.lat - half, b.lng - half),
            )
            val polygon = Polygon(map).apply {
                points = corners
                fillPaint.color = withAlpha(color, 80)
                outlinePaint.color = withAlpha(color, 200)
                outlinePaint.strokeWidth = 2f
            }
            map.overlays.add(polygon)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private val COIMBATORE: GeoPoint = GeoPoint(10.9026, 76.9366)
        private val COLOR_LOW: Int = Color.parseColor("#43A047")
        private val COLOR_MEDIUM: Int = Color.parseColor("#FFB300")
        private val COLOR_HIGH: Int = Color.parseColor("#E53935")
    }
}
