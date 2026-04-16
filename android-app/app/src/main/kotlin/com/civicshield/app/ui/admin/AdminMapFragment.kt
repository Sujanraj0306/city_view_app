package com.civicshield.app.ui.admin

import com.civicshield.app.ui.common.CaseMapFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.views.MapView

class AdminMapFragment : CaseMapFragment() {

    override val mode: Mode = Mode.ADMIN

    override suspend fun addExtraOverlays(map: MapView) {
        val zones = try {
            withContext(Dispatchers.IO) { api.getAnalyticsZones().features }
        } catch (_: Exception) {
            return
        }
        val points = zones.mapNotNull { f ->
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
        if (points.isNotEmpty()) {
            map.overlays.add(HeatmapOverlay(points))
        }
    }
}
