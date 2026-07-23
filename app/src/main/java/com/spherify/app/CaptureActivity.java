/*
 * CaptureActivity.java
 *
 * Educational overview:
 * CaptureActivity is the Phase 4 replacement capture instrument. The previous
 * sweep/paint/force-capture prototype tried to rescue uncertain source data
 * after capture. This version follows the research direction instead: guide the
 * user to deliberate still targets on a virtual sphere, capture only when pose
 * and motion are stable, validate each candidate against the existing capture
 * graph, and persist immutable raw facts separately from analysis facts.
 *
 * Data flow:
 * MainActivity launches CaptureActivity -> CameraX provides a live preview and
 * still JPEG capture -> rotation-vector/gyro sensors drive the target reticle
 * -> the user aligns one target and holds steady -> ImageCapture saves a still
 * JPEG -> CandidateQualityScorer checks blur/exposure/texture -> OpenCV overlap
 * validation compares the candidate with expected accepted neighbors ->
 * SpherifyLibrary records the candidate, accepted source frame, graph edge, and
 * legacy draft row for recovery/stitching compatibility.
 *
 * External files/functions:
 * Uses CameraX Preview and ImageCapture for broad-device still capture.
 * Uses SensorManager for orientation and angular-rate guidance.
 * Uses SpherifyLibrary for capture-session persistence and graph records.
 * Uses CandidateQualityScorer and OpenCvOverlapValidator for the first local
 * validation gate before a frame becomes trusted source data.
 *
 * Key variables:
 * previewView: CameraX preview surface.
 * guideView: custom overlay that draws the reticle, active target, and coverage.
 * imageCapture: CameraX still-capture use case.
 * targets: adaptive spherical target lattice for the selected camera.
 * sessionId: persistent capture-session id used by SpherifyLibrary.
 * headingDegrees/pitchDegrees/rollDegrees: latest fused orientation estimate.
 * yawRateDegreesPerSecond/pitchRateDegreesPerSecond/rollRateDegreesPerSecond:
 * latest gyro rates used to reject moving frames.
 */
