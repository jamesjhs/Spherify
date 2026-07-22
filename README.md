# Spherify

Version: 0.4.33

Spherify is an Android Play Store app concept for creating 360-degree PhotoSphere and Tiny Planet images from a phone camera, device motion sensors, and location services, then saving them locally and optionally publishing them to Google Maps or Google Photos.

This repository now contains the first Android proof-of-concept application code. The 0.4.33 build includes a GPU-backed PhotoSphere/Tiny Planet viewer, local import, app-owned library storage, saved variants, thumbnails, metadata, basic library management, setup/readiness flow, adjustment controls, separated camera-distance and zoom/focal-length controls, refined Tiny Planet camera yaw, safer viewport pitch limits, ARCore-required capture gating, ARCore GL camera preview, ARCore session camera-frame capture, ARCore pose-driven guidance, corrected capture preview orientation, upright draft-frame saves, in-app flat draft-frame viewing, separate compass-calibration and horizon-reference sweeps, start/end landmark alignment for horizon sweeps, sweep-first photosphere painting with simplified controls, pitch-only one-shot polar capture, polar Capture/Next/Spherify button flow, polar-only guide overlay, always-on vertical alignment line, wider capture-progress overlay button, spherical-width preview rows, pitch-aware compass direction, pole-layer colour infill, first-class draft-session library records, structured draft exposure metadata with non-blocking capture fallback, safer delete confirmations, rotation-state restore, Android launcher badge assets, default auto-paint capture, tap-to-recapture layers, draft-frame deletion, and confirmed bulk draft removal.

## Developer Build and Run Runbook

This section is intentionally basic and explicit. It describes how to build, install, and run the current Android project from VS Code and terminal commands.

Current status:

- The repository has a Gradle wrapper, Android app module, launcher activity, bundled test panorama, and debug build.
- The current application ID is `com.spherify.app`.
- The current debug build command is `.\gradlew.bat :app:assembleDebug` on Windows or `./gradlew :app:assembleDebug` on macOS/Linux.
- The current prototype is local-first, does not require broad photo-library permission for normal use, and now includes a portrait capture shell with sensor readiness, compass calibration, ARCore draft frame capture, an iterated Phase 4 guided capture flow that evolved from target dots into sweep-first paint layers, auto-capture for new sweep slices, first-class draft-session library records, structured exposure metadata for draft frames, draft-frame deletion, and a confirmed Remove All Drafts action for large draft sets.

### Version 0.4.33 Progress

This bugfix build separates compass readiness from capture reference imagery:

- Removes the ARCore-targeting sentence from the Capture intro popup.
- Adds a dedicated 360-degree horizon compass calibration sweep before the actual capture-reference horizon sweep can begin.
- Widens the shared Capture/Start/Next/Spherify overlay button so longer labels fit without wrapping.

### Version 0.4.32 Progress

This bugfix build protects the ARCore camera preview while keeping Phase 5 capture references:

- Makes ARCore exposure metadata reads non-blocking so missing/device-specific metadata cannot drop the camera preview to a black frame.
- Keeps draft-frame exposure references structured, with unavailable metadata recorded explicitly instead of as a prose placeholder.
- Promotes capture drafts into first-class draft-session library records so Phase 5 can select a coherent input set.

### Version 0.4.31 Progress

This build simplifies the final polar capture controls:

- Changes polar layers to use a single `Capture` button press instead of Start/Stop.
- Shows `Next` after the upper polar photograph and `Spherify!` after the lower polar photograph.
- Removes the separate `Finish` button from Capture.
- Moves `Cancel` into the secondary controls where `Finish` used to be.

### Version 0.4.30 Progress

This build packages the current polar-capture cleanup for preview testing:

- Carries forward pitch-only one-shot polar capture.
- Carries forward the always-visible vertical alignment line.
- Carries forward tap-to-recapture without drag-to-realign.

### Version 0.4.29 Progress

This build removes the remaining unreliable roll dependency from polar capture:

- Captures each pole from pitch alignment only, assuming the user has faced the origin as instructed.
- Removes the polar center dot and roll arrows from the pole guide.
- Keeps a vertical alignment line visible at all times in Capture.
- Disables drag-to-realign for painted panoramas; tapping a painted row can still prompt re-capture.

### Version 0.4.28 Progress

This build simplifies polar capture for reliability:

- Changes each polar layer to capture one vertical center frame instead of left/middle/right roll frames.
- Removes painted preview imagery during polar capture so the cylindrical row preview no longer misrepresents pole motion.
- Draws a polar-only overlay: the `80 deg` target line, left/right correction arrows, and a central alignment dot.
- Uses the adjacent `+30/-30` sweeps as overlap support for the single pole cap.
- Notes the apparent sensor stoppage near vertical is the yaw/roll singularity around the optical axis, not Android screen orientation taking over.

### Version 0.4.27 Progress

This build makes upper/lower polar capture independent of unstable polar yaw:

- Projects the original horizon start direction into the current ARCore camera screen plane for polar roll detection.
- Treats the upper pole origin as the bottom screen edge and the lower pole origin as the top screen edge.
- Avoids using compass/AR yaw to decide polar left/middle/right slots, preventing 180-degree behind-the-user flips near vertical.
- Confirms Android screen orientation is locked to portrait and Android rotation-vector/compass paths are bypassed while ARCore is running; the observed failure mode is yaw singularity near the pole, not competing screen-orientation sensors.

### Version 0.4.26 Progress

This build refreshes the compass draw frame:

- Keeps the normal yaw draw direction while the phone is vertical or pitched downward.
- Mirrors the compass only after the phone is pitched more than 30 degrees above the horizon, matching the user's upward-looking screen interpretation.

### Version 0.4.25 Progress

This build corrects capture orientation and improves the painted preview geometry:

- Flips the compass needle vertically at all times so north points toward magnetic north.
- Restores a stable vertical projection so `+30` capture rows appear above the horizon and `-30` rows appear below it.
- Keeps polar pitch fixed after `Start`, using the reported pitch-plane drift as the left/middle/right polar roll input near vertical.
- Records polar draft metadata with the intended fixed pitch and polar slot rather than noisy vertical-pose pitch drift.
- Narrows preview rows by latitude as a spherical approximation; a true future upgrade would draw the painted preview as a textured sphere or equirectangular remap instead of flat row bands.

### Version 0.4.24 Progress

This build corrects polar orientation and below-horizon projection:

- Treats the user's polar `Start` posture as the middle roll reference instead of using absolute roll.
- Mirrors down-looking polar left/right handedness so the origin point is treated as the top edge when looking down.
- Updates the polar information popup to explain that the origin is bottom-screen when looking up and top-screen when looking down.
- Reflects the compass and vertical guide projection whenever the camera is below the horizon.

### Version 0.4.23 Progress

This build makes top and bottom polar capture a special case:

- Changes uppermost and lowermost capture layers to near-vertical `+80/-80` targets.
- Shows a polar-capture popup explaining that yaw is unreliable at the poles.
- Captures polar layers from three roll positions: left, middle, and right.
- Keeps polar completion independent of yaw sweep speed or ARCore yaw bins.

### Version 0.4.22 Progress

This build fixes draft frame orientation and keeps draft review inside Spherify:

- Rotates landscape ARCore draft JPEGs clockwise before saving so new captured frames open upright.
- Opens draft-frame rows in the in-app flat viewer instead of sending them to an external Android image app.

### Version 0.4.21 Progress

This build improves painted preview alignment and drag clarity:

- Centers the painted preview on the Start-relative yaw bin as soon as the first horizon sample is available.
- Uses the composite painted photosphere preview for immediate horizon updates instead of a one-row strip path.
- Makes the painted overlay more opaque while dragging.
- Zooms the preview window while dragging to make alignment easier.
- Draws a vertical center line during drag alignment.

### Version 0.4.20 Progress

This build simplifies Capture controls and moves recapture into the painted preview:

- Removes visible `Auto Paint`, `Pause`, and `Capture Block` buttons.
- Keeps auto-paint as the default behaviour during each Start/Stop layer sweep.
- Keeps drag-to-align for preview recovery, but removes individual block editing from the main controls.
- Lets the user tap a painted layer row and choose `Re-capture` to clear and sweep that layer again.

### Version 0.4.19 Progress

This build adds recovery tools for AR orientation blips:

- Allows horizontal dragging of the painted preview to realign it with the real-world view.
- Applies the drag as a yaw-origin reset using the latest/current AR pose as valid.
- Lets `Capture Block` manually fill or replace the current upper/lower layer block after preview realignment.
- Stores manual recovery captures as `manual-block` metadata.
- Keeps auto-paint restricted to active Start/Stop sweeps while allowing manual block repair outside the sweep window.

### Version 0.4.18 Progress

This build aligns preview rows around the shared sweep start point and improves vertical overlap:

- Remaps the horizon preview row into the same Start-origin yaw coordinate system as later layers.
- Centers preview windows using the active Start-origin yaw bin so prior start points line up in the middle while preparing the next layer.
- Increases painted preview row height to better reflect real camera vertical overlap between horizon and upper/lower layers.
- Keeps the current five-layer capture plan for now; the visible gap was addressed as a preview projection issue rather than adding another layer.

