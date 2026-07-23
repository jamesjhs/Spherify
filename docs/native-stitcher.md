# Native Stitcher Backend

Spherify 0.4.1 keeps the Maven OpenCV AAR for Java-side capture validation, but that AAR does not link OpenCV's stitching/detail symbols. Production Spherify export therefore needs a separate native OpenCV Android SDK build that includes the stitching module.

The app build stays usable without this backend. In that default state, capture works and master export fails closed. To enable the backend, build or install an OpenCV Android SDK whose `OpenCVConfig.cmake` links `opencv_stitching`, then add these entries to `local.properties`. `spherify.opencvNativeDir` must point to the directory that directly contains `OpenCVConfig.cmake`.

```properties
spherify.nativeStitcher=true
spherify.opencvNativeDir=C:/path/to/OpenCV-android-sdk/sdk/native/jni
```

After that, run:

```powershell
.\gradlew.bat :app:assembleDebug
```

When the backend is enabled, the app builds only the ABIs present in the configured OpenCV SDK. The helper currently builds `arm64-v8a`, so Gradle filters native output to `arm64-v8a`. Build the OpenCV SDK again for each additional ABI before widening that filter.

The JNI bridge lives in `app/src/main/cpp`. It runs a controlled OpenCV detail pipeline: ORB features, BestOf2Nearest matching, largest-component filtering, homography estimation, ray bundle adjustment, wave correction, spherical warping, block gain exposure compensation, graph-cut seam finding, and multiband blending. Java still owns certification: Spherify validates exact 2:1 dimensions, minimum 3840 x 1920 resolution, file size under 75 MB, complete guided coverage, and GPano XMP readback before adding a master to the library.

If the native SDK links headers but not stitching symbols, the native build will fail at link time. That is intentional. Do not fall back to the retired Java renderer.
