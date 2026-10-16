package com.example.geostampcamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.util.Size
import android.view.View
import android.widget.Button
import android.app.AlertDialog
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var captureButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var currentLocation: Location? = null
    private var locationManager: LocationManager? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK
    private var cameraControl: CameraControl? = null
    private var maxZoomRatio = 4f
    private var zoomRatio = 1f
    private var exposureIndex = 0
    private var minExposureIndex = 0
    private var maxExposureIndex = 0
    private lateinit var zoomLabel: TextView
    private lateinit var timerButton: Button
    private lateinit var qualityButton: Button
    private lateinit var gridOverlay: GridOverlay
    private var gridEnabled = false
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var flashEnabled = false
    private var lastSavedUri: Uri? = null
    private var showDateTime = true
    private var showCoordinates = true
    private var showPlaceName = true
    private var watermarkPosition = 0 // 0=bottom-left, 1=bottom-right, 2=top-left, 3=top-right
    private var timerSeconds = 0
    private var photoQuality = 94
    private var ultraHdMode = false
    private var watermarkTextColor = Color.WHITE
    private var watermarkBackgroundAlpha = 0x99
    private var countdownActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) startCamera()
            else Toast.makeText(this, "Camera permission zaroori hai.", Toast.LENGTH_LONG).show()

            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                startLocationUpdates()
            } else {
                statusText.text = "Location permission nahi di gayi"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        val prefs = getSharedPreferences("geostamp_settings", MODE_PRIVATE)
        showDateTime = prefs.getBoolean("show_date_time", true)
        showCoordinates = prefs.getBoolean("show_coordinates", true)
        showPlaceName = prefs.getBoolean("show_place_name", true)
        watermarkPosition = prefs.getInt("watermark_position", 0).coerceIn(0, 3)
        timerSeconds = prefs.getInt("timer_seconds", 0).coerceIn(0, 10)
        photoQuality = prefs.getInt("photo_quality", 94).coerceIn(75, 100)
        ultraHdMode = prefs.getBoolean("ultra_hd_mode", false)
        watermarkTextColor = prefs.getInt("watermark_text_color", Color.WHITE)
        watermarkBackgroundAlpha = prefs.getInt("watermark_bg_alpha", 0x99).coerceIn(0x40, 0xE0)
        buildUi()
        requestNeededPermissions()
    }

    private fun buildUi() {
        val bg = Color.rgb(14, 17, 22)
        val panel = Color.rgb(25, 30, 38)
        val buttonBg = Color.rgb(43, 51, 63)
        val accent = Color.rgb(67, 220, 190)
        val muted = Color.rgb(171, 182, 196)

        fun rounded(color: Int, radiusDp: Float = 16f): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }
        fun styleButton(button: Button, color: Int = buttonBg, textColor: Int = Color.WHITE,
                        radius: Float = 14f, textSize: Float = 13f) {
            button.isAllCaps = false
            button.textSize = textSize
            button.setTextColor(textColor)
            button.background = rounded(color, radius)
            button.backgroundTintList = null
            button.minHeight = (42 * resources.displayMetrics.density).toInt()
            button.minimumHeight = button.minHeight
            button.setPadding(12, 0, 12, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) button.stateListAnimator = null
        }
        fun rowParams(weight: Float = 1f): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                setMargins(4, 4, 4, 4)
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(12, 0, 12, 8)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 14, 4, 12)
        }
        val brandStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "GeoStamp Camera"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val subtitle = TextView(this).apply {
            text = "CAPTURE • STAMP • SHARE"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(muted)
            setPadding(1, 2, 0, 0)
        }
        brandStack.addView(title)
        brandStack.addView(subtitle)
        header.addView(brandStack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val settingsQuick = Button(this).apply {
            text = "⚙"
            contentDescription = "Camera and watermark settings"
            textSize = 20f
            setOnClickListener { showSettingsDialog() }
        }
        styleButton(settingsQuick, panel, Color.WHITE, 16f, 20f)
        settingsQuick.minWidth = (48 * resources.displayMetrics.density).toInt()
        header.addView(settingsQuick)
        root.addView(header)

        val previewContainer = FrameLayout(this).apply {
            background = rounded(Color.BLACK, 22f)
            clipToOutline = true
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        gridOverlay = GridOverlay(this).apply { visibility = View.GONE }
        previewContainer.addView(previewView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        previewContainer.addView(gridOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(previewContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { setMargins(0, 0, 0, 10) })

        statusText = TextView(this).apply {
            text = "Finding GPS location…"
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 9, 14, 9)
            background = rounded(panel, 12f)
            maxLines = 2
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 8) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(4, 6, 4, 2)
            background = rounded(panel, 22f)
        }
        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun makeButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
            styleButton(this)
        }
        fun addRowButton(row: LinearLayout, label: String, action: () -> Unit) {
            row.addView(makeButton(label, action), rowParams())
        }
        addRowButton(firstRow, "Flip") {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            flashEnabled = false
            cameraControl?.enableTorch(false)
            startCamera()
        }
        addRowButton(firstRow, if (flashEnabled) "Flash On" else "Flash") {
            flashEnabled = !flashEnabled
            try {
                cameraControl?.enableTorch(flashEnabled)
                Toast.makeText(this, if (flashEnabled) "Flash on" else "Flash off", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                flashEnabled = false
                Toast.makeText(this, "Flash is camera par available nahi hai", Toast.LENGTH_SHORT).show()
            }
        }
        addRowButton(firstRow, "Grid") {
            gridEnabled = !gridEnabled
            gridOverlay.visibility = if (gridEnabled) View.VISIBLE else View.GONE
            Toast.makeText(this, if (gridEnabled) "Grid on" else "Grid off", Toast.LENGTH_SHORT).show()
        }
        addRowButton(secondRow, "Gallery") { openGallery() }
        captureButton = makeButton("●  PHOTO") { takePhoto() }.apply {
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            minHeight = (54 * resources.displayMetrics.density).toInt()
            minimumHeight = minHeight
        }
        styleButton(captureButton, accent, Color.rgb(8, 28, 27), 28f, 15f)
        secondRow.addView(captureButton, rowParams(1.35f))
        addRowButton(secondRow, "Share") { shareLastPhoto() }

        val proRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 4)
        }
        val zoomMinus = makeButton("−") { changeZoom(-0.5f) }
        proRow.addView(zoomMinus)
        zoomLabel = TextView(this).apply {
            text = "1.0×"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            minWidth = 52
        }
        proRow.addView(zoomLabel)
        proRow.addView(makeButton("+") { changeZoom(0.5f) })
        proRow.addView(makeButton("EV −") { changeExposure(-1) })
        proRow.addView(makeButton("EV +") { changeExposure(1) })
        timerButton = makeButton("Timer ${timerLabel()}") { cycleTimer() }
        qualityButton = makeButton(qualityLabel()) { cycleQuality() }
        proRow.addView(timerButton)
        proRow.addView(qualityButton)
        val proScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(proRow)
        }
        controls.addView(firstRow)
        controls.addView(secondRow)
        controls.addView(proScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(controls)
        setContentView(root)
    }

    private fun setupPreviewGestures() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val control = cameraControl ?: return false
                zoomRatio = (zoomRatio * detector.scaleFactor).coerceIn(1f, maxZoomRatio.coerceAtLeast(1f))
                control.setZoomRatio(zoomRatio)
                if (::zoomLabel.isInitialized) zoomLabel.text = "%.1f×".format(Locale.US, zoomRatio)
                return true
            }
        })
        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleGestureDetector.isInProgress) {
                try {
                    val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    cameraControl?.startFocusAndMetering(action)
                } catch (_: Exception) { }
            }
            true
        }
    }

    private fun timerLabel(): String = if (timerSeconds == 0) "Off" else "${timerSeconds}s"

    private fun qualityLabel(): String = when {
        ultraHdMode -> "Ultra HD"
        photoQuality >= 99 -> "Max"
        photoQuality >= 93 -> "High"
        else -> "Standard"
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
        getSharedPreferences("geostamp_settings", MODE_PRIVATE).edit()
            .putInt("timer_seconds", timerSeconds).apply()
        refreshProControls()
        Toast.makeText(this, "Timer: ${timerLabel()}", Toast.LENGTH_SHORT).show()
    }

    private fun cycleQuality() {
        when {
            ultraHdMode -> {
                ultraHdMode = false
                photoQuality = 85
            }
            photoQuality == 85 -> photoQuality = 94
            photoQuality == 94 -> photoQuality = 100
            else -> {
                ultraHdMode = true
                photoQuality = 100
            }
        }
        getSharedPreferences("geostamp_settings", MODE_PRIVATE).edit()
            .putInt("photo_quality", photoQuality)
            .putBoolean("ultra_hd_mode", ultraHdMode)
            .apply()
        refreshProControls()
        if (ultraHdMode) {
            startCamera()
            Toast.makeText(this, "Ultra HD: highest compatible ~4K resolution, JPEG 100%", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Photo quality: ${qualityLabel()} ($photoQuality%)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshProControls() {
        if (::timerButton.isInitialized) timerButton.text = "Timer ${timerLabel()}"
        if (::qualityButton.isInitialized) qualityButton.text = qualityLabel()
    }

    private fun changeZoom(delta: Float) {
        if (cameraControl == null) {
            Toast.makeText(this, "Camera abhi ready nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        zoomRatio = (zoomRatio + delta).coerceIn(1f, maxZoomRatio.coerceAtLeast(1f))
        cameraControl?.setZoomRatio(zoomRatio)
        if (::zoomLabel.isInitialized) zoomLabel.text = "%.1f×".format(Locale.US, zoomRatio)
    }

    private fun changeExposure(delta: Int) {
        val next = (exposureIndex + delta).coerceIn(minExposureIndex, maxExposureIndex)
        if (next == exposureIndex) {
            Toast.makeText(this, "Exposure limit reached", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            cameraControl?.setExposureCompensationIndex(next)
            exposureIndex = next
            val ev = cameraControl?.let { next }
            Toast.makeText(this, "Exposure index: ${ev ?: next}", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Exposure control available nahi hai", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else {
            startCamera()
            startLocationUpdates()
        }
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(if (ultraHdMode) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                                    else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                if (ultraHdMode) {
                    // Aim for UHD/4K-sized output while allowing CameraX to select a compatible size.
                    // The actual resolution depends on the camera hardware.
                    captureBuilder.setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(3840, 2160),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                }
                imageCapture = captureBuilder.build()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(cameraFacing).build()
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
                cameraControl = camera.cameraControl
                maxZoomRatio = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 4f
                zoomRatio = 1f
                cameraControl?.setZoomRatio(zoomRatio)
                val exposure = camera.cameraInfo.exposureState
                minExposureIndex = exposure.exposureCompensationRange.lower
                maxExposureIndex = exposure.exposureCompensationRange.upper
                exposureIndex = exposure.exposureCompensationIndex.coerceIn(minExposureIndex, maxExposureIndex)
                runOnUiThread { if (::zoomLabel.isInitialized) zoomLabel.text = "1.0×" }
                setupPreviewGestures()
                if (flashEnabled && cameraFacing == CameraSelector.LENS_FACING_BACK) {
                    cameraControl?.enableTorch(true)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Camera start nahi hua: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return

        try {
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            val manager = locationManager ?: return

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = location
                    statusText.text = "GPS: %.5f, %.5f".format(
                        Locale.US, location.latitude, location.longitude
                    )
                }
            }

            val providers = manager.getProviders(true)
            var requested = false
            for (provider in providers) {
                try {
                    manager.requestLocationUpdates(provider, 3000L, 2f, listener)
                    val last = manager.getLastKnownLocation(provider)
                    if (last != null && (currentLocation == null ||
                                last.time > currentLocation!!.time)) currentLocation = last
                    requested = true
                } catch (_: Exception) { }
            }
            if (!requested) statusText.text = "Location services on karein"
            currentLocation?.let {
                statusText.text = "GPS: %.5f, %.5f".format(Locale.US, it.latitude, it.longitude)
            }
        } catch (_: Exception) {
            statusText.text = "Location available nahi hai"
        }
    }

    private fun showSettingsDialog() {
        val options = arrayOf("Date & time watermark", "GPS coordinates", "Place name")
        val checked = booleanArrayOf(showDateTime, showCoordinates, showPlaceName)
        AlertDialog.Builder(this)
            .setTitle("Watermark options")
            .setMultiChoiceItems(options, checked) { _, which, isChecked ->
                when (which) {
                    0 -> showDateTime = isChecked
                    1 -> showCoordinates = isChecked
                    2 -> showPlaceName = isChecked
                }
                getSharedPreferences("geostamp_settings", MODE_PRIVATE).edit()
                    .putBoolean("show_date_time", showDateTime)
                    .putBoolean("show_coordinates", showCoordinates)
                    .putBoolean("show_place_name", showPlaceName)
                    .apply()
            }
            .setPositiveButton("Position") { _, _ -> showPositionDialog() }
            .setNegativeButton("Style") { _, _ -> showWatermarkStyleDialog() }
            .setNeutralButton("Done", null)
            .show()
    }

    private fun showWatermarkStyleDialog() {
        val colors = arrayOf("White", "Yellow", "Cyan")
        val colorValues = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN)
        val selectedColor = colorValues.indexOf(watermarkTextColor).let { if (it < 0) 0 else it }
        AlertDialog.Builder(this)
            .setTitle("Watermark text color")
            .setSingleChoiceItems(colors, selectedColor) { dialog, which ->
                watermarkTextColor = colorValues[which]
                getSharedPreferences("geostamp_settings", MODE_PRIVATE).edit()
                    .putInt("watermark_text_color", watermarkTextColor).apply()
                dialog.dismiss()
                showWatermarkBackgroundDialog()
            }
            .setNegativeButton("Cancel", null)
            .setOnCancelListener { }
            .show()
    }

    private fun showWatermarkBackgroundDialog() {
        val labels = arrayOf("Subtle", "Medium", "Dark")
        val alphas = intArrayOf(0x40, 0x99, 0xE0)
        val selected = alphas.indexOf(watermarkBackgroundAlpha).let { if (it < 0) 1 else it }
        AlertDialog.Builder(this)
            .setTitle("Watermark background")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                watermarkBackgroundAlpha = alphas[which]
                getSharedPreferences("geostamp_settings", MODE_PRIVATE).edit()
                    .putInt("watermark_bg_alpha", watermarkBackgroundAlpha).apply()
                dialog.dismiss()
                Toast.makeText(this, "Watermark style saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPositionDialog() {
        val positions = arrayOf("Bottom left", "Bottom right", "Top left", "Top right")
        AlertDialog.Builder(this)
            .setTitle("Watermark position")
            .setSingleChoiceItems(positions, watermarkPosition) { dialog, which ->
                watermarkPosition = which
                getSharedPreferences("geostamp_settings", MODE_PRIVATE).edit()
                    .putInt("watermark_position", which).apply()
                dialog.dismiss()
                Toast.makeText(this, "Position: ${positions[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivity(Intent.createChooser(intent, "Open photo gallery"))
        } catch (_: Exception) {
            Toast.makeText(this, "Gallery app nahi mili", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLastPhoto() {
        val uri = lastSavedUri
        if (uri == null) {
            Toast.makeText(this, "Pehle ek photo lein, phir Share karein", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share GeoStamp photo"))
        } catch (_: Exception) {
            Toast.makeText(this, "Photo share nahi ho saki", Toast.LENGTH_SHORT).show()
        }
    }

    private fun takePhoto() {
        if (countdownActive) return
        if (timerSeconds <= 0) {
            capturePhotoNow()
            return
        }
        countdownActive = true
        var remaining = timerSeconds
        val countdown = object : Runnable {
            override fun run() {
                if (remaining <= 0) {
                    countdownActive = false
                    capturePhotoNow()
                } else {
                    Toast.makeText(this@MainActivity, "Photo in $remaining...", Toast.LENGTH_SHORT).show()
                    remaining--
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        mainHandler.post(countdown)
    }

    private fun capturePhotoNow() {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera abhi ready nahi hai.", Toast.LENGTH_SHORT).show()
            return
        }
        captureButton.isEnabled = false
        val tempFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                        ?: throw IllegalStateException("Photo read nahi ho saki")
                    val rotated = rotateFromExif(bitmap, tempFile)
                    val stamped = addWatermark(rotated)
                    val uri = saveToGallery(stamped)
                    lastSavedUri = uri
                    runOnUiThread {
                        captureButton.isEnabled = true
                        if (uri != null) Toast.makeText(
                            this@MainActivity, "Photo save ho gayi: Gallery/Pictures/GeoStampCamera",
                            Toast.LENGTH_LONG
                        ).show()
                        else Toast.makeText(this@MainActivity, "Photo save nahi ho saki.",
                            Toast.LENGTH_LONG).show()
                    }
                    if (stamped !== rotated) stamped.recycle()
                    if (rotated !== bitmap) rotated.recycle()
                    bitmap.recycle()
                } catch (e: Exception) {
                    runOnUiThread {
                        captureButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "Photo error: ${e.message}",
                            Toast.LENGTH_LONG).show()
                    }
                } finally {
                    tempFile.delete()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    captureButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Capture fail: ${exception.message}",
                        Toast.LENGTH_LONG).show()
                }
                tempFile.delete()
            }
        })
    }

    private fun rotateFromExif(bitmap: Bitmap, file: File): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun addWatermark(source: Bitmap): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val scale = output.width / 1080f
        val padding = 26f * scale.coerceAtLeast(0.65f)
        val now = Date()
        val loc = currentLocation
        val lines = mutableListOf<String>()
        if (showDateTime) {
            lines.add(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(now))
            lines.add(SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(now))
        }
        if (showCoordinates) {
            lines.add(if (loc != null) "GPS: %.6f, %.6f".format(Locale.US, loc.latitude, loc.longitude)
                else "GPS: Location unavailable")
        }
        val place = if (showPlaceName && loc != null) getPlaceName(loc) else null
        if (!place.isNullOrBlank()) lines.add(place)
        if (lines.isEmpty()) lines.add("GeoStamp Camera")

        val textSize = 30f * scale.coerceAtLeast(0.65f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = watermarkTextColor
            this.textSize = textSize
            typeface = android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val lineHeight = textSize * 1.35f
        val maxWidth = lines.maxOf { paint.measureText(it) }
        val boxHeight = lineHeight * lines.size + padding
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (watermarkBackgroundAlpha shl 24) or 0x000000 }
        val left = padding
        val top = padding
        val boxWidth = maxWidth + padding * 1.5f
        val boxLeft = if (watermarkPosition == 1 || watermarkPosition == 3)
            output.width - boxWidth - padding / 2 else padding / 2
        val boxTop = if (watermarkPosition == 2 || watermarkPosition == 3)
            top else output.height - boxHeight - padding / 2
        val textX = boxLeft + padding / 2
        canvas.drawRoundRect(
            RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight),
            12f * scale.coerceAtLeast(0.65f), 12f * scale.coerceAtLeast(0.65f), box
        )
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, textX, boxTop + padding / 2 + textSize + index * lineHeight, paint)
        }
        return output
    }

    @Suppress("DEPRECATION")
    private fun getPlaceName(location: Location): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(this, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= 33) {
                // Avoid blocking the camera UI on newer Android versions.
                geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                    val address = addresses.firstOrNull()
                    val name = address?.locality ?: address?.subAdminArea ?: address?.adminArea
                    if (!name.isNullOrBlank()) runOnUiThread {
                        statusText.text = "Location: $name"
                    }
                }
                null
            } else {
                val address = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
                address?.locality ?: address?.subAdminArea ?: address?.adminArea
            }
        } catch (_: Exception) { null }
    }

    private fun saveToGallery(bitmap: Bitmap): Uri? {
        val name = "GeoStamp_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeoStampCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, photoQuality, stream))
                    throw IllegalStateException("JPEG encode failed")
            } ?: throw IllegalStateException("Gallery stream unavailable")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private class GridOverlay(context: android.content.Context) : View(context) {
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val thirdW = width / 3f
            val thirdH = height / 3f
            for (i in 1..2) {
                canvas.drawLine(thirdW * i, 0f, thirdW * i, height.toFloat(), gridPaint)
                canvas.drawLine(0f, thirdH * i, width.toFloat(), thirdH * i, gridPaint)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
             event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.repeatCount == 0) takePhoto()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager?.removeUpdates(object : LocationListener {
            override fun onLocationChanged(location: Location) {}
        }) } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
    }
}
