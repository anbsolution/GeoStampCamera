package com.example.geostampcamera

import android.Manifest
import android.content.ContentValues
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
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 18)
        }
        val switchButton = Button(this).apply {
            text = "Flip"
            setOnClickListener {
                cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                startCamera()
            }
        }
        captureButton = Button(this).apply {
            text = "PHOTO"
            textSize = 16f
            setOnClickListener { takePhoto() }
        }
        controls.addView(switchButton)
        controls.addView(captureButton)
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
                provider.bindToLifecycle(this, selector, preview, imageCapture)
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
        val dateText = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        val timeText = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
        val loc = currentLocation
        val coordinates = if (loc != null)
            "GPS: %.6f, %.6f".format(Locale.US, loc.latitude, loc.longitude)
        else "GPS: Location unavailable"

        val place = if (loc != null) getPlaceName(loc) else null
        val lines = mutableListOf(dateText, timeText, coordinates)
        if (!place.isNullOrBlank()) lines.add(place)

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
        val bottom = output.height - padding
        canvas.drawRoundRect(
            RectF(left / 2, bottom - boxHeight, left + maxWidth + padding, bottom + padding / 2),
            12f * scale.coerceAtLeast(0.65f), 12f * scale.coerceAtLeast(0.65f), box
        )
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, left, bottom - (lines.size - 1 - index) * lineHeight, paint)
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

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager?.removeUpdates(object : LocationListener {
            override fun onLocationChanged(location: Location) {}
        }) } catch (_: Exception) {}
        cameraExecutor.shutdown()
    }
}