### Version 0.4.17 Progress

This build improves sweep preview persistence and adds optional pole infill:

- Adds a `Skip Poles` control to fill high upper/high lower layers from average captured colours.
- Uses the top third of the upper layer and bottom third of the lower layer for pole infill colour.
- Maintains a composite painted-photosphere preview instead of showing only the active layer.
- Draws captured preview rows at their actual pitch lines so previews align with the layer they came from.
- Keeps previously captured preview imagery visible as the user moves through later layers.

### Version 0.4.16 Progress

This build trusts the user's layer Start/Stop actions and adds non-horizon image previews:

- Treats each layer `Start` press as yaw zero for that layer.
- Treats each layer `Stop` press as the 360-degree closure, without rejecting the user for AR yaw drift.
- Records sweep draft metadata with layer-relative yaw instead of raw AR yaw.
- Adds live captured-image preview strips for upper/lower layers, not only the horizon reference.
- Keeps the active preview window aligned to the layer-relative yaw slice.

### Version 0.4.15 Progress

This build makes upper/lower sweep painting deliberate and visible:

- Adds `Start` and `Stop` states for each upper/lower paint layer.
- Requires each layer to start and stop at the original real-world horizon landmark.
- Shows a horizontal target line at the required camera pitch for the active layer.
- Shows a live 24-slice paint strip so users can see coverage filling as they pan.
- Gates auto/manual painting so slices are saved only between `Start` and `Stop`.

### Version 0.4.14 Progress

This build fixes the transition from the initial horizon sweep into upper/lower layer painting:

- Treats the initial 360-degree horizon reference sweep as the painted horizon layer.
- Clears the return-to-start state after the reference sweep is closed.
- Prompts the user to tap `Next` for the upper layer instead of requiring a second horizon pass.

### Version 0.4.13 Progress

This build shifts Capture from stop-start guide targets to sweep-first photosphere painting:

- Hides the 24 visible point-target capture guides from the main overlay.
- Tracks capture as five horizontal paint layers: horizon, upper, lower, high upper, and high lower.
- Auto-paints unfilled yaw slices in the active layer as the user rotates slowly.
- Replaces target-lock capture gating with pitch-layer alignment, yaw-bin coverage, and sweep-speed quality checks.
- Changes Capture controls and session summaries from target language to painted sweep slices.

### Version 0.4.12 Progress

This build changes the horizon guide from a full 360-degree strip into a current-view reference:

- Draws only the yaw-centered portion of the horizon panorama over the live preview.
- Handles 0/360-degree wraparound so the guide remains continuous when looking back through the start direction.
- Establishes continuous horizontal photo sweeps as the preferred capture direction for future work, with point targets retained as a fallback/debug guide until sweep coverage and stitching are fully reliable.

### Version 0.4.11 Progress

This build refines strip-mode capture feedback and the horizon reference preview:

- Shows live sweep pace guidance so the user is told when they are moving too fast or too slow.
- Adds top and bottom red fade warnings when pitch drifts away from the starting horizon plane.
- Samples central vertical strips from portrait ARCore frames instead of squeezing full photos into each yaw bin.
- Rebuilds the live horizon strip as a single panorama preview and feathers neighboring joins.

### Version 0.4.10 Progress

This build refines the ARCore horizon sweep flow:

- Changes the initial horizon-sweep dialog action to `OK` so the user first sees the live camera crosshair.
- Uses a live `Start` button to lock the starting landmark, then changes it to `End` when the sweep is ready to close.
- Requires `End` to be pressed after re-aligning with the original landmark before capture unlocks.
- Draws portrait horizon-reference samples side-by-side horizontally instead of stretching them into a rotated band.
- Normalizes landscape ARCore CPU reference frames into portrait thumbnails before displaying them.

### Version 0.4.9 Progress

This build improves ARCore capture setup:

- Rotates the ARCore camera texture mapping to correct the 90-degree preview orientation.
- Lets the user point the crosshair at a fixed distant start landmark before beginning the horizon sweep.
- Requires the user to return the crosshair to that same start landmark after the full sweep before capture unlocks.
- Adds clearer sweep status text for start alignment, horizon sweep, and return-to-start phases.

### Version 0.4.8 Progress

This build fixes the blank ARCore capture preview:

- Replaces the temporary ARCore CPU-frame `ImageView` preview with a `GLSurfaceView` camera renderer.
- Creates an external OES camera texture and passes it to ARCore with `Session.setCameraTextureNames()`.
- Runs `Session.update()` from the GL render loop so ARCore has an attached camera background target.
- Keeps CPU camera images only for draft JPEG capture and horizon-reference thumbnails.

### Version 0.4.7 Progress

This build moves capture onto the ARCore session path:

- Replaces the CaptureActivity CameraX preview/capture source with ARCore session CPU camera frames.
- Creates the ARCore session with `Session.Feature.SHARED_CAMERA`.
- Drives capture yaw, pitch, roll, target guidance, and draft metadata from `Frame.getCamera().getPose()`.
- Writes draft JPEGs from the latest ARCore camera frame instead of CameraX `ImageCapture`.
- Keeps the 360-degree horizon reference sweep tied to the same ARCore frame/pose stream.

### Version 0.4.6 Progress

This build makes ARCore the required capture target and adds the first horizon-reference pass:

- Declares capture support as ARCore-required and adds the ARCore Android SDK.
- Blocks unsupported or declined ARCore setups with a clear message instead of falling back silently.
- Replaces the old compass calibration entry point with a 360-degree horizon sweep.
- Samples low-resolution preview frames around the horizon and draws them back as a 50% opacity reference belt.
- Keeps capture locked until enough horizon yaw sectors have been sampled near the horizon plane.

### Version 0.4.5 Progress

This build tightens viewport manipulation:

- Makes Tiny Planet single-finger horizontal dragging adjust camera yaw instead of also spinning roll.
- Clamps viewer pitch in both Photo Sphere and Tiny Planet modes so dragging cannot flip the projected geometry through a pole.
- Clamps restored pitch state from older sessions before rendering.

### Version 0.4.4 Progress

This build separates viewer distance from zoom:

- Changes the former Field of view adjustment into Camera distance.
- Keeps pinch zoom as the focal-length-like magnification control.
- Applies camera distance to both GPU preview and exported Photo Sphere/Tiny Planet variants.

### Version 0.4.3 Progress

This build improves Photo Sphere and Tiny Planet touch interaction:

- Maps two-finger vertical dragging to Horizon reference adjustment.
- Maps two-finger horizontal dragging to Field of view adjustment.
- Keeps pinch zoom and two-finger rotation available alongside the new drag adjustments.

### Version 0.4.2 Progress

This build improves large draft-session cleanup:

- Adds `Remove All Drafts` to the Draft Frames browser.
- Requires a second confirmation dialog before bulk removal.
- Deletes all draft JPEGs from app storage and resets `drafts.json` to an empty array.
- Keeps single draft-frame swipe deletion available for targeted cleanup.

### Version 0.2.8 Progress

This build improves the Phase 4 guided capture prototype:

- Enables auto-capture by default when the camera is aligned with the active target dot.
- Shows a one-second shrinking pie countdown over the active capture dot while target lock is held.
- Continues the countdown without requiring extra device movement, so a steady phone can trigger capture.
- Dampens the guide grid with additional yaw/pitch smoothing to reduce noisy dot movement.
- Lets Draft Frames use the same left-swipe confirmation deletion pattern as library images.
- Removes deleted draft JPEGs and their matching `drafts.json` metadata rows.

### Version 0.2.7 Progress

This build makes the first inroads into Phase 4 guided PhotoSphere capture:

- Adds a live yaw/pitch target grid over the CameraX preview.
- Adds a center reticle with alignment and steady-hold feedback.
- Adds target coverage progress so missing/weak coverage is visible before stitching exists.
- Gates manual capture on compass calibration, target alignment, and short stability dwell.
- Adds prototype auto-capture that reuses the same alignment and stability checks when enabled.
- Adds pause/resume, undo, cancel, and finish controls for guided capture sessions.
- Persists a recoverable draft session id across interruptions until the user finishes or cancels.
- Records per-frame session id, approximate heading, pitch, roll, target yaw/pitch, timestamp, location summary, and a placeholder exposure field in `drafts.json`.

### Version 0.2.6 Progress

This bugfix build tightens the capture-mode status experience:

- Simplifies the capture status bar to only show `Calibration needed`, `Capture ready`, or `Image captured`.
- Keeps detailed calibration data out of the status bar and in the graphical calibration panel or optional sensor overlay.
- Preserves `Image captured` after a successful frame instead of immediately overwriting it with live sensor updates.

### Version 0.2.5 Progress

This bugfix build advances Phase 3 capture readiness and app packaging:

