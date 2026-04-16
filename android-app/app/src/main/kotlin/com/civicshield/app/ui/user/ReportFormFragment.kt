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
import com.civicshield.app.R
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.model.ReportRequest
import com.civicshield.app.databinding.FragmentReportFormBinding
import com.google.android.gms.location.LocationServices
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ReportFormFragment : Fragment() {

    private var _binding: FragmentReportFormBinding? = null
    private val binding get() = _binding!!

    private lateinit var api: ApiService
    private lateinit var reportType: ReportType

    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null

    private var capturedBase64: String? = null
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
        binding.btnSubmit.setOnClickListener { submit() }
        refreshCapturedState()
    }

    override fun onResume() {
        super.onResume()
        ensurePermissionsAndStart()
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
                // Unbind only our previous bindings (if any) — leaves other fragments' bindings alone.
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
                    compressAndRemember(bytes, rotation)
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.btnCapture.isEnabled = true
                    showSnack("Capture failed: ${exception.message}")
                }
            },
        )
    }

    /** CPU-bound work: decode, rotate, recompress, base64-encode — all on Dispatchers.Default. */
    private fun compressAndRemember(rawJpeg: ByteArray, rotationDegrees: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val base64 = withContext(Dispatchers.Default) {
                val bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
                val rotated = if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        .also { if (it != bitmap) bitmap.recycle() }
                } else bitmap

                val baos = ByteArrayOutputStream()
                rotated.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                rotated.recycle()
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            }
            capturedBase64 = base64
            refreshCapturedState()
            binding.btnCapture.isEnabled = true
        }
    }

    private fun refreshCapturedState() {
        val b = _binding ?: return
        b.tvCapturedStatus.text = if (capturedBase64 != null) {
            getString(R.string.capture_ready)
        } else {
            getString(R.string.capture_prompt)
        }
        b.btnSubmit.isEnabled = capturedBase64 != null && latitude != null && longitude != null
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
                refreshCapturedState()
            }
            .addOnFailureListener { e ->
                _binding?.tvLocation?.text = "Location error: ${e.message}"
            }
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

        binding.progress.visibility = View.VISIBLE
        binding.btnSubmit.isEnabled = false
        binding.btnCapture.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val req = ReportRequest(base64, lat, lng, description.ifEmpty { null })
                    when (reportType) {
                        ReportType.HELMET -> api.reportHelmet(req)
                        ReportType.POTHOLE -> api.reportPothole(req)
                    }
                }
                showSnack(
                    getString(
                        R.string.submit_success,
                        response.caseId,
                        response.status,
                    )
                )
                resetForm()
            } catch (e: Exception) {
                showSnack("Upload failed: ${e.message}")
            } finally {
                _binding?.let {
                    it.progress.visibility = View.GONE
                    it.btnCapture.isEnabled = true
                    refreshCapturedState()
                }
            }
        }
    }

    private fun resetForm() {
        capturedBase64 = null
        binding.etDescription.setText("")
        refreshCapturedState()
    }

    // -----------------------------------------------------------------------
    // Util
    // -----------------------------------------------------------------------

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