package com.spherify.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.SizeF;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CaptureActivity extends ComponentActivity implements SensorEventListener {
    private static final String TAG_PROFILE = "handheld";
    private static final float TARGET_YAW_TOLERANCE_DEGREES = 7.5f;
    private static final float TARGET_PITCH_TOLERANCE_DEGREES = 6.5f;
    private static final float MAX_CAPTURE_RATE_DEGREES_PER_SECOND = 3.5f;
    private static final long REQUIRED_STABLE_MS = 850L;
    private static final long MIN_CAPTURE_INTERVAL_MS = 1200L;

    private PreviewView previewView;
    private TargetGuideView guideView;
    private TextView statusText;
    private Button autoButton;
    private Button captureButton;
    private ImageCapture imageCapture;
    private SpherifyLibrary library;
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private Sensor gyroscopeSensor;
    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();
    private final CandidateQualityScorer qualityScorer = new CandidateQualityScorer();
    private final OpenCvOverlapValidator overlapValidator = new OpenCvOverlapValidator();
    private final ArrayList<CaptureTarget> targets = new ArrayList<>();
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationValues = new float[3];
    private String sessionId;
    private boolean autoCaptureEnabled = true;
    private boolean captureInProgress;
    private boolean hasOrientation;
    private boolean hasGyro;
    private float headingDegrees;
    private float pitchDegrees;
    private float rollDegrees;
    private float yawRateDegreesPerSecond;
    private float pitchRateDegreesPerSecond;
    private float rollRateDegreesPerSecond;
    private int activeTargetIndex;
    private long alignedSinceMs;
    private long lastCaptureAtMs;
    private CameraFacts cameraFacts = CameraFacts.unavailable();
    private final TreeMap<Long, TotalCaptureResult> camera2ResultsByTimestamp = new TreeMap<>();

    /*
     * Function: onCreate
     * Arguments: savedInstanceState is Android lifecycle state; this screen
     * creates a fresh recoverable session when launched.
     * Calls: SpherifyLibrary, buildUi(), readBackCameraFacts(),
     * buildCaptureTargets(), ensureCaptureSession(), and startCamera().
     * Flow: initialise storage, camera facts, sensors, UI, capture session, and
     * CameraX preview before the user begins aligning targets.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            library = new SpherifyLibrary(this);
        } catch (IOException e) {
            throw new IllegalStateException("could not open capture library", e);
        }
        cameraFacts = readBackCameraFacts();
        sessionId = newSessionId();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        buildCaptureTargets();
        buildUi();
        ensureSession(false);
        startCamera();
        showOpeningGuidance();
    }

    /*
     * Function: onResume
     * Arguments: none.
     * Calls: registerListener().
     * Flow: subscribe to motion sensors only while capture is visible.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null) {
            if (rotationVectorSensor != null) {
                sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
            }
            if (gyroscopeSensor != null) {
                sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    /*
     * Function: onPause
     * Arguments: none.
     * Calls: unregisterListener().
     * Flow: stop sensor collection when capture is no longer active.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    /*
     * Function: onDestroy
     * Arguments: none.
     * Calls: shutdownNow().
     * Flow: stop background capture work when the Activity is destroyed.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        captureExecutor.shutdownNow();
    }

    /*
     * Function: buildUi
     * Arguments: none.
     * Calls: Android view constructors and setContentView().
     * Flow: build a full-preview capture screen with a compact status strip,
     * reticle overlay, and four clear actions.
     */
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF05070A);

        statusText = new TextView(this);
        statusText.setTextColor(0xFFE2E8F0);
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(16, 12, 16, 10);
        statusText.setText("Move to the first target");
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout previewFrame = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewFrame.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        guideView = new TargetGuideView(this);
        previewFrame.addView(guideView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(previewFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(10, 10, 10, 14);
        captureButton = makeButton("Capture");
        captureButton.setOnClickListener(v -> captureActiveTarget(false));
        autoButton = makeButton("Auto On");
        autoButton.setOnClickListener(v -> toggleAutoCapture());
        Button undoButton = makeButton("Undo");
        undoButton.setOnClickListener(v -> undoLastTarget());
        Button finishButton = makeButton("Finish");
        finishButton.setOnClickListener(v -> finishCapture());
        controls.addView(captureButton);
        controls.addView(autoButton);
        controls.addView(undoButton);
        controls.addView(finishButton);
        root.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        refreshUi();
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(0xFF0F172A);
        button.setContentDescription(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(5, 0, 5, 0);
        button.setLayoutParams(params);
        return button;
    }

    /*
     * Function: startCamera
     * Arguments: none.
     * Calls: ProcessCameraProvider.getInstance(), bindCameraUseCases().
     * Flow: request the CameraX provider asynchronously and bind preview/still
     * capture once the provider is ready.
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                bindCameraUseCases(providerFuture.get());
            } catch (Exception e) {
                statusText.setText("Camera unavailable");
                Toast.makeText(this, "Camera failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /*
     * Function: bindCameraUseCases
     * Arguments: provider owns CameraX lifecycle bindings.
     * Calls: Preview.Builder, ImageCapture.Builder, bindToLifecycle().
     * Flow: bind one preview stream and one moderate-quality JPEG still stream
     * to the back camera.
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindCameraUseCases(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(94);
        new Camera2Interop.Extender<>(captureBuilder)
                .setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(
                            CameraCaptureSession session,
                            android.hardware.camera2.CaptureRequest request,
                            TotalCaptureResult result) {
                        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                        if (timestamp == null) {
                            return;
                        }
                        synchronized (camera2ResultsByTimestamp) {
                            camera2ResultsByTimestamp.put(timestamp, result);
                            while (camera2ResultsByTimestamp.size() > 60) {
                                camera2ResultsByTimestamp.pollFirstEntry();
                            }
                        }
                    }
                });
        imageCapture = captureBuilder.build();
        provider.unbindAll();
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
        ensureSession(false);
        refreshUi();
    }

    /*
     * Function: onSensorChanged
     * Arguments: event is the latest Android sensor sample.
     * Calls: SensorManager.getRotationMatrixFromVector(), getOrientation(),
     * refreshUi(), and maybeAutoCapture().
     * Flow: update orientation from the rotation vector and angular velocity
     * from the gyroscope, then let the target lock decide if capture is ready.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationValues);
            headingDegrees = normalizeDegrees((float) Math.toDegrees(orientationValues[0]));
            pitchDegrees = clamp((float) Math.toDegrees(orientationValues[1]), -89f, 89f);
            rollDegrees = clamp((float) Math.toDegrees(orientationValues[2]), -89f, 89f);
            hasOrientation = true;
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            yawRateDegreesPerSecond = Math.abs((float) Math.toDegrees(event.values[2]));
            pitchRateDegreesPerSecond = Math.abs((float) Math.toDegrees(event.values[0]));
            rollRateDegreesPerSecond = Math.abs((float) Math.toDegrees(event.values[1]));
            hasGyro = true;
        }
        refreshUi();
        maybeAutoCapture();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Rotation-vector accuracy is reflected through target stability rather than exposed as diagnostics.
    }

    /*
     * Function: refreshUi
     * Arguments: none.
     * Calls: updateActiveTarget(), guideView.setState(), and TextView/Button
     * setters.
     * Flow: keep the screen focused on one instruction and one active target.
     */
    private void refreshUi() {
        updateActiveTarget();
        CaptureTarget target = activeTarget();
        boolean aligned = target != null && isAligned(target);
        boolean stable = aligned && isMotionStable();
        long now = System.currentTimeMillis();
        if (stable) {
            if (alignedSinceMs == 0L) {
                alignedSinceMs = now;
            }
        } else {
            alignedSinceMs = 0L;
        }
        float lockProgress = alignedSinceMs == 0L ? 0f : clamp01((now - alignedSinceMs) / (float) REQUIRED_STABLE_MS);
        guideView.setState(targets, activeTargetIndex, headingDegrees, pitchDegrees, aligned, stable, lockProgress);
        captureButton.setEnabled(!captureInProgress && imageCapture != null && hasOrientation && target != null);
        int accepted = acceptedTargetCount();
        String state = captureInProgress
                ? "Validating still frame"
                : target == null
                ? "Capture complete"
                : !hasOrientation
                ? "Waiting for motion tracking"
                : !aligned
                ? "Move to target"
                : !isMotionStable()
                ? "Hold steady"
                : "Hold steady - capture ready";
        statusText.setText(String.format(Locale.US, "%s  |  %d/%d", state, accepted, targets.size()));
    }

    private void maybeAutoCapture() {
        CaptureTarget target = activeTarget();
        long now = System.currentTimeMillis();
        if (!autoCaptureEnabled
                || captureInProgress
                || imageCapture == null
                || target == null
                || !isAligned(target)
                || !isMotionStable()
                || alignedSinceMs == 0L
                || now - alignedSinceMs < REQUIRED_STABLE_MS
                || now - lastCaptureAtMs < MIN_CAPTURE_INTERVAL_MS) {
            return;
        }
        captureActiveTarget(true);
    }

    /*
     * Function: captureActiveTarget
     * Arguments: automatic distinguishes reticle capture from button capture.
     * Calls: ImageCapture.takePicture().
     * Flow: capture one still into memory, match its ImageProxy timestamp to a
     * Camera2 TotalCaptureResult callback, write the JPEG, then validate it on
     * the single capture executor.
     */
    private void captureActiveTarget(boolean automatic) {
        CaptureTarget target = activeTarget();
        if (captureInProgress || imageCapture == null || target == null) {
            return;
        }
        if (!hasOrientation) {
            Toast.makeText(this, "Motion tracking is still starting", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!automatic && !isAligned(target)) {
            Toast.makeText(this, "Align the reticle with the target first", Toast.LENGTH_SHORT).show();
            return;
        }
        captureInProgress = true;
        refreshUi();
        File outputFile;
        try {
            outputFile = library.createDraftFrameFile();
        } catch (IOException e) {
            captureInProgress = false;
            Toast.makeText(this, "Could not create capture file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        imageCapture.takePicture(captureExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                try {
                    long sensorTimestamp = image.getImageInfo().getTimestamp();
                    TotalCaptureResult metadata = camera2MetadataFor(sensorTimestamp);
                    if (metadata == null) {
                        throw new IOException("Camera2 metadata did not arrive for the captured frame");
                    }
                    writeJpegImageProxy(image, outputFile);
                    validateCapturedFile(outputFile, target, automatic, metadata);
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        captureInProgress = false;
                        refreshUi();
                        Toast.makeText(CaptureActivity.this, "Frame rejected: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                } finally {
                    image.close();
                }
            }

            @Override
            public void onError(ImageCaptureException exception) {
                runOnUiThread(() -> {
                    captureInProgress = false;
                    refreshUi();
                    Toast.makeText(CaptureActivity.this, "Capture failed: " + exception.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /*
     * Function: validateCapturedFile
     * Arguments: imageFile is the CameraX JPEG; target is the intended sphere
     * sample; automatic records whether auto-capture fired.
     * Calls: CandidateQualityScorer, predictedAcceptedNeighbors(),
     * OpenCvOverlapValidator, and recordAnalyzedCandidateFrame().
     * Flow: convert the still into a graph-backed candidate and accept only if
     * local image quality and expected overlap support it.
     */
    private void validateCapturedFile(File imageFile, CaptureTarget target, boolean automatic, TotalCaptureResult metadata) {
        try {
            JSONObject exposure = exposureJsonFor(imageFile, metadata);
            CandidateQualityReport quality = qualityScorer.score(
                    imageFile,
                    yawRateDegreesPerSecond,
                    pitchRateDegreesPerSecond,
                    rollRateDegreesPerSecond);
            List<CaptureFrameRecord> neighbors = library.predictedAcceptedNeighbors(
                    sessionId,
                    target.yawDegrees,
                    target.pitchDegrees);
            CandidateAnalysisResult analysis = overlapValidator.analyze(imageFile, quality, neighbors, target.pitchDegrees);
            library.recordAnalyzedCandidateFrame(
                    imageFile,
                    sessionId,
                    readLocationSummary(),
                    headingDegrees,
                    pitchDegrees,
                    rollDegrees,
                    target.yawDegrees,
                    target.pitchDegrees,
                    automatic ? "dot-auto" : "dot-manual",
                    TAG_PROFILE,
                    exposure.toString(),
                    analysis);
            runOnUiThread(() -> handleValidationResult(target, analysis));
        } catch (IOException | JSONException e) {
            runOnUiThread(() -> {
                captureInProgress = false;
                refreshUi();
                Toast.makeText(this, "Frame rejected: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }

    private void handleValidationResult(CaptureTarget target, CandidateAnalysisResult analysis) {
        captureInProgress = false;
        lastCaptureAtMs = System.currentTimeMillis();
        if (analysis.accepted) {
            target.captured = true;
            target.weak = false;
            updateActiveTarget();
            ensureSession(true);
            Toast.makeText(this, analysis.inlierCount > 0 ? "Accepted - overlap valid" : "Accepted - first view", Toast.LENGTH_SHORT).show();
        } else {
            target.weak = true;
            Toast.makeText(this, analysis.rejectionReason.isEmpty() ? "Recapture this area" : analysis.rejectionReason, Toast.LENGTH_LONG).show();
        }
        alignedSinceMs = 0L;
        refreshUi();
    }

    private void toggleAutoCapture() {
        autoCaptureEnabled = !autoCaptureEnabled;
        autoButton.setText(autoCaptureEnabled ? "Auto On" : "Auto Off");
        autoButton.setContentDescription(autoButton.getText());
        refreshUi();
    }

    private void undoLastTarget() {
        for (int i = targets.size() - 1; i >= 0; i--) {
            CaptureTarget target = targets.get(i);
            if (target.captured) {
                target.captured = false;
                target.weak = true;
                activeTargetIndex = i;
                refreshUi();
                Toast.makeText(this, "Target reopened for recapture", Toast.LENGTH_SHORT).show();
                return;
            }
        }
    }

    private void finishCapture() {
        int accepted = acceptedTargetCount();
        if (accepted < targets.size()) {
            new AlertDialog.Builder(this)
                    .setTitle("Partial capture")
                    .setMessage("This session is saved, but missing targets will be treated as partial/local until recaptured.")
                    .setNegativeButton("Keep capturing", null)
                    .setPositiveButton("Save partial", (dialog, which) -> finish())
                    .show();
            return;
        }
        Toast.makeText(this, "Capture saved for Spherify validation", Toast.LENGTH_LONG).show();
        finish();
    }

    private void showOpeningGuidance() {
        new AlertDialog.Builder(this)
                .setTitle("Guided still capture")
                .setMessage("Stand in one place, turn from the same center, align the reticle with each target, and hold steady. Weak areas reopen for recapture instead of being forced into the sphere.")
                .setPositiveButton("Start", null)
                .show();
    }

    /*
     * Function: ensureSession
     * Arguments: capturing records whether the session is actively receiving
     * frames.
     * Calls: SpherifyLibrary.ensureCaptureSession() and
     * updateCaptureSessionReadiness().
     * Flow: keep a durable capture-session record visible in the library even
     * before the first accepted source frame exists.
     */
    private void ensureSession(boolean capturing) {
        try {
            JSONObject readiness = new JSONObject();
            readiness.put("cameraPermission", ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
            readiness.put("arCoreTracking", false);
            readiness.put("rotationVectorAvailable", rotationVectorSensor != null);
            readiness.put("gyroRotationVectorStable", rotationVectorSensor != null && gyroscopeSensor != null);
            readiness.put("cameraIntrinsicsAvailable", cameraFacts.available);
            readiness.put("storageAvailable", true);
            readiness.put("phase4Method", "guided_dot_still_capture");
            library.ensureCaptureSession(sessionId, CaptureMode.HAND_HELD, readiness);
            library.updateCaptureSessionReadiness(sessionId, readiness, capturing);
        } catch (IOException | JSONException e) {
            Toast.makeText(this, "Session record failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /*
     * Function: exposureJsonFor
     * Arguments: imageFile is the written CameraX JPEG; metadata is the exact
     * Camera2 TotalCaptureResult matched by sensor timestamp.
     * Calls: BitmapFactory.decodeFile() in bounds mode and CaptureResult.get().
     * Flow: persist real per-frame exposure/focus/white-balance metadata and
     * derived intrinsics. Missing required sensor data rejects the frame.
     */
    private JSONObject exposureJsonFor(File imageFile, TotalCaptureResult metadata) throws JSONException, IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("captured image is unreadable");
        }
        if (!cameraFacts.available || cameraFacts.sensorWidthMm <= 0f || cameraFacts.sensorHeightMm <= 0f) {
            throw new IOException("camera physical sensor size is unavailable");
        }
        Long exposureTime = metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer sensitivity = metadata.get(CaptureResult.SENSOR_SENSITIVITY);
        Long timestamp = metadata.get(CaptureResult.SENSOR_TIMESTAMP);
        Float focal = metadata.get(CaptureResult.LENS_FOCAL_LENGTH);
        if (exposureTime == null || sensitivity == null || timestamp == null || focal == null || focal <= 0f) {
            throw new IOException("Camera2 capture metadata is incomplete");
        }
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        float sensorWidth = cameraFacts.sensorWidthMm;
        float sensorHeight = cameraFacts.sensorHeightMm;
        JSONObject json = new JSONObject();
        json.put("available", true);
        json.put("source", "camerax-camera2-total-capture-result");
        json.put("sensorExposureTimeNs", exposureTime);
        json.put("sensorSensitivityIso", sensitivity);
        putIfPresent(json, "sensorFrameDurationNs", metadata.get(CaptureResult.SENSOR_FRAME_DURATION));
        json.put("sensorTimestampNs", timestamp);
        putIfPresent(json, "lensAperture", metadata.get(CaptureResult.LENS_APERTURE));
        json.put("lensFocalLengthMm", focal);
        json.put("sensorPhysicalWidthMm", sensorWidth);
        json.put("sensorPhysicalHeightMm", sensorHeight);
        json.put("imageFocalLengthXPixels", focal / sensorWidth * width);
        json.put("imageFocalLengthYPixels", focal / sensorHeight * height);
        json.put("imagePrincipalPointXPixels", width * 0.5f);
        json.put("imagePrincipalPointYPixels", height * 0.5f);
        json.put("imageIntrinsicsWidth", width);
        json.put("imageIntrinsicsHeight", height);
        putIfPresent(json, "aeState", metadata.get(CaptureResult.CONTROL_AE_STATE));
        putIfPresent(json, "awbState", metadata.get(CaptureResult.CONTROL_AWB_STATE));
        putIfPresent(json, "afState", metadata.get(CaptureResult.CONTROL_AF_STATE));
        putIfPresent(json, "aeExposureCompensation", metadata.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION));
        putIfPresent(json, "aeMode", metadata.get(CaptureResult.CONTROL_AE_MODE));
        putIfPresent(json, "awbMode", metadata.get(CaptureResult.CONTROL_AWB_MODE));
        putIfPresent(json, "afMode", metadata.get(CaptureResult.CONTROL_AF_MODE));
        return json;
    }

    private TotalCaptureResult camera2MetadataFor(long sensorTimestamp) {
        synchronized (camera2ResultsByTimestamp) {
            TotalCaptureResult exact = camera2ResultsByTimestamp.remove(sensorTimestamp);
            if (exact != null) {
                return exact;
            }
            Map.Entry<Long, TotalCaptureResult> floor = camera2ResultsByTimestamp.floorEntry(sensorTimestamp);
            Map.Entry<Long, TotalCaptureResult> ceiling = camera2ResultsByTimestamp.ceilingEntry(sensorTimestamp);
            Map.Entry<Long, TotalCaptureResult> best = nearer(sensorTimestamp, floor, ceiling);
            if (best == null || Math.abs(best.getKey() - sensorTimestamp) > 20_000_000L) {
                return null;
            }
            camera2ResultsByTimestamp.remove(best.getKey());
            return best.getValue();
        }
    }

    private static Map.Entry<Long, TotalCaptureResult> nearer(
            long timestamp,
            Map.Entry<Long, TotalCaptureResult> first,
            Map.Entry<Long, TotalCaptureResult> second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return Math.abs(first.getKey() - timestamp) <= Math.abs(second.getKey() - timestamp) ? first : second;
    }

    private static void writeJpegImageProxy(ImageProxy image, File outputFile) throws IOException {
        if (image.getFormat() != ImageFormat.JPEG || image.getPlanes().length == 0) {
            throw new IOException("CameraX ImageCapture did not return a JPEG image");
        }
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            output.write(bytes);
        }
    }

    private static void putIfPresent(JSONObject json, String key, Object value) throws JSONException {
        if (value != null) {
            json.put(key, value);
        }
    }

    private String readLocationSummary() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "";
        }
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            return "";
        }
        Location best = null;
        for (String provider : manager.getProviders(true)) {
            try {
                Location location = manager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getTime() > best.getTime())) {
                    best = location;
                }
            } catch (SecurityException ignored) {
                return "";
            }
        }
        return best == null
                ? ""
                : String.format(Locale.US, "%.6f,%.6f", best.getLatitude(), best.getLongitude());
    }

    /*
     * Function: buildCaptureTargets
     * Arguments: none.
     * Calls: addRing().
     * Flow: create an overlap-rich, easy-to-follow target lattice. Rows near the
     * poles use fewer yaw samples because their spherical circumference is
     * smaller.
     */
    private void buildCaptureTargets() {
        targets.clear();
        addRing(0, 8);
        addRing(35, 8);
        addRing(-35, 8);
        addRing(65, 4);
        addRing(-65, 4);
        targets.add(new CaptureTarget(0, 85));
        targets.add(new CaptureTarget(0, -85));
    }

    private void addRing(int pitch, int count) {
        for (int i = 0; i < count; i++) {
            targets.add(new CaptureTarget(Math.round(i * 360f / count), pitch));
        }
    }

    private void updateActiveTarget() {
        if (targets.isEmpty()) {
            activeTargetIndex = -1;
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            if (!targets.get(i).captured) {
                activeTargetIndex = i;
                return;
            }
        }
        activeTargetIndex = -1;
    }

    private CaptureTarget activeTarget() {
        return activeTargetIndex >= 0 && activeTargetIndex < targets.size()
                ? targets.get(activeTargetIndex)
                : null;
    }

    private boolean isAligned(CaptureTarget target) {
        return Math.abs(signedHeadingDelta(target.yawDegrees, headingDegrees)) <= TARGET_YAW_TOLERANCE_DEGREES
                && Math.abs(target.pitchDegrees - pitchDegrees) <= TARGET_PITCH_TOLERANCE_DEGREES;
    }

    private boolean isMotionStable() {
        return hasGyro
                && Math.max(yawRateDegreesPerSecond, Math.max(pitchRateDegreesPerSecond, rollRateDegreesPerSecond))
                <= MAX_CAPTURE_RATE_DEGREES_PER_SECOND;
    }

    private int acceptedTargetCount() {
        int count = 0;
        for (CaptureTarget target : targets) {
            if (target.captured) {
                count++;
            }
        }
        return count;
    }

    private CameraFacts readBackCameraFacts() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null) {
            return CameraFacts.unavailable();
        }
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) {
                    continue;
                }
                SizeF sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                float[] focals = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float[] apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                return new CameraFacts(
                        sensor != null,
                        sensor == null ? 0f : sensor.getWidth(),
                        sensor == null ? 0f : sensor.getHeight(),
                        focals == null || focals.length == 0 ? 0f : focals[0],
                        apertures == null || apertures.length == 0 ? 0f : apertures[0],
                        id);
            }
        } catch (CameraAccessException | SecurityException ignored) {
            return CameraFacts.unavailable();
        }
        return CameraFacts.unavailable();
    }

    private static String newSessionId() {
        return new SimpleDateFormat("yyMMdd-HHmmss-SSS", Locale.US).format(new Date());
    }

    private static float normalizeDegrees(float degrees) {
        float normalized = degrees % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    private static float signedHeadingDelta(float target, float current) {
        float delta = (target - current + 540f) % 360f - 180f;
        return delta < -180f ? delta + 360f : delta;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static final class CameraFacts {
        final boolean available;
        final float sensorWidthMm;
        final float sensorHeightMm;
        final float focalLengthMm;
        final float aperture;
        final String cameraId;

        CameraFacts(boolean available, float sensorWidthMm, float sensorHeightMm, float focalLengthMm, float aperture) {
            this(available, sensorWidthMm, sensorHeightMm, focalLengthMm, aperture, "");
        }

        CameraFacts(boolean available, float sensorWidthMm, float sensorHeightMm, float focalLengthMm, float aperture, String cameraId) {
            this.available = available;
            this.sensorWidthMm = sensorWidthMm;
            this.sensorHeightMm = sensorHeightMm;
            this.focalLengthMm = focalLengthMm;
            this.aperture = aperture;
            this.cameraId = cameraId == null ? "" : cameraId;
        }

        static CameraFacts unavailable() {
            return new CameraFacts(false, 0f, 0f, 0f, 0f);
        }
    }

    private static final class CaptureTarget {
        final int yawDegrees;
        final int pitchDegrees;
        boolean captured;
        boolean weak;

        CaptureTarget(int yawDegrees, int pitchDegrees) {
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
        }
    }

    /*
     * Class: TargetGuideView
     * Educational overview:
     * Draws the capture instrument: a center reticle, one active target, a lock
     * progress ring, and a compact coverage map. The view deliberately avoids
     * diagnostics so the user can focus on moving, aligning, and holding steady.
     */
    private static final class TargetGuideView extends View {
        private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private List<CaptureTarget> targets = new ArrayList<>();
        private int activeTargetIndex = -1;
        private float headingDegrees;
        private float pitchDegrees;
        private boolean aligned;
        private boolean stable;
        private float lockProgress;

        TargetGuideView(android.content.Context context) {
            super(context);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(4f);
            reticlePaint.setStyle(Paint.Style.STROKE);
            reticlePaint.setStrokeWidth(4f);
            panelPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(18f);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setContentDescription("PhotoSphere capture guide");
        }

        void setState(
                List<CaptureTarget> targets,
                int activeTargetIndex,
                float headingDegrees,
                float pitchDegrees,
                boolean aligned,
                boolean stable,
                float lockProgress) {
            this.targets = targets;
            this.activeTargetIndex = activeTargetIndex;
            this.headingDegrees = headingDegrees;
            this.pitchDegrees = pitchDegrees;
            this.aligned = aligned;
            this.stable = stable;
            this.lockProgress = lockProgress;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawActiveTarget(canvas);
            drawReticle(canvas);
            drawCoverage(canvas);
        }

        private void drawActiveTarget(Canvas canvas) {
            if (activeTargetIndex < 0 || activeTargetIndex >= targets.size()) {
                return;
            }
            CaptureTarget target = targets.get(activeTargetIndex);
            float yawDelta = signedHeadingDelta(target.yawDegrees, headingDegrees);
            float pitchDelta = target.pitchDegrees - pitchDegrees;
            float x = getWidth() * 0.5f + yawDelta / 70f * getWidth() * 0.5f;
            float y = getHeight() * 0.5f - pitchDelta / 55f * getHeight() * 0.45f;
            targetPaint.setColor(target.weak ? 0xFFF97316 : aligned ? 0xFFFACC15 : 0xFFE2E8F0);
            canvas.drawCircle(x, y, aligned ? 26f : 18f, targetPaint);
            if (aligned) {
                rect.set(x - 36f, y - 36f, x + 36f, y + 36f);
                targetPaint.setColor(stable ? 0xFF22C55E : 0xFFFACC15);
                canvas.drawArc(rect, -90f, 360f * lockProgress, false, targetPaint);
            }
        }

        private void drawReticle(Canvas canvas) {
            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            reticlePaint.setColor(stable ? 0xFF22C55E : aligned ? 0xFFFACC15 : 0xFFFFFFFF);
            canvas.drawCircle(centerX, centerY, 34f, reticlePaint);
            canvas.drawLine(centerX - 48f, centerY, centerX - 18f, centerY, reticlePaint);
            canvas.drawLine(centerX + 18f, centerY, centerX + 48f, centerY, reticlePaint);
            canvas.drawLine(centerX, centerY - 48f, centerX, centerY - 18f, reticlePaint);
            canvas.drawLine(centerX, centerY + 18f, centerX, centerY + 48f, reticlePaint);
            textPaint.setColor(0xFFFFFFFF);
            canvas.drawText(stable ? "Hold" : aligned ? "Steady" : "Aim", centerX, centerY + 78f, textPaint);
        }

        private void drawCoverage(Canvas canvas) {
            float left = 18f;
            float right = getWidth() - 18f;
            float bottom = getHeight() - 34f;
            float top = bottom - 72f;
            panelPaint.setColor(0xCC05070A);
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, 8f, 8f, panelPaint);
            if (targets.isEmpty()) {
                return;
            }
            float gap = 3f;
            float cell = Math.max(6f, (right - left - 28f - gap * (targets.size() - 1)) / targets.size());
            float x = left + 14f;
            float y = top + 42f;
            panelPaint.setColor(0xFF334155);
            for (int i = 0; i < targets.size(); i++) {
                CaptureTarget target = targets.get(i);
                if (target.captured) {
                    panelPaint.setColor(0xFF22C55E);
                } else if (target.weak) {
                    panelPaint.setColor(0xFFF97316);
                } else if (i == activeTargetIndex) {
                    panelPaint.setColor(0xFFFACC15);
                } else {
                    panelPaint.setColor(0xFF334155);
                }
                rect.set(x, y, x + cell, y + 14f);
                canvas.drawRoundRect(rect, 3f, 3f, panelPaint);
                x += cell + gap;
            }
        }
    }
}
