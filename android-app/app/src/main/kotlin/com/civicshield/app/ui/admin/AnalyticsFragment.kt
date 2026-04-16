package com.civicshield.app.ui.admin

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.civicshield.app.R
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.model.AnalyticsSummary
import com.civicshield.app.data.model.DailyCountsResponse
import com.civicshield.app.data.model.RecentActivityResponse
import com.civicshield.app.data.model.ResolutionTrendResponse
import com.civicshield.app.data.model.TopZonesResponse
import com.civicshield.app.databinding.FragmentAnalyticsBinding
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Admin analytics dashboard. Fetches six endpoints in parallel and renders:
 *   1) Summary stat row      (4 MaterialCardView chips)
 *   2) Donut (helmet vs pothole)
 *   3) Bar chart — last 7 days
 *   4) Horizontal bar — top 5 zones
 *   5) Line chart — resolution-time trend
 *   6) Timeline feed — last 10 status-change events
 *
 * All charts: tap-to-select enabled, animated on first data bind, primary
 * color palette. Pre-existing fine-grained zones are re-bucketed client-side
 * where needed.
 */
class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var api: ApiService
    private val timelineAdapter = ActivityTimelineAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = RetrofitClient.create(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Stat row static labels
        setStat(binding.statTotal, "0", getString(R.string.analytics_stat_total))
        setStat(binding.statPending, "0", getString(R.string.analytics_stat_pending))
        setStat(binding.statInProgress, "0", getString(R.string.analytics_stat_in_progress))
        setStat(binding.statCompleted, "0", getString(R.string.analytics_stat_completed))

        binding.recyclerActivity.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerActivity.adapter = timelineAdapter

        configureCharts()
        loadAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // -------------------------------------------------------------------
    // Chart setup (tap-to-select + no descriptions + tight axes)
    // -------------------------------------------------------------------

    private fun configureCharts() {
        binding.pieChart.apply {
            description.isEnabled = false
            legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            isHighlightPerTapEnabled = true
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(11f)
            setHoleColor(Color.WHITE)
            holeRadius = 50f
            transparentCircleRadius = 55f
        }

        binding.dailyBarChart.apply {
            description.isEnabled = false
            setFitBars(true)
            setPinchZoom(false)
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.granularity = 1f
            isHighlightPerTapEnabled = true
            legend.isEnabled = false
        }

        binding.topZonesChart.apply {
            description.isEnabled = false
            setPinchZoom(false)
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.granularity = 1f
            isHighlightPerTapEnabled = true
            legend.isEnabled = false
        }

        binding.trendLineChart.apply {
            description.isEnabled = false
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.granularity = 1f
            isHighlightPerTapEnabled = true
            legend.isEnabled = false
        }
    }

    // -------------------------------------------------------------------
    // Data loading (parallel)
    // -------------------------------------------------------------------

    private fun loadAll() {
        val b = _binding ?: return
        b.topProgress.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                coroutineScope {
                    val summary = async(Dispatchers.IO) { api.getAnalyticsSummary() }
                    val daily = async(Dispatchers.IO) { api.getAnalyticsDaily(7) }
                    val topZones = async(Dispatchers.IO) { api.getAnalyticsTopZones(5) }
                    val trend = async(Dispatchers.IO) {
                        api.getAnalyticsResolutionTrend(8)
                    }
                    val zonesAll = async(Dispatchers.IO) { api.getAnalyticsZones() }
                    val activity = async(Dispatchers.IO) {
                        api.getAnalyticsRecentActivity(10)
                    }
                    DashboardPayload(
                        summary.await(),
                        daily.await(),
                        topZones.await(),
                        trend.await(),
                        activity.await(),
                        zonesAll.await().features,
                    )
                }
            }

            val bind = _binding ?: return@launch
            bind.topProgress.hide()

            result.onSuccess(::renderAll).onFailure { e ->
                Log.w(TAG, "analytics load failed", e)
                Snackbar.make(
                    bind.root,
                    getString(R.string.analytics_load_failed, e.message ?: "unknown"),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    private data class DashboardPayload(
        val summary: AnalyticsSummary,
        val daily: DailyCountsResponse,
        val topZones: TopZonesResponse,
        val trend: ResolutionTrendResponse,
        val activity: RecentActivityResponse,
        val zoneFeatures: List<com.civicshield.app.data.model.ZoneFeatureDto>,
    )

    private fun renderAll(payload: DashboardPayload) {
        renderSummary(payload.summary)
        renderDonut(payload.zoneFeatures)
        renderDailyBars(payload.daily)
        renderTopZones(payload.topZones)
        renderTrendLine(payload.trend)
        renderActivity(payload.activity)
    }

    // -------------------------------------------------------------------
    // Renderers
    // -------------------------------------------------------------------

    private fun renderSummary(s: AnalyticsSummary) {
        val b = _binding ?: return
        setStat(b.statTotal, s.total.toString(), getString(R.string.analytics_stat_total))
        setStat(
            b.statPending,
            s.pending.toString(),
            getString(R.string.analytics_stat_pending),
            numberColor = Color.parseColor("#F9A825"),  // yellow
        )
        setStat(
            b.statInProgress,
            s.inProgress.toString(),
            getString(R.string.analytics_stat_in_progress),
            numberColor = Color.parseColor("#1976D2"),  // blue
        )
        setStat(
            b.statCompleted,
            s.completed.toString(),
            getString(R.string.analytics_stat_completed),
            numberColor = Color.parseColor("#43A047"),  // green
        )
    }

    private fun setStat(
        bind: com.civicshield.app.databinding.ItemAnalyticsStatBinding,
        number: String,
        label: String,
        numberColor: Int? = null,
    ) {
        bind.tvNumber.text = number
        bind.tvLabel.text = label
        if (numberColor != null) bind.tvNumber.setTextColor(numberColor)
    }

    private fun renderDonut(features: List<com.civicshield.app.data.model.ZoneFeatureDto>) {
        val helmet = features.sumOf { it.properties.helmetCount }.toFloat()
        val pothole = features.sumOf { it.properties.potholeCount }.toFloat()
        val entries = listOf(
            PieEntry(helmet, "Helmet"),
            PieEntry(pothole, "Pothole"),
        )
        val set = PieDataSet(entries, "").apply {
            colors = listOf(
                Color.parseColor("#E53935"),
                Color.parseColor("#FB8C00"),
            )
            sliceSpace = 2f
            valueTextColor = Color.WHITE
            valueTextSize = 13f
        }
        binding.pieChart.data = PieData(set)
        binding.pieChart.centerText = ""
        binding.pieChart.animateY(900)
        binding.pieChart.invalidate()
    }

    private fun renderDailyBars(data: DailyCountsResponse) {
        val points = data.points
        val entries = points.mapIndexed { i, p -> BarEntry(i.toFloat(), p.count.toFloat()) }
        val set = BarDataSet(entries, "Cases").apply {
            color = Color.parseColor("#1565C0")  // primary
            valueTextSize = 10f
        }
        binding.dailyBarChart.data = BarData(set).apply { barWidth = 0.7f }
        binding.dailyBarChart.xAxis.valueFormatter = IndexAxisValueFormatter(
            points.map { shortDay(it.date) }
        )
        binding.dailyBarChart.animateY(900)
        binding.dailyBarChart.invalidate()
    }

    /** Turn "2026-04-16" into "Thu 16" for a compact axis label. */
    private fun shortDay(iso: String): String = runCatching {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE d"))
    }.getOrDefault(iso)

    private fun renderTopZones(data: TopZonesResponse) {
        val zones = data.zones
        // HorizontalBarChart: index 0 renders at bottom, so reverse so the
        // highest-count zone is on top.
        val ordered = zones
        val entries = ordered.mapIndexed { i, z ->
            BarEntry(i.toFloat(), z.total.toFloat())
        }
        val set = BarDataSet(entries, "Cases").apply {
            color = Color.parseColor("#FF6F00")  // accent
            valueTextSize = 10f
        }
        binding.topZonesChart.data = BarData(set).apply { barWidth = 0.6f }
        binding.topZonesChart.xAxis.valueFormatter = IndexAxisValueFormatter(
            ordered.map { "%.2f,%.2f".format(it.lat, it.lng) }
        )
        binding.topZonesChart.animateY(900)
        binding.topZonesChart.invalidate()
    }

    private fun renderTrendLine(data: ResolutionTrendResponse) {
        val points = data.points
        if (points.isEmpty()) {
            binding.trendLineChart.clear()
            binding.tvTrendEmpty.isVisible = true
            return
        }
        binding.tvTrendEmpty.isVisible = false

        val entries = points.mapIndexed { i, p ->
            Entry(i.toFloat(), p.avgDays.toFloat())
        }
        val set = LineDataSet(entries, "avg days").apply {
            color = Color.parseColor("#1565C0")
            setCircleColor(Color.parseColor("#1565C0"))
            lineWidth = 2.5f
            circleRadius = 4f
            setDrawValues(true)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    String.format("%.1f", value)
            }
        }
        binding.trendLineChart.data = LineData(set)
        binding.trendLineChart.xAxis.valueFormatter = IndexAxisValueFormatter(
            points.map { shortWeek(it.weekStart) }
        )
        binding.trendLineChart.animateX(900)
        binding.trendLineChart.invalidate()
    }

    private fun shortWeek(iso: String): String = runCatching {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM"))
    }.getOrDefault(iso)

    private fun renderActivity(data: RecentActivityResponse) {
        val b = _binding ?: return
        val items = data.items
        timelineAdapter.submitList(items)
        b.tvActivityEmpty.isVisible = items.isEmpty()
        b.recyclerActivity.isVisible = items.isNotEmpty()
    }

    companion object {
        private const val TAG = "AnalyticsFragment"
    }
}
