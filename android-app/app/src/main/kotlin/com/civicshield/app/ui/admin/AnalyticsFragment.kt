package com.civicshield.app.ui.admin

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.model.ZoneFeatureDto
import com.civicshield.app.databinding.FragmentAnalyticsBinding
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
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
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.barChart.description.isEnabled = false
        binding.barChart.legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
        binding.pieChart.description.isEnabled = false
        binding.pieChart.legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER

        loadAnalytics()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadAnalytics() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val features = withContext(Dispatchers.IO) {
                    api.getAnalyticsZones().features
                }
                _binding?.let { render(features) }
            } catch (e: Exception) {
                val root = _binding?.root ?: return@launch
                Snackbar.make(root, "Analytics load failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun render(features: List<ZoneFeatureDto>) {
        // ---- Bar chart: top 10 zones by total ----
        val top10 = features.sortedByDescending { it.properties.total }.take(10)
        val barEntries = top10.mapIndexed { i, f ->
            BarEntry(i.toFloat(), f.properties.total.toFloat())
        }
        val barSet = BarDataSet(barEntries, "Top zones by violation count").apply {
            color = Color.parseColor("#1565C0")
        }
        binding.barChart.data = BarData(barSet)
        binding.barChart.xAxis.valueFormatter = IndexAxisValueFormatter(
            top10.map { "%.2f,%.2f".format(it.geometry.coordinates.getOrNull(1) ?: 0.0, it.geometry.coordinates.getOrNull(0) ?: 0.0) }
        )
        binding.barChart.xAxis.labelRotationAngle = -45f
        binding.barChart.xAxis.setDrawGridLines(false)
        binding.barChart.axisRight.isEnabled = false
        binding.barChart.invalidate()

        // ---- Pie chart: helmet vs pothole ----
        val totalHelmet = features.sumOf { it.properties.helmetCount }
        val totalPothole = features.sumOf { it.properties.potholeCount }
        val pieEntries = listOf(
            PieEntry(totalHelmet.toFloat(), "Helmet"),
            PieEntry(totalPothole.toFloat(), "Pothole"),
        )
        val pieSet = PieDataSet(pieEntries, "").apply {
            colors = listOf(Color.parseColor("#E53935"), Color.parseColor("#FB8C00"))
            valueTextColor = Color.WHITE
            valueTextSize = 14f
        }
        binding.pieChart.data = PieData(pieSet)
        binding.pieChart.centerText = "Helmet vs Pothole"
        binding.pieChart.setEntryLabelTextSize(12f)
        binding.pieChart.invalidate()
    }
}
