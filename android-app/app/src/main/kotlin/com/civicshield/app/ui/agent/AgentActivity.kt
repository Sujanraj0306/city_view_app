package com.civicshield.app.ui.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.civicshield.app.R
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.local.AuthStore
import com.civicshield.app.data.model.AgentChatRequest
import com.civicshield.app.data.model.CaseAdminItem
import com.civicshield.app.data.model.ReportRequest
import com.civicshield.app.databinding.ActivityAgentBinding
import com.civicshield.app.ui.user.UserCaseAdapter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Conversational CivicShield assistant. The model's replies can carry an
 * `action` hint — the Activity acts on it: open the camera, reveal the
 * user's cases, or submit a prepared report.
 */
class AgentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentBinding
    private lateinit var api: ApiService
    private lateinit var authStore: AuthStore
    private val adapter = AgentChatAdapter()
    private val casesAdapter = UserCaseAdapter()

    private val sessionId: String = UUID.randomUUID().toString()
    private var userId: Long = 0L

    private var pendingImageBase64: String? = null
    private var pendingBitmap: Bitmap? = null
    private var latitude: Double? = null
    private var longitude: Double? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsMuted = false

    private val messages = mutableListOf<ChatMessage>()
    private var typingMessageId: Long? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    // -------------------------------------------------------------------
    // Permission launchers
    // -------------------------------------------------------------------

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraOverlay() else snack(getString(R.string.err_camera_permission))
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchLocation()
    }

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleListening() else snack(getString(R.string.agent_mic_permission))
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) handlePickedImage(uri)
    }

    // -------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        api = RetrofitClient.create(applicationContext)
        authStore = AuthStore(applicationContext)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.lottieRobot.setFailureListener { _ -> /* degrade silently */ }
        binding.lottieRobot.setAnimationFromUrl(LOTTIE_ROBOT_URL)
        binding.lottieRobot.speed = IDLE_SPEED
        binding.lottieRobot.playAnimation()

        binding.rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChat.itemAnimator = DefaultItemAnimator()
        binding.rvChat.adapter = adapter

        binding.rvCases.layoutManager = LinearLayoutManager(this)
        binding.rvCases.adapter = casesAdapter

        binding.btnSend.setOnClickListener { onSendClicked() }
        binding.btnMic.setOnClickListener { onMicClicked() }
        binding.btnAttach.setOnClickListener { onAttachClicked() }
        binding.btnClearAttached.setOnClickListener { clearAttachedImage() }
        binding.btnCameraClose.setOnClickListener { hideCameraOverlay() }
        binding.fabShutter.setOnClickListener { capturePhoto() }
        binding.btnCloseCases.setOnClickListener { hideCasesSheet() }
        binding.fabTts.setOnClickListener { toggleMute() }

        initTts()

        lifecycleScope.launch {
            userId = authStore.userId.first() ?: 0L
            seedGreeting()
        }

        ensureLocationPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        pendingBitmap?.recycle()
        pendingBitmap = null
    }

    // -------------------------------------------------------------------
    // Chat rendering
    // -------------------------------------------------------------------

    private fun seedGreeting() {
        addAgentMessage(getString(R.string.agent_greeting))
    }

    private fun addMessage(m: ChatMessage) {
        messages.add(m)
        adapter.submitList(messages.toList()) {
            binding.rvChat.smoothScrollToPosition(messages.lastIndex.coerceAtLeast(0))
        }
    }

    private fun addAgentMessage(text: String) {
        addMessage(ChatMessage.Agent(System.nanoTime(), text))
        vibrateTick()
        speak(text)
    }

    private fun showTypingIndicator() {
        if (typingMessageId != null) return
        val id = System.nanoTime()
        typingMessageId = id
        addMessage(ChatMessage.Typing(id))
    }

    private fun removeTypingIndicator() {
        val id = typingMessageId ?: return
        typingMessageId = null
        val idx = messages.indexOfFirst { it.id == id }
        if (idx >= 0) {
            messages.removeAt(idx)
            adapter.submitList(messages.toList())
        }
    }

    // -------------------------------------------------------------------
    // Send / receive
    // -------------------------------------------------------------------

    private fun onSendClicked() {
        val text = binding.etMessage.text?.toString()?.trim().orEmpty()
        val imageB64 = pendingImageBase64
        if (text.isEmpty() && imageB64 == null) return

        val userText = if (text.isNotEmpty()) text
        else getString(R.string.agent_user_image_only)
        addMessage(ChatMessage.User(System.nanoTime(), userText))
        binding.etMessage.setText("")

        val image = imageB64
        // Consume the attached image on send — the agent has seen it now.
        clearAttachedImage()

        binding.btnSend.isEnabled = false
        showTypingIndicator()

        lifecycleScope.launch {
            val reply = try {
                withContext(Dispatchers.IO) {
                    api.agentChat(
                        AgentChatRequest(
                            userId = userId.toString(),
                            sessionId = sessionId,
                            message = text,
                            imageBase64 = image,
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "agentChat failed", e)
                null
            } finally {
                binding.btnSend.isEnabled = true
                removeTypingIndicator()
            }

            if (reply == null) {
                addAgentMessage(getString(R.string.agent_transport_error))
                return@launch
            }

            val replyText = reply.reply.ifBlank { getString(R.string.agent_empty_reply) }
            addAgentMessage(replyText)
            dispatchAction(reply.action, reply.data)
        }
    }

    // -------------------------------------------------------------------
    // Action dispatch
    // -------------------------------------------------------------------

    private fun dispatchAction(action: String?, data: Map<String, Any?>) {
        when (action) {
            "open_camera" -> launchCameraOverlay()
            "show_cases" -> showCasesSheet()
            "submit_report" -> confirmAndSubmitReport(data)
            else -> Unit
        }
    }

    // -------------------------------------------------------------------
    // Camera overlay (CameraX)
    // -------------------------------------------------------------------

    private fun launchCameraOverlay() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        startCameraOverlay()
    }

    private fun startCameraOverlay() {
        binding.cameraOverlay.visibility = View.VISIBLE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val newPreview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val newCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    newPreview,
                    newCapture,
                )
                preview = newPreview
                imageCapture = newCapture
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                snack("Camera unavailable: ${e.message}")
                hideCameraOverlay()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hideCameraOverlay() {
        binding.cameraOverlay.visibility = View.GONE
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        imageCapture = null
    }

    private fun capturePhoto() {
        val ic = imageCapture ?: run {
            snack("Camera not ready")
            return
        }
        binding.fabShutter.isEnabled = false
        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = image.planes[0].buffer.run {
                        val b = ByteArray(remaining()); get(b); b
                    }
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    compressAndAttach(bytes, rotation)
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.fabShutter.isEnabled = true
                    snack("Capture failed: ${exception.message}")
                }
            },
        )
    }

    private fun compressAndAttach(rawJpeg: ByteArray, rotationDegrees: Int) {
        lifecycleScope.launch {
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
            attachImage(prepared.bitmap, prepared.base64)
            binding.fabShutter.isEnabled = true
            hideCameraOverlay()
        }
    }

    // -------------------------------------------------------------------
    // Gallery attach
    // -------------------------------------------------------------------

    private fun onAttachClicked() {
        val options = arrayOf(
            getString(R.string.agent_attach_camera),
            getString(R.string.agent_attach_gallery),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_attach)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCameraOverlay()
                    1 -> pickMediaLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
            }
            .show()
    }

    private fun handlePickedImage(uri: Uri) {
        lifecycleScope.launch {
            data class Prepared(val bitmap: Bitmap, val base64: String)
            val prepared = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val decoded = BitmapFactory.decodeStream(stream)
                        ?: return@withContext null
                    val scaled = scaleIfLarge(decoded, 1600)
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    Prepared(scaled, Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                }
            }
            if (prepared == null) {
                snack(getString(R.string.agent_attach_failed))
                return@launch
            }
            attachImage(prepared.bitmap, prepared.base64)
        }
    }

    private fun scaleIfLarge(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width; val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        val matrix = Matrix().apply { postScale(scale, scale) }
        val scaled = Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
        if (scaled != src) src.recycle()
        return scaled
    }

    private fun attachImage(bitmap: Bitmap, base64: String) {
        pendingBitmap?.recycle()
        pendingBitmap = bitmap
        pendingImageBase64 = base64
        binding.attachedRow.visibility = View.VISIBLE
        Glide.with(this).load(bitmap).into(binding.ivAttachedThumb)
    }

    private fun clearAttachedImage() {
        pendingBitmap?.recycle()
        pendingBitmap = null
        pendingImageBase64 = null
        binding.attachedRow.visibility = View.GONE
    }

    // -------------------------------------------------------------------
    // Speech recognizer
    // -------------------------------------------------------------------

    private fun onMicClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        toggleListening()
    }

    private fun toggleListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            snack(getString(R.string.agent_mic_unavailable))
            return
        }
        if (listening) {
            speechRecognizer?.stopListening()
            listening = false
            return
        }
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false }
                override fun onError(error: Int) {
                    listening = false
                    snack(getString(R.string.agent_mic_error, error))
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    listening = false
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?: return
                    val transcript = matches.firstOrNull()?.trim().orEmpty()
                    if (transcript.isEmpty()) return
                    val current = binding.etMessage.text?.toString().orEmpty()
                    val joined = if (current.isEmpty()) transcript
                    else "$current $transcript"
                    binding.etMessage.setText(joined)
                    binding.etMessage.setSelection(joined.length)
                }
            })
            speechRecognizer = it
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(intent)
        listening = true
    }

    // -------------------------------------------------------------------
    // Cases sheet
    // -------------------------------------------------------------------

    private fun showCasesSheet() {
        if (binding.casesSheet.isVisible) return
        binding.casesSheet.visibility = View.VISIBLE
        binding.casesSheet.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom)
        )
        loadCases()
    }

    private fun hideCasesSheet() {
        if (!binding.casesSheet.isVisible) return
        val anim = AnimationUtils.loadAnimation(this, R.anim.slide_out_bottom)
        anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(a: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
            override fun onAnimationEnd(a: android.view.animation.Animation?) {
                binding.casesSheet.visibility = View.GONE
            }
        })
        binding.casesSheet.startAnimation(anim)
    }

    private fun loadCases() {
        lifecycleScope.launch {
            val cases: List<CaseAdminItem> = try {
                withContext(Dispatchers.IO) { api.listMyCases("me") }
            } catch (e: Exception) {
                Log.w(TAG, "listMyCases failed", e)
                emptyList()
            }
            binding.tvCasesEmpty.visibility = if (cases.isEmpty()) View.VISIBLE else View.GONE
            binding.rvCases.visibility = if (cases.isEmpty()) View.GONE else View.VISIBLE
            casesAdapter.submitList(cases)
        }
    }

    // -------------------------------------------------------------------
    // Submit report
    // -------------------------------------------------------------------

    private fun confirmAndSubmitReport(data: Map<String, Any?>) {
        val type = (data["type"] as? String)?.lowercase()?.takeIf {
            it == "helmet" || it == "pothole"
        } ?: "helmet"
        val description = (data["description"] as? String).orEmpty()

        val image = pendingImageBase64
        if (image == null) {
            snack(getString(R.string.agent_submit_no_image))
            return
        }
        val lat = latitude
        val lng = longitude
        if (lat == null || lng == null) {
            snack(getString(R.string.submit_missing_location))
            ensureLocationPermission()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_confirm_submit_title)
            .setMessage(getString(R.string.agent_confirm_submit_msg, type))
            .setPositiveButton(R.string.btn_submit) { _, _ ->
                submitReport(type, description, image, lat, lng)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun submitReport(
        type: String,
        description: String,
        image: String,
        lat: Double,
        lng: Double,
    ) {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val req = ReportRequest(image, lat, lng, description.ifBlank { null })
                    if (type == "helmet") api.reportHelmet(req) else api.reportPothole(req)
                }
                clearAttachedImage()
                addAgentMessage(
                    getString(
                        R.string.agent_submit_success,
                        response.caseId.take(8),
                        response.status,
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "submit failed", e)
                addAgentMessage(
                    getString(R.string.agent_submit_failed, e.message ?: "unknown")
                )
            }
        }
    }

    // -------------------------------------------------------------------
    // Location
    // -------------------------------------------------------------------

    private fun ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        fetchLocation()
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    latitude = loc.latitude
                    longitude = loc.longitude
                }
            }
            .addOnFailureListener {
                Log.i(TAG, "location fetch failed: ${it.message}")
            }
    }

    // -------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------

    private fun snack(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }

    // -------------------------------------------------------------------
    // Text-to-speech + haptic
    // -------------------------------------------------------------------

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed: $status")
                return@TextToSpeech
            }
            tts?.language = java.util.Locale.US
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(0.9f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread {
                        binding.lottieRobot.speed = SPEAKING_SPEED
                        binding.lottieRobot.playAnimation()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    runOnUiThread { binding.lottieRobot.speed = IDLE_SPEED }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { binding.lottieRobot.speed = IDLE_SPEED }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    runOnUiThread { binding.lottieRobot.speed = IDLE_SPEED }
                }
            })
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        if (ttsMuted || !ttsReady || text.isBlank()) return
        val id = "agent-${System.nanoTime()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun toggleMute() {
        ttsMuted = !ttsMuted
        if (ttsMuted) {
            tts?.stop()
            binding.lottieRobot.speed = IDLE_SPEED
            binding.fabTts.setImageResource(R.drawable.ic_volume_off)
            snack(getString(R.string.agent_tts_off))
        } else {
            binding.fabTts.setImageResource(R.drawable.ic_volume_up)
            snack(getString(R.string.agent_tts_on))
        }
    }

    private fun vibrateTick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        private const val TAG = "AgentActivity"
        private const val LOTTIE_ROBOT_URL =
            "https://assets3.lottiefiles.com/packages/lf20_ystsffqy.json"
        private const val IDLE_SPEED = 0.5f
        private const val SPEAKING_SPEED = 1.5f
    }
}