- Uses `images/icon.png` as the Android app badge and launcher icon, including adaptive and density-specific launcher resources.
- Locks image capture mode to portrait orientation.
- Keeps a north pointer visible on the live camera preview.
- Requires a deliberate compass calibration step before draft frame capture can unlock.
- Explains compass calibration in simple language through a closeable popup.
- Dampens noisy compass calibration data with low-pass sensor filtering and circular heading smoothing.
- Adds a graphical calibration progress panel for time, accuracy, heading steadiness, rotation coverage, and tilt coverage.
- Keeps the sensor diagnostics overlay available through a toggle on the live capture image.

### Export APK for Previewers

Use this when you want to commit or share a downloadable APK from the repository for app previewers.

1. Build the debug APK from the repository root.

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:assembleDebug
```

2. Copy the generated APK into the repository preview folder with the current version and a short one- or two-word edit description in the filename. Use `spherify-{version}-{description}.apk`, not a generic `-debug` suffix.

Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force preview-apks
Copy-Item app\build\outputs\apk\debug\app-debug.apk preview-apks\spherify-{version}-{description}.apk
```

macOS/Linux:

```bash
mkdir -p preview-apks
cp app/build/outputs/apk/debug/app-debug.apk preview-apks/spherify-{version}-{description}.apk
```

3. Commit the copied APK only when you intentionally want previewers to download that exact build from the repository.

Previewers can install the APK on an Android device by downloading the latest descriptive APK in `preview-apks/`, such as `preview-apks/spherify-{version}-{description}.apk`, enabling installation from their browser or file manager if prompted, and opening the file on the device. Developers can also install it over USB with:

```powershell
adb install -r preview-apks\spherify-{version}-{description}.apk
```

The repository contains:

- `settings.gradle` or `settings.gradle.kts`.
- A root `build.gradle` or `build.gradle.kts`.
- A Gradle wrapper: `gradlew`, `gradlew.bat`, and the `gradle/wrapper/` directory.
- An Android app module, usually named `app/`.
- An Android manifest at a path like `app/src/main/AndroidManifest.xml`.
- A debug build variant, usually available as `app:assembleDebug`.
- A runnable launcher activity.

The goal of this runbook is to install and run the developer/debug version of Spherify from VS Code in two places:

- A physically linked Android phone or tablet connected by USB.
- An Android Emulator virtual device, which acts as a sandboxed developer device.

### Required Tools

Install these before attempting either run path.

1. Install Visual Studio Code.
2. Install Git.
3. Install Java/JDK at the version required by the Android Gradle Plugin chosen by the project.
4. Install the Android SDK.
5. Install Android SDK Platform-Tools.
6. Install Android SDK Build-Tools.
7. Install the Android SDK Platform for the target API level chosen by the project.
8. Install the Android Emulator package if emulator testing is needed.
9. Install Android command-line tools so `sdkmanager`, `avdmanager`, `adb`, and `emulator` are available.
10. Keep Android Studio installed if desired as the provider of the Android SDK and emulator backend, but do not use Android Studio as the IDE for normal Spherify development.
11. On Windows, install the Google USB Driver if using a Google Pixel or another compatible device.
12. On Windows, install the device manufacturer's USB driver if using a non-Google device that needs one.
13. Make sure there is enough free disk space for VS Code, Android SDK packages, Gradle caches, emulator images, build outputs, and app data.
14. Use a reliable USB data cable for physical-device testing. Some cables charge only and cannot transfer data.

Useful VS Code areas:

- Explorer: browse and edit project files.
- Source Control: inspect Git changes.
- Terminal: run Gradle, ADB, emulator, and SDK commands.
- Problems: inspect compiler, Kotlin, XML, and lint issues reported by extensions or tasks.
- Extensions: install Kotlin, Gradle, XML, Android, and debugger helpers if they are useful for the project.

Recommended VS Code extensions once app code exists:

- Kotlin language support.
- Gradle task support.
- XML support.
- Android resource/file helpers if preferred.
- A Java debugger if JVM debugging is needed.

Android Studio may be installed on the same machine, but the intended development rule is simple: edit, build, run, and inspect from VS Code and terminal commands. Only use Android Studio for rare SDK or emulator administration if the command-line tools are broken or missing.

Useful command-line tools:

- `adb`: Android Debug Bridge. It lists devices, installs APKs, opens shells, reads logs, and starts activities.
- `gradlew.bat`: the Windows Gradle wrapper script committed with the project.
- `./gradlew`: the macOS/Linux Gradle wrapper script committed with the project.
- `emulator`: the Android Emulator command-line launcher.
- `sdkmanager`: installs Android SDK packages from the command line.
- `avdmanager`: creates and manages Android Virtual Devices from the command line.
- `java`: runs Java tooling used by Gradle and Android builds.

On Windows PowerShell, run project commands from the repository root:

```powershell
cd C:\GitHub\Spherify
```

On macOS or Linux, run project commands from wherever the repository was cloned:

```bash
cd /path/to/Spherify
```

### First Repository Check

Do this before trying to run on either a real device or an emulator.

1. Open a terminal.
2. Change into the repository root.
3. Check that the repository has app code.

Windows PowerShell:

```powershell
Get-ChildItem
```

macOS/Linux:

```bash
ls
```

4. Look for `gradlew.bat` on Windows or `gradlew` on macOS/Linux.
5. Look for `settings.gradle` or `settings.gradle.kts`.
6. Look for an app module directory, usually `app`.
7. If those files are missing, stop and restore the Android project scaffold before building.
8. If those files exist, continue.

### Open the Project in VS Code

1. Start VS Code.
2. Choose `File > Open Folder`.
3. Browse to the local repository folder.
4. Select the repository root, not the `app/` directory.
5. Trust the workspace only if this is your local checkout of Spherify.
6. Open the VS Code terminal with ``Ctrl+` ``.
7. Confirm the terminal is at the repository root.
8. If it is not, change into the repository root.
9. Check the Java installation.

```bash
java -version
```

10. Check that ADB is available.

```bash
adb version
```

11. Check that the emulator command is available if emulator testing will be used.

```bash
emulator -version
```

12. Check that the Gradle wrapper exists once app code has been scaffolded.

Windows PowerShell:

```powershell
Test-Path .\gradlew.bat
```

macOS/Linux:

```bash
test -f ./gradlew && echo "gradlew found"
```

13. If `adb`, `emulator`, or Java are not found, fix the system `PATH` or Android SDK/JDK installation before continuing.
14. If `gradlew` or `gradlew.bat` is missing, stop. The Android project scaffold has not been created yet.
15. Use VS Code Explorer to edit files.
16. Use VS Code Source Control to review changes before committing.
17. Use the VS Code terminal for all build, install, run, reset, and log commands.

### Build the Debug App

This checks that the developer build can compile before attempting installation.

VS Code terminal path:

1. Open the repository in VS Code.
2. Open the integrated terminal.
3. Confirm the terminal is at the repository root.
4. Run the debug build command.

Command-line path on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

Command-line path on macOS/Linux:

```bash
./gradlew :app:assembleDebug
```

Expected successful result:

- Gradle ends with `BUILD SUCCESSFUL`.
- A debug APK exists under a path similar to `app/build/outputs/apk/debug/app-debug.apk`.

If the build fails:

1. Read the first real error, not only the final `BUILD FAILED` line.
2. If the error says an SDK is missing, install that SDK with `sdkmanager` or the installed Android SDK tooling.
3. If the error says Java is wrong, use the JDK version required by the Android Gradle Plugin.
4. If the error says a dependency cannot be resolved, check the internet connection and Gradle repositories.
5. If the error says a file is missing, confirm the file exists in Git and was not ignored.
6. Re-run the build after fixing the first error.

Example SDK install command:

```bash
sdkmanager "platform-tools" "build-tools;35.0.0" "platforms;android-35"
```

The exact API level and build-tools version should match the future project configuration.

### Run on a Physically Linked Android Device

This path installs and runs the debug version of Spherify on a real phone or tablet connected to the development computer.

Use this path when testing:

- Real camera behavior.
- Real gyroscope, accelerometer, compass, and rotation-vector behavior.
- Real location behavior.
- Real storage and MediaStore behavior.
- Real performance and thermal behavior.
- Real permission prompts.

The emulator is useful, but it cannot fully prove Spherify's capture experience because PhotoSphere capture depends heavily on physical camera and motion sensors.

#### Prepare the Android Device

1. Turn on the Android device.
2. Unlock the device.
3. Open the device's Settings app.
4. Scroll to `About phone` or `About tablet`.
5. Find `Build number`.
6. Tap `Build number` seven times.
7. Enter the device PIN, password, or pattern if Android asks.
8. Confirm that Android says developer options are enabled.
9. Go back to the main Settings screen.
10. Open `System`.
11. Open `Developer options`. The exact location varies by manufacturer.
12. Turn on `USB debugging`.
13. If the device has a `Default USB configuration` setting, set it to file transfer or data transfer if available.
14. Leave the device unlocked for the first connection.

#### Connect the Device by USB

1. Plug the USB cable into the computer.
2. Plug the USB cable into the Android device.
3. If the phone asks what to do with the USB connection, choose file transfer, data transfer, or the closest equivalent.
4. Watch the phone screen for an `Allow USB debugging?` prompt.
5. Check the computer's RSA fingerprint if you want to be careful.
6. Select `Always allow from this computer` if this is your own development machine.
7. Tap `Allow`.
8. Keep the device unlocked until the computer recognizes it.

#### Confirm That ADB Sees the Device

Run this from a terminal:

Windows PowerShell:

```powershell
adb devices
```

macOS/Linux:

```bash
adb devices
```

Expected result:

- A line appears with a device serial number.
- The state says `device`.

Common results and what they mean:

- `unauthorized`: unlock the phone and accept the USB debugging prompt.
- `offline`: unplug the cable, plug it back in, and re-run `adb devices`.
- No device listed: try a different USB cable, USB port, or driver.
- Multiple devices listed: disconnect extra devices or pass an explicit device serial to `adb`.

On Windows, if no device appears:

1. Install `Google USB Driver` if using a compatible Google device.
2. Install the manufacturer's USB driver if the device is not covered by the Google USB Driver.
3. Unplug and reconnect the phone.
4. Re-run `adb devices`.

#### Run on the Physical Device from VS Code

1. Build the debug APK.

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:assembleDebug
```

