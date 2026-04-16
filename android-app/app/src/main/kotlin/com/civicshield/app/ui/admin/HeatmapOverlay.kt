package com.civicshield.app.ui.admin

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Draws a translucent heat circle at each zone centroid. Radius scales with total count;
 * color intensifies with violation density.
 */
class HeatmapOverlay(
    private val zones: List<ZonePoint>,
) : Overlay() {

    data class ZonePoint(
        val lat: Double,
        val lng: Double,
        val total: Int,
        val helmetCount: Int,
        val potholeCount: Int,
    )

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas, mapView: MapView?, shadow: Boolean) {
        if (shadow || mapView == null) return
        val projection = mapView.projection
        val reusablePoint = Point()

        for (zone in zones) {
            projection.toPixels(GeoPoint(zone.lat, zone.lng), reusablePoint)

            // Radius scales with total; clamp to keep circles readable on any zoom.
            val radiusPx = (24f + (zone.total.coerceAtMost(20) * 8f))
            // Intensity: darker red for denser zones.
            val alpha = (60 + zone.total.coerceAtMost(10) * 15).coerceAtMost(200)
            paint.color = Color.argb(alpha, 229, 57, 53)

            canvas.drawCircle(
                reusablePoint.x.toFloat(),
                reusablePoint.y.toFloat(),
                radiusPx,
                paint,
            )
        }
    }
}
