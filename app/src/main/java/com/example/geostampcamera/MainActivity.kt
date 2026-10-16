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
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.app.AlertDialog
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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
    private var flashEnabled = false
    private var lastSavedUri: Uri? = null
    private var showDateTime = true
    private var showCoordinates = true
    private var showPlaceName = true
    private var watermarkPosition = 0 // 0=bottom-left, 1=bottom-right, 2=top-left, 3=top-right

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
        buildUi()
        requestNeededPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = TextView(this).apply {
            text = "GEOSTAMP CAMERA"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 12)
        }
        root.addView(header)

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        statusText = TextView(this).apply {
            text = "Location dhoondh rahe hain..."
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(12, 10, 12, 10)
        }
        root.addView(statusText)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 14)
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
            isAllCaps = false
            setOnClickListener { action() }
        }
        firstRow.addView(makeButton("Flip") {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            flashEnabled = false
            cameraControl?.enableTorch(false)
            startCamera()
        })
        firstRow.addView(makeButton("Flash") {
            flashEnabled = !flashEnabled
            try {
                cameraControl?.enableTorch(flashEnabled)
                Toast.makeText(this, if (flashEnabled) "Flash on" else "Flash off", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                flashEnabled = false
                Toast.makeText(this, "Flash is camera par available nahi hai", Toast.LENGTH_SHORT).show()
            }
        })
        firstRow.addView(makeButton("Settings") { showSettingsDialog() })

        secondRow.addView(makeButton("Gallery") { openGallery() })
        captureButton = makeButton("PHOTO") { takePhoto() }.apply { textSize = 16f }
        secondRow.addView(captureButton)
        secondRow.addView(makeButton("Share") { shareLastPhoto() })
        controls.addView(firstRow)
        controls.addView(secondRow)
        root.addView(controls)
        setContentView(root)
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
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(cameraFacing).build()
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
                cameraControl = camera.cameraControl
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
            .setPositiveButton("Watermark position") { _, _ -> showPositionDialog() }
            .setNeutralButton("Done", null)
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
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val lineHeight = textSize * 1.35f
        val maxWidth = lines.maxOf { paint.measureText(it) }
        val boxHeight = lineHeight * lines.size + padding
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
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
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream))
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
        cameraExecutor.shutdown()
    }
}