2. Confirm that the device is visible.

```bash
adb devices
```

3. Install the debug APK.

Windows PowerShell:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS/Linux:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

4. If more than one device or emulator is connected, copy the target serial from `adb devices`.
5. Install to that exact serial.

Windows PowerShell:

```powershell
adb -s DEVICE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS/Linux:

```bash
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

6. Start the app from the launcher on the phone, or start it with an `adb shell am start` command after the package name and launch activity are known.

The package name is expected to be something like `com.spherify.app`, but the actual package name must come from the future Android manifest or Gradle namespace.

Example only:

```bash
adb shell monkey -p com.spherify.app 1
```

7. Read device logs while testing.

```bash
adb logcat
```

8. Stop logging with `Ctrl+C`.
9. Keep VS Code open for edits.
10. Repeat the edit, build, install, launch, log loop as needed.

Optional filtered logs after the final package name is known:

```bash
adb logcat --pid=$(adb shell pidof -s com.spherify.app)
```

On Windows PowerShell, use the simpler unfiltered `adb logcat` first unless a project script provides a reliable filtered command.

#### Reset the App on the Physical Device

Use this when the app has bad local state, stale permissions, or old test data.

1. Find the package name from the manifest or Gradle namespace.
2. Clear the app data.

Example only:

```bash
adb shell pm clear com.spherify.app
```

3. Reopen the app.
4. Repeat the first-run and permission flow.

To uninstall the debug app:

```bash
adb uninstall com.spherify.app
```

### Run in an Android Emulator Sandbox from VS Code

This path installs and runs the debug version of Spherify inside an Android Virtual Device.

Use this path when testing:

- Basic app launch.
- Navigation.
- Layout and Compose rendering.
- PhotoSphere viewer rendering.
- Tiny Planet reprojection controls.
- Import/export flows that do not require real camera behavior.
- Permission copy and denial paths.
- Different screen sizes, densities, orientations, and Android versions.
- Clean app state without touching a real phone.

Do not rely on the emulator alone for final capture validation. Camera, compass, gyroscope, location, GPU behavior, and storage behavior can differ from real hardware.

#### Create an Android Virtual Device

1. Open VS Code.
2. Open the repository folder.
3. Open the integrated terminal.
4. List installed SDK packages.

```bash
sdkmanager --list_installed
```

5. If no suitable emulator system image is installed, install one.

Example:

```bash
sdkmanager "system-images;android-35;google_apis;x86_64"
```

6. List available device profiles.

```bash
avdmanager list device
```

7. Create an AVD with a clear Spherify name.

Example:

```bash
avdmanager create avd -n Spherify_API_35_Pixel -k "system-images;android-35;google_apis;x86_64" -d pixel_7
```

8. If `avdmanager` asks whether to create a custom hardware profile, answer `no` unless the project needs special hardware settings.
9. Confirm the AVD exists.

```bash
emulator -list-avds
```

10. Prefer a recent stable Android API level that matches the project's compile and target plans.
11. Use a Google APIs or Google Play image if Google services are needed.
12. Use a plain AOSP image if Google services are not needed for the test.
13. Use hardware graphics acceleration when available.
14. Keep the AVD name stable so scripts and notes can refer to it.

#### Start the Emulator

1. Open VS Code.
2. Open the integrated terminal.
3. List available emulator devices.

```bash
emulator -list-avds
```

4. Start the chosen emulator.

```bash
emulator -avd Spherify_API_35_Pixel
```

5. Leave the terminal open while the emulator runs.
6. Open a second VS Code terminal.
7. Confirm that ADB sees the emulator.

```bash
adb devices
```

Expected result:

- A device appears with a name like `emulator-5554`.
- The state says `device`.

If the emulator is slow or fails to boot:

1. Confirm that the computer meets Android Emulator system requirements.
2. Enable hardware virtualization in BIOS or firmware if needed.
3. Use a smaller device profile.
4. Use a lower-resolution emulator.
5. Close other memory-heavy applications.
6. Wipe the emulator data if the virtual device state is corrupted.
7. Create a new AVD if wiping data does not help.

#### Run on the Emulator from VS Code

1. Start the emulator.
2. Confirm ADB sees it.

```bash
adb devices
```

3. Build the debug APK.

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:assembleDebug
```

4. Install the debug APK.

Windows PowerShell:

```powershell
adb -e install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS/Linux:

```bash
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
```

The `-e` flag targets the emulator. Use it when a physical device is also connected.

5. Start the app from the emulator launcher, or use an `adb shell monkey` command after the package name is known.

Example only:

```bash
adb -e shell monkey -p com.spherify.app 1
```

6. Read emulator logs.

```bash
adb -e logcat
```

7. Stop logging with `Ctrl+C`.
8. Keep VS Code open for edits.
9. Repeat the edit, build, install, launch, log loop as needed.

Useful emulator commands:

```bash
adb -e emu kill
```

```bash
emulator -avd Spherify_API_35_Pixel -wipe-data
```

The first command closes the running emulator. The second starts the AVD with its data wiped.

#### Reset the Emulator Sandbox

Use this when you want a clean developer app environment.

To clear only Spherify's app data:

```bash
adb -e shell pm clear com.spherify.app
```

To uninstall the debug app:

```bash
adb -e uninstall com.spherify.app
```

To wipe the whole emulator:

1. Close the emulator.
2. Start the emulator with `-wipe-data`.

```bash
emulator -avd Spherify_API_35_Pixel -wipe-data
```

3. Wait for the emulator to boot.
4. Reinstall the app.

Wiping the emulator deletes its apps, local files, settings, and test state. It does not delete the source code repository.

### Permission Checks During Developer Runs

Spherify is expected to request permissions contextually, not all at startup. During testing, check each permission at the moment the app needs it.

Physical device checks:

1. Start the app fresh.
2. Confirm it does not immediately request every sensitive permission.
3. Start a capture flow.
4. Confirm camera permission appears only when capture needs it.
5. Grant camera permission.
6. Confirm the camera preview opens.
7. Enable map-ready location tagging.
8. Confirm location permission appears at that moment.
9. Grant location permission.
10. Confirm location status appears in the app only where relevant.
11. Deny a permission on a second run.
12. Confirm the app explains the reduced feature without crashing.

Emulator checks:

1. Start from a clean emulator or clear app data.
2. Run the app.
3. Test first launch.
4. Test permission denial.
5. Test permission grant.
6. Use emulator location controls if location behavior is being tested.
7. Use emulator camera controls only for basic camera plumbing. Do not treat emulator camera behavior as proof of real PhotoSphere capture quality.

### Developer Run Checklist

Before each test run:

1. Pull or fetch the latest intended code.
2. Confirm the working tree changes are understood.
3. Open the repository in VS Code.
4. Open the VS Code integrated terminal at the repository root.
5. Run a debug build.
6. Choose exactly one target device unless deliberately testing multiple devices.
7. Confirm `adb devices` shows the target.
8. Install and run the debug app.
9. Watch `adb logcat`.
10. Record the device model, Android version, and app commit when reporting a bug.

For physical-device capture testing, also record:

- Device manufacturer and model.
- Android version.
- Camera lens used.
- Whether gyroscope is present.
- Whether accelerometer is present.
- Whether magnetometer/compass is present.
- Whether rotation vector is present.
- Whether location was enabled.
- Lighting conditions.
- Approximate capture duration.
- Whether source frames were saved.

For emulator testing, also record:

- AVD name.
- Hardware profile.
- Android API level.
- System image type, such as AOSP, Google APIs, or Google Play.
- Graphics mode.
- Whether the emulator was cold booted, quick booted, or wiped.

### Common Problems

`gradlew` or `gradlew.bat` is missing:

1. The Android project scaffold has not been created yet.
2. Stop and create or restore the Gradle wrapper before trying to build.

The build cannot find an Android SDK:

