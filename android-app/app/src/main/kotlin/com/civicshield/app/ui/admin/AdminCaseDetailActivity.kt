package com.civicshield.app.ui.admin

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.civicshield.app.BuildConfig
import com.civicshield.app.R
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.model.CaseAnalysis
import com.civicshield.app.data.model.CaseStatusUpdate
import com.civicshield.app.databinding.ActivityAdminCaseDetailBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Full-screen detail view for a single case. Shows the stored image with a
 * shimmer placeholder while loading, runs Gemini's detailed analysis in the
 * background, reverse-geocodes the coordinates, and lets admins flip status
 * via a colored MaterialButtonToggleGroup.
 */
class AdminCaseDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminCaseDetailBinding
    private lateinit var api: ApiService

    private lateinit var caseId: String
    private lateinit var caseType: String
    private var initialStatus: String = "pending"
    private var currentStatus: String = "pending"
    private var caseLatitude: Double = 0.0
    private var caseLongitude: Double = 0.0
    private var caseCreatedAt: String = ""
    private var caseUsername: String = ""
    private var caseEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminCaseDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        caseId = intent.getStringExtra(EXTRA_CASE_ID).orEmpty()
        caseType = intent.getStringExtra(EXTRA_CASE_TYPE).orEmpty()
        initialStatus = intent.getStringExtra(EXTRA_CASE_STATUS).orEmpty()
            .ifBlank { "pending" }
        currentStatus = initialStatus
        caseLatitude = intent.getDoubleExtra(EXTRA_CASE_LAT, 0.0)
        caseLongitude = intent.getDoubleExtra(EXTRA_CASE_LNG, 0.0)
        caseCreatedAt = intent.getStringExtra(EXTRA_CASE_CREATED_AT).orEmpty()
        caseUsername = intent.getStringExtra(EXTRA_CASE_USERNAME).orEmpty()
        caseEmail = intent.getStringExtra(EXTRA_CASE_EMAIL)

        if (caseId.isBlank()) {
            Snackbar.make(binding.root, "Invalid case", Snackbar.LENGTH_SHORT).show()
            finish()
            return
        }

        api = RetrofitClient.create(applicationContext)

        binding.toolbar.setNavigationOnClickListener { finish() }

        renderComplainant()
        renderLocationFallback()
        setupStatusToggle()
        loadImage()

        lifecycleScope.launch { runAnalysis() }
        lifecycleScope.launch { fetchAddress() }
    }

    // -----------------------------------------------------------------------
    // Image with Shimmer placeholder
    // -----------------------------------------------------------------------

    private fun loadImage() {
        val url = "${BuildConfig.BASE_URL.trimEnd('/')}/cases/$caseId/image"
        binding.imageShimmer.startShimmer()
        binding.tvImageFail.isVisible = false

        Glide.with(this)
            .load(url)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    binding.imageShimmer.stopShimmer()
                    binding.imageShimmer.isVisible = false
                    binding.tvImageFail.isVisible = true
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    binding.imageShimmer.stopShimmer()
                    binding.imageShimmer.isVisible = false
                    return false
                }
            })
            .into(binding.imgCase)
    }

    // -----------------------------------------------------------------------
    // Gemini analysis
    // -----------------------------------------------------------------------

    private suspend fun runAnalysis() {
        val b = binding
        b.analysisProgress.show()
        b.tvAnalysisStatus.isVisible = true
        b.tvAnalysisStatus.text = getString(R.string.detail_analyzing)
        b.analysisGroup.isVisible = false

        val result = runCatching {
            withContext(Dispatchers.IO) { api.analyzeCase(caseId) }
        }
        b.analysisProgress.hide()
        b.tvAnalysisStatus.isVisible = false

        result.onSuccess { analysis ->
            renderAnalysis(analysis)
        }.onFailure { e ->
            Log.w(TAG, "analyzeCase failed", e)
            b.tvAnalysisStatus.isVisible = true
            b.tvAnalysisStatus.text =
                getString(R.string.detail_analysis_failed, e.message ?: "unknown")
        }
    }

    private fun renderAnalysis(a: CaseAnalysis) {
        val b = binding
        b.analysisGroup.isVisible = true

        setRow(b.rowScene.root, R.string.detail_scene, a.sceneDescription)
        setRow(
            b.rowViolation.root,
            R.string.detail_violation,
            if (a.violationConfirmed) getString(R.string.detail_yes)
            else getString(R.string.detail_no),
        )
        // Only show vehicle number prominently for helmet cases; hide otherwise.
        if (caseType == "helmet") {
            setRow(
                b.rowVehicle.root,
                R.string.detail_vehicle,
                a.vehicleNumber?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.detail_value_na),
            )
        } else {
            b.rowVehicle.root.isVisible = false
        }
        setRow(
            b.rowZone.root,
            R.string.detail_zone,
            a.violationZone.takeIf { it.isNotBlank() }
                ?: getString(R.string.detail_value_na),
        )
        setRow(b.rowSeverity.root, R.string.detail_severity, a.severity.uppercase())

        // Helmet bounding box overlay — only when Gemini gave us a bbox.
        val bbox = a.boundingBox
        if (caseType == "helmet" && bbox != null) {
            binding.bboxOverlay.setBox(bbox.x, bbox.y, bbox.width, bbox.height)
        } else {
            binding.bboxOverlay.clearBox()
        }
    }

    // -----------------------------------------------------------------------
    // Address (reverse geocode)
    // -----------------------------------------------------------------------

    private suspend fun fetchAddress() {
        val result = runCatching {
            withContext(Dispatchers.IO) { api.getCaseAddress(caseId) }
        }
        result.onSuccess { addr ->
            val display = addr.displayName.takeIf { it.isNotBlank() }
            if (display != null) binding.tvAddress.text = display
            else renderLocationFallback()
        }.onFailure { e ->
            Log.i(TAG, "getCaseAddress failed, falling back to coords: ${e.message}")
            renderLocationFallback()
        }
    }

    private fun renderLocationFallback() {
        binding.tvAddress.text = getString(
            R.string.detail_location_fallback, caseLatitude, caseLongitude
        )
    }

    // -----------------------------------------------------------------------
    // Complainant card
    // -----------------------------------------------------------------------

    private fun renderComplainant() {
        setRow(
            binding.rowUsername.root,
            R.string.detail_complainant_username,
            caseUsername.ifBlank { getString(R.string.detail_value_na) },
        )
        setRow(
            binding.rowEmail.root,
            R.string.detail_complainant_email,
            caseEmail?.takeIf { it.isNotBlank() }
                ?: getString(R.string.detail_value_na),
        )
        setRow(
            binding.rowSubmittedAt.root,
            R.string.detail_complainant_submitted,
            formatPrettyDate(caseCreatedAt),
        )
    }

    private fun formatPrettyDate(iso: String): String {
        if (iso.isBlank()) return getString(R.string.detail_value_na)
        return try {
            OffsetDateTime.parse(iso)
                .toLocalDateTime()
                .format(
                    DateTimeFormatter.ofPattern(
                        "dd MMM yyyy 'at' h:mm a",
                        Locale.getDefault(),
                    )
                )
        } catch (_: Exception) {
            iso
        }
    }

    // -----------------------------------------------------------------------
    // Status toggle
    // -----------------------------------------------------------------------

    private fun setupStatusToggle() {
        val selectedId = when (initialStatus) {
            "verified" -> R.id.btnVerified
            "in_progress" -> R.id.btnInProgress
            "completed" -> R.id.btnCompleted
            else -> R.id.btnPending
        }
        binding.statusToggleGroup.check(selectedId)

        binding.statusToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newStatus = when (checkedId) {
                R.id.btnVerified -> "verified"
                R.id.btnInProgress -> "in_progress"
                R.id.btnCompleted -> "completed"
                else -> "pending"
            }
            if (newStatus == currentStatus) return@addOnButtonCheckedListener
            submitStatusChange(newStatus)
        }
    }

    private fun submitStatusChange(newStatus: String) {
        val previous = currentStatus
        currentStatus = newStatus
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.updateCaseStatus(caseId, CaseStatusUpdate(newStatus))
                }
            }
            result.onSuccess {
                Snackbar.make(
                    binding.root,
                    getString(R.string.detail_status_updated, newStatus.replace('_', ' ')),
                    Snackbar.LENGTH_SHORT,
                ).show()
            }.onFailure { e ->
                currentStatus = previous
                // Revert UI so the user knows nothing was actually persisted.
                val revertId = when (previous) {
                    "verified" -> R.id.btnVerified
                    "in_progress" -> R.id.btnInProgress
                    "completed" -> R.id.btnCompleted
                    else -> R.id.btnPending
                }
                binding.statusToggleGroup.check(revertId)
                Snackbar.make(
                    binding.root,
                    getString(R.string.detail_status_failed, e.message ?: "unknown"),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun setRow(rowRoot: View, labelRes: Int, value: String) {
        rowRoot.isVisible = true
        val label = rowRoot.findViewById<android.widget.TextView>(R.id.tvLabel)
        val valueTv = rowRoot.findViewById<android.widget.TextView>(R.id.tvValue)
        label?.setText(labelRes)
        valueTv?.text = value
    }

    companion object {
        private const val TAG = "AdminCaseDetail"

        private const val EXTRA_CASE_ID = "case_id"
        private const val EXTRA_CASE_TYPE = "case_type"
        private const val EXTRA_CASE_STATUS = "case_status"
        private const val EXTRA_CASE_LAT = "case_lat"
        private const val EXTRA_CASE_LNG = "case_lng"
        private const val EXTRA_CASE_CREATED_AT = "case_created_at"
        private const val EXTRA_CASE_USERNAME = "case_username"
        private const val EXTRA_CASE_EMAIL = "case_email"

        fun newIntent(
            context: Context,
            caseId: String,
            caseType: String,
            status: String,
            latitude: Double,
            longitude: Double,
            createdAt: String,
            username: String,
            email: String?,
        ): Intent = Intent(context, AdminCaseDetailActivity::class.java).apply {
            putExtra(EXTRA_CASE_ID, caseId)
            putExtra(EXTRA_CASE_TYPE, caseType)
            putExtra(EXTRA_CASE_STATUS, status)
            putExtra(EXTRA_CASE_LAT, latitude)
            putExtra(EXTRA_CASE_LNG, longitude)
            putExtra(EXTRA_CASE_CREATED_AT, createdAt)
            putExtra(EXTRA_CASE_USERNAME, username)
            putExtra(EXTRA_CASE_EMAIL, email)
        }
    }
}
