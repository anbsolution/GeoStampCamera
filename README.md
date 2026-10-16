# GeoStamp Camera

Beginner-friendly Android camera app built with Kotlin + CameraX.

## Features
- Camera preview, photo capture, front/back camera switch
- Date and time burned into the image
- GPS latitude/longitude watermark when location permission and a location fix are available
- Best-effort place name lookup
- Saves photos under `Pictures/GeoStampCamera`

## Build APK with GitHub Actions
1. Create a new **public** GitHub repository named `GeoStampCamera`.
2. Upload all files from this project, including the `.github` folder, to the repository's `main` branch.
3. Open **Actions** in the repository and select **Build GeoStamp Camera APK**.
4. If prompted, enable workflows. Push to `main` or click **Run workflow**.
5. Open the completed run and download `GeoStampCamera-debug-apk` under **Artifacts**.
6. Extract the downloaded ZIP on your phone and install `app-debug.apk`. Android may ask permission to install apps from that source.

## Important
- This is a **debug APK**, suitable for testing, not a signed release for Play Store publishing.
- GPS may be unavailable indoors or if location services are off. Grant camera and location permissions.
- Place-name lookup depends on Android's Geocoder service and may not return a name on every device.
- Test on your own device before relying on the location stamp.