1. Confirm `ANDROID_HOME` or `ANDROID_SDK_ROOT` points to the installed Android SDK.
2. Confirm `platform-tools` is on `PATH`.
3. Run `adb version`.
4. Install the SDK Platform required by the project with `sdkmanager`.
5. Install Platform-Tools with `sdkmanager` if needed.
6. Re-run the Gradle build from the VS Code terminal.

The device does not appear in ADB:

1. Run `adb devices`.
2. If the device does not appear there, VS Code and Gradle install commands cannot target it.
3. Check the cable.
4. Check USB debugging.
5. Check the debugging authorization prompt.
6. Check Windows USB drivers if on Windows.

The device appears as `unauthorized`:

1. Unlock the device.
2. Look for the USB debugging prompt.
3. Tap `Allow`.
4. If the prompt does not appear, turn USB debugging off and on again.
5. Unplug and reconnect the cable.

The emulator boots but the app will not install:

1. Confirm the emulator is fully booted and unlocked.
2. Run `adb devices`.
3. Confirm the emulator state is `device`.
4. Rebuild the debug APK.
5. If install still fails, uninstall the old package or wipe app data.

The app starts and immediately closes:

1. Run `adb logcat`.
2. Filter by the app package name.
3. Look for `FATAL EXCEPTION`.
4. Fix the first crash stack trace.
5. Rebuild and reinstall.

The app behaves differently on emulator and real phone:

1. Prefer the real phone result for camera, sensors, compass, storage, thermal, and location behavior.
2. Prefer emulator results only for controlled UI, navigation, layout, and clean-state tests.
3. Add a device-specific note if a hardware sensor is missing or unreliable.

### Official References

- VS Code documentation: https://code.visualstudio.com/docs
- Android command-line tools: https://developer.android.com/tools
- Run apps on a local device: https://developer.android.com/studio/run/device
- Google USB Driver for Windows: https://developer.android.com/studio/run/win-usb
- Run apps on the Android Emulator: https://developer.android.com/studio/run/emulator
- Start the emulator from the command line: https://developer.android.com/studio/run/emulator-commandline
- Android Emulator troubleshooting: https://developer.android.com/studio/run/emulator-troubleshooting
- Android Emulator hardware acceleration: https://developer.android.com/studio/run/emulator-acceleration

## Development Blog

### 2026-07-20: First Research Pass

The original spark is nicely ambitious: make a free Android app that can guide a user through capturing a full 360 x 180 scene, use the camera plus accelerometer, gyroscope, compass, and location services to stabilize and orient the capture, stitch and exposure-balance the frames, save the result to device storage, and later browse or reproject the saved images between PhotoSphere and Tiny Planet views.

The core file format target should be an equirectangular JPEG master at a 2:1 aspect ratio. Google Maps' own Photo Sphere guidance expects 7.5 MP or larger, a 2:1 image, no more than 75 MB, no horizon gaps, and no major stitching errors. That makes a 3840 x 1920 output the minimum credible target, with higher resolutions desirable on modern phones.

The best product direction is probably not "one-click magic" at first. A realistic first version would be a guided capture tool with a clear sphere/cube coverage map, live horizon/orientation feedback, local saves, a gallery, and reprojection/export. Full automatic high-quality stitching is the hard part. Publishing and browsing are approachable; capture guidance is approachable; reliable stitching across phones, lenses, parallax, moving objects, low light, and exposure shifts is the real research project.

### Current Market Notes

I did not find a clearly free current Android or PWA offering that does all of the desired pieces in one place: guided phone-only PhotoSphere capture, sensor-assisted stabilization/orientation, image matching and exposure compensation, Tiny Planet generation, local gallery, reversible reprojection browser, Google Photos integration, and Google Maps publishing.

There are partial products:

- Google Maps can publish Photo Spheres and Google documents that developers can build publishing tools with the Street View Publish API, but Google's standalone Street View app was discontinued in 2023.
- Panorra 360 appears to focus on 360-photo sharing and one-tap Google Street View publishing.
- Go Street View Photo Sphere and 360 Photo Cam advertise phone-based 360 capture/stitching flows.
- Tiny Planet - Global Photo focuses on turning photos/panoramas into Tiny Planet or wormhole-style images, with import/camera/export features.
- Web libraries such as Photo Sphere Viewer, Pannellum, and Marzipano are excellent for viewing equirectangular panoramas in browsers, but they are viewers/toolkits rather than complete phone capture, stitch, local-gallery, and Google-publishing products.

The gap seems real: the market has viewers, editors, uploaders, and capture apps, but not a polished free app that combines the whole workflow and treats Tiny Planet and PhotoSphere as two projections of the same saved master.

### Google Platform Reality

Google Maps publishing is possible, but must be treated carefully. Google says Photo Spheres can be uploaded with the Android Google Maps app or browser, and developers can create tools with the Street View Publish API. The API can publish 360 photos to Google Maps with position, orientation, and connectivity metadata. That is promising for Spherify, especially if each image stores GPS, compass heading, capture time, and XMP Photo Sphere metadata.

Google Photos is more constrained. Since the March 31, 2025 API changes, the Library API is aimed at managing photos and videos created by the app. Access to media not uploaded by the app moved toward explicit user selection through the Picker API. For Spherify this suggests:

- Upload app-created exports to Google Photos using the Google Photos Library API.
- Import existing Google Photos items through the Android photo picker or Google Photos Picker API when the user explicitly selects them.
- Avoid promising unrestricted browsing of the user's whole Google Photos account.
- Keep a local app gallery as the reliable primary library.

Device storage is straightforward in principle. Android MediaStore supports saving app-created images into shared media storage, and Android 10+ does not require broad storage permission for photos the app itself creates. Reading unrelated images should go through the Android photo picker where possible.

### Technical Feasibility

The app is buildable, but not small.

The feasible first release is an Android-native app using CameraX or Camera2, Android SensorManager, fused location/orientation data, MediaStore, a local database, and a GPU-backed viewer. Capture can start with a structured grid of overlapping photos around the user, using sensor fusion to suggest the next target direction. The app should save all source frames plus a generated equirectangular master so reprocessing can improve over time.

The hard engineering work is the stitching pipeline:

- Feature detection and image matching across overlapping camera frames.
- Sensor-informed initial pose estimates from gyroscope, accelerometer, magnetometer/compass, and rotation vector.
- Exposure/white-balance compensation across frames.
- Seam selection, blending, and ghost handling for moving objects.
- Horizon correction and nadir/zenith handling.
- Robust behavior across ultra-wide, wide, and telephoto lenses.

The Tiny Planet feature is easier if it is treated as a reprojection of an equirectangular master using stereographic projection. The reverse direction is only lossless when the original equirectangular master is retained. A flattened Tiny Planet export alone does not contain enough information to reconstruct a full PhotoSphere.

### PWA Versus Android Native

A PWA could be useful for viewing and reprojection experiments, but a Play Store Android-native app is the better primary route.

PWAs can use WebGL and libraries like Photo Sphere Viewer, Pannellum, or Marzipano for interactive viewing. Some browsers expose camera, geolocation, orientation, and motion APIs, but capture permissions, sensor availability, background behavior, file access, performance, and Play Store distribution are less predictable than native Android. A PWA or web module might still be valuable later as a browser-based gallery/export companion, or wrapped inside a native shell for the viewer only.

### Product Shape

The first useful app could be:

- Capture: guided sphere capture with orientation targets, overlap hints, exposure lock, and live progress.
- Process: stitch source frames into an equirectangular PhotoSphere master, store source frames for later reprocessing, and generate preview thumbnails.
- Browse: local gallery of Spherify captures and imports.
- Reproject: interactive PhotoSphere view, Tiny Planet view, wormhole/inverted view, horizon rotation, zoom, roll, and export.
- Save: write masters and exported projections to device storage.
- Publish: upload app-created images to Google Photos, share/export to Google Maps manually, and later add Street View Publish API support.

### Design Prerequisites

Before the first line of app code, the product needs a few design decisions pinned down. These are not visual-polish questions; they determine whether the app can feel trustworthy, stable, and feasible.

The app should be designed around a local-first library. Google Photos and Google Maps should feel like export destinations, not the place where the app's primary state lives. The local library should keep a durable record for every capture: source frame set, stitched equirectangular master, exported Tiny Planet variants, capture metadata, processing status, and publishing status. That gives the user confidence that a failed upload or later API change will not strand their work.

The permission model should be contextual and progressive. Android guidance says runtime permissions should be requested when the user starts the feature that needs them, not at app startup. For Spherify this means:

- Camera permission appears when the user starts a new capture.
- Location permission appears when the user enables map-ready geotagging or publishing metadata.
- Photo/media access is avoided for app-created images and handled through MediaStore; imports from other apps or Google Photos should use the Android photo picker where possible.
- Google account authorization appears only when the user chooses Google Photos upload or Google Maps/Street View publishing.
- Motion sensors should be used while capture is visible and active, with no background collection.

