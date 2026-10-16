# GeoStamp Camera

Android CameraX app that stamps photos with date/time and optional GPS coordinates/place name.

## Features in version 1.1
- Custom GeoStamp Camera launcher icon
- Back/front camera switch
- Flash toggle (back cameras that support torch)
- Watermark options: date/time, GPS coordinates and place name
- Choose watermark corner (bottom-left/right or top-left/right)
- Save stamped JPEG photos to `Pictures/GeoStampCamera`
- Open the photo picker and share the latest photo captured in the current app session
- Java/Kotlin JVM target aligned to Java 17 for GitHub Actions builds

## Build
GitHub Actions runs `gradle --no-daemon assembleDebug` on pushes to `main` and uploads `GeoStampCamera-debug-apk`. The artifact is a debug APK for testing, not a signed Play Store release.
