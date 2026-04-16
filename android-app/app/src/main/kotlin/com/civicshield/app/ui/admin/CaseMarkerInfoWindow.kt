package com.civicshield.app.ui.admin

import android.widget.TextView
import com.civicshield.app.R
import com.civicshield.app.data.model.CaseAdminItem
import com.google.android.material.button.MaterialButton
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow

/**
 * Info window anchored to a case marker. Shows case ID, type·status,
 * complainant, and a "View Full" button that opens [AdminCaseDetailActivity].
 *
 * OSMDroid inflates [layoutId] when a marker is tapped — we override [onOpen]
 * to read the [Marker.getRelatedObject] (the [CaseAdminItem]) and populate
 * the custom views.
 */
class CaseMarkerInfoWindow(
    layoutId: Int,
    mapView: MapView,
) : MarkerInfoWindow(layoutId, mapView) {

    override fun onOpen(item: Any?) {
        super.onOpen(item)
        val marker = item as? Marker ?: return
        val case = marker.relatedObject as? CaseAdminItem ?: return
        val ctx = mView.context

        mView.findViewById<TextView>(R.id.tvInfoCaseId)?.apply {
            text = ctx.getString(R.string.map_info_case_id, case.id.take(8))
        }
        mView.findViewById<TextView>(R.id.tvInfoType)?.apply {
            text = ctx.getString(
                R.string.map_info_type,
                "${case.type} · ${case.status.replace('_', ' ')}",
            )
        }
        mView.findViewById<TextView>(R.id.tvInfoComplainant)?.apply {
            text = ctx.getString(
                R.string.map_info_complainant,
                case.user.username.ifBlank { "—" },
            )
        }
        mView.findViewById<MaterialButton>(R.id.btnViewFull)?.setOnClickListener {
            close()
            ctx.startActivity(
                AdminCaseDetailActivity.newIntent(
                    context = ctx,
                    caseId = case.id,
                    caseType = case.type,
                    status = case.status,
                    latitude = case.latitude,
                    longitude = case.longitude,
                    createdAt = case.createdAt,
                    username = case.user.username,
                    email = case.user.email,
                )
            )
        }
    }
}