The app should also be useful when optional permissions are denied. Without location, the user can still create and save PhotoSpheres and Tiny Planets, but exports will not be map-ready until location is added manually. Without Google sign-in, the user can still save locally and share files. Without photo-library access, the user can still use the Spherify library and select individual external images through the picker.

The minimum viable technical surface should include:

- Camera preview and capture with exposure/focus controls.
- Motion/orientation sensor fusion for capture guidance.
- Foreground-only location capture for optional geotagging.
- Local storage with app-owned image access.
- Equirectangular image viewer.
- Stereographic Tiny Planet renderer/exporter.
- Processing queue with resumable stitch/reproject jobs.
- Metadata editor for title, location, orientation, projection, and publishing readiness.

The minimum viable design surface should include:

- A home/gallery view that opens straight into existing work, not a marketing page.
- One primary action: start capture or import image.
- A capture screen that is mostly camera preview, with a sphere coverage guide and minimal controls.
- A processing screen that explains progress without pretending stitching is instant.
- A viewer/editor that treats PhotoSphere and Tiny Planet as modes of the same master image.
- A publish/export sheet that separates local save, Google Photos upload, Google Maps publish/share, and generic Android share.

### Workflow Investigation

The intended user journey has six major phases.

1. First launch and setup

The first launch should be short and practical. The app should show the local gallery, empty if new, with a primary capture action and a secondary import action. It should not ask for camera, location, files, or Google sign-in immediately. A small first-run note can explain that capture needs camera access, map-ready exports need location, and cloud publishing is optional.

The empty state should offer:

- Capture PhotoSphere.
- Import 360 image.
- Open sample panorama, if bundled later.

2. Capture preparation

When the user taps Capture PhotoSphere, the app checks the environment and requested capture mode. This screen should detect available cameras, gyroscope, accelerometer, magnetometer/compass, rotation vector, current battery state, storage availability, and whether location is enabled. It should warn without blocking where possible.

The ideal prep checklist:

- Camera ready.
- Motion tracking ready.
- Compass quality acceptable or needs calibration.
- Enough free storage for source frames and stitched output.
- Location available, optional.
- Lighting stable enough for exposure lock, optional but recommended.

The user should be able to choose:

- Capture quality: standard, high, archival.
- Lens: main wide, ultra-wide if supported, manual choice if multiple lenses are reliable.
- Save source frames: always, ask, or storage saver.
- Location tagging: on/off.
- Exposure mode: auto, locked after first frame, manual compensation.

3. Guided capture

The capture UI should behave like a calm instrument panel. The camera preview takes the whole screen. On top of it, the app overlays a sphere coverage guide: target dots or tiles arranged around yaw/pitch positions. The user turns slowly and aligns a reticle with each target. A frame is captured automatically when alignment, stability, and overlap are acceptable, or manually if the user chooses.

The screen needs:

- Center reticle and next target marker.
- Coverage mini-map showing captured, missing, and weak-overlap regions.
- Horizon/level indicator.
- Stability indicator based on gyro movement.
- Exposure lock status.
- Undo last frame.
- Pause/resume capture.
- Finish when enough coverage exists.
- Cancel with a clear "keep source frames?" choice.

Capture should prefer fewer, better prompts over dense instruction text. The app can use short states: Move slower, Hold steady, Aim higher, Recapture weak area, Too dark, Exposure shifted, Complete. These states should be visible but not block the preview.

The capture flow should support failure recovery:

- If compass quality drops, continue with gyro-relative orientation and flag metadata for review.
- If a frame is blurred, prompt for recapture.
- If a moving object crosses a frame, mark it as likely ghosting risk.
- If capture is interrupted, save a draft session.
- If coverage is incomplete, allow partial save but label it as not map-ready.

4. Processing and quality review

After capture, processing should be explicit. Stitching can be slow, so the app needs a queue rather than a single spinner. The processing screen should show stages:

- Align frames.
- Match overlap.
- Balance exposure and white balance.
- Blend seams.
- Fill/flag zenith and nadir gaps.
- Generate equirectangular master.
- Generate preview projections.
- Validate PhotoSphere metadata.

The review screen should show the equirectangular master first, with quality flags:

- Resolution and aspect ratio.
- Location present or missing.
- Compass/orientation confidence.
- Horizon quality.
- Stitching confidence.
- Gap/blur/ghosting warnings.
- Google Maps readiness against known requirements.

The user should be able to fix what can be fixed:

- Rotate horizon.
- Adjust heading/north.
- Crop or pad only if the output remains valid 2:1.
- Re-run stitch with alternate seam/blend settings.
- Hide nadir with blur/patch if allowed.
- Add or edit location.
- Save as draft, master, or export.

5. Local browsing and reprojection

The gallery should be the emotional center of the app. It should make later re-use pleasant, not merely list files. Each item should show a thumbnail, projection badges, capture date, location status, and publish status. Filters should include Drafts, Masters, Tiny Planets, Map-ready, Uploaded, Needs review, and Imports.

Opening an item should launch the viewer/editor. The main viewer modes:

- PhotoSphere: interactive immersive viewer with pan, tilt, zoom, gyro look-around toggle, compass overlay, and reset view.
- Tiny Planet: stereographic projection with planet/wormhole toggle, roll, zoom, center point, horizon bend, rotation, and field-of-view controls.
- Equirectangular: flat technical view for checking seams, metadata, and map readiness.

The bonus "on-the-fly image browser" should be designed as a non-destructive projection workspace. The master image remains untouched. Each Tiny Planet or adjusted PhotoSphere is a saved variant linked back to the master. If the user imports a Tiny Planet-only flat image, the app should treat it as a flat image and not pretend it can reconstruct the original sphere.

6. Export and publish

Export should be a bottom sheet or dedicated publish screen with four distinct choices:

- Save to device: writes the selected master or projection to shared media storage.
- Share: opens Android share for any exported image.
- Upload to Google Photos: uploads app-created export through the Google Photos Library API, with clear status.
- Publish to Google Maps/Street View: either hand off to Google Maps/manual upload in early versions, or use the Street View Publish API later.

For Google Maps, the app should run a readiness check before publishing:

- Equirectangular 2:1 output.
- At least 3840 x 1920.
- File under 75 MB.
- GPS location present.
- Heading/orientation present or user-reviewed.
- No known major stitch/gap errors.
- User confirms imagery is appropriate for public Maps contribution.

Publishing status should be visible after the export:

- Local only.
- Saved to device.
- Uploaded to Google Photos.
- Submitted to Google Maps.
- Processing on Google Maps.
- Published.
- Failed, with retry.

### Probable User Interface

The likely UI architecture is a bottom-navigation Android app with four destinations:

- Library: local gallery and capture history.
- Capture: camera-driven PhotoSphere capture.
- Create: import/reproject/export workspace.
- Settings: permissions, storage, accounts, quality defaults, privacy, and diagnostics.

The Library screen should use a dense but friendly grid. The top app bar can contain search, filter, sort, and account/status. A prominent capture button should be available, likely a floating action button because capture is the primary creation action. Import can sit beside it in a small action menu or as a secondary button in the empty state.

The Capture screen should hide bottom navigation while active. It should use the whole display for preview, with a top strip for close, flash/exposure, lens, and help, and a bottom strip for shutter/auto-capture, undo, pause, and finish. The sphere coverage mini-map should be visible but compact. Accessibility matters here: indicators should not rely on color alone, and capture state should be readable through labels/haptics.

The Processing screen should look like a job card list, not a modal trap. Users should be able to leave processing and return later. Each job shows thumbnail, current stage, elapsed time, estimated remaining time if meaningful, and actions for pause, cancel, or view draft.

The Viewer/Editor screen should have a full-bleed image viewer with a compact mode switch: Sphere, Tiny Planet, Flat. Editing controls should slide up only when needed. For Tiny Planet, the most important controls are center, zoom, twist/roll, invert, and export. For PhotoSphere, the important controls are heading, horizon, gyro toggle, metadata, and export.

The Publish/Export UI should be conservative and explicit. It should never make a public upload feel like a casual save. Google Maps publishing especially needs a preflight page with a preview, location, account, visibility implication, and readiness warnings. Google Photos upload can be lighter, but still should clearly say which account is being used and whether the item is app-created.

The Settings screen should include:

- Capture defaults: quality, source-frame retention, auto-capture sensitivity, exposure lock, lens preference.
- Storage: local library size, source frames size, cache size, cleanup controls.
- Accounts: Google Photos connection, Google Maps/Street View publishing connection.
- Privacy: location tagging default, metadata export choices, permission status.
- Diagnostics: sensor availability, compass calibration, camera capabilities, export logs.
- About: version 0.4.33, license, acknowledgements.

### Suggested Setup-First UI Workflow

Although Android and Google Play guidance generally favors requesting permissions in context, Spherify can still offer an immediate setup path if it is framed as a friendly readiness flow. The app cannot force permissions or account access; the user must grant them through Android and Google consent screens. The design goal is to make the fastest route obvious: "Get Spherify ready now," while still allowing the user to skip and use local features.

The setup-first flow should use simple splash screens with one clear action per screen.

1. Welcome splash

Purpose: establish the app's promise in one glance.

