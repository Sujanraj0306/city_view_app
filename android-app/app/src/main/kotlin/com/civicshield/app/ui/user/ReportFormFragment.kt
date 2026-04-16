package com.civicshield.app.ui.user

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieDrawable
import com.bumptech.glide.Glide
import com.civicshield.app.R
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.model.ReportRequest
import com.civicshield.app.databinding.FragmentReportFormBinding
import com.civicshield.app.ui.common.LottieUrls
import com.google.android.gms.location.LocationServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.ByteArrayOutputStream

class ReportFormFragment : Fragment() {

    private var _binding: FragmentReportFormBinding? = null
    private val binding get() = _binding!!

    private lateinit var api: ApiService
    private lateinit var reportType: ReportType

    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null

    private var capturedBase64: String? = null
    private var capturedBitmap: Bitmap? = null
    private var latitude: Double? = null
    private var longitude: Double? = null

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(requireContext())
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) startCamera()
        else showSnack(getString(R.string.err_camera_permission))

        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) fetchLocation()
        else showSnack(getString(R.string.err_location_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportType = ReportType.valueOf(
            requireArguments().getString(ARG_TYPE)
                ?: error("ReportType argument missing")
        )
        api = RetrofitClient.create(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReportFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnRetake.setOnClickListener { retake() }
        binding.btnUsePhoto.setOnClickListener { confirmPhoto() }
        binding.btnSubmit.setOnClickListener { submit() }
        applyUiState(UiState.LIVE_CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (uiState == UiState.LIVE_CAMERA) ensurePermissionsAndStart()
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        capturedBitmap?.recycle()
        capturedBitmap = null
        _binding = null
    }

    // -----------------------------------------------------------------------
    // UI state — only the preview/capture row toggles. The form below is
    // always visible per spec.
    // -----------------------------------------------------------------------

    private enum class UiState { LIVE_CAMERA, PHOTO_PREVIEW, CONFIRMED }
    private var uiState: UiState = UiState.LIVE_CAMERA

    private fun applyUiState(state: UiState) {
        uiState = state
        val b = _binding ?: return
        when (state) {
            UiState.LIVE_CAMERA -> {
                b.previewView.visibility = View.VISIBLE
                b.ivCaptured.visibility = View.GONE
                b.btnCapture.visibility = View.VISIBLE
                b.confirmRow.visibility = View.GONE
            }
            UiState.PHOTO_PREVIEW -> {
                b.previewView.visibility = View.GONE
                b.ivCaptured.visibility = View.VISIBLE
                b.btnCapture.visibility = View.GONE
                b.confirmRow.visibility = View.VISIBLE
            }
            UiState.CONFIRMED -> {
                b.previewView.visibility = View.GONE
                b.ivCaptured.visibility = View.VISIBLE
                b.btnCapture.visibility = View.VISIBLE
                b.btnCapture.text = getString(R.string.btn_retake)
                b.confirmRow.visibility = View.GONE
            }
        }
        if (state != UiState.CONFIRMED) b.btnCapture.text = getString(R.string.btn_capture)
        refreshSubmitEnabled()
    }

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    private fun ensurePermissionsAndStart() {
        val needed = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isEmpty()) {
            startCamera()
            fetchLocation()
        } else {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

    // -----------------------------------------------------------------------
    // Camera
    // -----------------------------------------------------------------------

    private fun startCamera() {
        if (_binding == null) return
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (_binding == null) return@addListener
            val cameraProvider = future.get()

            val newPreview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val newCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                preview?.let { cameraProvider.unbind(it) }
                imageCapture?.let { cameraProvider.unbind(it) }

                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    newPreview,
                    newCapture,
                )
                preview = newPreview
                imageCapture = newCapture
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                showSnack("Camera unavailable: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val cameraProvider = future.get()
            preview?.let { cameraProvider.unbind(it) }
            imageCapture?.let { cameraProvider.unbind(it) }
            preview = null
            imageCapture = null
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        // If we're already in CONFIRMED state, treat this as "retake".
        if (uiState == UiState.CONFIRMED) {
            retake()
            return
        }
        val ic = imageCapture ?: run {
            showSnack("Camera not ready")
            return
        }
        binding.btnCapture.isEnabled = false
        ic.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = image.planes[0].buffer.run {
                        val b = ByteArray(remaining())
                        get(b)
                        b
                    }
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    compressAndPreview(bytes, rotation)
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.btnCapture.isEnabled = true
                    showSnack("Capture failed: ${exception.message}")
                }
            },
        )
    }

    private fun compressAndPreview(rawJpeg: ByteArray, rotationDegrees: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            data class Prepared(val bitmap: Bitmap, val base64: String)
            val prepared = withContext(Dispatchers.Default) {
                val decoded = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
                val rotated = if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        .also { if (it != decoded) decoded.recycle() }
                } else decoded
                val baos = ByteArrayOutputStream()
                rotated.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                Prepared(rotated, Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
            }

            capturedBitmap?.recycle()
            capturedBitmap = prepared.bitmap
            capturedBase64 = prepared.base64

            Glide.with(this@ReportFormFragment)
                .load(prepared.bitmap)
                .into(binding.ivCaptured)

            stopCamera()
            binding.btnCapture.isEnabled = true
            applyUiState(UiState.PHOTO_PREVIEW)
        }
    }

    private fun retake() {
        capturedBitmap?.recycle()
        capturedBitmap = null
        capturedBase64 = null
        applyUiState(UiState.LIVE_CAMERA)
        ensurePermissionsAndStart()
    }

    private fun confirmPhoto() {
        applyUiState(UiState.CONFIRMED)
        if (latitude == null || longitude == null) fetchLocation()
    }

    // -----------------------------------------------------------------------
    // Location
    // -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        binding.tvLocation.text = getString(R.string.loc_fetching)
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (_binding == null) return@addOnSuccessListener
                if (location == null) {
                    binding.tvLocation.text = getString(R.string.loc_unavailable)
                    return@addOnSuccessListener
                }
                latitude = location.latitude
                longitude = location.longitude
                binding.tvLocation.text = getString(
                    R.string.loc_format, location.latitude, location.longitude
                )
                refreshSubmitEnabled()
            }
            .addOnFailureListener { e ->
                _binding?.tvLocation?.text = "Location error: ${e.message}"
            }
    }

    private fun refreshSubmitEnabled() {
        val b = _binding ?: return
        b.btnSubmit.isEnabled =
            uiState == UiState.CONFIRMED &&
                capturedBase64 != null && latitude != null && longitude != null
    }

    // -----------------------------------------------------------------------
    // Submit
    // -----------------------------------------------------------------------

    private fun submit() {
        val base64 = capturedBase64 ?: run {
            showSnack(getString(R.string.submit_missing_image))
            return
        }
        val lat = latitude
        val lng = longitude
        if (lat == null || lng == null) {
            showSnack(getString(R.string.submit_missing_location))
            return
        }
        val description = binding.etDescription.text?.toString()?.trim().orEmpty()

        binding.topProgress.show()
        binding.btnSubmit.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val req = ReportRequest(base64, lat, lng, description.ifEmpty { null })
                    when (reportType) {
                        ReportType.HELMET -> api.reportHelmet(req)
                        ReportType.POTHOLE -> api.reportPothole(req)
                    }
                }
                showSnack(getString(R.string.success_toast, response.caseId.take(8)))
                resetAfterSuccess()
            } catch (e: HttpException) {
                if (e.code() == 422) {
                    val reason = extractRejectionReason(e)
                    shakeSubmit()
                    showRejectionDialog(reason)
                } else {
                    showSnack("Upload failed (${e.code()}): ${e.message()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "submit failed", e)
                showSnack("Upload failed: ${e.message}")
            } finally {
                _binding?.let {
                    it.topProgress.hide()
                    refreshSubmitEnabled()
                }
            }
        }
    }

    /** Parse `detail.rejection_reason` out of the FastAPI 422 body. */
    private fun extractRejectionReason(e: HttpException): String? {
        val raw = e.response()?.errorBody()?.string().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            val detail = org.json.JSONObject(raw).opt("detail")
            when (detail) {
                is org.json.JSONObject ->
                    detail.optString("rejection_reason").takeIf { it.isNotBlank() }
                is String -> detail.takeIf { it.isNotBlank() }
                else -> null
            }
        }.getOrNull()
    }

    private fun shakeSubmit() {
        val shake = AnimationUtils.loadAnimation(requireContext(), R.anim.shake)
        binding.btnSubmit.startAnimation(shake)
    }

    private fun showRejectionDialog(reason: String?) {
        val msg = reason?.takeIf { it.isNotBlank() } ?: getString(R.string.rejection_generic)
        val ctx = requireContext()

        val lottieView = com.airbnb.lottie.LottieAnimationView(ctx).apply {
            setFailureListener { _ -> /* silently degrade */ }
            setAnimationFromUrl(LottieUrls.WARNING)
            repeatCount = LottieDrawable.INFINITE
            playAnimation()
            val params = FrameLayout.LayoutParams(
                (resources.displayMetrics.density * 140).toInt(),
                (resources.displayMetrics.density * 140).toInt(),
            )
            params.gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = params
        }
        val container = FrameLayout(ctx).apply {
            val pad = (resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad, pad, 0)
            addView(lottieView)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rejection_title)
            .setMessage(msg)
            .setView(container)
            .setPositiveButton(R.string.rejection_retake) { d, _ ->
                d.dismiss()
                retake()
            }
            .setCancelable(true)
            .show()
    }

    private fun resetAfterSuccess() {
        capturedBitmap?.recycle()
        capturedBitmap = null
        capturedBase64 = null
        _binding?.etDescription?.setText("")
        applyUiState(UiState.LIVE_CAMERA)
        ensurePermissionsAndStart()
    }

    private fun showSnack(message: String) {
        val root = _binding?.root ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ReportFormFragment"
        private const val ARG_TYPE = "type"

        fun newInstance(type: ReportType): ReportFormFragment {
            return ReportFormFragment().apply {
                arguments = Bundle().apply { putString(ARG_TYPE, type.name) }
            }
        }
    }
}
