package com.civicshield.app.ui.admin

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.civicshield.app.BuildConfig
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.databinding.FragmentAdminMapBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

class MapFragment : Fragment() {

    private var _binding: FragmentAdminMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var api: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = requireContext().applicationContext
        Configuration.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = BuildConfig.APPLICATION_ID
        }
        api = RetrofitClient.create(ctx)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAdminMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(12.5)
        binding.mapView.controller.setCenter(COIMBATORE)

        loadOverlays()
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

    private fun loadOverlays() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                coroutineScope {
                    val casesDeferred = async(Dispatchers.IO) {
                        api.listAdminCases(page = 1, limit = 500).items
                    }
                    val zonesDeferred = async(Dispatchers.IO) {
                        api.getAnalyticsZones().features
                    }

                    val cases = casesDeferred.await()
                    val zones = zonesDeferred.await()

                    val binding = _binding ?: return@coroutineScope
                    val map = binding.mapView

                    // Heatmap underneath markers
                    val zonePoints = zones.mapNotNull { f ->
                        val lng = f.geometry.coordinates.getOrNull(0) ?: return@mapNotNull null
                        val lat = f.geometry.coordinates.getOrNull(1) ?: return@mapNotNull null
                        HeatmapOverlay.ZonePoint(
                            lat = lat,
                            lng = lng,
                            total = f.properties.total,
                            helmetCount = f.properties.helmetCount,
                            potholeCount = f.properties.potholeCount,
                        )
                    }
                    map.overlays.add(HeatmapOverlay(zonePoints))

                    // Per-case markers on top
                    cases.forEach { c ->
                        val marker = Marker(map).apply {
                            position = GeoPoint(c.latitude, c.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "${c.type} · ${c.status}"
                            subDescription = "by ${c.user.username} · AI=${c.aiVerified}"
                            icon = coloredPinDrawable(
                                if (c.type == "helmet") Color.parseColor("#E53935")
                                else Color.parseColor("#FB8C00")
                            )
                        }
                        map.overlays.add(marker)
                    }

                    map.invalidate()
                }
            } catch (e: Exception) {
                val root = _binding?.root ?: return@launch
                Snackbar.make(root, "Map load failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** Cheap circular pin: solid color square as placeholder (OSMDroid stretches it to the anchor). */
    private fun coloredPinDrawable(color: Int): Drawable {
        return ColorDrawable(color).apply { setBounds(0, 0, 36, 36) }
    }

    companion object {
        // Coimbatore, Tamil Nadu
        private val COIMBATORE = GeoPoint(11.0168, 76.9558)
    }
}