Primary text: Create PhotoSpheres and Tiny Planets.

Supporting text: Capture, reproject, save, and publish 360 images from your phone.

Buttons:

- Set up Spherify.
- Browse without setup.

2. Readiness splash

Purpose: explain why the app asks for several sensitive capabilities.

Primary text: A few things make the sphere work.

Supporting text: Camera captures the scene, motion sensors guide the sphere, location prepares map-ready images, and Google accounts enable optional upload.

Buttons:

- Grant essentials.
- Choose one by one.

3. Camera permission screen

Purpose: unlock capture.

UI:

- Large camera icon.
- Short copy: Camera access lets Spherify capture the frames for your PhotoSphere.
- Button: Allow camera.
- Secondary: Skip for now.

Result:

- If granted, continue.
- If denied, mark Capture as unavailable and continue to import/gallery setup.

4. Motion/orientation readiness screen

Purpose: check sensors without making the screen feel technical.

UI:

- Simple animated phone-orbit graphic.
- Status chips: Gyroscope, Accelerometer, Compass, Rotation vector.
- Button: Check motion tracking.
- Secondary: Continue anyway.

Result:

- If sensors are present, show Ready.
- If compass quality is weak, offer Calibrate compass.
- If a sensor is missing, continue with reduced capture guidance.

5. Location permission screen

Purpose: enable map-ready images.

UI:

- Map pin over a small sphere thumbnail.
- Short copy: Location helps Google Maps place your PhotoSphere correctly. You can still create images without it.
- Buttons: Allow location, Use without location.

Preferred permission:

- Foreground location only.
- Precise location if the user wants Google Maps publishing.
- Approximate location is acceptable for private/local organization but should be flagged as not ideal for Maps.

6. Local storage setup screen

Purpose: reassure the user that their work is saved on the device first.

UI:

- Folder/gallery icon.
- Short copy: Spherify saves your masters and exports locally first.
- Buttons: Create local library, Change later.

Notes:

- App-created media should use app-owned storage and MediaStore.
- Broad photo/video permission should be avoided unless future requirements truly need it.
- Imports should use the Android photo picker.

7. Google account setup screen

Purpose: connect Google Photos and Google Maps publishing after core device permissions.

UI:

- Two account cards:
- Google Photos: Upload app-created exports.
- Google Maps / Street View: Publish map-ready PhotoSpheres.

Buttons:

- Connect Google.
- Skip cloud setup.

Result:

- If connected, show the selected Google account and enabled destinations.
- If skipped, local save and Android share remain available.

8. Setup complete splash

Purpose: land the user with confidence and next steps.

Primary text: Ready to make a sphere.

Status checklist:

- Camera ready.
- Motion tracking ready.
- Local library ready.
- Location ready or skipped.
- Google upload ready or skipped.

Buttons:

- Capture PhotoSphere.
- Import 360 image.
- Open Library.

This setup-first approach keeps the interface friendly while satisfying the desire to grant permissions and accounts immediately. The important product rule is that every skipped or denied item must degrade gracefully: the app should never punish the user with a dead end.

### Simple Function Buttons

The home screen after setup should avoid clutter. A user should be able to understand the app from four large function buttons:

- Capture: start a guided PhotoSphere capture.
- Import: choose an existing 360 image or panorama.
- Browse: open the local Spherify library.
- Create Tiny Planet: open the reprojection editor from a selected master or imported image.

Secondary actions can live behind smaller icons or menus:

- Save to device.
- Upload to Google Photos.
- Publish to Google Maps.
- Share.
- Settings.

The button language should remain concrete. Prefer Capture, Import, Browse, Create, Save, Upload, Publish, and Share over abstract words like Workspace, Assets, Pipeline, or Render.

### Design Risks

The biggest UX risk is over-promising capture quality. The app should be honest about draft, partial, and map-ready states. A beautiful Tiny Planet can tolerate flaws that Google Maps should reject, so the UI must separate creative exports from public 360 imagery.

The second risk is permission fatigue. Asking for camera, location, media, motion, and Google account access in one clump would feel alarming. Contextual requests and graceful degradation are central to the design.

The third risk is losing the user during capture. PhotoSphere capture is physical: the user is rotating, aiming, holding the phone steady, and trying not to lose their place. The UI should behave like a guide, not a control panel.

The fourth risk is treating Tiny Planet and PhotoSphere as reversible when they are not always reversible. The app's data model and UI should make "master" and "exported variant" clear.

The fifth risk is Google platform dependency. Direct publishing features should be modular, so a Google API policy change does not break local creation, browsing, and export.

### Open Questions

- Should version 0.1 aim for high-quality stitching, or should it start with import/reprojection/gallery and add capture later?
- Is the project willing to depend on native image-processing libraries such as OpenCV, Hugin/Panorama Tools-inspired algorithms, or commercial SDKs if open-source quality is insufficient?
- Should Google Maps publishing be direct through Street View Publish API from the start, or should the first release hand off exported Photo Spheres to Google Maps?
- What minimum Android version should be supported? Android 13+ has better photo-picker behavior with Google Photos cloud media, but a wider minimum increases reach.
- Should the app preserve all capture frames by default, or offer a storage-saving mode that keeps only the stitched master?
- Should the first UI expose advanced stitching controls, or keep them behind a diagnostics/advanced panel?
- Should the app include a bundled sample panorama so users can try Tiny Planet mode before granting camera permission?
- Should incomplete captures be allowed as creative Tiny Planet drafts even when they are not valid PhotoSpheres?

## Research Sources

- Google Maps Help: Create and publish Photo Spheres to Google Maps: https://support.google.com/maps/answer/7012050
- Google Street View Publish API: https://developers.google.com/streetview/publish
- Google Street View contribution guidance: https://www.google.com/streetview/contribute/
- Google Photos API updates: https://developers.google.com/photos/support/updates
- Google Photos upload media guide: https://developers.google.com/photos/library/guides/upload-media
- Google Photos Picker API: https://developers.google.com/photos/picker/guides/get-started-picker
- Android MediaStore and shared storage: https://developer.android.com/training/data-storage/shared/media
- Android photo picker with Google Photos: https://support.google.com/photos/answer/14442861
- Android CameraX configuration and exposure compensation: https://developer.android.com/media/camera/camerax/configuration
- Android sensors overview: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- Android permissions overview: https://developer.android.com/guide/topics/permissions/overview
- Android runtime permission requests: https://developer.android.com/training/permissions/requesting
- Android location permissions: https://developer.android.com/develop/sensors-and-location/location/permissions
- Android core app quality guidelines: https://developer.android.com/docs/quality-guidelines/core-app-quality
- Google Play policies: https://developer.android.com/distribute/play-policies
- Material Design floating action button guidance: https://m3.material.io/components/floating-action-button/guidelines
- Photo Sphere Viewer: https://photo-sphere-viewer.js.org/
- Pannellum: https://pannellum.org/
- Marzipano: https://www.marzipano.net/
- Panorra 360 on Google Play: https://play.google.com/store/apps/details?id=com.gothru.panorra
- Go Street View Photo Sphere on Google Play: https://play.google.com/store/apps/details?id=com.gostreetview.camera
- 360 Photo Cam on Google Play: https://play.google.com/store/apps/details?id=com.dospace.photo360
- Tiny Planet - Global Photo on Google Play: https://play.google.com/store/apps/details?id=globe.tiny.planet.globalphoto.tinyplanet

## Development Workflow and Timeline

This roadmap assumes the project starts from no application code and grows through small, testable milestones. The order is intentionally local-first: prove image handling, projection, and storage before depending on Google account flows or public publishing.

### Phase 0: Repository and Product Groundwork

Goal: prepare the project without committing to the full implementation too early.

Work:

- Choose the Android baseline: Kotlin, Jetpack Compose, CameraX/Camera2 decision, minimum Android version, and target Play Store requirements.
- Choose a local image metadata model: capture session, source frame, equirectangular master, exported variant, location metadata, and publish status.
- Decide whether stitching research starts with OpenCV or another native/image-processing library.
- Define sample assets for testing equirectangular viewing and Tiny Planet reprojection.
- Create a privacy and permissions matrix before app code begins.

Exit criteria:

- A clear app architecture note.
- A local-first data model.
- A short list of candidate image-processing libraries.
- A decision on minimum Android version.

### Phase 1: Barebones Technology Proof of Concept

Goal: prove the core image manipulation idea with the least possible UI.

Work:

- Load a bundled equirectangular test image.
- Display it in a basic interactive PhotoSphere viewer.
- Reproject it into Tiny Planet/stereographic view.
- Allow simple manipulation: rotate, zoom, invert, reset.
- Export the current projection as a JPEG or PNG into app storage.
- Save a generated thumbnail.

Exit criteria:

- One test panorama can be viewed as PhotoSphere.
- The same panorama can be transformed into Tiny Planet.
- The transformed output can be saved and reopened.

### Phase 2: Local Storage and Image Library

Goal: make image work durable and user-visible.

Work:

