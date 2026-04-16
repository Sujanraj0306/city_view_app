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
import com.civicshield.app.BuildConfig
import com.civicshield.app.R
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.model.CaseAdminItem
import com.civicshield.app.databinding.FragmentCaseMapBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
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
            val marker = Marker(map).apply {
                position = GeoPoint(c.latitude, c.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = c.type.replaceFirstChar { it.uppercase() } + " · " + c.status
                icon = pinDrawable(ctx, pinColorFor(c))
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

    private fun pinColorFor(c: CaseAdminItem): Int = when {
        c.status == "completed" -> COLOR_COMPLETED
        c.type == "helmet" -> COLOR_HELMET
        c.type == "pothole" -> COLOR_POTHOLE
        else -> Color.GRAY
    }

    private fun pinDrawable(ctx: Context, color: Int): BitmapDrawable {
        val d = ctx.resources.displayMetrics.density
        val w = (28 * d).toInt()
        val h = (40 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        val cyHead = w / 2f
        val r = (w / 2f) - 2 * d

        val path = Path().apply {
            addCircle(cx, cyHead, r, Path.Direction.CW)
            moveTo(cx - r * 0.55f, cyHead + r * 0.75f)
            lineTo(cx, h.toFloat() - 2 * d)
            lineTo(cx + r * 0.55f, cyHead + r * 0.75f)
            close()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        canvas.drawPath(path, paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cyHead, r * 0.35f, paint)

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
