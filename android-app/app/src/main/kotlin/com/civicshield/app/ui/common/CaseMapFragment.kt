package com.civicshield.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
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
import com.civicshield.app.databinding.FragmentCaseMapBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

open class CaseMapFragment : Fragment() {

    enum class Mode { USER, ADMIN }

    private var _binding: FragmentCaseMapBinding? = null
    protected val binding get() = _binding!!

    protected lateinit var api: ApiService

    protected open val mode: Mode
        get() = Mode.values()[arguments?.getInt(ARG_MODE) ?: 0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OSMDroid is configured once in CivicShieldApp.onCreate — don't re-init here.
        api = RetrofitClient.create(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCaseMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(13.0)
        binding.mapView.controller.setCenter(COIMBATORE)

        loadAndRender()
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

    protected open suspend fun fetchCases(): List<CaseAdminItem> = when (mode) {
        Mode.USER -> api.listMyCases("me")
        Mode.ADMIN -> api.listAdminCases(page = 1, limit = 500).items
    }

    /** Hook for subclasses to add extra overlays (e.g. admin heatmap) before markers. */
    protected open suspend fun addExtraOverlays(map: MapView) = Unit

    private fun loadAndRender() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cases = withContext(Dispatchers.IO) { fetchCases() }

                val b = _binding ?: return@launch
                val map = b.mapView

                addExtraOverlays(map)
                renderMarkers(map, cases)

                if (cases.isEmpty()) {
                    b.tvEmpty.visibility = View.VISIBLE
                    b.tvEmpty.text = getString(
                        if (mode == Mode.USER) R.string.map_empty_user
                        else R.string.map_empty_admin
                    )
                } else {
                    b.tvEmpty.visibility = View.GONE
                    // Most-recent case = first item (backend orders by created_at desc).
                    val first = cases.first()
                    map.controller.setCenter(GeoPoint(first.latitude, first.longitude))
                }

                map.invalidate()
            } catch (e: Exception) {
                val root = _binding?.root ?: return@launch
                Snackbar.make(
                    root,
                    getString(R.string.map_load_failed, e.message ?: ""),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun renderMarkers(map: MapView, cases: List<CaseAdminItem>) {
        val ctx = requireContext()
        cases.forEach { c ->
            val completed = c.status == "completed"
            val color = when {
                completed -> COLOR_COMPLETED
                c.type == "helmet" -> COLOR_HELMET
                c.type == "pothole" -> COLOR_POTHOLE
                else -> Color.GRAY
            }
            val marker = Marker(map).apply {
                position = GeoPoint(c.latitude, c.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = c.id.ifBlank { "—" }
                icon = circleIcon(ctx, color, withCheck = completed)
                setOnMarkerClickListener { _, _ ->
                    openDetail(c)
                    true
                }
            }
            map.overlays.add(marker)
        }
    }

    private fun openDetail(c: CaseAdminItem) {
        CaseDetailBottomSheet.newInstance(
            caseId = c.id,
            type = c.type,
            status = c.status,
            aiDescription = c.aiDescription,
            createdAt = c.createdAt,
        ).show(childFragmentManager, "case_detail")
    }

    /**
     * Draws a solid-color circle with a 2dp white stroke. When [withCheck] is true
     * (completed cases) a white checkmark is stroked on top.
     */
    private fun circleIcon(ctx: Context, color: Int, withCheck: Boolean): BitmapDrawable {
        val d = ctx.resources.displayMetrics.density
        val size = (28 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = (size / 2f) - (2 * d)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        canvas.drawCircle(cx, cy, r, fill)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * d
            this.color = Color.WHITE
        }
        canvas.drawCircle(cx, cy, r, stroke)

        if (withCheck) {
            val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3 * d
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = Color.WHITE
            }
            val path = Path().apply {
                moveTo(cx - r * 0.45f, cy + r * 0.02f)
                lineTo(cx - r * 0.10f, cy + r * 0.40f)
                lineTo(cx + r * 0.50f, cy - r * 0.35f)
            }
            canvas.drawPath(path, check)
        }

        return BitmapDrawable(ctx.resources, bmp)
    }

    companion object {
        private const val ARG_MODE = "mode"

        // Coimbatore per spec.
        val COIMBATORE: GeoPoint = GeoPoint(10.9026, 76.9366)

        private val COLOR_HELMET = Color.parseColor("#E53935")
        private val COLOR_POTHOLE = Color.parseColor("#FB8C00")
        private val COLOR_COMPLETED = Color.parseColor("#43A047")

        fun newInstance(mode: Mode): CaseMapFragment = CaseMapFragment().apply {
            arguments = Bundle().apply { putInt(ARG_MODE, mode.ordinal) }
        }
    }
}