- Build the local Spherify library.
- Store masters, variants, thumbnails, and metadata.
- Save app-created images through MediaStore where appropriate.
- Import external images through the Android photo picker.
- Add gallery filters for Masters, Tiny Planets, Imports, Drafts, and Saved.
- Add delete, rename/title, duplicate/export variant, and metadata view.

Exit criteria:

- A user can import an image, create a Tiny Planet variant, save it, close the app, reopen, and continue working.
- App-created images are visible in the device gallery when exported.
- The app does not require broad photo-library permission for normal use.
- Draft capture sessions are represented as first-class local library records, not only as loose frame files.

### Phase 3: Capture Shell and Sensor Readiness

Goal: prove the phone can guide a capture before attempting full stitching.

Work:

- Create the setup-first splash workflow.
- Request camera/location/account permissions through friendly, skippable screens.
- Build camera preview with simple still capture.
- Read accelerometer, gyroscope, compass/magnetometer, and rotation vector where available.
- Show sensor readiness and compass calibration status.
- Save captured frames into a draft capture session.
- Save foreground location metadata when enabled.

Exit criteria:

- A user can grant camera/location, capture frames, and see the draft session in the library.
- Sensor status is visible and does not block unsupported devices unnecessarily.
- Location remains optional.

### Phase 4: Guided PhotoSphere Capture Prototype

Goal: turn raw camera capture into a structured sphere capture experience.

Work:

- Add sphere guidance around yaw/pitch coverage positions.
- Add reticle alignment, horizon/level feedback, stability/sweep-speed detection, and coverage progress.
- Support manual capture first, then auto-capture when alignment and sweep pace are good.
- Add undo, pause/resume, cancel, and finish.
- Store each frame with approximate orientation, timestamp, exposure references, and location/session metadata.
- Add draft recovery if capture is interrupted.

Current implementation:

- The first implementation used a 24-target yaw/pitch guide grid with target locking, reticle alignment, and auto-capture. Real-device experiments showed that discrete target chasing was less useful than broad, continuous overlap coverage, so Phase 4 iterated into an ARCore sweep-painting method rather than treating the original target grid as final.
- Capture now runs through ARCore with an OpenGL camera preview, ARCore pose-derived yaw/pitch/roll, and ARCore CPU camera frames saved as upright draft JPEGs.
- The sphere capture pattern is five paint layers: horizon, upper, lower, high upper, and high lower. The high upper/lower pole layers are treated as pitch-only one-shot captures because yaw/roll becomes unreliable near vertical.
- The UI uses a start/end landmark horizon sweep, pitch target line, always-visible vertical alignment line, sweep-speed feedback, painted preview rows, coverage progress, tap-to-recapture rows, optional pole infill, and simplified polar Capture/Next/Spherify controls.
- Auto-capture is now the default for unpainted sweep slices when ARCore is ready, pitch is aligned, and the sweep pace is acceptable; manual capture and manual recovery remain available.
- Undo, pause/resume, cancel, finish, draft-frame deletion, and confirmed bulk draft removal are implemented.
- Draft metadata now includes a persistent session id, frame path, approximate orientation, target yaw/pitch or polar slot reference, timestamp, location summary, capture mode, and structured ARCore camera exposure references instead of a placeholder.
- Each capture session is promoted into a first-class Draft library record as soon as its first frame is saved. The raw "Draft Frames" browser remains as a diagnostic view, but normal browsing can treat draft captures as sessions.
- The active guided session id is persisted, so interruption without explicit finish/cancel resumes into the same draft session.

Exit criteria:

- A user can complete a guided capture session with enough overlapping frames for stitching experiments.
- The app can show missing/weak coverage areas.
- Capture drafts survive app interruption.
- Phase 5 can enumerate frames by draft session and read orientation/exposure references from metadata without guessing which frames belong together.

### Phase 5: Stitching and Equirectangular Master Generation

Goal: produce the first real app-created PhotoSphere master.

Prerequisites from Phase 4:

- Draft sessions must be first-class library records with stable `sessionId` values.
- Every draft frame must record enough references for stitching experiments: file path, timestamp, session id, capture mode, approximate heading/pitch/roll, target yaw/pitch, optional location, and camera exposure metadata such as exposure time, ISO/sensitivity, frame duration, sensor timestamp, aperture, focal length, AE state, AWB state, and exposure compensation.
- The stitched input set should come from one selected draft session, not from the raw all-drafts frame list.
- Missing exposure values must be stored explicitly as unavailable/null fields, not as prose placeholders, so Phase 5 can decide whether to compensate, warn, or skip a frame.

Work:

- Build or integrate a stitching pipeline.
- Use sensor orientation as an initial pose estimate.
- Match features across overlapping frames.
- Estimate alignment and correct drift.
- Balance exposure and white balance between frames.
- Blend seams and flag weak areas.
- Generate a 2:1 equirectangular JPEG master.
- Add basic PhotoSphere/XMP-style metadata.
- Validate output against Google Maps readiness requirements.

Exit criteria:

- At least one real phone capture produces a usable equirectangular master.
- The master can be viewed, reprojected, saved, and exported.
- The app identifies non-map-ready captures instead of silently treating them as publishable.

### Phase 6: Review, Repair, and Creative Editing

Goal: let users improve imperfect outputs and make beautiful variants.

Work:

- Add quality review screen with resolution, aspect ratio, horizon, stitch confidence, gap warnings, and location status.
- Add heading/north adjustment.
- Add horizon rotation and simple leveling.
- Add nadir/zenith patch or blur options if feasible.
- Add alternate Tiny Planet controls: center, zoom, roll, planet/wormhole, field of view.
- Add non-destructive saved variants linked to the master.

Exit criteria:

- Users can fix common metadata/orientation issues without reprocessing the whole session.
- Creative Tiny Planet export is reliable even when Google Maps readiness is not.
- Master and variant relationships are clear in the UI.

### Phase 7: Google Photos Upload

Goal: add optional cloud upload for app-created exports.

Work:

- Add Google sign-in/account connection for upload features.
- Use the Google Photos Library API for app-created media uploads.
- Keep imports from Google Photos user-selected through the picker model.
- Add upload progress, retry, and failure states.
- Store uploaded status and account destination metadata.

Exit criteria:

- A user can upload an app-created master or Tiny Planet export to Google Photos.
- The app clearly shows which account was used.
- Failed uploads remain safely available locally.

### Phase 8: Google Maps Publishing Preparation

Goal: prepare map-ready PhotoSpheres and user-facing publish checks.

Work:

- Add Google Maps readiness preflight.
- Validate 2:1 aspect ratio, minimum 3840 x 1920 resolution, file size under 75 MB, location, heading, and stitch warnings.
- Add metadata editor for location and heading review.
- Add a manual export/share-to-Google-Maps path for early releases.
- Document the expected Google Maps processing delay and public contribution implications.

Exit criteria:

- The user can produce a map-ready export and hand it off manually.
- The app prevents obvious invalid submissions from being labeled ready.
- Local publish status distinguishes "exported for Maps" from "published on Maps."

### Phase 9: Direct Street View Publish API Integration

Goal: submit app-created 360 imagery directly to Google Maps/Street View where policy and API access allow.

Work:

- Complete Street View Publish API authorization and OAuth scopes.
- Upload 360 photos with required metadata.
- Handle API errors, quota issues, retries, and user cancellation.
- Track submitted, processing, published, failed, and removed states.
- Add optional linking/connectivity for tours if this becomes a core use case.
- Add clear public-publishing confirmation before submission.

Exit criteria:

- A user can submit a validated PhotoSphere from Spherify to Google Maps/Street View.
- The app records submission status and exposes retry/failure details.
- Public upload is never confused with local save or private Google Photos upload.

### Phase 10: Play Store Readiness and Beta

Goal: turn the prototype into a testable Play Store app.

Work:

- Run privacy, permissions, data safety, and Play policy review.
- Add crash reporting and performance diagnostics without logging sensitive image/location data.
- Test on multiple camera/sensor combinations.
- Optimize memory use for large panoramas.
- Add accessibility pass for capture controls, labels, color contrast, and haptics.
- Prepare closed testing release.
- Collect real-world capture examples and failure cases.

Exit criteria:

- Closed beta users can capture, save, reproject, upload, and attempt Maps-ready export.
- Known device limitations are documented.
- Data safety and permission justifications are ready for Play Console.

### Phase 11: Public Release and Iteration

Goal: launch carefully, then improve quality from real captures.

Work:

- Release a conservative 1.0 focused on local creation, save/export, reprojection, Google Photos upload, and Maps-ready export or direct Maps publishing if stable.
- Improve stitching based on real-world failures.
- Add capture presets for indoor, outdoor, low light, and fast creative Tiny Planet.
- Add batch export and cloud backup options only if privacy and API limits permit.
- Explore web/PWA companion for viewing and sharing if useful.

Exit criteria:

- Users can reliably create and keep their images without cloud dependency.
- Google integrations enhance the product but do not define whether it works.
- The app has a feedback loop for capture quality, device support, and publishing success.

## License

GPL-3.0. See [LICENSE](LICENSE).
