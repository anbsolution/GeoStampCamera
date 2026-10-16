# GeoStamp Camera

Android CameraX app that stamps photos with date/time and optional GPS coordinates/place name.

## Features in version 1.3 Pro Max (development build)
- Custom GeoStamp Camera launcher icon
- Back/front camera switch
- Flash toggle (back cameras that support torch)
- Watermark options: date/time, GPS coordinates and place name
- Choose watermark corner (bottom-left/right or top-left/right)
- Save stamped JPEG photos to `Pictures/GeoStampCamera`
- Open the photo picker and share the latest photo captured in the current app session
- Volume Up/Down buttons trigger the camera shutter
- Pinch-to-zoom gesture and Zoom +/- controls
- Tap-to-focus / metering on the preview
- Exposure compensation controls (EV +/-)
- Rule-of-thirds composition grid toggle
- Timer shutter: Off, 3 s, 5 s, or 10 s (saved between launches)
- JPEG quality presets: Standard (85%), High (94%), Max (100%)
- Watermark text color: white, yellow, or cyan
- Watermark background opacity: subtle, medium, or dark
- Java/Kotlin JVM target aligned to Java 17 for GitHub Actions builds

This is an incremental development build (version 1.3). Camera capabilities such as zoom, exposure and flash depend on the device hardware. Run a GitHub Actions debug build before installing; this is not a Play Store release.

## Build
GitHub Actions runs `gradle --no-daemon assembleDebug` on pushes to `main` and uploads `GeoStampCamera-debug-apk`. The artifact is a debug APK for testing, not a signed Play Store release.


## Version 1.4 Ultra HD
- Added an Ultra HD quality mode to the Quality control.
- Ultra HD requests a compatible resolution near 3840×2160 and uses CameraX quality-priority capture with JPEG quality 100.
- Actual output resolution depends on the phone camera and supported CameraX sizes; not every device can produce true 4K still photos.
- Higher resolution uses more memory and storage.


## Version 1.5 — UI/UX refresh
- Refreshed dark camera interface with clearer visual hierarchy and accent shutter button.
- Improved spacing, rounded control surfaces, and readable GPS status panel.
- Moved advanced controls into a horizontally scrollable row to avoid squeezing small screens.
- Kept existing camera, zoom, tap-to-focus, exposure, grid, timer, watermark, gallery, share, and quality flows.
