package com.civicshield.app.ui.common

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.civicshield.app.BuildConfig
import com.civicshield.app.R
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.databinding.BottomSheetCaseDetailBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class CaseDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCaseDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetCaseDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        val caseId = args.getString(ARG_ID).orEmpty()
        val type = args.getString(ARG_TYPE).orEmpty()
        val status = args.getString(ARG_STATUS).orEmpty()
        val aiDescription = args.getString(ARG_AI_DESC).orEmpty()
        val createdAt = args.getString(ARG_CREATED_AT).orEmpty()
        val lat = args.getDouble(ARG_LAT, Double.NaN)
        val lng = args.getDouble(ARG_LNG, Double.NaN)

        binding.tvCaseId.text = getString(R.string.sheet_case_id, caseId.take(8))

        binding.tvType.text = type.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        binding.tvType.backgroundTintList = ColorStateList.valueOf(typeColor(type))

        binding.tvStatus.text = status.replace('_', ' ')
        binding.tvStatus.backgroundTintList = ColorStateList.valueOf(statusColor(status))

        binding.tvAiDescription.text =
            aiDescription.ifBlank { getString(R.string.sheet_no_description) }

        binding.tvDate.text = formatDate(createdAt)

        val imageUrl = "${BuildConfig.BASE_URL.trimEnd('/')}/cases/$caseId/image"
        binding.tvImageError.visibility = View.GONE
        Glide.with(this)
            .load(imageUrl)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    binding.tvImageError.visibility = View.VISIBLE
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean = false
            })
            .into(binding.imgCase)

        fetchAddress(caseId, lat, lng)
    }

    private fun fetchAddress(caseId: String, lat: Double, lng: Double) {
        if (caseId.isBlank()) {
            renderAddressFallback(lat, lng)
            return
        }
        val api = RetrofitClient.create(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.getCaseAddress(caseId) }
            }
            val bind = _binding ?: return@launch
            result.onSuccess { addr ->
                val display = addr.displayName.takeIf { it.isNotBlank() }
                if (display != null) bind.tvAddress.text = display
                else renderAddressFallback(lat, lng)
            }.onFailure { e ->
                Log.i(TAG, "address lookup failed: ${e.message}")
                renderAddressFallback(lat, lng)
            }
        }
    }

    private fun renderAddressFallback(lat: Double, lng: Double) {
        val bind = _binding ?: return
        bind.tvAddress.text = if (lat.isNaN() || lng.isNaN()) {
            getString(R.string.detail_value_na)
        } else {
            getString(R.string.detail_location_fallback, lat, lng)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun typeColor(type: String): Int = when (type) {
        "helmet" -> Color.parseColor("#E53935")
        "pothole" -> Color.parseColor("#FB8C00")
        else -> Color.GRAY
    }

    private fun statusColor(status: String): Int = when (status) {
        "pending" -> Color.parseColor("#9E9E9E")
        "verified" -> Color.parseColor("#1976D2")
        "in_progress" -> Color.parseColor("#F57C00")
        "completed" -> Color.parseColor("#43A047")
        else -> Color.GRAY
    }

    private fun formatDate(iso: String): String {
        if (iso.isBlank()) return "—"
        return try {
            OffsetDateTime.parse(iso)
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.getDefault()))
        } catch (_: Exception) {
            iso
        }
    }

    companion object {
        private const val TAG = "CaseDetailSheet"

        private const val ARG_ID = "case_id"
        private const val ARG_TYPE = "type"
        private const val ARG_STATUS = "status"
        private const val ARG_AI_DESC = "ai_desc"
        private const val ARG_CREATED_AT = "created_at"
        private const val ARG_LAT = "lat"
        private const val ARG_LNG = "lng"

        fun newInstance(
            caseId: String,
            type: String,
            status: String,
            aiDescription: String?,
            createdAt: String,
            latitude: Double = Double.NaN,
            longitude: Double = Double.NaN,
        ): CaseDetailBottomSheet = CaseDetailBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, caseId)
                putString(ARG_TYPE, type)
                putString(ARG_STATUS, status)
                putString(ARG_AI_DESC, aiDescription.orEmpty())
                putString(ARG_CREATED_AT, createdAt)
                putDouble(ARG_LAT, latitude)
                putDouble(ARG_LNG, longitude)
            }
        }
    }
}
