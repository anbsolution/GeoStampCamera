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
        val bg = Color.rgb(5, 10, 15)
        val panel = Color.rgb(18, 27, 36)
        val accent = Color.rgb(40, 220, 185)
        val muted = Color.rgb(166, 183, 197)
        fun rounded(color: Int, radius: Float = 16f) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * resources.displayMetrics.density
            setStroke(resources.displayMetrics.density.toInt(), Color.rgb(36, 58, 70))
        }
        fun styleButton(b: Button, color: Int = Color.rgb(24, 36, 47), textColor: Int = Color.WHITE, size: Float = 12f) {
            b.isAllCaps = false
            b.textSize = size
            b.setTextColor(textColor)
            b.background = rounded(color, 18f)
            b.backgroundTintList = null
            b.minHeight = (44 * resources.displayMetrics.density).toInt()
            b.minimumHeight = b.minHeight
            b.setPadding(4, 0, 4, 0)
            if (Build.VERSION.SDK_INT >= 21) b.stateListAnimator = null
        }
        fun rowParams(weight: Float = 1f) = LinearLayout.LayoutParams(0, -2, weight).apply {
            setMargins(2, 2, 2, 2)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(10, 0, 10, 5)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(3, 8, 3, 4)
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "GeoStamp Camera  ♛ PRO"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        brand.addView(TextView(this).apply {
            text = "CAPTURE • STAMP • SHARE"
            textSize = 10f
            letterSpacing = .12f
            setTextColor(muted)
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val gear = Button(this).apply {
            text = "⚙"
            contentDescription = "Settings"
            setOnClickListener { showSettingsDialog() }
        }
        styleButton(gear, panel, Color.WHITE, 22f)
        header.addView(gear, LinearLayout.LayoutParams(
            (50 * resources.displayMetrics.density).toInt(),
            (44 * resources.displayMetrics.density).toInt()
        ))
        root.addView(header)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 6)
        }
        fun topAction(label: String, click: () -> Unit) {
            val b = Button(this).apply { text = label; setOnClickListener { click() } }
            styleButton(b, bg, Color.WHITE, 10f)
            topBar.addView(b, rowParams())
        }
        topAction("⟳\nFlip") {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            flashEnabled = false
            cameraControl?.enableTorch(false)
            startCamera()
        }
        topAction("⚡\nFlash") {
            flashEnabled = !flashEnabled
            try { cameraControl?.enableTorch(flashEnabled) }
            catch (_: Exception) { flashEnabled = false }
        }
        topAction("▦\nGrid") {
            gridEnabled = !gridEnabled
            if (::gridOverlay.isInitialized) gridOverlay.visibility =
                if (gridEnabled) View.VISIBLE else View.GONE
        }
        topAction("◷\nTimer") { cycleTimer() }
        topAction("4:3\nRatio") { showAspectRatioDialog() }
        topAction("•••\nMore") { showSettingsDialog() }
        root.addView(topBar)

        val previewContainer = FrameLayout(this).apply {
            background = rounded(Color.BLACK, 22f)
            clipToOutline = true
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        gridOverlay = GridOverlay(this).apply { visibility = View.GONE }
        previewContainer.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        previewContainer.addView(gridOverlay, FrameLayout.LayoutParams(-1, -1))
        statusText = TextView(this).apply {
            text = "Finding GPS location…"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
            maxLines = 2
            background = GradientDrawable().apply {
                setColor(0xAA101820.toInt())
                cornerRadius = 14f * resources.displayMetrics.density
            }
        }
        previewContainer.addView(statusText, FrameLayout.LayoutParams(
            -1, -2, Gravity.BOTTOM
        ).apply { setMargins(8, 8, 8, 8) })
        root.addView(previewContainer, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(0, 0, 0, 6)
        })

        val captureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val gallery = Button(this).apply {
            text = "▣\nGallery"
            setOnClickListener { openGallery() }
        }
        styleButton(gallery, bg, Color.WHITE, 12f)
        captureRow.addView(gallery, rowParams())
        captureButton = Button(this).apply {
            text = "◉  PHOTO"
            textSize = 18f
            contentDescription = "Take photo"
            setOnClickListener { takePhoto() }
        }
        styleButton(captureButton, accent, Color.rgb(4, 28, 27), 17f)
        captureRow.addView(captureButton, LinearLayout.LayoutParams(
            0, (62 * resources.displayMetrics.density).toInt(), 1.3f
        ).apply { setMargins(7, 2, 7, 2) })
        val switch = Button(this).apply {
            text = "▣\nSwitch"
            setOnClickListener {
                cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                flashEnabled = false
                cameraControl?.enableTorch(false)
                startCamera()
            }
        }
        styleButton(switch, bg, Color.WHITE, 12f)
        captureRow.addView(switch, rowParams())
        root.addView(captureRow)

        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("PHOTO", "VIDEO", "PORTRAIT", "MORE").forEachIndexed { index, label ->
            val b = Button(this).apply {
                text = label
                setOnClickListener {
                    Toast.makeText(this@MainActivity,
                        if (index == 0) "Photo mode active" else label + " mode is not available yet",
                        Toast.LENGTH_SHORT).show()
                }
            }
            styleButton(b, if (index == 0) Color.rgb(7, 48, 46) else bg,
                if (index == 0) accent else muted, 10f)
            modes.addView(b, rowParams())
        }
        root.addView(modes)
        setContentView(root)
    }

    private fun showAspectRatioDialog() {
        val options = arrayOf("4:3 (recommended)", "16:9 (wide)", "1:1 (square)")
        val prefs = getSharedPreferences("geostamp_settings", MODE_PRIVATE)
        AlertDialog.Builder(this).setTitle("Aspect ratio")
            .setSingleChoiceItems(options, prefs.getInt("aspect_ratio", 0).coerceIn(0, 2)) { dialog, which ->
                prefs.edit().putInt("aspect_ratio", which).apply()
                dialog.dismiss()
                Toast.makeText(this,
                    "Ratio preference saved; actual output depends on camera hardware.",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                    statusText.text = "⌖  GPS: %.5f, %.5f".format(
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
            if (!requested) statusText.text = "⌖  Location services on karein"
            currentLocation?.let {
                statusText.text = "⌖  GPS: %.5f, %.5f".format(Locale.US, it.latitude, it.longitude)
            }
        } catch (_: Exception) {
            statusText.text = "⌖  Location available nahi hai"
        }
    }

    private fun showSettingsDialog() {
        val options = arrayOf("General • GPS", "Watermark", "Camera controls", "Advanced PRO")
        AlertDialog.Builder(this).setTitle("GeoStamp Camera PRO • Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this).setTitle("Location & GPS")
                        .setMultiChoiceItems(
                            arrayOf("Show date & time", "Show GPS coordinates", "Show place name"),
                            booleanArrayOf(showDateTime, showCoordinates, showPlaceName)
                        ) { _, index, checked ->
                            when (index) {
                                0 -> showDateTime = checked
                                1 -> showCoordinates = checked
                                2 -> showPlaceName = checked
                            }
                            getSharedPreferences("geostamp_settings", MODE_PRIVATE).edit()
                                .putBoolean("show_date_time", showDateTime)
                                .putBoolean("show_coordinates", showCoordinates)
                                .putBoolean("show_place_name", showPlaceName).apply()
                        }.setPositiveButton("Done", null).show()
                    1 -> AlertDialog.Builder(this).setTitle("Watermark")
                        .setItems(arrayOf("Position", "Text color and background")) { _, choice ->
                            if (choice == 0) showPositionDialog() else showWatermarkStyleDialog()
                        }.show()
                    2 -> AlertDialog.Builder(this).setTitle("Camera controls")
                        .setItems(arrayOf(
                            "Timer: " + timerLabel(), "Photo quality: " + qualityLabel(),
                            "Zoom", "Exposure", "Aspect ratio", "Grid lines"
                        )) { _, choice ->
                            when (choice) {
                                0 -> cycleTimer()
                                1 -> cycleQuality()
                                2 -> AlertDialog.Builder(this).setTitle("Zoom")
                                    .setItems(arrayOf("1.0×", "1.5×", "2.0×", "3.0×", "4.0×")) { _, i ->
                                        val values = floatArrayOf(1f, 1.5f, 2f, 3f, 4f)
                                        zoomRatio = values[i].coerceAtMost(maxZoomRatio).coerceAtLeast(1f)
                                        cameraControl?.setZoomRatio(zoomRatio)
                                        if (::zoomLabel.isInitialized) zoomLabel.text = "%.1f×".format(Locale.US, zoomRatio)
                                    }.show()
                                3 -> AlertDialog.Builder(this).setTitle("Exposure")
                                    .setItems(arrayOf("−2 EV", "−1 EV", "0 EV", "+1 EV", "+2 EV")) { _, i ->
                                        exposureIndex = intArrayOf(-2, -1, 0, 1, 2)[i]
                                            .coerceIn(minExposureIndex, maxExposureIndex)
                                        cameraControl?.setExposureCompensationIndex(exposureIndex)
                                    }.show()
                                4 -> showAspectRatioDialog()
                                5 -> {
                                    gridEnabled = !gridEnabled
                                    if (::gridOverlay.isInitialized) gridOverlay.visibility =
                                        if (gridEnabled) View.VISIBLE else View.GONE
                                }
                            }
                        }.show()
                    3 -> AlertDialog.Builder(this).setTitle("Advanced PRO")
                        .setItems(arrayOf(
                            "Resolution: " + if (ultraHdMode) "Ultra HD" else "Standard",
                            "Watermark template", "Custom field"
                        )) { _, choice ->
                            when (choice) {
                                0 -> {
                                    ultraHdMode = !ultraHdMode
                                    photoQuality = if (ultraHdMode) 100 else 94
                                    getSharedPreferences("geostamp_settings", MODE_PRIVATE).edit()
                                        .putBoolean("ultra_hd_mode", ultraHdMode)
                                        .putInt("photo_quality", photoQuality).apply()
                                    startCamera()
                                    Toast.makeText(this,
                                        "Resolution preference updated; hardware limits apply.",
                                        Toast.LENGTH_LONG).show()
                                }
                                1 -> AlertDialog.Builder(this).setTitle("Watermark template")
                                    .setItems(arrayOf("Professional", "Minimal", "Classic", "Map style")) { _, i ->
                                        Toast.makeText(this,
                                            "Template selected: " + arrayOf("Professional", "Minimal", "Classic", "Map style")[i],
                                            Toast.LENGTH_SHORT).show()
                                    }.show()
                                2 -> {
                                    val input = android.widget.EditText(this).apply {
                                        hint = "Project name or note"
                                        setText(getSharedPreferences("geostamp_settings", MODE_PRIVATE)
                                            .getString("custom_field", ""))
                                    }
                                    AlertDialog.Builder(this).setTitle("Custom field").setView(input)
                                        .setPositiveButton("Save") { _, _ ->
                                            getSharedPreferences("geostamp_settings", MODE_PRIVATE).edit()
                                                .putString("custom_field", input.text.toString()).apply()
                                            Toast.makeText(this, "Custom field saved", Toast.LENGTH_SHORT).show()
                                        }.setNegativeButton("Cancel", null).show()
                                }
                            }
                        }.show()
                }
            }.show()
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
