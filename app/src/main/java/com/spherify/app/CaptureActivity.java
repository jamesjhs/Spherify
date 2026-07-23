/*
 * CaptureActivity.java
 *
 * Educational overview:
 * CaptureActivity is the Phase 3 capture shell with Phase 4 guided-capture
 * scaffolding. It shows a live ARCore camera preview, captures draft JPEG frames,
 * reads available motion/orientation sensors, displays optional diagnostics, and
 * overlays a simple yaw/pitch target grid so a user can begin collecting sphere
 * coverage deliberately. It does not stitch a full Photo Sphere yet; instead it
 * creates draft frame files and draft metadata that later phases can use for
 * guided capture, alignment, and review.
 *
 * Data flow:
 * User taps Capture in MainActivity -> MainActivity opens this Activity after
 * camera permission -> ARCore streams preview pixels into GLSurfaceView -> user
 * taps Capture Frame -> SpherifyLibrary creates a draft file -> the latest
 * ARCore frame JPEG is written -> readLocationSummary() optionally adds last-known location ->
 * SpherifyLibrary appends draft metadata to drafts.json. In parallel, Android's
 * SensorManager sends accelerometer/gyroscope/magnetometer/rotation-vector
 * events to onSensorChanged(), which refreshes both the diagnostic TextView and
 * the guided target overlay.
 *
 * External files/functions:
 * Uses ARCore session camera frames for preview, guidance pose, and capture.
 * Uses Android SensorManager for device motion/orientation readiness.
 * Uses SpherifyLibrary for local draft frame paths and metadata writes.
 * Uses LocationManager for optional coarse/fine last-known location summaries.
 *
 * Key variables:
 * previewView: ARCore camera frame preview shown inside the preview frame.
 * statusText: top status label for camera/capture progress.
 * sensorOverlayText/sensorOverlayButton: overlay UI and its toggle button.
 * library: local storage helper for draft files and metadata.
 * sensorManager and sensor fields: Android motion/orientation sensor handles.
 * sensorOverlayVisible: current overlay visibility state.
 * compassAccuracy: most recent magnetometer calibration status.
 * guideView: Phase 4 target grid and reticle overlay.
 * sessionId: persistent id for this interrupted/resumed guided capture draft.
 * *Reading strings: latest formatted sensor values or "waiting".
 */
package com.spherify.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.YuvImage;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Rational;
import android.util.SizeF;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.ImageMetadata;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.MetadataNotFoundException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class CaptureActivity extends ComponentActivity implements SensorEventListener {
    private static final String TAG = "SpherifyCapture";
    private static final String PREFS = "spherify_capture";
    private static final String PREF_ACTIVE_SESSION_ID = "activeSessionId";
    private static final String PREF_CAPTURE_PROFILE = "captureProfile";
    private static final String CAPTURE_PROFILE_HANDHELD = "handheld";
    private static final String CAPTURE_PROFILE_FIXED_GIMBAL = "fixed_gimbal";
    private static final long REQUIRED_COMPASS_HIGH_ACCURACY_MS = 2000L;
    private static final long REQUIRED_CALIBRATION_DURATION_MS = 8000L;
    private static final long REQUIRED_HEADING_STABILITY_MS = 1000L;
    private static final long REQUIRED_TARGET_LOCK_MS = 1000L;
    private static final int REQUIRED_HEADING_BINS = 8;
    private static final int HORIZON_REFERENCE_BIN_COUNT = 16;
    private static final int REQUIRED_HORIZON_REFERENCE_BINS = 14;
    private static final int HORIZON_REFERENCE_SAMPLE_WIDTH = 96;
    private static final int HORIZON_REFERENCE_SAMPLE_HEIGHT = 144;
    private static final float MAX_HORIZON_SWEEP_PITCH_DEGREES = 18f;
    private static final float MAX_SWEEP_CLOSE_YAW_DELTA_DEGREES = 5f;
    private static final float MAX_SWEEP_CLOSE_PITCH_DELTA_DEGREES = 5f;
    private static final long SWEEP_SPEED_SAMPLE_MIN_MS = 180L;
    private static final float MIN_SWEEP_YAW_RATE_DEGREES_PER_SECOND = 8f;
    private static final float MAX_SWEEP_YAW_RATE_DEGREES_PER_SECOND = 50f;
    private static final float TILT_WARNING_START_DEGREES = 2f;
    private static final float TILT_WARNING_FULL_DEGREES = 12f;
    private static final float HORIZON_REFERENCE_VIEW_DEGREES = 82f;
    private static final int SWEEP_CAPTURE_BIN_COUNT = 16;
    private static final int REQUIRED_SWEEP_CAPTURE_BINS = 14;
    private static final int[] SWEEP_LAYER_PITCH_DEGREES = {0, 30, -30, 65, -65};
    private static final int SWEEP_LAYER_HIGH_UPPER_INDEX = 3;
    private static final int SWEEP_LAYER_HIGH_LOWER_INDEX = 4;
    private static final int[] HIGH_PITCH_RING_YAW_DEGREES = {0, 45, 90, 135, 180, 225, 270, 315};
    private static final int[] HIGH_PITCH_RING_SWEEP_BINS = {0, 2, 4, 6, 8, 10, 12, 14};
    private static final float MAX_HIGH_PITCH_RING_YAW_DELTA_DEGREES = 14f;
    private static final float MAX_SWEEP_LAYER_PITCH_DELTA_DEGREES = 12f;
    private static final int[] POLAR_ROLL_SLOT_TARGETS = {0};
    private static final int[] POLAR_ROLL_SLOT_SWEEP_BINS = {12};
    private static final float COMPASS_SCREEN_FLIP_PITCH_DEGREES = 30f;
    private static final long MIN_SWEEP_CAPTURE_INTERVAL_MS = 850L;
    private static final long REQUIRED_KEYFRAME_STILL_MS = 900L;
    private static final long CAPTURE_METADATA_RETRY_TIMEOUT_MS = 2600L;
    private static final long CAPTURE_METADATA_RETRY_INTERVAL_MS = 120L;
    private static final long CAPTURE_METADATA_DEFAULT_MATCH_TOLERANCE_NS = 40_000_000L;
    private static final long CAPTURE_METADATA_MIN_MATCH_TOLERANCE_NS = 8_000_000L;
    private static final long CAPTURE_METADATA_MAX_MATCH_TOLERANCE_NS = 50_000_000L;
    private static final int CAPTURE_METADATA_BUFFER_LIMIT = 24;
    private static final float MAX_STABLE_HEADING_DELTA_DEGREES = 4f;
    private static final float MAX_KEYFRAME_YAW_RATE_DEGREES_PER_SECOND = 3.0f;
    private static final float MAX_KEYFRAME_PITCH_RATE_DEGREES_PER_SECOND = 2.5f;
    private static final float MAX_KEYFRAME_ROLL_RATE_DEGREES_PER_SECOND = 3.5f;
    private static final float MAX_TARGET_YAW_DELTA_DEGREES = 10f;
    private static final float MAX_TARGET_PITCH_DELTA_DEGREES = 9f;
    private static final float REQUIRED_TILT_VARIATION = 0.55f;
    private static final int HEADING_BIN_COUNT = 12;
    private static final float SENSOR_LOW_PASS_ALPHA = 0.18f;
    private static final float HEADING_SMOOTHING_ALPHA = 0.14f;
    private static final float GUIDE_HEADING_SMOOTHING_ALPHA = 0.08f;
    private static final float GUIDE_PITCH_SMOOTHING_ALPHA = 0.10f;

    private GLSurfaceView previewView;
    private ArCameraRenderer arRenderer;
    private TextView statusText;
    private TextView sensorOverlayText;
    private TextView compassStatusText;
    private CompassNeedleView compassNeedleView;
    private CalibrationProgressView calibrationProgressView;
    private TargetGuideView guideView;
    private Button captureButton;
    private Button sensorOverlayButton;
    private Button calibrationButton;
    private Button pauseButton;
    private Button autoCaptureButton;
    private Button captureProfileButton;
    private SpherifyLibrary library;
    private SharedPreferences capturePrefs;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private Sensor magnetometer;
    private Sensor rotationVector;
    private boolean sensorOverlayVisible;
    private int compassAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private String accelerometerReading = "waiting";
    private String gyroscopeReading = "waiting";
    private String magnetometerReading = "waiting";
    private String rotationVectorReading = "waiting";
    private final float[] accelerometerValues = new float[3];
    private final float[] magnetometerValues = new float[3];
    private final float[] filteredAccelerometerValues = new float[3];
    private final float[] filteredMagnetometerValues = new float[3];
    private final float[] rotationMatrix = new float[9];
    private final float[] rotationVectorMatrix = new float[9];
    private final float[] orientationValues = new float[3];
    private final boolean[] headingBins = new boolean[HEADING_BIN_COUNT];
    private boolean hasAccelerometerReading;
    private boolean hasMagnetometerReading;
    private boolean hasFilteredAccelerometerReading;
    private boolean hasFilteredMagnetometerReading;
    private boolean hasHeadingReading;
    private boolean hasSmoothedHeadingReading;
    private boolean hasPitchRollReading;
    private boolean calibrationStarted;
    private boolean compassCalibrationComplete;
    private boolean arCoreReady;
    private boolean horizonSweepStarted;
    private boolean horizonSweepComplete;
    private boolean horizonSweepAwaitingClose;
    private boolean arCoreInstallRequested;
    private boolean arCoreUnsupportedShown;
    private boolean arSessionRunning;
    private boolean capturePaused;
    private boolean autoCaptureEnabled = true;
    private boolean captureInProgress;
    private boolean sweepLayerPaintingActive;
    private boolean polarCaptureInfoShown;
    private boolean highPitchRingInfoShown;
    private boolean guideRefreshPosted;
    private long calibrationStartedAt;
    private long highAccuracyStartedAt;
    private long stableHeadingStartedAt;
    private long targetAlignedStartedAt;
    private long lastAutoCaptureAt;
    private float previewDragStartX;
    private boolean previewDragActive;
    private boolean previewDragMoved;
    private float compassHeadingDegrees;
    private float rawCompassHeadingDegrees;
    private float lastCompassHeadingDegrees;
    private float pitchDegrees;
    private float rollDegrees;
    private float guideHeadingDegrees;
    private float guidePitchDegrees;
    private float horizonStartHeadingDegrees;
    private float horizonStartPitchDegrees;
    private float sweepLayerStartHeadingDegrees;
    private float[] latestArPoseMatrix = new float[16];
    private String sessionId;
    private int activeTargetIndex;
    private int lastCapturedTargetIndex = -1;
    private int currentSweepLayerIndex;
    private int lastCapturedSweepLayerIndex = -1;
    private int lastCapturedSweepBin = -1;
    private boolean hasGuideHeadingReading;
    private boolean hasGuidePitchReading;
    private final CaptureTarget[] captureTargets = buildCaptureTargets();
    private final boolean[] horizonReferenceBins = new boolean[HORIZON_REFERENCE_BIN_COUNT];
    private final Bitmap[] horizonReferenceFrames = new Bitmap[HORIZON_REFERENCE_BIN_COUNT];
    private final boolean[][] sweepCaptureBins =
            new boolean[SWEEP_LAYER_PITCH_DEGREES.length][SWEEP_CAPTURE_BIN_COUNT];
    private final Bitmap[][] sweepCapturePreviewFrames =
            new Bitmap[SWEEP_LAYER_PITCH_DEGREES.length][SWEEP_CAPTURE_BIN_COUNT];
    private Bitmap sweepLayerPreviewPanorama;
    private Bitmap paintedPhotospherePreview;
    private Bitmap horizonReferencePanorama;
    private long lastSweepSpeedAt;
    private float lastSweepSpeedHeadingDegrees;
    private float sweepYawRateDegreesPerSecond;
    private String sweepSpeedMessage = "";
    private long lastKeyframeMotionAt;
    private long keyframeStillStartedAt;
    private float lastKeyframeHeadingDegrees;
    private float lastKeyframePitchDegrees;
    private float lastKeyframeRollDegrees;
    private float keyframeYawRateDegreesPerSecond;
    private float keyframePitchRateDegreesPerSecond;
    private float keyframeRollRateDegreesPerSecond;
    private final Object arFrameLock = new Object();
    private Session arSession;
    private SharedCamera sharedCamera;
    private int arCameraTextureId;
    private byte[] latestArJpeg;
    private byte[] latestCaptureReadyJpeg;
    private String latestCaptureReadyMetadataJson;
    private String latestExposureMetadataJson;
    private String latestCaptureMetadataBlocker = "Capture waiting for complete camera metadata";
    private long latestMetadataAttemptAt;
    private long latestCaptureReadyAt;
    private int captureMetadataRetryAttempts;
    private long latestImageTimestampNs;
    private long latestMatchedMetadataTimestampNs;
    private long latestTimestampMatchDeltaNs;
    private long latestCaptureDebugLogAt;
    private long arFrameUpdateCount;
    private long arMetadataSuccessCount;
    private long arMetadataNotReadyCount;
    private long arMetadataUnavailableCount;
    private long arImageAcquiredCount;
    private long arImageNotReadyCount;
    private long arFrameThrowableCount;
    private long latestArFrameTimestampNs;
    private long latestArMetadataTimestampNs;
    private long latestArDebugLogAt;
    private String latestArTrackingState = "unknown";
    private String latestArMetadataFailure = "";
    private String latestArMetadataFieldSummary = "";
    private final LinkedHashMap<Long, String> captureMetadataByTimestamp = new LinkedHashMap<>();
    private String captureProfile = CAPTURE_PROFILE_HANDHELD;
    private Bitmap latestArPreviewBitmap;
    private SizeF cameraSensorPhysicalSize;
    private float minAccelX = Float.MAX_VALUE;
    private float maxAccelX = -Float.MAX_VALUE;
    private float minAccelY = Float.MAX_VALUE;
    private float maxAccelY = -Float.MAX_VALUE;
    private float minAccelZ = Float.MAX_VALUE;
    private float maxAccelZ = -Float.MAX_VALUE;
    // Pre-allocated buffers to reduce per-frame allocation pressure
    private byte[] yuvNv21Buffer;
    private java.io.ByteArrayOutputStream yuvJpegStream;
    private int[] featherPixelBuffer;
    private final CameraCaptureSession.CaptureCallback camera2CaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        CameraCaptureSession session,
                        android.hardware.camera2.CaptureRequest request,
                        TotalCaptureResult result) {
                    rememberCamera2CaptureResult(result);
                }
            };
    private int[] featherOriginalBuffer;
    private final Paint bitmapDrawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /*
     * Function: onCreate
     * Arguments: savedInstanceState is Android lifecycle state; this Activity
     * currently builds fresh UI and does not restore custom state from it.
     * Calls: SpherifyLibrary constructor, SensorManager.getDefaultSensor(),
     * Android view constructors, toggle/capture click handlers, setContentView(),
     * requestApplyInsets(), and startArCoreCapture().
     * Flow: initialize storage and sensors, build a vertical layout with status,
     * camera preview, overlay, and controls, then start the ARCore preview.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            library = new SpherifyLibrary(this);
        } catch (IOException e) {
            throw new IllegalStateException("could not open capture library", e);
        }
        capturePrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        captureProfile = normalizeCaptureProfile(capturePrefs.getString(PREF_CAPTURE_PROFILE, CAPTURE_PROFILE_HANDHELD));
        sessionId = capturePrefs.getString(PREF_ACTIVE_SESSION_ID, "");
        if (sessionId.isEmpty()) {
            sessionId = newSessionId();
            capturePrefs.edit().putString(PREF_ACTIVE_SESSION_ID, sessionId).apply();
        }
        cameraSensorPhysicalSize = readBackCameraSensorPhysicalSize();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF05070A);

        statusText = new TextView(this);
        statusText.setTextColor(0xFFE2E8F0);
        statusText.setTextSize(14);
        statusText.setPadding(16, 14, 16, 10);
        statusText.setText("Horizon sweep needed");
        root.addView(statusText);

        previewView = new GLSurfaceView(this);
        previewView.setEGLContextClientVersion(2);
        previewView.setPreserveEGLContextOnPause(true);
        arRenderer = new ArCameraRenderer();
        previewView.setRenderer(arRenderer);
        previewView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        compassNeedleView = new CompassNeedleView(this);
        FrameLayout.LayoutParams needleParams = new FrameLayout.LayoutParams(96, 96, Gravity.TOP | Gravity.RIGHT);
        needleParams.setMargins(0, 14, 14, 0);
        previewFrame.addView(compassNeedleView, needleParams);

        compassStatusText = new TextView(this);
        compassStatusText.setTextColor(0xFFFFFFFF);
        compassStatusText.setTextSize(12);
        compassStatusText.setGravity(Gravity.CENTER);
        compassStatusText.setBackgroundColor(0x9905070A);
        compassStatusText.setPadding(8, 5, 8, 5);
        FrameLayout.LayoutParams compassStatusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        compassStatusParams.setMargins(0, 108, 12, 0);
        previewFrame.addView(compassStatusText, compassStatusParams);

        calibrationProgressView = new CalibrationProgressView(this);
        FrameLayout.LayoutParams calibrationParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                156,
                Gravity.BOTTOM);
        calibrationParams.setMargins(12, 0, 12, 14);
        previewFrame.addView(calibrationProgressView, calibrationParams);

        guideView = new TargetGuideView(this);
        guideView.setOnTouchListener((view, event) -> handlePreviewTouch(event));
        previewFrame.addView(guideView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        calibrationButton = makeButton("Start");
        calibrationButton.setOnClickListener(v -> handleHorizonSweepButton());
        FrameLayout.LayoutParams sweepButtonParams = new FrameLayout.LayoutParams(
                240,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT);
        sweepButtonParams.setMargins(0, 0, 14, 182);
        previewFrame.addView(calibrationButton, sweepButtonParams);

        sensorOverlayText = new TextView(this);
        sensorOverlayText.setTextColor(0xFFFFFFFF);
        sensorOverlayText.setTextSize(12);
        sensorOverlayText.setLineSpacing(2, 1.0f);
        sensorOverlayText.setBackgroundColor(0xCC05070A);
        sensorOverlayText.setPadding(14, 12, 14, 12);
        sensorOverlayText.setVisibility(TextView.GONE);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        overlayParams.setMargins(12, 12, 12, 0);
        previewFrame.addView(sensorOverlayText, overlayParams);
        updateSensorOverlay();

        root.addView(previewFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(10, 10, 10, 14);

        sensorOverlayButton = makeButton("Show Data");
        sensorOverlayButton.setOnClickListener(v -> toggleSensorOverlay());
        captureProfileButton = makeButton(captureProfileLabel());
        captureProfileButton.setOnClickListener(v -> chooseCaptureProfile());
        Button skipPolesButton = makeButton("Skip Poles");
        skipPolesButton.setOnClickListener(v -> skipHighAndLowLayers());
        Button cancelButton = makeButton("Cancel");
        cancelButton.setOnClickListener(v -> cancelCaptureSession());
        root.addView(controls);

        LinearLayout secondaryControls = new LinearLayout(this);
        secondaryControls.setGravity(Gravity.CENTER);
        secondaryControls.setPadding(10, 0, 10, 14);
        secondaryControls.addView(sensorOverlayButton);
        secondaryControls.addView(captureProfileButton);
        secondaryControls.addView(skipPolesButton);
        secondaryControls.addView(cancelButton);
        root.addView(secondaryControls);

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
        verifyArCoreSupport();
        startArCoreCapture();
        updateCompassUi();
        root.post(this::showHorizonSweepInstructions);
    }

    /*
     * Function: onResume
     * Arguments: none beyond Android lifecycle dispatch.
     * Calls: super.onResume() and registerSensors().
     * Flow: when the screen becomes active, subscribe to sensor updates so the
     * overlay can show live readings.
     */
    @Override
    protected void onResume() {
        super.onResume();
        verifyArCoreSupport();
        previewView.onResume();
        resumeArSession();
        registerSensors();
    }

    /*
     * Function: onPause
     * Arguments: none beyond Android lifecycle dispatch.
     * Calls: unregisterSensors() and super.onPause().
     * Flow: stop sensor callbacks while the Activity is not foregrounded to
     * avoid battery drain and unnecessary UI updates.
     */
    @Override
    protected void onPause() {
        unregisterSensors();
        pauseArSession();
        previewView.onPause();
        super.onPause();
    }

    /*
     * Function: onDestroy
     * Arguments: none beyond Android lifecycle dispatch.
     * Calls: super.onDestroy() and Session.close().
     * Flow: release ARCore native resources and frame callbacks when the
     * Activity is ending.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        synchronized (arFrameLock) {
            if (latestArPreviewBitmap != null) {
                latestArPreviewBitmap.recycle();
                latestArPreviewBitmap = null;
            }
        }
        // Recycle all horizon-reference and sweep-preview bitmaps to avoid
        // retaining up to ~88 MB of bitmap memory after the Activity exits.
        for (int i = 0; i < horizonReferenceFrames.length; i++) {
            if (horizonReferenceFrames[i] != null) {
                horizonReferenceFrames[i].recycle();
                horizonReferenceFrames[i] = null;
            }
        }
        for (int layer = 0; layer < sweepCapturePreviewFrames.length; layer++) {
            for (int bin = 0; bin < sweepCapturePreviewFrames[layer].length; bin++) {
                if (sweepCapturePreviewFrames[layer][bin] != null) {
                    sweepCapturePreviewFrames[layer][bin].recycle();
                    sweepCapturePreviewFrames[layer][bin] = null;
                }
            }
        }
        if (horizonReferencePanorama != null) {
            horizonReferencePanorama.recycle();
            horizonReferencePanorama = null;
        }
        if (sweepLayerPreviewPanorama != null) {
            sweepLayerPreviewPanorama.recycle();
            sweepLayerPreviewPanorama = null;
        }
        if (paintedPhotospherePreview != null) {
            paintedPhotospherePreview.recycle();
            paintedPhotospherePreview = null;
        }
        if (arSession != null) {
            arSession.close();
            arSession = null;
        }
    }

    /*
     * Function: makeButton
     * Arguments: text is the label shown on the button.
     * Calls: Button setters and LinearLayout.LayoutParams.
     * Flow: create a consistently styled equal-width control button for the
     * capture bar.
     */
    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        params.setMargins(5, 0, 5, 0);
        button.setLayoutParams(params);
        return button;
    }

    /*
     * Function: toggleSensorOverlay
     * Arguments: none; invoked by sensorOverlayButton's click listener.
     * Calls: TextView.setVisibility(), Button.setText(), and updateSensorOverlay().
     * Flow: flip the boolean state, show/hide the overlay, update the button
     * label, and redraw the latest sensor text.
     */
    private void toggleSensorOverlay() {
        sensorOverlayVisible = !sensorOverlayVisible;
        sensorOverlayText.setVisibility(sensorOverlayVisible ? TextView.VISIBLE : TextView.GONE);
        sensorOverlayButton.setText(sensorOverlayVisible ? "Hide Data" : "Show Data");
        updateSensorOverlay();
    }

    /*
     * Function: verifyArCoreSupport
     * Arguments: none.
     * Calls: ArCoreApk.checkAvailability(), ArCoreApk.requestInstall(),
     * updateCompassUi(), and showArCoreUnsupportedMessage().
     * Flow: treat ARCore as the required capture backend. Supported devices may
     * prompt for Google Play Services for AR; unsupported devices are blocked
     * with a clear message instead of falling back to raw compass capture.
     */
    private void verifyArCoreSupport() {
        ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
        if (availability.isTransient()) {
            previewView.postDelayed(this::verifyArCoreSupport, 250L);
            return;
        }
        if (!availability.isSupported()) {
            arCoreReady = false;
            showArCoreUnsupportedMessage();
            updateCompassUi();
            return;
        }

        try {
            ArCoreApk.InstallStatus installStatus = ArCoreApk.getInstance().requestInstall(
                    this,
                    !arCoreInstallRequested);
            arCoreInstallRequested = true;
            arCoreReady = installStatus == ArCoreApk.InstallStatus.INSTALLED;
        } catch (UnavailableUserDeclinedInstallationException e) {
            arCoreReady = false;
            showArCoreUnsupportedMessage();
        } catch (UnavailableException e) {
            arCoreReady = false;
            showArCoreUnsupportedMessage();
        }
        runOnUiThread(() -> {
            updateSensorOverlay();
            updateCompassUi();
        });
    }

    /*
     * Function: showArCoreUnsupportedMessage
     * Arguments: none.
     * Calls: AlertDialog.Builder and View setters.
     * Flow: explain that ARCore support is unavailable and keep the capture
     * workflow locked until the device can provide AR tracking.
     */
    private void showArCoreUnsupportedMessage() {
        if (statusText != null) {
            statusText.setText("ARCore unavailable");
        }
        if (captureButton != null) {
            captureButton.setEnabled(false);
        }
        if (arCoreUnsupportedShown || isFinishing()) {
            return;
        }
        arCoreUnsupportedShown = true;
        new AlertDialog.Builder(this)
                .setTitle("ARCore required")
                .setMessage("This capture mode requires Google Play Services for AR on an ARCore-supported device.")
                .setPositiveButton("Close", null)
                .show();
    }

    /*
     * Function: registerSensors
     * Arguments: none.
     * Calls: registerSensor() for accelerometer, gyroscope, magnetometer, and
     * rotation vector, then updateSensorOverlay().
     * Flow: subscribe to every available sensor; missing sensors are skipped but
     * still reported as missing in the overlay.
     */
    private void registerSensors() {
        if (sensorManager == null) {
            return;
        }
        registerSensor(accelerometer);
        registerSensor(gyroscope);
        registerSensor(magnetometer);
        registerSensor(rotationVector);
        updateSensorOverlay();
    }

    /*
     * Function: registerSensor
     * Arguments: sensor is one Android Sensor handle, or null if unavailable.
     * Calls: SensorManager.registerListener().
     * Flow: if present, register this Activity as a listener at UI-friendly
     * sampling speed so readings update without overwhelming the main thread.
     */
    private void registerSensor(Sensor sensor) {
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    /*
     * Function: unregisterSensors
     * Arguments: none.
     * Calls: SensorManager.unregisterListener().
     * Flow: remove this Activity from all sensor callback lists at once.
     */
    private void unregisterSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    /*
     * Function: onSensorChanged
     * Arguments: event contains the sensor type and latest values.
     * Calls: formatVector() and updateSensorOverlay().
     * Flow: route the event by sensor type, store a formatted reading string,
     * and refresh the overlay text.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, accelerometerValues, 0, accelerometerValues.length);
                hasFilteredAccelerometerReading = lowPassVector(
                        event.values,
                        filteredAccelerometerValues,
                        hasFilteredAccelerometerReading);
                hasAccelerometerReading = true;
                recordTiltCoverage();
                accelerometerReading = formatVector(event.values, 3);
                break;
            case Sensor.TYPE_GYROSCOPE:
                gyroscopeReading = formatVector(event.values, 3);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(event.values, 0, magnetometerValues, 0, magnetometerValues.length);
                hasFilteredMagnetometerReading = lowPassVector(
                        event.values,
                        filteredMagnetometerValues,
                        hasFilteredMagnetometerReading);
                hasMagnetometerReading = true;
                magnetometerReading = formatVector(event.values, 3);
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                rotationVectorReading = formatVector(event.values, Math.min(event.values.length, 4));
                updateCompassHeadingFromRotationVector(event.values);
                break;
            default:
                return;
        }
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            updateCompassHeading();
        }
        updateSensorOverlay();
        updateCompassUi();
    }

    /*
     * Function: onAccuracyChanged
     * Arguments: sensor identifies which sensor changed; accuracy is Android's
     * calibration/readiness status constant.
     * Calls: updateSensorOverlay().
     * Flow: when the magnetometer accuracy changes, save it as compassAccuracy
     * so compassCalibrationStatus() can explain readiness to the user.
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor != null && sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            compassAccuracy = accuracy;
            if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH) {
                if (highAccuracyStartedAt == 0L) {
                    highAccuracyStartedAt = System.currentTimeMillis();
                }
            } else {
                highAccuracyStartedAt = 0L;
            }
            updateSensorOverlay();
            updateCompassUi();
        }
    }

    /*
     * Function: updateSensorOverlay
     * Arguments: none.
     * Calls: sensorLine(), compassCalibrationStatus(), and TextView.setText().
     * Flow: compose the complete sensor readiness/calibration display from the
     * latest stored readings and write it into the overlay TextView.
     */
    private void updateSensorOverlay() {
        if (!isOnMainThread()) {
            runOnUiThread(this::updateSensorOverlay);
            return;
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (sensorOverlayText == null) {
            return;
        }
        sensorOverlayText.setText("Capture data\n"
                + "Capture packet: " + capturePacketSummary() + "\n"
                + "Blocker: " + latestCaptureBlockerSummary() + "\n"
                + "Metadata age: " + captureMetadataAgeSummary() + "\n"
                + "Retry attempts: " + captureMetadataRetryAttempts + "\n"
                + "ARCore: " + (arCoreReady ? "ready" : "required") + "\n"
                + "Accelerometer: " + sensorLine(accelerometer, accelerometerReading) + "\n"
                + "Gyroscope: " + sensorLine(gyroscope, gyroscopeReading) + "\n"
                + "Compass/magnetometer: " + sensorLine(magnetometer, magnetometerReading) + "\n"
                + "Rotation vector: " + sensorLine(rotationVector, rotationVectorReading) + "\n"
                + "Guide orientation: " + guideOrientationSummary() + "\n"
                + "Horizon reference: " + horizonReferenceProgressText() + "\n"
                + "Compass fallback: " + compassCalibrationStatus() + "\n\n"
                + "Required capture metadata\n"
                + captureMetadataSummary());
    }

    /*
     * Function: sensorLine
     * Arguments: sensor is the hardware handle; reading is the latest formatted
     * value string.
     * Calls: no external functions.
     * Flow: return "missing" when the device lacks the sensor, otherwise pair
     * the ready state with the most recent reading.
     */
    private String sensorLine(Sensor sensor, String reading) {
        if (sensor == null) {
            return "missing";
        }
        return "ready, " + reading;
    }

    private String capturePacketSummary() {
        synchronized (arFrameLock) {
            if (latestCaptureReadyJpeg != null && latestCaptureReadyMetadataJson != null) {
                return "ready";
            }
            if (latestArJpeg != null) {
                return "preview only";
            }
            return "waiting for ARCore image";
        }
    }

    private String latestCaptureBlockerSummary() {
        synchronized (arFrameLock) {
            return latestCaptureMetadataBlocker == null ? "none" : latestCaptureMetadataBlocker;
        }
    }

    private String captureMetadataAgeSummary() {
        synchronized (arFrameLock) {
            long now = System.currentTimeMillis();
            String attemptAge = latestMetadataAttemptAt <= 0L ? "never" : (now - latestMetadataAttemptAt) + " ms";
            String readyAge = latestCaptureReadyAt <= 0L ? "never" : (now - latestCaptureReadyAt) + " ms";
            return "attempt " + attemptAge
                    + ", ready " + readyAge
                    + ", image ts " + latestImageTimestampNs
                    + ", matched ts " + latestMatchedMetadataTimestampNs
                    + ", delta " + latestTimestampMatchDeltaNs + " ns"
                    + ", buffer " + captureMetadataByTimestamp.size()
                    + "\nAR frames " + arFrameUpdateCount
                    + ", images " + arImageAcquiredCount + "/" + arImageNotReadyCount
                    + ", metadata ok/not-ready/fail "
                    + arMetadataSuccessCount + "/" + arMetadataNotReadyCount + "/" + arMetadataUnavailableCount
                    + "\nAR frame ts " + latestArFrameTimestampNs
                    + ", AR metadata ts " + latestArMetadataTimestampNs
                    + ", tracking " + latestArTrackingState
                    + "\nMetadata fields: " + safeText(latestArMetadataFieldSummary, "")
                    + "\nMetadata failure: " + safeText(latestArMetadataFailure, "none");
        }
    }

    private String captureMetadataSummary() {
        String exposureJson;
        synchronized (arFrameLock) {
            exposureJson = latestExposureMetadataJson;
        }
        if (exposureJson == null || exposureJson.trim().isEmpty()) {
            return "metadata JSON: missing";
        }
        try {
            JSONObject json = new JSONObject(exposureJson);
            StringBuilder builder = new StringBuilder();
            builder.append("available: ").append(json.optBoolean("available", false)).append("\n");
            appendRequiredMetadataLine(builder, json, "sensorExposureTimeNs", true);
            appendRequiredMetadataLine(builder, json, "sensorSensitivityIso", true);
            appendRequiredMetadataLine(builder, json, "sensorFrameDurationNs", true);
            appendRequiredMetadataLine(builder, json, "sensorTimestampNs", true);
            appendRequiredMetadataLine(builder, json, "lensAperture", true);
            appendRequiredMetadataLine(builder, json, "lensFocalLengthMm", true);
            appendRequiredMetadataLine(builder, json, "sensorPhysicalWidthMm", true);
            appendRequiredMetadataLine(builder, json, "sensorPhysicalHeightMm", true);
            appendRequiredMetadataLine(builder, json, "imageFocalLengthXPixels", true);
            appendRequiredMetadataLine(builder, json, "imageFocalLengthYPixels", true);
            appendRequiredMetadataLine(builder, json, "imagePrincipalPointXPixels", true);
            appendRequiredMetadataLine(builder, json, "imagePrincipalPointYPixels", true);
            appendRequiredMetadataLine(builder, json, "imageIntrinsicsWidth", true);
            appendRequiredMetadataLine(builder, json, "imageIntrinsicsHeight", true);
            appendRequiredMetadataLine(builder, json, "aeState", false);
            appendRequiredMetadataLine(builder, json, "awbState", false);
            appendRequiredMetadataLine(builder, json, "aeExposureCompensation", false);
            appendRequiredMetadataLine(builder, json, "aeMode", false);
            appendRequiredMetadataLine(builder, json, "awbMode", false);
            if (json.has("reason") && !json.isNull("reason")) {
                builder.append("reason: ").append(json.optString("reason", "")).append("\n");
            }
            return builder.toString().trim();
        } catch (JSONException ignored) {
            return "metadata JSON: invalid";
        }
    }

    private static void appendRequiredMetadataLine(
            StringBuilder builder,
            JSONObject json,
            String field,
            boolean requirePositive) {
        if (!json.has(field) || json.isNull(field)) {
            builder.append(field).append(": missing\n");
            return;
        }
        double value = json.optDouble(field, Double.NaN);
        if (requirePositive && (!(value > 0.0) || Double.isNaN(value))) {
            builder.append(field).append(": invalid ").append(json.opt(field)).append("\n");
            return;
        }
        builder.append(field).append(": ").append(json.opt(field)).append("\n");
    }

    /*
     * Function: compassCalibrationStatus
     * Arguments: none.
     * Calls: no helpers beyond switch logic.
     * Flow: translate Android magnetometer accuracy constants into user-facing
     * compass calibration text.
     */
    private String compassCalibrationStatus() {
        if (magnetometer == null) {
            return "unavailable";
        }
        switch (compassAccuracy) {
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                return "high";
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
                return "medium";
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                return "low - calibrate compass";
            case SensorManager.SENSOR_STATUS_UNRELIABLE:
            default:
                return "unreliable - calibrate compass";
        }
    }

    /*
     * Function: formatVector
     * Arguments: values is the float array from SensorEvent; count is how many
     * components to display.
     * Calls: String.format(Locale.US).
     * Flow: format up to count sensor components to two decimal places for a
     * compact overlay line.
     */
    private String formatVector(float[] values, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count && i < values.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.format(Locale.US, "%.2f", values[i]));
        }
        return builder.toString();
    }

    /*
     * Function: handlePreviewTouch
     * Arguments: event is the touch event from the painted preview overlay.
     * Calls: handlePreviewTap().
     * Flow: taps on existing painted rows can prompt re-capture. Drag alignment
     * is intentionally disabled now that horizontal stability has improved.
     */
    private boolean handlePreviewTouch(MotionEvent event) {
        if (!horizonSweepComplete || event == null || guideView == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                previewDragStartX = event.getX();
                previewDragMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                previewDragMoved = previewDragMoved || Math.abs(event.getX() - previewDragStartX) > 18f;
                return true;
            case MotionEvent.ACTION_UP:
                if (!previewDragMoved) {
                    handlePreviewTap(event.getY());
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                previewDragMoved = false;
                return true;
            default:
                return true;
        }
    }

    /*
     * Function: handlePreviewTap
     * Arguments: y is the tap position in the guide overlay.
     * Calls: nearestPreviewLayerForY() and promptRecaptureLayer().
     * Flow: let users tap an already-painted row and choose to recapture that
     * whole layer rather than editing individual blocks.
     */
    private void handlePreviewTap(float y) {
        int layer = nearestPreviewLayerForY(y);
        if (layer < 0) {
            return;
        }
        promptRecaptureLayer(layer);
    }

    /*
     * Function: nearestPreviewLayerForY
     * Arguments: y is a screen-space vertical coordinate.
     * Calls: angularToScreenY().
     * Flow: match a tap to the closest visible painted layer row.
     */
    private int nearestPreviewLayerForY(float y) {
        float bestDistance = Float.MAX_VALUE;
        int bestLayer = -1;
        float bandHalfHeight = guideView == null ? 0f : guideView.getHeight() * 0.13f;
        for (int layer = 0; layer < SWEEP_LAYER_PITCH_DEGREES.length; layer++) {
            if (capturedSweepBinCount(layer) == 0) {
                continue;
            }
            float layerY = angularToScreenY(SWEEP_LAYER_PITCH_DEGREES[layer] - pitchDegrees);
            float distance = Math.abs(y - layerY);
            if (distance < bestDistance && distance <= bandHalfHeight) {
                bestDistance = distance;
                bestLayer = layer;
            }
        }
        return bestLayer;
    }

    /*
     * Function: angularToScreenY
     * Arguments: pitchDelta is signed angular distance from center.
     * Calls: View.getHeight().
     * Flow: mirror TargetGuideView's vertical projection for tap hit-testing.
     */
    private float angularToScreenY(float pitchDelta) {
        float height = guideView == null ? 1f : guideView.getHeight();
        return height * 0.5f - pitchDelta / 55f * height * 0.45f;
    }

    /*
     * Function: promptRecaptureLayer
     * Arguments: layer is the selected paint row.
     * Calls: AlertDialog.Builder and recaptureLayer().
     * Flow: ask for confirmation before discarding a completed layer.
     */
    private void promptRecaptureLayer(int layer) {
        String layerName = layerName(layer);
        new AlertDialog.Builder(this)
                .setTitle("Re-capture " + layerName + "?")
                .setMessage("This will clear that layer and make it the active sweep again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Re-capture", (dialog, which) -> recaptureLayer(layer))
                .show();
    }

    /*
     * Function: recaptureLayer
     * Arguments: layer is the paint row to reset.
     * Calls: startHorizonReferenceSweep(), resetSweepLayer(), and updateCompassUi().
     * Flow: reset a selected layer for a fresh Start/Stop sweep.
     */
    private void recaptureLayer(int layer) {
        if (layer == 0) {
            startHorizonReferenceSweep();
            return;
        }
        resetSweepLayer(layer);
        currentSweepLayerIndex = layer;
        sweepLayerPaintingActive = false;
        sweepLayerStartHeadingDegrees = compassHeadingDegrees;
        clearSweepLayerPreviewPanorama();
        rebuildPaintedPhotospherePreview();
        Toast.makeText(this, "Layer cleared. Align view and tap Start.", Toast.LENGTH_LONG).show();
        showPolarCaptureInfoIfNeeded();
        updateCompassUi();
    }

    /*
     * Function: handleHorizonSweepButton
     * Arguments: none; invoked by the Start/End/Resweep button.
     * Calls: startCompassCalibrationSweep(), completeCompassCalibrationSweep(),
     * startHorizonReferenceSweep(), or endHorizonReferenceSweep().
     * Flow: use one button for the capture gate. It first runs a compass
     * calibration horizon sweep, then runs the separate horizon reference sweep,
     * and finally advances through capture layers.
     */
    private void handleHorizonSweepButton() {
        if (!compassCalibrationComplete) {
            if (!calibrationStarted) {
                startCompassCalibrationSweep();
            } else if (isCompassCalibrationReady()) {
                completeCompassCalibrationSweep();
            } else {
                Toast.makeText(this, compassCalibrationProgressText(), Toast.LENGTH_LONG).show();
            }
        } else if (horizonSweepAwaitingClose && !horizonSweepComplete) {
            endHorizonReferenceSweep();
        } else if (horizonSweepComplete) {
            handleSweepLayerButton();
        } else if (horizonSweepStarted) {
            confirmRestartHorizonReferenceSweep();
        } else {
            startHorizonReferenceSweep();
        }
    }

    private void confirmRestartHorizonReferenceSweep() {
        new AlertDialog.Builder(this)
                .setTitle("Restart horizon set?")
                .setMessage("This clears the initial horizon reference samples and lets you set a fresh start point. Later capture layers are not affected.")
                .setNegativeButton("Keep Going", null)
                .setPositiveButton("Restart", (dialog, which) -> restartHorizonReferenceSweep())
                .show();
    }

    private void restartHorizonReferenceSweep() {
        resetHorizonReferenceSweep(false);
        horizonSweepStarted = false;
        horizonSweepComplete = false;
        horizonSweepAwaitingClose = false;
        statusText.setText("Align start point");
        Toast.makeText(this, "Horizon set cleared. Align a start point and tap Start.", Toast.LENGTH_LONG).show();
        updateCompassUi();
    }

    /*
     * Function: startCompassCalibrationSweep
     * Arguments: none.
     * Calls: resetCompassCalibration(), updateCompassUi(), and Toast.
     * Flow: begin a dedicated 360-degree horizon turn that warms up orientation
     * readings before the app records the actual horizon reference imagery.
     */
    private void startCompassCalibrationSweep() {
        resetCompassCalibration();
        calibrationStarted = true;
        compassCalibrationComplete = false;
        calibrationStartedAt = System.currentTimeMillis();
        statusText.setText("Calibrate compass");
        Toast.makeText(
                this,
                "Slowly turn once around the horizon. Keep the phone level so the compass can settle before capture.",
                Toast.LENGTH_LONG).show();
        updateCompassUi();
    }

    /*
     * Function: completeCompassCalibrationSweep
     * Arguments: none.
     * Calls: updateCompassUi() and Toast.
     * Flow: close the calibration-only sweep once enough heading/time/stability
     * coverage exists, unlocking the separate horizon reference pass.
     */
    private void completeCompassCalibrationSweep() {
        compassCalibrationComplete = true;
        statusText.setText("Horizon sweep needed");
        Toast.makeText(
                this,
                "Compass calibrated. Point at a distant start landmark, then tap Start for the capture reference sweep.",
                Toast.LENGTH_LONG).show();
        updateCompassUi();
    }

    /*
     * Function: startHorizonReferenceSweep
     * Arguments: none.
     * Calls: resetHorizonReferenceSweep(), updateCompassUi(), and Toast.
     * Flow: begin the mandatory 360-degree horizon pass. The app captures small
     * preview samples around the yaw circle and later draws them as a translucent
     * reference belt over above/below horizon capture targets.
     */
    private void startHorizonReferenceSweep() {
        if (!arCoreReady) {
            Toast.makeText(this, "ARCore is required before capture can start", Toast.LENGTH_LONG).show();
            verifyArCoreSupport();
            return;
        }
        if (!compassCalibrationComplete) {
            Toast.makeText(this, "Complete the compass calibration sweep first", Toast.LENGTH_LONG).show();
            updateCompassUi();
            return;
        }
        resetHorizonReferenceSweep(true);
        horizonSweepStarted = true;
        horizonSweepComplete = false;
        horizonSweepAwaitingClose = false;
        horizonStartHeadingDegrees = compassHeadingDegrees;
        horizonStartPitchDegrees = pitchDegrees;
        sweepLayerStartHeadingDegrees = compassHeadingDegrees;
        currentSweepLayerIndex = 0;
        sweepLayerPaintingActive = false;
        polarCaptureInfoShown = false;
        highPitchRingInfoShown = false;
        resetSweepCaptureCoverage();
        resetSweepMotionFeedback();
        statusText.setText("Sweep horizon");
        Toast.makeText(
                this,
                "Reference point set. Move dot to dot around the horizon and hold still for each capture.",
                Toast.LENGTH_LONG).show();
        updateCompassUi();
    }

    /*
     * Function: endHorizonReferenceSweep
     * Arguments: none.
     * Calls: isAlignedWithHorizonStart(), updateCompassUi(), and Toast.
     * Flow: close the 360-degree horizon reference only when the user has
     * returned the crosshair to the fixed distant start landmark.
     */
    private void endHorizonReferenceSweep() {
        if (!horizonSweepAwaitingClose) {
            Toast.makeText(this, "Finish the 360-degree sweep first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isAlignedWithHorizonStart()) {
            Toast.makeText(this, "Line the crosshair up with the same start point", Toast.LENGTH_LONG).show();
            updateCompassUi();
            return;
        }
        horizonSweepComplete = true;
        horizonSweepAwaitingClose = false;
        currentSweepLayerIndex = 0;
        sweepLayerStartHeadingDegrees = horizonStartHeadingDegrees;
        sweepLayerPaintingActive = false;
        resetSweepMotionFeedback();
        statusText.setText("Horizon painted");
        Toast.makeText(this, "Horizon layer painted. Tap Next for the upper layer.", Toast.LENGTH_LONG).show();
        updateCompassUi();
    }

    /*
     * Function: resetHorizonReferenceSweep
     * Arguments: none.
     * Calls: Bitmap.recycle().
     * Flow: clear all captured horizon reference samples so a fresh 360-degree
     * pass can replace the translucent overlay.
     */
    private void resetHorizonReferenceSweep() {
        resetHorizonReferenceSweep(true);
    }

    private void resetHorizonReferenceSweep(boolean resetAllCoverage) {
        for (int i = 0; i < horizonReferenceBins.length; i++) {
            horizonReferenceBins[i] = false;
            if (horizonReferenceFrames[i] != null) {
                horizonReferenceFrames[i].recycle();
                horizonReferenceFrames[i] = null;
            }
        }
        if (horizonReferencePanorama != null) {
            horizonReferencePanorama.recycle();
            horizonReferencePanorama = null;
        }
        horizonSweepAwaitingClose = false;
        currentSweepLayerIndex = 0;
        sweepLayerStartHeadingDegrees = compassHeadingDegrees;
        sweepLayerPaintingActive = false;
        if (resetAllCoverage) {
            resetSweepCaptureCoverage();
        } else {
            for (int i = 0; i < sweepCaptureBins[0].length; i++) {
                sweepCaptureBins[0][i] = false;
                if (sweepCapturePreviewFrames[0][i] != null) {
                    sweepCapturePreviewFrames[0][i].recycle();
                    sweepCapturePreviewFrames[0][i] = null;
                }
            }
            rebuildPaintedPhotospherePreview();
        }
        resetSweepMotionFeedback();
        if (guideView != null) {
            guideView.setHorizonReference(null, false, false, 0f, false, false, "", 0f, null, 0);
            guideView.setSweepPaintState(
                    false,
                    "Horizon",
                    "",
                    0,
                    0f,
                    0f,
                    false,
                    false,
                    false,
                    false,
                    null,
                    0,
                    0f,
                    false,
                    false,
                    false);
        }
    }

    /*
     * Function: resetSweepCaptureCoverage
     * Arguments: none.
     * Calls: no external functions.
     * Flow: clear the painted coverage map for the sweep-first capture mode so
     * each new horizon reference pass starts a fresh photosphere draft.
     */
    private void resetSweepCaptureCoverage() {
        for (int layer = 0; layer < sweepCaptureBins.length; layer++) {
            for (int bin = 0; bin < sweepCaptureBins[layer].length; bin++) {
                sweepCaptureBins[layer][bin] = false;
                if (sweepCapturePreviewFrames[layer][bin] != null) {
                    sweepCapturePreviewFrames[layer][bin].recycle();
                    sweepCapturePreviewFrames[layer][bin] = null;
                }
            }
        }
        if (sweepLayerPreviewPanorama != null) {
            sweepLayerPreviewPanorama.recycle();
            sweepLayerPreviewPanorama = null;
        }
        if (paintedPhotospherePreview != null) {
            paintedPhotospherePreview.recycle();
            paintedPhotospherePreview = null;
        }
        lastCapturedSweepLayerIndex = -1;
        lastCapturedSweepBin = -1;
        lastCapturedTargetIndex = -1;
        sweepLayerPaintingActive = false;
    }

    /*
     * Function: clearSweepLayerPreviewPanorama
     * Arguments: none.
     * Calls: Bitmap.recycle().
     * Flow: clear the displayed non-horizon preview when moving between paint
     * bands so each layer starts visually empty.
     */
    private void clearSweepLayerPreviewPanorama() {
        if (sweepLayerPreviewPanorama != null) {
            sweepLayerPreviewPanorama.recycle();
            sweepLayerPreviewPanorama = null;
        }
    }

    /*
     * Function: resetSweepLayer
     * Arguments: layer is the sweep row to clear.
     * Calls: Bitmap.recycle().
     * Flow: remove captured/infilled preview data for one layer so it can be
     * swept again from the normal Start/Stop flow.
     */
    private void resetSweepLayer(int layer) {
        if (layer < 0 || layer >= sweepCaptureBins.length) {
            return;
        }
        for (int bin = 0; bin < sweepCaptureBins[layer].length; bin++) {
            sweepCaptureBins[layer][bin] = false;
            if (sweepCapturePreviewFrames[layer][bin] != null) {
                sweepCapturePreviewFrames[layer][bin].recycle();
                sweepCapturePreviewFrames[layer][bin] = null;
            }
        }
    }

    /*
     * Function: skipHighAndLowLayers
     * Arguments: none.
     * Calls: infillSweepLayerFromSource(), rebuildPaintedPhotospherePreview(),
     * updateCompassUi(), and Toast.
     * Flow: allow low-detail ceilings/skies/floors to be approximated from the
     * nearest captured upper/lower layers rather than requiring two more sweeps.
     */
    private void skipHighAndLowLayers() {
        if (!horizonSweepComplete) {
            Toast.makeText(this, "Complete the horizon sweep first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (capturedSweepBinCount(1) == 0 || capturedSweepBinCount(2) == 0) {
            Toast.makeText(this, "Paint upper and lower layers before skipping poles", Toast.LENGTH_LONG).show();
            return;
        }
        infillSweepLayerFromSource(SWEEP_LAYER_HIGH_UPPER_INDEX, 1, true);
        infillSweepLayerFromSource(SWEEP_LAYER_HIGH_LOWER_INDEX, 2, false);
        if (currentSweepLayerIndex >= SWEEP_LAYER_HIGH_UPPER_INDEX) {
            currentSweepLayerIndex = SWEEP_LAYER_PITCH_DEGREES.length - 1;
            sweepLayerPaintingActive = false;
        }
        rebuildPaintedPhotospherePreview();
        Toast.makeText(this, "Uppermost and lowermost layers filled from captured colour", Toast.LENGTH_LONG).show();
        updateCompassUi();
    }

    /*
     * Function: handleSweepLayerButton
     * Arguments: none.
     * Calls: startSweepLayerPainting(), stopSweepLayerPainting(), and
     * advanceSweepLayer().
     * Flow: after the horizon reference pass, use the overlay button as a
     * deliberate Start/Stop control for each upper/lower paint layer.
     */
    private void handleSweepLayerButton() {
        if (isCurrentHighPitchRingLayer()) {
            handleHighPitchRingButton();
        } else if (isCurrentPolarLayer()) {
            handlePolarLayerButton();
        } else if (sweepLayerPaintingActive) {
            stopSweepLayerPainting();
        } else if (currentSweepLayerComplete()) {
            advanceSweepLayer();
        } else {
            startSweepLayerPainting();
        }
    }

    /*
     * Function: handleHighPitchRingButton
     * Arguments: none.
     * Calls: captureFrame(), advanceSweepLayer(), finishCaptureSession(), and
     * updateCompassUi().
     * Flow: capture the safer near-pole rows as four deliberate cardinal shots
     * instead of a continuous high-pitch sweep. The user is asked to return to
     * the horizon between shots to keep ARCore tracking stable.
     */
    private void handleHighPitchRingButton() {
        showHighPitchRingInfoIfNeeded();
        if (currentSweepLayerComplete()) {
            if (currentSweepLayerIndex >= SWEEP_LAYER_PITCH_DEGREES.length - 1) {
                finishCaptureSession();
            } else {
                advanceSweepLayer();
            }
            return;
        }
        if (!isSweepLayerPitchAligned()) {
            Toast.makeText(
                    this,
                    String.format(Locale.US, "From the horizon, pan to %+d deg", currentSweepLayerPitch()),
                    Toast.LENGTH_LONG).show();
            updateCompassUi();
            return;
        }
        int bin = currentCaptureBin();
        if (bin < 0) {
            Toast.makeText(this, highPitchRingInstruction(), Toast.LENGTH_LONG).show();
            updateCompassUi();
            return;
        }
        if (sweepCaptureBins[currentSweepLayerIndex][bin]) {
            Toast.makeText(this, "That high-angle direction is already captured. Return to horizon, rotate, then pan back.", Toast.LENGTH_LONG).show();
            updateCompassUi();
            return;
        }
        captureFrame("high-ring-manual");
    }

    /*
     * Function: handlePolarLayerButton
     * Arguments: none.
     * Calls: captureFrame(), advanceSweepLayer(), and finishCaptureSession().
     * Flow: retained for older polar-control experiments. Current high-pitch
     * layers are sweep rings, so handleSweepLayerButton() normally routes around
     * this method.
     */
    private void handlePolarLayerButton() {
        if (currentSweepLayerComplete()) {
            if (currentSweepLayerIndex >= SWEEP_LAYER_PITCH_DEGREES.length - 1) {
                finishCaptureSession();
            } else {
                advanceSweepLayer();
            }
            return;
        }
        showPolarCaptureInfoIfNeeded();
        if (!isSweepLayerPitchAligned()) {
            Toast.makeText(this, "Align the pitch line before capturing", Toast.LENGTH_SHORT).show();
            updateCompassUi();
            return;
        }
        captureFrame("polar-manual");
    }

    /*
     * Function: startSweepLayerPainting
     * Arguments: none.
     * Calls: isAlignedWithSweepLayerStart(), resetSweepMotionFeedback(), and
     * updateCompassUi().
     * Flow: begin painting the current layer after the user has lined up their
     * chosen start view. Their Start tap defines the yaw origin for this layer.
     */
    private void startSweepLayerPainting() {
        showPolarCaptureInfoIfNeeded();
        if (!isSweepLayerPitchAligned()) {
            Toast.makeText(this, "Tilt to the target line before starting", Toast.LENGTH_LONG).show();
            updateCompassUi();
            return;
        }
        sweepLayerPaintingActive = true;
        sweepLayerStartHeadingDegrees = compassHeadingDegrees;
        lastAutoCaptureAt = 0L;
        resetSweepMotionFeedback();
        statusText.setText("Painting " + currentSweepLayerName());
        Toast.makeText(
                this,
                isCurrentPolarLayer()
                        ? "Polar capture started. Face the origin point and hold the pitch line."
                        : isCurrentHighPitchRingLayer()
                        ? "High ring started. Use four horizon-to-pitch captures."
                        : "Start painting. Rotate slowly through 360 degrees.",
                Toast.LENGTH_LONG).show();
        updateCompassUi();
    }

    /*
     * Function: stopSweepLayerPainting
     * Arguments: none.
     * Calls: currentSweepLayerComplete(), isAlignedWithSweepLayerStart(), and
     * updateCompassUi().
     * Flow: trust the user's Stop tap as the 360-degree closure for this layer
     * and advance to the next paint band.
     */
    private void stopSweepLayerPainting() {
        if (isCurrentPolarLayer() && !currentSweepLayerComplete()) {
            Toast.makeText(this, polarRollInstruction(), Toast.LENGTH_SHORT).show();
            updateCompassUi();
            return;
        }
        markCurrentSweepLayerClosed();
        sweepLayerPaintingActive = false;
        if (currentSweepLayerIndex >= SWEEP_LAYER_PITCH_DEGREES.length - 1) {
            Toast.makeText(this, "All sweep layers painted", Toast.LENGTH_SHORT).show();
        } else {
            currentSweepLayerIndex++;
            sweepLayerStartHeadingDegrees = compassHeadingDegrees;
            clearSweepLayerPreviewPanorama();
            resetSweepMotionFeedback();
            showPolarCaptureInfoIfNeeded();
            Toast.makeText(this, currentSweepLayerPrompt(), Toast.LENGTH_LONG).show();
        }
        updateCompassUi();
    }

    /*
     * Function: advanceSweepLayer
     * Arguments: none.
     * Calls: currentSweepLayerComplete(), updateCompassUi(), and Toast.
     * Flow: move the user through the photosphere paint bands after each layer
     * has enough yaw coverage.
     */
    private void advanceSweepLayer() {
        if (!horizonSweepComplete) {
            return;
        }
        if (!currentSweepLayerComplete()) {
            Toast.makeText(this, "Finish painting this layer before moving on", Toast.LENGTH_SHORT).show();
            updateCompassUi();
            return;
        }
        if (currentSweepLayerIndex >= SWEEP_LAYER_PITCH_DEGREES.length - 1) {
            Toast.makeText(this, "All sweep layers painted", Toast.LENGTH_SHORT).show();
            updateCompassUi();
            return;
        }
        currentSweepLayerIndex++;
        sweepLayerPaintingActive = false;
        sweepLayerStartHeadingDegrees = compassHeadingDegrees;
        clearSweepLayerPreviewPanorama();
        lastAutoCaptureAt = 0L;
        resetSweepMotionFeedback();
        showPolarCaptureInfoIfNeeded();
        Toast.makeText(this, currentSweepLayerPrompt(), Toast.LENGTH_LONG).show();
        updateCompassUi();
    }

    /*
     * Function: resetCompassCalibration
     * Arguments: none.
     * Calls: no external functions.
     * Flow: clear heading-bin and tilt-coverage state for a fresh mandatory
     * compass calibration attempt.
     */
    private void resetCompassCalibration() {
        for (int i = 0; i < headingBins.length; i++) {
            headingBins[i] = false;
        }
        minAccelX = Float.MAX_VALUE;
        maxAccelX = -Float.MAX_VALUE;
        minAccelY = Float.MAX_VALUE;
        maxAccelY = -Float.MAX_VALUE;
        minAccelZ = Float.MAX_VALUE;
        maxAccelZ = -Float.MAX_VALUE;
        highAccuracyStartedAt = compassAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                ? System.currentTimeMillis()
                : 0L;
        stableHeadingStartedAt = 0L;
        lastCompassHeadingDegrees = compassHeadingDegrees;
    }

    /*
     * Function: lowPassVector
     * Arguments: input is the latest raw sensor vector; output is the retained
     * filtered vector; initialized tells whether output already has a baseline.
     * Calls: no external functions.
     * Flow: use an exponential moving average to remove rapid sensor jitter while
     * still allowing deliberate calibration movement to come through.
     */
    private boolean lowPassVector(float[] input, float[] output, boolean initialized) {
        if (!initialized) {
            System.arraycopy(input, 0, output, 0, output.length);
            return true;
        }
        for (int i = 0; i < output.length && i < input.length; i++) {
            output[i] = output[i] + SENSOR_LOW_PASS_ALPHA * (input[i] - output[i]);
        }
        return true;
    }

    /*
     * Function: updateCompassHeading
     * Arguments: none.
     * Calls: SensorManager.getRotationMatrix(), SensorManager.getOrientation(),
     * Math.toDegrees(), recordHeadingCoverage(), and CompassNeedleView.setHeading().
     * Flow: combine accelerometer and magnetometer readings into a bearing to
     * magnetic north, store it as degrees, and rotate the always-visible pointer.
     */
    private void updateCompassHeading() {
        if (arSessionRunning) {
            return;
        }
        if (!hasFilteredAccelerometerReading || !hasFilteredMagnetometerReading) {
            return;
        }
        if (!SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                filteredAccelerometerValues,
                filteredMagnetometerValues)) {
            return;
        }
        SensorManager.getOrientation(rotationMatrix, orientationValues);
        acceptPitchRoll();
        acceptCompassHeading((float) Math.toDegrees(orientationValues[0]));
    }

    /*
     * Function: updateCompassHeadingFromRotationVector
     * Arguments: values is the fused Android rotation-vector sensor payload.
     * Calls: SensorManager.getRotationMatrixFromVector(), SensorManager.getOrientation(),
     * and acceptCompassHeading().
     * Flow: when Android provides fused orientation data, convert it to azimuth
     * and feed the same north-pointer/calibration progress path as raw compass
     * readings.
     */
    private void updateCompassHeadingFromRotationVector(float[] values) {
        if (arSessionRunning) {
            return;
        }
        SensorManager.getRotationMatrixFromVector(rotationVectorMatrix, values);
        SensorManager.getOrientation(rotationVectorMatrix, orientationValues);
        acceptPitchRoll();
        acceptCompassHeading((float) Math.toDegrees(orientationValues[0]));
    }

    /*
     * Function: acceptPitchRoll
     * Arguments: none; reads the latest values in orientationValues.
     * Calls: Math.toDegrees() and updateGuideState().
     * Flow: convert Android's pitch/roll radians into degrees, store them for
     * draft metadata, and refresh Phase 4 target alignment whenever orientation
     * data changes.
     */
    private void acceptPitchRoll() {
        pitchDegrees = (float) Math.toDegrees(orientationValues[1]);
        rollDegrees = (float) Math.toDegrees(orientationValues[2]);
        hasPitchRollReading = true;
        updateGuideState();
    }

    /*
     * Function: acceptCompassHeading
     * Arguments: headingDegrees is a possibly negative azimuth in degrees.
     * Calls: normalizeHeading(), updateHeadingStability(), recordHeadingCoverage(),
     * and CompassNeedleView.setHeadingDegrees().
     * Flow: normalize and store the latest heading, update stability/coverage
     * metrics, and rotate the north pointer.
     */
    private void acceptCompassHeading(float headingDegrees) {
        rawCompassHeadingDegrees = normalizeHeading(headingDegrees);
        compassHeadingDegrees = smoothHeading(rawCompassHeadingDegrees);
        hasHeadingReading = true;
        if (hasPitchRollReading) {
            updateKeyframeStillness();
        }
        updateHeadingStability();
        recordHeadingCoverage();
        recordHorizonReferenceSample();
        if (compassNeedleView != null) {
            compassNeedleView.setHeadingDegrees(compassHeadingDegrees, pitchDegrees);
        }
        updateGuideState();
    }

    /*
     * Function: acceptArPose
     * Arguments: pose is the latest ARCore camera pose in the session frame.
     * Calls: Pose.toMatrix(), Math trig helpers, normalizeHeading(),
     * updateHeadingStability(), recordHorizonReferenceSample(), and
     * updateGuideState().
     * Flow: convert ARCore's 6-DoF camera pose into yaw/pitch/roll guidance
     * values, flipping ARCore's yaw handedness into the app's clockwise heading
     * convention so overlay targets move with the user's rotation.
     */
    private void acceptArPose(Pose pose) {
        pose.toMatrix(latestArPoseMatrix, 0);
        float forwardX = -latestArPoseMatrix[8];
        float forwardY = -latestArPoseMatrix[9];
        float forwardZ = -latestArPoseMatrix[10];
        rawCompassHeadingDegrees = normalizeHeading((float) -Math.toDegrees(Math.atan2(forwardX, forwardZ)));
        compassHeadingDegrees = smoothHeading(rawCompassHeadingDegrees);
        pitchDegrees = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, forwardY))));
        rollDegrees = (float) Math.toDegrees(Math.atan2(latestArPoseMatrix[1], latestArPoseMatrix[5]));
        hasHeadingReading = true;
        hasPitchRollReading = true;
        updateKeyframeStillness();
        updateHeadingStability();
        recordHeadingCoverage();
        updateSweepMotionFeedback();
        recordHorizonReferenceSample();
        runOnUiThread(() -> {
            if (compassNeedleView != null) {
                compassNeedleView.setHeadingDegrees(compassHeadingDegrees, pitchDegrees);
            }
            updateSensorOverlay();
            updateCompassUi();
            updateGuideState();
        });
    }

    /*
     * Function: updateGuideState
     * Arguments: none.
     * Calls: sweep coverage helpers, TargetGuideView.setGuideState(), and
     * maybeAutoCapture().
     * Flow: update the sweep-first overlay around the current pitch band and
     * optionally save frames as the user paints around the yaw circle.
     */
    private void updateGuideState() {
        if (!hasHeadingReading || !hasPitchRollReading) {
            return;
        }
        guideHeadingDegrees = smoothGuideHeading(compassHeadingDegrees);
        guidePitchDegrees = smoothGuidePitch(effectiveGuidePitchDegrees());
        activeTargetIndex = -1;
        boolean aligned = horizonSweepComplete
                ? isSweepLayerPitchAligned()
                : horizonSweepStarted && Math.abs(pitchDegrees) <= MAX_HORIZON_SWEEP_PITCH_DEGREES;
        boolean stable = aligned && isKeyframeStable();
        float lockProgress = keyframeLockProgress();
        if (guideView != null) {
            guideView.setHorizonReference(
                    paintedPhotospherePreview,
                    horizonSweepStarted,
                    horizonSweepComplete,
                    horizonReferenceBinCount() / (float) REQUIRED_HORIZON_REFERENCE_BINS,
                    horizonSweepAwaitingClose,
                    isAlignedWithHorizonStart(),
                    currentSweepSpeedMessage(),
                    currentSweepTiltDeltaDegrees(),
                    horizonReferenceBins,
                    horizonReferenceBinForHeading(compassHeadingDegrees));
            guideView.setGuideState(
                    captureTargets,
                    activeTargetIndex,
                    guideHeadingDegrees,
                    guidePitchDegrees,
                    aligned,
                    stable,
                    lockProgress,
                    totalSweepCoverageProgress(),
                    capturePaused);
            guideView.setSweepPaintState(
                    horizonSweepComplete,
                    currentSweepLayerName(),
                    currentSweepLayerPrompt(),
                    currentSweepLayerPitch(),
                    sweepLayerProgress(currentSweepLayerIndex),
                    totalSweepCoverageProgress(),
                    isSweepLayerPitchAligned(),
                    currentSweepLayerComplete(),
                    sweepLayerPaintingActive,
                    isAlignedWithSweepLayerStart(),
                    currentSweepLayerBins(),
                    currentOverlayBin(),
                    relativeSweepYawDegrees(guideHeadingDegrees),
                    isFirstCurrentLayerDotCaptured(),
                    previewDragActive,
                    isCurrentPolarLayer());
            if (aligned && !stable && !guideRefreshPosted) {
                guideRefreshPosted = true;
                guideView.postDelayed(() -> {
                    guideRefreshPosted = false;
                    updateGuideState();
                }, 33L);
            }
        }
        maybeAutoCapture();
    }

    /*
     * Function: smoothHeading
     * Arguments: headingDegrees is a normalized heading from raw or fused sensor
     * data.
     * Calls: normalizeHeading(), Math.sin(), Math.cos(), Math.atan2(), and
     * Math.toRadians()/toDegrees().
     * Flow: smooth headings on the unit circle rather than as plain numbers so
     * crossing north from 359 to 0 degrees does not look like a huge jump.
     */
    private float smoothHeading(float headingDegrees) {
        if (!hasSmoothedHeadingReading) {
            hasSmoothedHeadingReading = true;
            return normalizeHeading(headingDegrees);
        }
        double currentRadians = Math.toRadians(compassHeadingDegrees);
        double targetRadians = Math.toRadians(headingDegrees);
        double x = (1.0 - HEADING_SMOOTHING_ALPHA) * Math.cos(currentRadians)
                + HEADING_SMOOTHING_ALPHA * Math.cos(targetRadians);
        double y = (1.0 - HEADING_SMOOTHING_ALPHA) * Math.sin(currentRadians)
                + HEADING_SMOOTHING_ALPHA * Math.sin(targetRadians);
        return normalizeHeading((float) Math.toDegrees(Math.atan2(y, x)));
    }

    /*
     * Function: smoothGuideHeading
     * Arguments: headingDegrees is the already-smoothed compass heading.
     * Calls: normalizeHeading(), Math.sin(), Math.cos(), and Math.atan2().
     * Flow: apply a second, slower circular smoothing pass for overlay movement
     * so the target grid feels calm even while raw sensor callbacks are noisy.
     */
    private float smoothGuideHeading(float headingDegrees) {
        if (!hasGuideHeadingReading) {
            hasGuideHeadingReading = true;
            return normalizeHeading(headingDegrees);
        }
        double currentRadians = Math.toRadians(guideHeadingDegrees);
        double targetRadians = Math.toRadians(headingDegrees);
        double x = (1.0 - GUIDE_HEADING_SMOOTHING_ALPHA) * Math.cos(currentRadians)
                + GUIDE_HEADING_SMOOTHING_ALPHA * Math.cos(targetRadians);
        double y = (1.0 - GUIDE_HEADING_SMOOTHING_ALPHA) * Math.sin(currentRadians)
                + GUIDE_HEADING_SMOOTHING_ALPHA * Math.sin(targetRadians);
        return normalizeHeading((float) Math.toDegrees(Math.atan2(y, x)));
    }

    /*
     * Function: smoothGuidePitch
     * Arguments: pitchDegrees is the latest measured device pitch.
     * Calls: no external helpers.
     * Flow: apply a slow moving average to vertical guide movement so pitch noise
     * does not make the active dot flicker around the reticle.
     */
    private float smoothGuidePitch(float pitchDegrees) {
        if (!hasGuidePitchReading) {
            hasGuidePitchReading = true;
            return pitchDegrees;
        }
        return guidePitchDegrees + GUIDE_PITCH_SMOOTHING_ALPHA * (pitchDegrees - guidePitchDegrees);
    }

    /*
     * Function: updateHeadingStability
     * Arguments: none.
     * Calls: headingDeltaDegrees() and System.currentTimeMillis().
     * Flow: require the heading to settle before capture unlocks, preventing a
     * successful calibration while the compass is still swinging wildly.
     */
    private void updateHeadingStability() {
        long now = System.currentTimeMillis();
        if (headingDeltaDegrees(compassHeadingDegrees, lastCompassHeadingDegrees)
                <= MAX_STABLE_HEADING_DELTA_DEGREES) {
            if (stableHeadingStartedAt == 0L) {
                stableHeadingStartedAt = now;
            }
        } else {
            stableHeadingStartedAt = 0L;
        }
        lastCompassHeadingDegrees = compassHeadingDegrees;
    }

    /*
     * Function: recordHeadingCoverage
     * Arguments: none.
     * Calls: normalizeHeading().
     * Flow: when calibration is active, mark one of twelve compass sectors as
     * observed so the user must rotate through broad heading coverage.
     */
    private void recordHeadingCoverage() {
        if (!calibrationStarted || !hasHeadingReading) {
            return;
        }
        int bin = (int) (normalizeHeading(compassHeadingDegrees) / (360f / HEADING_BIN_COUNT));
        headingBins[Math.max(0, Math.min(HEADING_BIN_COUNT - 1, bin))] = true;
    }

    /*
     * Function: recordTiltCoverage
     * Arguments: none.
     * Calls: Math.sqrt().
     * Flow: normalize the gravity vector from accelerometer data and track the
     * range seen on each axis, requiring the user to tilt the device rather than
     * only spin it flat.
     */
    private void recordTiltCoverage() {
        if (!calibrationStarted) {
            return;
        }
        float length = (float) Math.sqrt(
                filteredAccelerometerValues[0] * filteredAccelerometerValues[0]
                        + filteredAccelerometerValues[1] * filteredAccelerometerValues[1]
                        + filteredAccelerometerValues[2] * filteredAccelerometerValues[2]);
        if (length <= 0.001f) {
            return;
        }
        float x = filteredAccelerometerValues[0] / length;
        float y = filteredAccelerometerValues[1] / length;
        float z = filteredAccelerometerValues[2] / length;
        minAccelX = Math.min(minAccelX, x);
        maxAccelX = Math.max(maxAccelX, x);
        minAccelY = Math.min(minAccelY, y);
        maxAccelY = Math.max(maxAccelY, y);
        minAccelZ = Math.min(minAccelZ, z);
        maxAccelZ = Math.max(maxAccelZ, z);
    }

    /*
     * Function: updateCompassUi
     * Arguments: none.
     * Calls: horizonReferenceProgressText(), isCaptureSetupReady(), and view
     * setters.
     * Flow: keep the AR/horizon label, sweep button, capture enabled state, and
     * status text synchronized with the capture setup gate.
     */
    private void updateCompassUi() {
        if (!isOnMainThread()) {
            runOnUiThread(this::updateCompassUi);
            return;
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boolean ready = isCaptureSetupReady();
        if (compassStatusText != null) {
            setViewText(compassStatusText, String.format(
                    Locale.US,
                    "AR %.0f deg\n%s",
                    compassHeadingDegrees,
                    ready ? "ready" : safeText(horizonReferenceProgressText(), "warming up")));
        }
        if (captureButton != null) {
            captureButton.setEnabled(ready && !capturePaused && !captureInProgress && latestArJpeg != null);
            setViewText(captureButton, "Capture Block");
        }
        if (calibrationButton != null) {
            setViewText(calibrationButton, !compassCalibrationComplete
                    ? !calibrationStarted
                    ? "Start"
                    : isCompassCalibrationReady()
                    ? "Complete"
                    : "Calibrating"
                    : ready
                    ? currentSweepButtonText()
                    : horizonSweepAwaitingClose
                    ? "End"
                    : horizonSweepStarted
                    ? "Restart"
                    : "Start");
        }
        if (calibrationProgressView != null) {
            calibrationProgressView.setCalibrationState(
                    calibrationStarted || horizonSweepStarted,
                    ready,
                    timeProgress(),
                    arCoreReady ? 1f : 0f,
                    stabilityProgress(),
                    headingProgress(),
                    horizonPitchProgress());
        }
        if (statusText != null) {
            if (!arCoreReady) {
                setViewText(statusText, "ARCore required");
            } else if (!compassCalibrationComplete) {
                setViewText(statusText, calibrationStarted
                        ? "Calibrate compass"
                        : "Compass calibration");
            } else if (!ready) {
                setViewText(statusText, horizonSweepStarted
                        ? horizonSweepAwaitingClose ? "Return to start point" : currentSweepStatusText()
                        : "Align start point");
            } else if (!"Image captured".contentEquals(statusText.getText())) {
                setViewText(statusText, capturePaused ? "Capture paused" : currentSweepCaptureStatusText());
            }
        }
        updateGuideState();
    }

    private static void setViewText(TextView view, String text) {
        view.setText(safeText(text, ""));
    }

    private static String safeText(String text, String fallback) {
        return text == null ? fallback : text;
    }

    /*
     * Function: currentSweepButtonText
     * Arguments: none.
     * Calls: currentSweepLayerComplete().
     * Flow: label the shared capture-progress button for sweep layers and the
     * one-shot polar layers.
     */
    private String currentSweepButtonText() {
        if (isCurrentHighPitchRingLayer()) {
            if (currentSweepLayerComplete()) {
                return currentSweepLayerIndex >= SWEEP_LAYER_PITCH_DEGREES.length - 1
                        ? "Spherify!"
                        : "Next";
            }
            return "Capture";
        }
        if (isCurrentPolarLayer()) {
            if (currentSweepLayerComplete()) {
                return currentSweepLayerIndex >= SWEEP_LAYER_PITCH_DEGREES.length - 1
                        ? "Spherify!"
                        : "Next";
            }
            return "Capture";
        }
        if (sweepLayerPaintingActive) {
            return "Stop";
        }
        if (currentSweepLayerComplete()) {
            return currentSweepLayerIndex >= SWEEP_LAYER_PITCH_DEGREES.length - 1
                    ? "Spherify!"
                    : "Next";
        }
        return "Start";
    }

    /*
     * Function: isCaptureSetupReady
     * Arguments: none.
     * Calls: horizonReferenceBinCount() and System.currentTimeMillis().
     * Flow: enforce the capture gate by requiring ARCore support, a completed
     * compass calibration sweep, orientation readings, and a 360-degree horizon
     * reference sweep before photo capture.
     */
    private boolean isCaptureSetupReady() {
        if (!arCoreReady || !compassCalibrationComplete || !horizonSweepStarted
                || !hasHeadingReading || !hasPitchRollReading) {
            return false;
        }
        if (horizonSweepComplete) {
            return true;
        }
        long now = System.currentTimeMillis();
        boolean headingStable = stableHeadingStartedAt > 0L
                && now - stableHeadingStartedAt >= REQUIRED_HEADING_STABILITY_MS;
        boolean longEnough = now - calibrationStartedAt >= REQUIRED_CALIBRATION_DURATION_MS;
        boolean sweepCovered = headingStable
                && longEnough
                && horizonReferenceBinCount() >= REQUIRED_HORIZON_REFERENCE_BINS
                && Math.abs(pitchDegrees) <= MAX_HORIZON_SWEEP_PITCH_DEGREES;
        if (sweepCovered) {
            horizonSweepAwaitingClose = true;
        }
        return false;
    }

    /*
     * Function: isAlignedWithHorizonStart
     * Arguments: none.
     * Calls: headingDeltaDegrees() and Math.abs().
     * Flow: after the 360-degree sweep has enough coverage, require the user to
     * point the reticle back at the original distant landmark before unlocking.
     */
    private boolean isAlignedWithHorizonStart() {
        if (!horizonSweepStarted || !hasHeadingReading || !hasPitchRollReading) {
            return false;
        }
        return headingDeltaDegrees(compassHeadingDegrees, horizonStartHeadingDegrees)
                <= MAX_SWEEP_CLOSE_YAW_DELTA_DEGREES
                && Math.abs(pitchDegrees - horizonStartPitchDegrees)
                <= MAX_SWEEP_CLOSE_PITCH_DELTA_DEGREES;
    }

    /*
     * Function: horizonReferenceProgressText
     * Arguments: none.
     * Calls: horizonReferenceBinCount() and System.currentTimeMillis().
     * Flow: produce concise progress text explaining how much of the 360-degree
     * reference sweep has been sampled.
     */
    private String horizonReferenceProgressText() {
        if (!arCoreReady) {
            return "ARCore required";
        }
        if (!compassCalibrationComplete) {
            return compassCalibrationProgressText();
        }
        if (!horizonSweepStarted) {
            return "align start point";
        }
        if (horizonSweepAwaitingClose && !horizonSweepComplete) {
            return String.format(
                    Locale.US,
                    "return %.0f deg / %.0f deg",
                    headingDeltaDegrees(compassHeadingDegrees, horizonStartHeadingDegrees),
                    Math.abs(pitchDegrees - horizonStartPitchDegrees));
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - calibrationStartedAt);
        return String.format(
                Locale.US,
                "%ds/%ds, yaw %d/%d, pitch %s, %s",
                elapsed / 1000L,
                REQUIRED_CALIBRATION_DURATION_MS / 1000L,
                horizonReferenceBinCount(),
                REQUIRED_HORIZON_REFERENCE_BINS,
                Math.abs(pitchDegrees) <= MAX_HORIZON_SWEEP_PITCH_DEGREES ? "level" : "too high/low",
                currentSweepSpeedMessage());
    }

    /*
     * Function: isCompassCalibrationReady
     * Arguments: none.
     * Calls: System.currentTimeMillis(), headingBinCount(), and Math.abs().
     * Flow: require a short, stable, broad 360-degree horizon sweep before the
     * capture-reference sweep can record any imagery.
     */
    private boolean isCompassCalibrationReady() {
        if (!calibrationStarted || !hasHeadingReading || !hasPitchRollReading) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean headingStable = stableHeadingStartedAt > 0L
                && now - stableHeadingStartedAt >= REQUIRED_HEADING_STABILITY_MS;
        boolean longEnough = now - calibrationStartedAt >= REQUIRED_CALIBRATION_DURATION_MS;
        return headingStable
                && longEnough
                && headingBinCount() >= REQUIRED_HEADING_BINS
                && Math.abs(pitchDegrees) <= MAX_HORIZON_SWEEP_PITCH_DEGREES;
    }

    /*
     * Function: compassCalibrationProgressText
     * Arguments: none.
     * Calls: headingBinCount() and System.currentTimeMillis().
     * Flow: summarize calibration progress without mixing it with the later
     * horizon reference image sweep.
     */
    private String compassCalibrationProgressText() {
        if (!calibrationStarted) {
            return "tap Start for compass sweep";
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - calibrationStartedAt);
        return String.format(
                Locale.US,
                "calibrate %ds/%ds, yaw %d/%d, pitch %s",
                elapsed / 1000L,
                REQUIRED_CALIBRATION_DURATION_MS / 1000L,
                headingBinCount(),
                REQUIRED_HEADING_BINS,
                Math.abs(pitchDegrees) <= MAX_HORIZON_SWEEP_PITCH_DEGREES ? "level" : "too high/low");
    }

    /*
     * Function: updateSweepMotionFeedback
     * Arguments: none.
     * Calls: headingDeltaDegrees() and System.currentTimeMillis().
     * Flow: estimate how quickly the user is turning during the horizon strip
     * pass so the overlay can warn when the sweep pace is hard to stitch.
     */
    private void updateSweepMotionFeedback() {
        if (!horizonSweepStarted || horizonSweepAwaitingClose || !hasHeadingReading) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastSweepSpeedAt == 0L) {
            lastSweepSpeedAt = now;
            lastSweepSpeedHeadingDegrees = compassHeadingDegrees;
            sweepSpeedMessage = "Begin sweep";
            return;
        }
        long elapsedMs = now - lastSweepSpeedAt;
        if (elapsedMs < SWEEP_SPEED_SAMPLE_MIN_MS) {
            return;
        }
        float yawDelta = headingDeltaDegrees(compassHeadingDegrees, lastSweepSpeedHeadingDegrees);
        sweepYawRateDegreesPerSecond = yawDelta * 1000f / Math.max(1L, elapsedMs);
        if (sweepYawRateDegreesPerSecond < MIN_SWEEP_YAW_RATE_DEGREES_PER_SECOND) {
            sweepSpeedMessage = "Move faster";
        } else if (sweepYawRateDegreesPerSecond > MAX_SWEEP_YAW_RATE_DEGREES_PER_SECOND) {
            sweepSpeedMessage = "Move slower";
        } else {
            sweepSpeedMessage = "Good pace";
        }
        lastSweepSpeedAt = now;
        lastSweepSpeedHeadingDegrees = compassHeadingDegrees;
    }

    /*
     * Function: resetSweepMotionFeedback
     * Arguments: none.
     * Calls: no external functions.
     * Flow: clear transient sweep pace estimates before starting a fresh strip.
     */
    private void resetSweepMotionFeedback() {
        lastSweepSpeedAt = 0L;
        lastSweepSpeedHeadingDegrees = compassHeadingDegrees;
        sweepYawRateDegreesPerSecond = 0f;
        sweepSpeedMessage = "Begin sweep";
        resetKeyframeStillness();
    }

    private void updateKeyframeStillness() {
        long now = System.currentTimeMillis();
        if (lastKeyframeMotionAt == 0L) {
            lastKeyframeMotionAt = now;
            lastKeyframeHeadingDegrees = compassHeadingDegrees;
            lastKeyframePitchDegrees = pitchDegrees;
            lastKeyframeRollDegrees = rollDegrees;
            keyframeStillStartedAt = now;
            return;
        }
        long elapsedMs = now - lastKeyframeMotionAt;
        if (elapsedMs < 80L) {
            return;
        }
        keyframeYawRateDegreesPerSecond = headingDeltaDegrees(compassHeadingDegrees, lastKeyframeHeadingDegrees)
                * 1000f / Math.max(1L, elapsedMs);
        keyframePitchRateDegreesPerSecond = Math.abs(pitchDegrees - lastKeyframePitchDegrees)
                * 1000f / Math.max(1L, elapsedMs);
        keyframeRollRateDegreesPerSecond = Math.abs(rollDegrees - lastKeyframeRollDegrees)
                * 1000f / Math.max(1L, elapsedMs);
        if (!isKeyframeMotionQuiet()) {
            keyframeStillStartedAt = 0L;
        } else if (keyframeStillStartedAt == 0L) {
            keyframeStillStartedAt = now;
        }
        lastKeyframeMotionAt = now;
        lastKeyframeHeadingDegrees = compassHeadingDegrees;
        lastKeyframePitchDegrees = pitchDegrees;
        lastKeyframeRollDegrees = rollDegrees;
    }

    private void resetKeyframeStillness() {
        lastKeyframeMotionAt = 0L;
        keyframeStillStartedAt = 0L;
        keyframeYawRateDegreesPerSecond = 0f;
        keyframePitchRateDegreesPerSecond = 0f;
        keyframeRollRateDegreesPerSecond = 0f;
    }

    private boolean isKeyframeMotionQuiet() {
        return keyframeYawRateDegreesPerSecond <= MAX_KEYFRAME_YAW_RATE_DEGREES_PER_SECOND
                && keyframePitchRateDegreesPerSecond <= MAX_KEYFRAME_PITCH_RATE_DEGREES_PER_SECOND
                && keyframeRollRateDegreesPerSecond <= MAX_KEYFRAME_ROLL_RATE_DEGREES_PER_SECOND;
    }

    private boolean isKeyframeStable() {
        return keyframeStillStartedAt > 0L
                && System.currentTimeMillis() - keyframeStillStartedAt >= REQUIRED_KEYFRAME_STILL_MS
                && isKeyframeMotionQuiet();
    }

    private float keyframeLockProgress() {
        if (keyframeStillStartedAt == 0L || !isKeyframeMotionQuiet()) {
            return 0f;
        }
        return clamp01((System.currentTimeMillis() - keyframeStillStartedAt)
                / (float) REQUIRED_KEYFRAME_STILL_MS);
    }

    private String keyframeStillnessMessage() {
        if (!isKeyframeMotionQuiet()) {
            return "Hold still";
        }
        if (!isKeyframeStable()) {
            return "Hold for keyframe";
        }
        return "Keyframe ready";
    }

    /*
     * Function: currentSweepStatusText
     * Arguments: none.
     * Calls: currentSweepSpeedMessage().
     * Flow: produce the large status text shown above the AR preview while the
     * user is building the horizon strip.
     */
    private String currentSweepStatusText() {
        if (horizonSweepStarted && !horizonSweepComplete) {
            return keyframeStillnessMessage();
        }
        String message = currentSweepSpeedMessage();
        return "Good pace".equals(message) ? "Sweep horizon" : message;
    }

    /*
     * Function: currentSweepSpeedMessage
     * Arguments: none.
     * Calls: no external functions.
     * Flow: return a stable, user-facing pace hint for both text and overlay UI.
     */
    private String currentSweepSpeedMessage() {
        return sweepSpeedMessage == null || sweepSpeedMessage.isEmpty()
                ? "Begin sweep"
                : sweepSpeedMessage;
    }

    /*
     * Function: currentSweepTiltDeltaDegrees
     * Arguments: none.
     * Calls: no external functions.
     * Flow: compare the live pitch against the start landmark pitch so the guide
     * can show an immediate tilt-drift warning.
     */
    private float currentSweepTiltDeltaDegrees() {
        if (!horizonSweepStarted || horizonSweepComplete || horizonSweepAwaitingClose) {
            return 0f;
        }
        return pitchDegrees - horizonStartPitchDegrees;
    }

    /*
     * Function: timeProgress
     * Arguments: none.
     * Calls: System.currentTimeMillis() and clamp01().
     * Flow: convert elapsed calibration time into a 0..1 progress value for the
     * visual checklist.
     */
    private float timeProgress() {
        if (!calibrationStarted) {
            return 0f;
        }
        return clamp01((System.currentTimeMillis() - calibrationStartedAt)
                / (float) REQUIRED_CALIBRATION_DURATION_MS);
    }

    /*
     * Function: accuracyProgress
     * Arguments: none.
     * Calls: System.currentTimeMillis() and clamp01().
     * Flow: show medium accuracy as partial progress and high accuracy held long
     * enough as complete progress.
     */
    private float accuracyProgress() {
        if (compassAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH && highAccuracyStartedAt > 0L) {
            return clamp01((System.currentTimeMillis() - highAccuracyStartedAt)
                    / (float) REQUIRED_COMPASS_HIGH_ACCURACY_MS);
        }
        if (compassAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            return 0.5f;
        }
        return 0f;
    }

    /*
     * Function: stabilityProgress
     * Arguments: none.
     * Calls: System.currentTimeMillis() and clamp01().
     * Flow: convert the current stable-heading duration into visual progress.
     */
    private float stabilityProgress() {
        if (stableHeadingStartedAt == 0L) {
            return 0f;
        }
        return clamp01((System.currentTimeMillis() - stableHeadingStartedAt)
                / (float) REQUIRED_HEADING_STABILITY_MS);
    }

    /*
     * Function: headingProgress
     * Arguments: none.
     * Calls: headingBinCount() and clamp01().
     * Flow: convert observed yaw sector coverage into visual progress.
     */
    private float headingProgress() {
        if (!compassCalibrationComplete) {
            return clamp01(headingBinCount() / (float) REQUIRED_HEADING_BINS);
        }
        return clamp01(horizonReferenceBinCount() / (float) REQUIRED_HORIZON_REFERENCE_BINS);
    }

    /*
     * Function: horizonPitchProgress
     * Arguments: none.
     * Calls: Math.abs() and clamp01().
     * Flow: show whether the user is keeping the initial reference pass near the
     * horizon plane while collecting 360-degree samples.
     */
    private float horizonPitchProgress() {
        if ((!calibrationStarted && !horizonSweepStarted) || !hasPitchRollReading) {
            return 0f;
        }
        return clamp01(1f - Math.abs(pitchDegrees) / MAX_HORIZON_SWEEP_PITCH_DEGREES);
    }

    /*
     * Function: recordHorizonReferenceSample
     * Arguments: none.
     * Calls: createHorizonReferenceStrip(), updateHorizonReferencePanorama(),
     * TargetGuideView.setHorizonReference().
     * Flow: during the initial sweep, sample a central vertical slice for each
     * yaw sector while the phone is close to the horizon plane.
     */
    private void recordHorizonReferenceSample() {
        if (!compassCalibrationComplete
                || !horizonSweepStarted
                || horizonSweepComplete
                || previewView == null
                || !hasHeadingReading
                || !hasPitchRollReading
                || !isKeyframeStable()
                || Math.abs(pitchDegrees) > MAX_HORIZON_SWEEP_PITCH_DEGREES) {
            return;
        }
        int bin = horizonReferenceBinForHeading(compassHeadingDegrees);
        if (horizonReferenceBins[bin]) {
            return;
        }
        Bitmap source;
        synchronized (arFrameLock) {
            if (latestCaptureReadyJpeg == null || latestCaptureReadyMetadataJson == null) {
                return;
            }
            source = latestArPreviewBitmap == null ? null : latestArPreviewBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (source == null) {
            return;
        }
        Bitmap portraitSource = orientReferenceSampleToPortrait(source);
        Bitmap reference = createHorizonReferenceStrip(portraitSource);
        if (portraitSource != source) {
            portraitSource.recycle();
        }
        source.recycle();
        horizonReferenceBins[bin] = true;
        sweepCaptureBins[0][sweepCaptureBinForHeading(compassHeadingDegrees)] = true;
        if (horizonReferenceFrames[bin] != null) {
            horizonReferenceFrames[bin].recycle();
        }
        horizonReferenceFrames[bin] = reference;
        updateHorizonReferencePanorama(bin, reference);
        saveHorizonReferenceDraftFrame(bin);
        if (guideView != null) {
            guideView.setHorizonReference(
                    paintedPhotospherePreview,
                    horizonSweepStarted,
                    isCaptureSetupReady(),
                    horizonReferenceBinCount() / (float) REQUIRED_HORIZON_REFERENCE_BINS,
                    horizonSweepAwaitingClose,
                    isAlignedWithHorizonStart(),
                    currentSweepSpeedMessage(),
                    currentSweepTiltDeltaDegrees(),
                    horizonReferenceBins,
                    horizonReferenceBinForHeading(compassHeadingDegrees));
        }
    }

    private void saveHorizonReferenceDraftFrame(int horizonBin) {
        byte[] jpeg;
        String exposureJson;
        synchronized (arFrameLock) {
            jpeg = latestCaptureReadyJpeg == null ? null : latestCaptureReadyJpeg.clone();
            exposureJson = latestCaptureReadyMetadataJson;
        }
        String metadataBlocker = requiredCaptureMetadataBlocker(exposureJson);
        if (jpeg == null || metadataBlocker != null || !hasHeadingReading || !hasPitchRollReading) {
            return;
        }
        File outputFile;
        try {
            outputFile = library.createDraftFrameFile();
        } catch (IOException ignored) {
            return;
        }
        float recordedHeadingDegrees = compassHeadingDegrees;
        float recordedPitchDegrees = pitchDegrees;
        float recordedRollDegrees = rollDegrees;
        int targetYawDegrees = Math.round(normalizeHeading(
                (horizonBin + 0.5f) * 360f / HORIZON_REFERENCE_BIN_COUNT
                        - horizonStartHeadingDegrees));
        int targetPitchDegrees = 0;
        new Thread(() -> {
            byte[] uprightJpeg = orientCapturedJpegUpright(jpeg);
            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                output.write(uprightJpeg);
                String location = readLocationSummary();
                library.recordDraftFrame(
                        outputFile,
                        sessionId,
                        location,
                        recordedHeadingDegrees,
                        recordedPitchDegrees,
                        recordedRollDegrees,
                        targetYawDegrees,
                        targetPitchDegrees,
                        "horizon-keyframe",
                        captureProfile,
                        exposureJson);
            } catch (IOException ignored) {
                // Best-effort cleanup of an incomplete horizon keyframe.
                outputFile.delete();
            }
        }).start();
    }

    /*
     * Function: updateHorizonReferencePanorama
     * Arguments: bin is the yaw sector index; reference is the portrait thumbnail
     * for that sector.
     * Calls: Bitmap.createBitmap(), Canvas.drawColor(), Canvas.drawBitmap(), and
     * featherHorizonReferenceSeams().
     * Flow: maintain a single live 360-degree horizon strip by placing each
     * center-sampled strip next to its neighbors and softening visible seams.
     */
    private void updateHorizonReferencePanorama(int bin, Bitmap reference) {
        if (horizonReferencePanorama == null) {
            horizonReferencePanorama = Bitmap.createBitmap(
                    HORIZON_REFERENCE_SAMPLE_WIDTH * HORIZON_REFERENCE_BIN_COUNT,
                    HORIZON_REFERENCE_SAMPLE_HEIGHT,
                    Bitmap.Config.ARGB_8888);
        }
        Canvas panoramaCanvas = new Canvas(horizonReferencePanorama);
        panoramaCanvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR);
        Rect sourceRect = new Rect(0, 0, HORIZON_REFERENCE_SAMPLE_WIDTH, HORIZON_REFERENCE_SAMPLE_HEIGHT);
        Rect destinationRect = new Rect();
        for (int i = 0; i < horizonReferenceFrames.length; i++) {
            Bitmap frame = horizonReferenceFrames[i];
            if (frame == null || frame.isRecycled()) {
                continue;
            }
            destinationRect.set(
                    i * HORIZON_REFERENCE_SAMPLE_WIDTH,
                    0,
                    (i + 1) * HORIZON_REFERENCE_SAMPLE_WIDTH,
                    HORIZON_REFERENCE_SAMPLE_HEIGHT);
            panoramaCanvas.drawBitmap(frame, sourceRect, destinationRect, bitmapDrawPaint);
        }
        featherHorizonReferenceSeams();
        rebuildPaintedPhotospherePreview();
    }

    /*
     * Function: updateSweepLayerPreviewPanorama
     * Arguments: none.
     * Calls: Bitmap.createBitmap(), Canvas.drawBitmap(), and
     * featherBitmapSeams().
     * Flow: rebuild the active non-horizon layer's preview strip from captured
     * slices so the overlay shows live painting beyond the horizon reference.
     */
    private void updateSweepLayerPreviewPanorama() {
        if (currentSweepLayerIndex <= 0 || currentSweepLayerIndex >= sweepCapturePreviewFrames.length) {
            return;
        }
        if (sweepLayerPreviewPanorama == null) {
            sweepLayerPreviewPanorama = Bitmap.createBitmap(
                    HORIZON_REFERENCE_SAMPLE_WIDTH * SWEEP_CAPTURE_BIN_COUNT,
                    HORIZON_REFERENCE_SAMPLE_HEIGHT,
                    Bitmap.Config.ARGB_8888);
        }
        Canvas panoramaCanvas = new Canvas(sweepLayerPreviewPanorama);
        panoramaCanvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR);
        Rect sourceRect = new Rect(0, 0, HORIZON_REFERENCE_SAMPLE_WIDTH, HORIZON_REFERENCE_SAMPLE_HEIGHT);
        Rect destinationRect = new Rect();
        for (int i = 0; i < sweepCapturePreviewFrames[currentSweepLayerIndex].length; i++) {
            Bitmap frame = sweepCapturePreviewFrames[currentSweepLayerIndex][i];
            if (frame == null || frame.isRecycled()) {
                continue;
            }
            destinationRect.set(
                    i * HORIZON_REFERENCE_SAMPLE_WIDTH,
                    0,
                    (i + 1) * HORIZON_REFERENCE_SAMPLE_WIDTH,
                    HORIZON_REFERENCE_SAMPLE_HEIGHT);
            panoramaCanvas.drawBitmap(frame, sourceRect, destinationRect, bitmapDrawPaint);
        }
        featherBitmapSeams(sweepLayerPreviewPanorama, sweepCaptureBins[currentSweepLayerIndex]);
        rebuildPaintedPhotospherePreview();
    }

    /*
     * Function: rebuildPaintedPhotospherePreview
     * Arguments: none.
     * Calls: drawLayerPreviewRow(), Canvas.drawColor(), and Bitmap APIs.
     * Flow: build one compact equirectangular-style preview of every painted
     * layer so the overlay reflects the current state of the photosphere.
     */
    private void rebuildPaintedPhotospherePreview() {
        int width = HORIZON_REFERENCE_SAMPLE_WIDTH * SWEEP_CAPTURE_BIN_COUNT;
        int height = HORIZON_REFERENCE_SAMPLE_HEIGHT * SWEEP_LAYER_PITCH_DEGREES.length;
        if (paintedPhotospherePreview == null) {
            paintedPhotospherePreview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(paintedPhotospherePreview);
        canvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR);
        for (int layer = 0; layer < SWEEP_LAYER_PITCH_DEGREES.length; layer++) {
            drawLayerPreviewRow(canvas, layer, layer * HORIZON_REFERENCE_SAMPLE_HEIGHT);
        }
    }

    /*
     * Function: drawLayerPreviewRow
     * Arguments: canvas is the composite target; layer is the paint layer index;
     * top is the y position of that row.
     * Calls: Canvas.drawBitmap().
     * Flow: draw either horizon samples or layer capture samples into a stable
     * row within the overall painted photosphere preview.
     */
    private void drawLayerPreviewRow(Canvas canvas, int layer, int top) {
        Rect sourceRect = new Rect(0, 0, HORIZON_REFERENCE_SAMPLE_WIDTH, HORIZON_REFERENCE_SAMPLE_HEIGHT);
        Rect destinationRect = new Rect();
        for (int bin = 0; bin < SWEEP_CAPTURE_BIN_COUNT; bin++) {
            int destinationBin = bin;
            Bitmap frame;
            if (layer == 0) {
                frame = horizonReferenceFrames[bin];
                destinationBin = relativeBinForRawHorizonBin(bin);
            } else {
                frame = sweepCapturePreviewFrames[layer][bin];
            }
            if (frame == null || frame.isRecycled()) {
                continue;
            }
            destinationRect.set(
                    destinationBin * HORIZON_REFERENCE_SAMPLE_WIDTH,
                    top,
                    (destinationBin + 1) * HORIZON_REFERENCE_SAMPLE_WIDTH,
                    top + HORIZON_REFERENCE_SAMPLE_HEIGHT);
            canvas.drawBitmap(frame, sourceRect, destinationRect, bitmapDrawPaint);
        }
    }

    /*
     * Function: createHorizonReferenceStrip
     * Arguments: portraitSource is the normalized AR camera frame.
     * Calls: Bitmap.createBitmap() and Bitmap.createScaledBitmap().
     * Flow: take the central vertical slice of the camera image instead of
     * squashing the whole portrait frame into a narrow yaw bin.
     */
    private Bitmap createHorizonReferenceStrip(Bitmap portraitSource) {
        int cropWidth = Math.max(1, Math.round(portraitSource.getWidth() * 0.28f));
        int cropX = Math.max(0, (portraitSource.getWidth() - cropWidth) / 2);
        Bitmap cropped = Bitmap.createBitmap(
                portraitSource,
                cropX,
                0,
                cropWidth,
                portraitSource.getHeight());
        Bitmap strip = Bitmap.createScaledBitmap(
                cropped,
                HORIZON_REFERENCE_SAMPLE_WIDTH,
                HORIZON_REFERENCE_SAMPLE_HEIGHT,
                true);
        cropped.recycle();
        return strip;
    }

    /*
     * Function: createCurrentSweepPreviewStrip
     * Arguments: none.
     * Calls: orientReferenceSampleToPortrait() and createHorizonReferenceStrip().
     * Flow: copy the latest AR preview frame and turn it into the same kind of
     * small central strip used by the horizon reference overlay.
     */
    private Bitmap createCurrentSweepPreviewStrip() {
        Bitmap source;
        synchronized (arFrameLock) {
            source = latestArPreviewBitmap == null ? null : latestArPreviewBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (source == null) {
            return null;
        }
        Bitmap portraitSource = orientReferenceSampleToPortrait(source);
        Bitmap strip = createHorizonReferenceStrip(portraitSource);
        if (portraitSource != source) {
            portraitSource.recycle();
        }
        source.recycle();
        return strip;
    }

    /*
     * Function: infillSweepLayerFromSource
     * Arguments: targetLayer is the high/low layer to fill; sourceLayer is the
     * nearest painted layer; sampleTopThird chooses top or bottom sample colour.
     * Calls: averageLayerEdgeColor() and Bitmap.createBitmap().
     * Flow: create simple colour infill strips for low-detail pole regions.
     */
    private void infillSweepLayerFromSource(int targetLayer, int sourceLayer, boolean sampleTopThird) {
        if (targetLayer < 0 || targetLayer >= sweepCapturePreviewFrames.length) {
            return;
        }
        int color = averageLayerEdgeColor(sourceLayer, sampleTopThird);
        for (int bin = 0; bin < SWEEP_CAPTURE_BIN_COUNT; bin++) {
            if (sweepCapturePreviewFrames[targetLayer][bin] != null) {
                sweepCapturePreviewFrames[targetLayer][bin].recycle();
            }
            Bitmap infill = Bitmap.createBitmap(
                    HORIZON_REFERENCE_SAMPLE_WIDTH,
                    HORIZON_REFERENCE_SAMPLE_HEIGHT,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(infill);
            canvas.drawColor(color);
            sweepCapturePreviewFrames[targetLayer][bin] = infill;
            sweepCaptureBins[targetLayer][bin] = true;
        }
    }

    /*
     * Function: averageLayerEdgeColor
     * Arguments: layer is the captured source layer; sampleTopThird chooses the
     * sampled vertical third.
     * Calls: averageBitmapThirdColor().
     * Flow: combine captured edge colours into one representative infill colour.
     */
    private int averageLayerEdgeColor(int layer, boolean sampleTopThird) {
        long a = 0L;
        long r = 0L;
        long g = 0L;
        long b = 0L;
        long count = 0L;
        if (layer >= 0 && layer < sweepCapturePreviewFrames.length) {
            for (Bitmap frame : sweepCapturePreviewFrames[layer]) {
                if (frame == null || frame.isRecycled()) {
                    continue;
                }
                int color = averageBitmapThirdColor(frame, sampleTopThird);
                a += (color >>> 24) & 0xFF;
                r += (color >>> 16) & 0xFF;
                g += (color >>> 8) & 0xFF;
                b += color & 0xFF;
                count++;
            }
        }
        if (count == 0L) {
            return 0xAA0F172A;
        }
        return ((int) (a / count) << 24)
                | ((int) (r / count) << 16)
                | ((int) (g / count) << 8)
                | (int) (b / count);
    }

    /*
     * Function: averageBitmapThirdColor
     * Arguments: bitmap is a captured strip; sampleTopThird selects top or
     * bottom third.
     * Calls: Bitmap.getPixel().
     * Flow: estimate a simple representative colour for pole infill.
     */
    private int averageBitmapThirdColor(Bitmap bitmap, boolean sampleTopThird) {
        int yStart = sampleTopThird ? 0 : bitmap.getHeight() * 2 / 3;
        int yEnd = sampleTopThird ? bitmap.getHeight() / 3 : bitmap.getHeight();
        long a = 0L;
        long r = 0L;
        long g = 0L;
        long b = 0L;
        long count = 0L;
        for (int y = yStart; y < yEnd; y += 3) {
            for (int x = 0; x < bitmap.getWidth(); x += 3) {
                int color = bitmap.getPixel(x, y);
                a += (color >>> 24) & 0xFF;
                r += (color >>> 16) & 0xFF;
                g += (color >>> 8) & 0xFF;
                b += color & 0xFF;
                count++;
            }
        }
        if (count == 0L) {
            return 0xAA0F172A;
        }
        return ((int) (a / count) << 24)
                | ((int) (r / count) << 16)
                | ((int) (g / count) << 8)
                | (int) (b / count);
    }

    /*
     * Function: featherHorizonReferenceSeams
     * Arguments: none.
     * Calls: Bitmap.getPixels(), blendColors(), and Bitmap.setPixels().
     * Flow: replace hard joins between adjacent yaw strips with a short blended
     * transition so the live reference overlay reads as one panorama.
     */
    private void featherHorizonReferenceSeams() {
        if (horizonReferencePanorama == null) {
            return;
        }
        featherBitmapSeams(horizonReferencePanorama, horizonReferenceBins);
    }

    /*
     * Function: featherBitmapSeams
     * Arguments: bitmap is the strip to soften; bins says which sections exist.
     * Calls: Bitmap.getPixels(), blendColors(), and Bitmap.setPixels().
     * Flow: replace hard joins between adjacent yaw strips with a short blended
     * transition so preview overlays read as one panorama. Pixel arrays are
     * lazily allocated as fields and reused across calls to avoid repeated large
     * allocations (~1.3 MB each) during the horizon sweep.
     */
    private void featherBitmapSeams(Bitmap bitmap, boolean[] bins) {
        if (bitmap == null || bins == null) {
            return;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int size = width * height;
        if (featherPixelBuffer == null || featherPixelBuffer.length < size) {
            featherPixelBuffer = new int[size];
            featherOriginalBuffer = new int[size];
        }
        int overlap = Math.max(4, HORIZON_REFERENCE_SAMPLE_WIDTH / 6);
        bitmap.getPixels(featherPixelBuffer, 0, width, 0, 0, width, height);
        System.arraycopy(featherPixelBuffer, 0, featherOriginalBuffer, 0, size);
        for (int boundaryBin = 1; boundaryBin < bins.length; boundaryBin++) {
            if (!bins[boundaryBin - 1] || !bins[boundaryBin]) {
                continue;
            }
            int boundaryX = boundaryBin * HORIZON_REFERENCE_SAMPLE_WIDTH;
            int leftSampleX = Math.max(0, boundaryX - overlap - 1);
            int rightSampleX = Math.min(width - 1, boundaryX + overlap);
            for (int dx = -overlap; dx < overlap; dx++) {
                int x = boundaryX + dx;
                if (x < 0 || x >= width) {
                    continue;
                }
                float amount = (dx + overlap) / (float) (overlap * 2 - 1);
                for (int y = 0; y < height; y++) {
                    int row = y * width;
                    featherPixelBuffer[row + x] = blendColors(
                            featherOriginalBuffer[row + leftSampleX],
                            featherOriginalBuffer[row + rightSampleX],
                            amount);
                }
            }
        }
        bitmap.setPixels(featherPixelBuffer, 0, width, 0, 0, width, height);
    }

    /*
     * Function: blendColors
     * Arguments: first/second are ARGB colors and amount is the 0..1 mix.
     * Calls: clamp01().
     * Flow: linearly blend two bitmap pixels while preserving alpha.
     */
    private static int blendColors(int first, int second, float amount) {
        float t = clamp01(amount);
        int a = Math.round(((first >>> 24) & 0xFF) * (1f - t) + ((second >>> 24) & 0xFF) * t);
        int r = Math.round(((first >>> 16) & 0xFF) * (1f - t) + ((second >>> 16) & 0xFF) * t);
        int g = Math.round(((first >>> 8) & 0xFF) * (1f - t) + ((second >>> 8) & 0xFF) * t);
        int b = Math.round((first & 0xFF) * (1f - t) + (second & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /*
     * Function: orientReferenceSampleToPortrait
     * Arguments: source is a latest ARCore CPU frame snapshot.
     * Calls: Matrix.postRotate() and Bitmap.createBitmap().
     * Flow: ARCore CPU images often arrive in camera sensor orientation; normalize
     * horizon reference thumbnails to portrait before drawing them side-by-side.
     */
    private Bitmap orientReferenceSampleToPortrait(Bitmap source) {
        if (source.getHeight() >= source.getWidth()) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(90f);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    /*
     * Function: horizonReferenceBinForHeading
     * Arguments: headingDegrees is the current yaw around the capture session.
     * Calls: normalizeHeading().
     * Flow: convert heading into one of the fixed horizon-reference sectors.
     */
    private int horizonReferenceBinForHeading(float headingDegrees) {
        int bin = (int) (normalizeHeading(headingDegrees) / (360f / HORIZON_REFERENCE_BIN_COUNT));
        return Math.max(0, Math.min(HORIZON_REFERENCE_BIN_COUNT - 1, bin));
    }

    /*
     * Function: relativeBinForRawHorizonBin
     * Arguments: rawBin is a horizon-reference bin in absolute AR yaw order.
     * Calls: normalizeHeading().
     * Flow: remap the initial horizon row into the same user Start-origin yaw
     * coordinates used by subsequent painted layers.
     */
    private int relativeBinForRawHorizonBin(int rawBin) {
        float rawCenterDegrees = (rawBin + 0.5f) * 360f / HORIZON_REFERENCE_BIN_COUNT;
        float relativeDegrees = normalizeHeading(rawCenterDegrees - horizonStartHeadingDegrees);
        int bin = (int) (relativeDegrees / (360f / SWEEP_CAPTURE_BIN_COUNT));
        return Math.max(0, Math.min(SWEEP_CAPTURE_BIN_COUNT - 1, bin));
    }

    /*
     * Function: horizonReferenceBinCount
     * Arguments: none.
     * Calls: no external functions.
     * Flow: count how many yaw sectors now have translucent reference imagery.
     */
    private int horizonReferenceBinCount() {
        int count = 0;
        for (boolean seen : horizonReferenceBins) {
            if (seen) {
                count++;
            }
        }
        return count;
    }

    /*
     * Function: tiltProgress
     * Arguments: none.
     * Calls: tiltCoverageCount() and clamp01().
     * Flow: convert observed tilt-axis coverage into visual progress.
     */
    private float tiltProgress() {
        return clamp01(tiltCoverageCount() / 2f);
    }

    /*
     * Function: headingBinCount
     * Arguments: none.
     * Calls: no external functions.
     * Flow: count how many heading sectors have been observed during calibration.
     */
    private int headingBinCount() {
        int count = 0;
        for (boolean seen : headingBins) {
            if (seen) {
                count++;
            }
        }
        return count;
    }

    /*
     * Function: tiltCoverageCount
     * Arguments: none.
     * Calls: no external functions.
     * Flow: count how many normalized accelerometer axes have varied enough to
     * indicate deliberate tilt calibration motion.
     */
    private int tiltCoverageCount() {
        int count = 0;
        if (maxAccelX - minAccelX >= REQUIRED_TILT_VARIATION) {
            count++;
        }
        if (maxAccelY - minAccelY >= REQUIRED_TILT_VARIATION) {
            count++;
        }
        if (maxAccelZ - minAccelZ >= REQUIRED_TILT_VARIATION) {
            count++;
        }
        return count;
    }

    /*
     * Function: guideOrientationSummary
     * Arguments: none.
     * Calls: String.format(Locale.US).
     * Flow: provide a compact yaw/pitch/roll line for the optional sensor
     * overlay so Phase 4 guidance can be debugged without reading raw vectors.
     */
    private String guideOrientationSummary() {
        if (!hasHeadingReading || !hasPitchRollReading) {
            return "waiting";
        }
        return String.format(
                Locale.US,
                "yaw %.0f, pitch %.0f, roll %.0f",
                compassHeadingDegrees,
                pitchDegrees,
                rollDegrees);
    }

    /*
     * Function: nearestOpenTargetIndex
     * Arguments: none.
     * Calls: targetDistanceScore().
     * Flow: choose the uncovered guide target closest to the current phone
     * orientation, giving the reticle one clear place to guide the user.
     */
    private int nearestOpenTargetIndex() {
        int bestIndex = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < captureTargets.length; i++) {
            if (captureTargets[i].captured) {
                continue;
            }
            float score = targetDistanceScore(captureTargets[i]);
            if (score < bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /*
     * Function: targetDistanceScore
     * Arguments: target is one yaw/pitch guide point.
     * Calls: headingDeltaDegrees() and Math.abs().
     * Flow: combine yaw and pitch distance into one simple score for nearest
     * target selection.
     */
    private float targetDistanceScore(CaptureTarget target) {
        return headingDeltaDegrees(guideHeadingDegrees, target.yawDegrees)
                + Math.abs(guidePitchDegrees - target.pitchDegrees) * 1.4f;
    }

    /*
     * Function: targetAligned
     * Arguments: target is the candidate guide point.
     * Calls: headingDeltaDegrees() and Math.abs().
     * Flow: decide whether the reticle is close enough to capture a useful
     * overlapping frame for this prototype grid.
     */
    private boolean targetAligned(CaptureTarget target) {
        return headingDeltaDegrees(guideHeadingDegrees, target.yawDegrees)
                <= MAX_TARGET_YAW_DELTA_DEGREES
                && Math.abs(guidePitchDegrees - target.pitchDegrees)
                <= MAX_TARGET_PITCH_DELTA_DEGREES;
    }

    /*
     * Function: isTargetStable
     * Arguments: none.
     * Calls: System.currentTimeMillis().
     * Flow: require the phone to dwell on a target briefly before calling the
     * alignment stable enough for manual or automatic capture.
     */
    private boolean isTargetStable() {
        return targetAlignedStartedAt > 0L
                && System.currentTimeMillis() - targetAlignedStartedAt >= REQUIRED_TARGET_LOCK_MS;
    }

    /*
     * Function: targetLockProgress
     * Arguments: none.
     * Calls: System.currentTimeMillis() and clamp01().
     * Flow: convert the current active-target lock time into a 0..1 countdown
     * fraction for the pie chart drawn over the active dot.
     */
    private float targetLockProgress() {
        if (targetAlignedStartedAt == 0L) {
            return 0f;
        }
        return clamp01((System.currentTimeMillis() - targetAlignedStartedAt)
                / (float) REQUIRED_TARGET_LOCK_MS);
    }

    /*
     * Function: coverageProgress
     * Arguments: none.
     * Calls: no helpers.
     * Flow: count captured targets and convert them into a fraction for the
     * overlay and finish summary.
     */
    private float coverageProgress() {
        int captured = 0;
        for (CaptureTarget target : captureTargets) {
            if (target.captured) {
                captured++;
            }
        }
        return captured / (float) captureTargets.length;
    }

    /*
     * Function: currentSweepLayerPitch
     * Arguments: none.
     * Calls: no external functions.
     * Flow: return the target pitch for the layer the user is currently painting.
     */
    private int currentSweepLayerPitch() {
        return SWEEP_LAYER_PITCH_DEGREES[Math.max(
                0,
                Math.min(SWEEP_LAYER_PITCH_DEGREES.length - 1, currentSweepLayerIndex))];
    }

    /*
     * Function: currentSweepLayerName
     * Arguments: none.
     * Calls: currentSweepLayerPitch().
     * Flow: produce a compact label for the active photosphere paint band.
     */
    private String currentSweepLayerName() {
        return layerName(currentSweepLayerIndex);
    }

    /*
     * Function: layerName
     * Arguments: layer is a sweep row index.
     * Calls: no external functions.
     * Flow: produce a compact label for any photosphere paint band.
     */
    private String layerName(int layer) {
        if (layer < 0 || layer >= SWEEP_LAYER_PITCH_DEGREES.length) {
            return "Layer";
        }
        int pitch = SWEEP_LAYER_PITCH_DEGREES[layer];
        if (pitch == 0) {
            return "Horizon";
        }
        return pitch > 0 ? "Upper " + pitch : "Lower " + Math.abs(pitch);
    }

    /*
     * Function: currentSweepLayerPrompt
     * Arguments: none.
     * Calls: currentSweepLayerName() and currentSweepLayerPitch().
     * Flow: tell the user how to align the phone for the active horizontal sweep.
     */
    private String currentSweepLayerPrompt() {
        int pitch = currentSweepLayerPitch();
        if (pitch == 0) {
            return "Paint horizon: rotate slowly through 360 degrees";
        }
        if (isCurrentHighPitchRingLayer()) {
            return String.format(
                    Locale.US,
                    "High %s: start at horizon, face %s, pan to %+d deg, capture, then pan back down",
                    currentSweepLayerName(),
                    nextHighPitchRingYawLabel(),
                    pitch);
        }
        if (isCurrentPolarLayer()) {
            return String.format(
                    Locale.US,
                    "Polar %s: face the start point, point %+d deg, then hold steady",
                    currentSweepLayerName(),
                    pitch);
        }
        return String.format(
                Locale.US,
                "Paint %s: tilt to %+d deg, then rotate 360 degrees",
                currentSweepLayerName(),
                pitch);
    }

    /*
     * Function: currentSweepCaptureStatusText
     * Arguments: none.
     * Calls: currentSweepLayerPrompt(), sweepLayerProgress(), and
     * currentSweepSpeedMessage().
     * Flow: keep the main Capture status focused on painting coverage rather
     * than waiting at discrete target points.
     */
    private String currentSweepCaptureStatusText() {
        if (isCurrentHighPitchRingLayer() && !currentSweepLayerComplete()) {
            if (!isSweepLayerPitchAligned()) {
                return String.format(Locale.US, "From horizon, pan to %+d deg", currentSweepLayerPitch());
            }
            int bin = currentCaptureBin();
            return bin < 0
                    ? "Rotate to " + nextHighPitchRingYawLabel()
                    : "Capture " + highPitchRingYawLabelForBin(bin);
        }
        if (!isSweepLayerPitchAligned()) {
            return String.format(Locale.US, "Tilt to %+d deg", currentSweepLayerPitch());
        }
        if (isCurrentPolarLayer() && !currentSweepLayerComplete()) {
            String rollText = polarRollInstruction();
            if (!sweepLayerPaintingActive) {
                return "Face start point - tap Capture";
            }
            return rollText;
        }
        if (!sweepLayerPaintingActive && !currentSweepLayerComplete()) {
            return "Align view - tap Start";
        }
        if (currentSweepLayerComplete()) {
            if (sweepLayerPaintingActive) {
                return "Tap Stop to close 360";
            }
            return currentSweepLayerIndex >= SWEEP_LAYER_PITCH_DEGREES.length - 1
                    ? "Photosphere painted"
                    : "Layer complete - tap Next";
        }
        return String.format(
                Locale.US,
                "%s %.0f%% - %s",
                currentSweepLayerName(),
                sweepLayerProgress(currentSweepLayerIndex) * 100f,
                keyframeStillnessMessage());
    }

    /*
     * Function: isSweepLayerPitchAligned
     * Arguments: none.
     * Calls: currentSweepLayerPitch() and Math.abs().
     * Flow: allow sweep painting when the camera is close to the active pitch
     * band instead of requiring a point target lock.
     */
    private boolean isSweepLayerPitchAligned() {
        if (isCurrentPolarLayer() && sweepLayerPaintingActive) {
            return true;
        }
        return Math.abs(pitchDegrees - currentSweepLayerPitch()) <= MAX_SWEEP_LAYER_PITCH_DELTA_DEGREES;
    }

    /*
     * Function: effectiveGuidePitchDegrees
     * Arguments: none.
     * Calls: currentSweepLayerPitch().
     * Flow: keep the polar overlay fixed on the intended vertical pitch once
     * capture starts, because yaw/pitch labels become unstable near the pole.
     */
    private float effectiveGuidePitchDegrees() {
        if (isCurrentPolarLayer() && sweepLayerPaintingActive) {
            return currentSweepLayerPitch();
        }
        return pitchDegrees;
    }

    /*
     * Function: isCurrentPolarLayer
     * Arguments: none.
     * Calls: isPolarLayer().
     * Flow: high upper/lower layers used to be near-vertical special cases.
     * They are now safer high-pitch sweep rings, so no active layer is polar.
     */
    private boolean isCurrentPolarLayer() {
        return isPolarLayer(currentSweepLayerIndex);
    }

    private boolean isCurrentHighPitchRingLayer() {
        return isHighPitchRingLayer(currentSweepLayerIndex);
    }

    private boolean isHighPitchRingLayer(int layer) {
        return layer == SWEEP_LAYER_HIGH_UPPER_INDEX || layer == SWEEP_LAYER_HIGH_LOWER_INDEX;
    }

    /*
     * Function: isPolarLayer
     * Arguments: layer is a sweep row index.
     * Calls: no external functions.
     * Flow: return false so the high upper/lower rows use normal yaw sweeps at
     * +/-65 degrees, avoiding the AR lockout seen near vertical capture.
     */
    private boolean isPolarLayer(int layer) {
        return false;
    }

    /*
     * Function: showHighPitchRingInfoIfNeeded
     * Arguments: none.
     * Calls: AlertDialog.Builder.
     * Flow: explain the eight-shot high-pitch ring once, before the first high row
     * asks the user to pan away from and back to the horizon for each shot.
     */
    private void showHighPitchRingInfoIfNeeded() {
        if (highPitchRingInfoShown || !isCurrentHighPitchRingLayer()) {
            return;
        }
        highPitchRingInfoShown = true;
        new AlertDialog.Builder(this)
                .setTitle("High-pitch ring")
                .setMessage("For the top and bottom coverage rows, take eight steady photographs every 45 degrees around the horizon.\n\nFor each one: face the horizon direction first, pan up or down to the pitch line, capture, then pan back to the horizon before rotating to the next direction.\n\nThis gives the stitcher more high-angle overlap while keeping ARCore tracking healthier than pointing almost straight up or down.")
                .setPositiveButton("OK", null)
                .show();
    }

    /*
     * Function: showPolarCaptureInfoIfNeeded
     * Arguments: none.
     * Calls: AlertDialog.Builder.
     * Flow: explain the polar exception once, just before the first polar layer
     * asks the user to stop relying on yaw and roll the phone instead.
     */
    private void showPolarCaptureInfoIfNeeded() {
        if (polarCaptureInfoShown || !isCurrentPolarLayer()) {
            return;
        }
        polarCaptureInfoShown = true;
        new AlertDialog.Builder(this)
                .setTitle("Polar capture")
                .setMessage("At the top and bottom of a photosphere, yaw and roll become unreliable.\n\nThe nearby +30/-30 degree sweeps already provide overlap, so each pole captures one vertical frame.\n\nFace your original start point, point the device vertically up or down to the target pitch line, and hold steady while Spherify captures from pitch alignment.")
                .setPositiveButton("OK", null)
                .show();
    }

    /*
     * Function: currentCaptureBin
     * Arguments: none.
     * Calls: polarSweepBinForRoll() or sweepCaptureBinForHeading().
     * Flow: use yaw bins for normal sweeps, but use one fixed center bin for
     * polar cap layers.
     */
    private int currentCaptureBin() {
        if (isCurrentHighPitchRingLayer()) {
            if (!isFirstCurrentLayerDotCaptured()) {
                return HIGH_PITCH_RING_SWEEP_BINS[0];
            }
            return highPitchRingBinForHeading(compassHeadingDegrees);
        }
        if (isCurrentPolarLayer()) {
            return POLAR_ROLL_SLOT_SWEEP_BINS[0];
        }
        if (horizonSweepComplete && !isFirstCurrentLayerDotCaptured()) {
            return 0;
        }
        return sweepCaptureBinForHeading(compassHeadingDegrees);
    }

    /*
     * Function: currentOverlayBin
     * Arguments: none.
     * Calls: polarRollSlotForRoll() or sweepCaptureBinForHeading().
     * Flow: report a compact three-position progress index for polar overlays.
     */
    private int currentOverlayBin() {
        if (isCurrentHighPitchRingLayer()) {
            if (!isFirstCurrentLayerDotCaptured()) {
                return 0;
            }
            int bin = highPitchRingBinForHeading(compassHeadingDegrees);
            return bin < 0 ? nearestHighPitchRingSlotForPendingShot() : highPitchRingSlotIndexForSweepBin(bin);
        }
        if (isCurrentPolarLayer()) {
            return 0;
        }
        if (horizonSweepComplete && !isFirstCurrentLayerDotCaptured()) {
            return 0;
        }
        return sweepCaptureBinForHeading(compassHeadingDegrees);
    }

    /*
     * Function: polarSweepBinForRoll
     * Arguments: none.
     * Calls: no external functions.
     * Flow: return the fixed center bin for one-shot polar cap capture.
     */
    private int polarSweepBinForRoll() {
        return POLAR_ROLL_SLOT_SWEEP_BINS[0];
    }

    /*
     * Function: polarRollInstruction
     * Arguments: none.
     * Calls: polarSlotCaptured().
     * Flow: tell the user whether the one-shot polar cap still needs capture.
     */
    private String polarRollInstruction() {
        return polarSlotCaptured(currentSweepLayerIndex, 0)
                ? "Polar layer complete"
                : "Align pitch line";
    }

    /*
     * Function: polarSlotName
     * Arguments: slot is 0, 1, or 2.
     * Calls: no external functions.
     * Flow: provide concise user-facing roll labels.
     */
    private String polarSlotName(int slot) {
        return "center";
    }

    /*
     * Function: polarSlotCaptured
     * Arguments: layer is a polar layer; slot is the roll slot.
     * Calls: no external functions.
     * Flow: read coverage from the synthetic preview bins used for polar frames.
     */
    private boolean polarSlotCaptured(int layer, int slot) {
        if (layer < 0 || layer >= sweepCaptureBins.length
                || slot < 0 || slot >= POLAR_ROLL_SLOT_SWEEP_BINS.length) {
            return false;
        }
        return sweepCaptureBins[layer][POLAR_ROLL_SLOT_SWEEP_BINS[slot]];
    }

    /*
     * Function: polarSlotIndexForSweepBin
     * Arguments: sweepBin is the synthetic row bin used for a polar frame.
     * Calls: no external functions.
     * Flow: map a stored polar preview bin back to its left/middle/right slot.
     */
    private int polarSlotIndexForSweepBin(int sweepBin) {
        for (int i = 0; i < POLAR_ROLL_SLOT_SWEEP_BINS.length; i++) {
            if (POLAR_ROLL_SLOT_SWEEP_BINS[i] == sweepBin) {
                return i;
            }
        }
        return 1;
    }

    private int highPitchRingBinForHeading(float headingDegrees) {
        float relativeDegrees = relativeSweepYawDegrees(headingDegrees);
        int bestIndex = 0;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < HIGH_PITCH_RING_YAW_DEGREES.length; i++) {
            float delta = Math.abs(signedAngleDeltaDegrees(HIGH_PITCH_RING_YAW_DEGREES[i], relativeDegrees));
            if (delta < bestDelta) {
                bestDelta = delta;
                bestIndex = i;
            }
        }
        return bestDelta <= MAX_HIGH_PITCH_RING_YAW_DELTA_DEGREES
                ? HIGH_PITCH_RING_SWEEP_BINS[bestIndex]
                : -1;
    }

    private int highPitchRingSlotIndexForSweepBin(int sweepBin) {
        for (int i = 0; i < HIGH_PITCH_RING_SWEEP_BINS.length; i++) {
            if (HIGH_PITCH_RING_SWEEP_BINS[i] == sweepBin) {
                return i;
            }
        }
        return 0;
    }

    private int nearestHighPitchRingSlotForPendingShot() {
        for (int i = 0; i < HIGH_PITCH_RING_SWEEP_BINS.length; i++) {
            if (!sweepCaptureBins[currentSweepLayerIndex][HIGH_PITCH_RING_SWEEP_BINS[i]]) {
                return i;
            }
        }
        return 0;
    }

    private String nextHighPitchRingYawLabel() {
        return highPitchRingYawLabelForSlot(nearestHighPitchRingSlotForPendingShot());
    }

    private String highPitchRingYawLabelForBin(int sweepBin) {
        return highPitchRingYawLabelForSlot(highPitchRingSlotIndexForSweepBin(sweepBin));
    }

    private String highPitchRingYawLabelForSlot(int slot) {
        int safeSlot = Math.max(0, Math.min(HIGH_PITCH_RING_YAW_DEGREES.length - 1, slot));
        return HIGH_PITCH_RING_YAW_DEGREES[safeSlot] + " deg";
    }

    private String highPitchRingInstruction() {
        return "Return to the horizon, rotate to " + nextHighPitchRingYawLabel()
                + ", then pan to " + currentSweepLayerPitch() + " deg and capture.";
    }

    /*
     * Function: signedAngleDeltaDegrees
     * Arguments: fromDegrees and toDegrees are angular values.
     * Calls: no external functions.
     * Flow: return the shortest signed angular delta in degrees.
     */
    private float signedAngleDeltaDegrees(float fromDegrees, float toDegrees) {
        float delta = (fromDegrees - toDegrees) % 360f;
        if (delta > 180f) {
            delta -= 360f;
        } else if (delta < -180f) {
            delta += 360f;
        }
        return delta;
    }

    /*
     * Function: isAlignedWithSweepLayerStart
     * Arguments: none.
     * Calls: headingDeltaDegrees() and isSweepLayerPitchAligned().
     * Flow: require layer sweeps to start and stop at the original real-world
     * horizon landmark while allowing the target pitch to be above or below it.
     */
    private boolean isAlignedWithSweepLayerStart() {
        return horizonSweepComplete && isSweepLayerPitchAligned();
    }

    /*
     * Function: sweepCaptureBinForHeading
     * Arguments: headingDegrees is the current ARCore yaw.
     * Calls: normalizeHeading().
     * Flow: map yaw to the sweep coverage bin used for automatic frame saves.
     */
    private int sweepCaptureBinForHeading(float headingDegrees) {
        int bin = (int) (relativeSweepYawDegrees(headingDegrees) / (360f / SWEEP_CAPTURE_BIN_COUNT));
        return Math.max(0, Math.min(SWEEP_CAPTURE_BIN_COUNT - 1, bin));
    }

    /*
     * Function: relativeSweepYawDegrees
     * Arguments: headingDegrees is the current ARCore yaw.
     * Calls: normalizeHeading().
     * Flow: convert AR yaw into the user-defined layer coordinate system where
     * the Start button is always yaw zero.
     */
    private float relativeSweepYawDegrees(float headingDegrees) {
        return normalizeHeading(headingDegrees - sweepLayerStartHeadingDegrees);
    }

    /*
     * Function: markCurrentSweepLayerClosed
     * Arguments: none.
     * Calls: no external functions.
     * Flow: trust the user's Stop press as completing one full turn, even if AR
     * yaw drift prevented every intermediate bin from filling.
     */
    private void markCurrentSweepLayerClosed() {
        if (currentSweepLayerIndex < 0 || currentSweepLayerIndex >= sweepCaptureBins.length) {
            return;
        }
        for (int i = 0; i < sweepCaptureBins[currentSweepLayerIndex].length; i++) {
            sweepCaptureBins[currentSweepLayerIndex][i] = true;
        }
    }

    /*
     * Function: sweepCaptureBinCenterDegrees
     * Arguments: bin is a sweep coverage bin index.
     * Calls: normalizeHeading().
     * Flow: return the yaw represented by a captured sweep bin for metadata.
     */
    private int sweepCaptureBinCenterDegrees(int bin) {
        return Math.round(normalizeHeading((bin + 0.5f) * 360f / SWEEP_CAPTURE_BIN_COUNT));
    }

    /*
     * Function: sweepLayerProgress
     * Arguments: layerIndex is the sweep layer to summarize.
     * Calls: capturedSweepBinCount() and clamp01().
     * Flow: convert one layer's painted yaw bins into a completion fraction.
     */
    private float sweepLayerProgress(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= sweepCaptureBins.length) {
            return 0f;
        }
        if (isHighPitchRingLayer(layerIndex)) {
            return clamp01(capturedSweepBinCount(layerIndex) / (float) HIGH_PITCH_RING_SWEEP_BINS.length);
        }
        if (isPolarLayer(layerIndex)) {
            return clamp01(capturedSweepBinCount(layerIndex) / (float) POLAR_ROLL_SLOT_TARGETS.length);
        }
        return clamp01(capturedSweepBinCount(layerIndex) / (float) REQUIRED_SWEEP_CAPTURE_BINS);
    }

    /*
     * Function: currentSweepLayerComplete
     * Arguments: none.
     * Calls: capturedSweepBinCount().
     * Flow: determine when enough of the current horizontal band has been painted.
     */
    private boolean currentSweepLayerComplete() {
        int required = isCurrentHighPitchRingLayer()
                ? HIGH_PITCH_RING_SWEEP_BINS.length
                : isCurrentPolarLayer()
                ? POLAR_ROLL_SLOT_TARGETS.length
                : REQUIRED_SWEEP_CAPTURE_BINS;
        return capturedSweepBinCount(currentSweepLayerIndex) >= required;
    }

    /*
     * Function: capturedSweepBinCount
     * Arguments: layerIndex is the sweep layer to count.
     * Calls: no external functions.
     * Flow: count painted yaw bins within a layer.
     */
    private int capturedSweepBinCount(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= sweepCaptureBins.length) {
            return 0;
        }
        if (isHighPitchRingLayer(layerIndex)) {
            int captured = 0;
            for (int bin : HIGH_PITCH_RING_SWEEP_BINS) {
                if (sweepCaptureBins[layerIndex][bin]) {
                    captured++;
                }
            }
            return captured;
        }
        if (isPolarLayer(layerIndex)) {
            int captured = 0;
            for (int slot = 0; slot < POLAR_ROLL_SLOT_SWEEP_BINS.length; slot++) {
                if (polarSlotCaptured(layerIndex, slot)) {
                    captured++;
                }
            }
            return captured;
        }
        int captured = 0;
        for (boolean seen : sweepCaptureBins[layerIndex]) {
            if (seen) {
                captured++;
            }
        }
        return captured;
    }

    /*
     * Function: totalSweepCoverageProgress
     * Arguments: none.
     * Calls: capturedSweepBinCount() and clamp01().
     * Flow: summarize all paint bands as one overall coverage fraction.
     */
    private float totalSweepCoverageProgress() {
        int captured = 0;
        int required = 0;
        for (int layer = 0; layer < sweepCaptureBins.length; layer++) {
            int layerRequired = isHighPitchRingLayer(layer)
                    ? HIGH_PITCH_RING_SWEEP_BINS.length
                    : isPolarLayer(layer)
                    ? POLAR_ROLL_SLOT_TARGETS.length
                    : REQUIRED_SWEEP_CAPTURE_BINS;
            captured += Math.min(layerRequired, capturedSweepBinCount(layer));
            required += layerRequired;
        }
        return required == 0 ? 0f : clamp01(captured / (float) required);
    }

    /*
     * Function: capturedSweepFrameCount
     * Arguments: none.
     * Calls: capturedSweepBinCount().
     * Flow: count painted bins across every layer for session summaries.
     */
    private int capturedSweepFrameCount() {
        int captured = 0;
        for (int layer = 0; layer < sweepCaptureBins.length; layer++) {
            captured += capturedSweepBinCount(layer);
        }
        return captured;
    }

    private int requiredSweepFrameCount() {
        int required = 0;
        for (int layer = 0; layer < sweepCaptureBins.length; layer++) {
            required += isHighPitchRingLayer(layer)
                    ? HIGH_PITCH_RING_SWEEP_BINS.length
                    : isPolarLayer(layer)
                    ? POLAR_ROLL_SLOT_TARGETS.length
                    : REQUIRED_SWEEP_CAPTURE_BINS;
        }
        return required;
    }

    /*
     * Function: currentSweepLayerBins
     * Arguments: none.
     * Calls: no external functions.
     * Flow: return a defensive copy of the active layer's yaw coverage for the
     * overlay paint strip.
     */
    private boolean[] currentSweepLayerBins() {
        boolean[] bins = new boolean[SWEEP_CAPTURE_BIN_COUNT];
        if (currentSweepLayerIndex < 0 || currentSweepLayerIndex >= sweepCaptureBins.length) {
            return bins;
        }
        if (isCurrentHighPitchRingLayer()) {
            bins = new boolean[HIGH_PITCH_RING_SWEEP_BINS.length];
            for (int slot = 0; slot < bins.length; slot++) {
                bins[slot] = sweepCaptureBins[currentSweepLayerIndex][HIGH_PITCH_RING_SWEEP_BINS[slot]];
            }
            return bins;
        }
        if (isCurrentPolarLayer()) {
            bins = new boolean[POLAR_ROLL_SLOT_SWEEP_BINS.length];
            for (int slot = 0; slot < bins.length; slot++) {
                bins[slot] = polarSlotCaptured(currentSweepLayerIndex, slot);
            }
            return bins;
        }
        for (int i = 0; i < bins.length; i++) {
            bins[i] = sweepCaptureBins[currentSweepLayerIndex][i];
        }
        return bins;
    }

    private boolean isFirstCurrentLayerDotCaptured() {
        if (currentSweepLayerIndex < 0 || currentSweepLayerIndex >= sweepCaptureBins.length) {
            return false;
        }
        if (isCurrentHighPitchRingLayer()) {
            return sweepCaptureBins[currentSweepLayerIndex][HIGH_PITCH_RING_SWEEP_BINS[0]];
        }
        if (isCurrentPolarLayer()) {
            return polarSlotCaptured(currentSweepLayerIndex, 0);
        }
        return sweepCaptureBins[currentSweepLayerIndex][0];
    }

    /*
     * Function: currentOverlayPanorama
     * Arguments: none.
     * Calls: no external functions.
     * Flow: use the active layer preview outside the horizon layer so the user
     * can see non-horizon images being painted in the overlay.
     */
    private Bitmap currentOverlayPanorama() {
        if (currentSweepLayerIndex > 0 && sweepLayerPreviewPanorama != null) {
            return sweepLayerPreviewPanorama;
        }
        return horizonReferencePanorama;
    }

    /*
     * Function: capturedTargetCount
     * Arguments: none.
     * Calls: no helpers.
     * Flow: count covered targets for user-facing completion text.
     */
    private int capturedTargetCount() {
        int captured = 0;
        for (CaptureTarget target : captureTargets) {
            if (target.captured) {
                captured++;
            }
        }
        return captured;
    }

    /*
     * Function: maybeAutoCapture
     * Arguments: none.
     * Calls: captureFrame() and System.currentTimeMillis().
     * Flow: auto-capture a new still keyframe only after the active sweep layer
     * reaches an unpainted yaw bin and yaw/pitch/roll motion has been quiet for
     * a short dwell. The preview remains continuous, but moving transition
     * frames are ignored for stitching.
     */
    private void maybeAutoCapture() {
        long now = System.currentTimeMillis();
        int bin = currentCaptureBin();
        if (bin < 0) {
            return;
        }
        if (autoCaptureEnabled
                && isCaptureAllowed()
                && sweepLayerPaintingActive
                && !isCurrentHighPitchRingLayer()
                && isSweepLayerPitchAligned()
                && (isCurrentPolarLayer() || !"Move slower".equals(currentSweepSpeedMessage()))
                && isKeyframeStable()
                && !sweepCaptureBins[currentSweepLayerIndex][bin]
                && now - lastAutoCaptureAt > MIN_SWEEP_CAPTURE_INTERVAL_MS) {
            lastAutoCaptureAt = now;
            captureFrame(isCurrentPolarLayer() ? "polar-auto" : "sweep-auto");
        }
    }

    /*
     * Function: isCaptureAllowed
     * Arguments: none.
     * Calls: isCaptureSetupReady().
     * Flow: centralize all capture gates so manual and auto-capture follow the
     * same paused/calibrated/camera-ready/in-progress rules.
     */
    private boolean isCaptureAllowed() {
        return !capturePaused
                && !captureInProgress
                && latestArJpeg != null
                && isCaptureSetupReady()
                && currentSweepLayerIndex >= 0
                && currentSweepLayerIndex < sweepCaptureBins.length;
    }

    /*
     * Function: toggleCapturePaused
     * Arguments: none; invoked by the Pause/Resume button.
     * Calls: Button.setText(), updateGuideState(), and Toast.
     * Flow: pause guidance and capture without leaving the Activity, letting the
     * user reposition or take a break mid-session.
     */
    private void toggleCapturePaused() {
        capturePaused = !capturePaused;
        pauseButton.setText(capturePaused ? "Resume" : "Pause");
        Toast.makeText(this, capturePaused ? "Capture paused" : "Capture resumed", Toast.LENGTH_SHORT).show();
        updateGuideState();
    }

    /*
     * Function: toggleAutoCapture
     * Arguments: none; invoked by the Auto button.
     * Calls: Button.setText(), Toast, and maybeAutoCapture().
     * Flow: expose the Phase 4 auto-capture prototype while keeping manual
     * capture as the default path.
     */
    private void toggleAutoCapture() {
        autoCaptureEnabled = !autoCaptureEnabled;
        autoCaptureButton.setText(autoCaptureEnabled ? "Auto Keyframes" : "Manual Keyframes");
        Toast.makeText(
                this,
                autoCaptureEnabled ? "Auto saves still keyframes only" : "Manual keyframes only",
                Toast.LENGTH_SHORT).show();
        maybeAutoCapture();
    }

    private void chooseCaptureProfile() {
        String[] labels = {"Hand-held", "Fixed gimbal"};
        String[] values = {CAPTURE_PROFILE_HANDHELD, CAPTURE_PROFILE_FIXED_GIMBAL};
        int checked = CAPTURE_PROFILE_FIXED_GIMBAL.equals(captureProfile) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Capture profile")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    captureProfile = values[which];
                    capturePrefs.edit().putString(PREF_CAPTURE_PROFILE, captureProfile).apply();
                    captureProfileButton.setText(captureProfileLabel());
                    Toast.makeText(this, captureProfileDescription(), Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .show();
    }

    private String captureProfileLabel() {
        return CAPTURE_PROFILE_FIXED_GIMBAL.equals(captureProfile) ? "Gimbal" : "Hand-held";
    }

    private String captureProfileDescription() {
        return CAPTURE_PROFILE_FIXED_GIMBAL.equals(captureProfile)
                ? "Fixed gimbal: rotation-only stitching assumptions"
                : "Hand-held: parallax treated as a stitching risk";
    }

    private static String normalizeCaptureProfile(String value) {
        return CAPTURE_PROFILE_FIXED_GIMBAL.equals(value) ? CAPTURE_PROFILE_FIXED_GIMBAL : CAPTURE_PROFILE_HANDHELD;
    }

    /*
     * Function: undoLastTarget
     * Arguments: none.
     * Calls: Toast and updateGuideState().
     * Flow: remove the latest sweep coverage mark. The already-written JPEG
     * remains in the draft folder for recovery, but the guide lets the user
     * recapture that direction.
     */
    private void undoLastTarget() {
        if (lastCapturedSweepLayerIndex < 0 || lastCapturedSweepBin < 0) {
            Toast.makeText(this, "No sweep slice to undo", Toast.LENGTH_SHORT).show();
            return;
        }
        sweepCaptureBins[lastCapturedSweepLayerIndex][lastCapturedSweepBin] = false;
        lastCapturedSweepLayerIndex = -1;
        lastCapturedSweepBin = -1;
        Toast.makeText(this, "Last sweep slice reopened", Toast.LENGTH_SHORT).show();
        updateGuideState();
    }

    /*
     * Function: finishCaptureSession
     * Arguments: none; invoked by Finish.
     * Calls: AlertDialog.Builder, clearActiveSession(), and finish().
     * Flow: summarize coverage before ending the draft session so the user knows
     * whether enough frames exist for later stitching experiments.
     */
    private void finishCaptureSession() {
        int captured = capturedSweepFrameCount();
        new AlertDialog.Builder(this)
                .setTitle("Finish draft capture?")
                .setMessage("Painted " + captured + " of "
                        + requiredSweepFrameCount()
                        + " capture positions.\n\nPending frames remain saved for Phase 5 Spherify experiments.")
                .setNegativeButton("Keep capturing", null)
                .setPositiveButton("Finish", (dialog, which) -> {
                    clearActiveSession();
                    finish();
                })
                .show();
    }

    /*
     * Function: cancelCaptureSession
     * Arguments: none; invoked by Cancel.
     * Calls: AlertDialog.Builder, clearActiveSession(), and finish().
     * Flow: leave already-saved draft files intact but abandon this active
     * guidance session id so the next capture starts fresh.
     */
    private void cancelCaptureSession() {
        new AlertDialog.Builder(this)
                .setTitle("Cancel capture?")
                .setMessage("Saved draft frames will stay in app storage, but this guided session will close.")
                .setNegativeButton("Keep capturing", null)
                .setPositiveButton("Cancel capture", (dialog, which) -> {
                    clearActiveSession();
                    finish();
                })
                .show();
    }

    /*
     * Function: clearActiveSession
     * Arguments: none.
     * Calls: SharedPreferences.Editor.remove().
     * Flow: remove the persisted session id once the user explicitly finishes or
     * cancels, while interruption without either action keeps the draft recoverable.
     */
    private void clearActiveSession() {
        capturePrefs.edit().remove(PREF_ACTIVE_SESSION_ID).apply();
    }

    /*
     * Function: normalizeHeading
     * Arguments: degrees is any heading angle.
     * Calls: modulo arithmetic only.
     * Flow: convert headings into the compass range 0 <= heading < 360.
     */
    private static float normalizeHeading(float degrees) {
        float normalized = degrees % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    /*
     * Function: clamp01
     * Arguments: value is any progress value.
     * Calls: Math.max() and Math.min().
     * Flow: force progress fractions into the valid 0..1 drawing range.
     */
    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /*
     * Function: headingDeltaDegrees
     * Arguments: first and second are compass headings in degrees.
     * Calls: Math.abs().
     * Flow: calculate the smallest angular distance between two headings while
     * respecting the 0/360 wraparound.
     */
    private static float headingDeltaDegrees(float first, float second) {
        float delta = Math.abs(normalizeHeading(first) - normalizeHeading(second));
        return Math.min(delta, 360f - delta);
    }

    /*
     * Function: showHorizonSweepInstructions
     * Arguments: none.
     * Calls: AlertDialog.Builder.
     * Flow: explain the separate compass calibration and horizon-reference
     * passes as soon as capture opens.
     */
    private void showHorizonSweepInstructions() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Start with compass calibration")
                .setMessage("First, tap Start and slowly turn once around the horizon to let the compass settle.\n\n"
                        + "After calibration, point the crosshair at a fixed distant landmark, tap Start again, sweep once around the horizon, return to the same landmark, then tap End.")
                .setPositiveButton("OK", null)
                .show();
    }

    /*
     * Function: startArCoreCapture
     * Arguments: none.
     * Calls: ContextCompat.checkSelfPermission(), verifyArCoreSupport(),
     * initializeArSession(), resumeArSession(), and updateCompassUi().
     * Flow: verify camera permission and hand camera ownership to an ARCore
     * shared-camera session, which supplies both pose and CPU camera frames.
     */
    private void startArCoreCapture() {
        Log.d(TAG, "startArCoreCapture cameraPermission="
                + (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
                + " arCoreReady=" + arCoreReady);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            statusText.setText("ARCore required");
            return;
        }
        verifyArCoreSupport();
        initializeArSession();
        resumeArSession();
        updateCompassUi();
    }

    /*
     * Function: initializeArSession
     * Arguments: none.
     * Calls: Session constructor, Session.configure(), and getSharedCamera().
     * Flow: create one ARCore session with the shared-camera feature and configure
     * autofocus so every preview/capture frame has an ARCore pose source.
     */
    private void initializeArSession() {
        if (!arCoreReady || arSession != null) {
            Log.d(TAG, "initializeArSession skipped arCoreReady=" + arCoreReady
                    + " hasSession=" + (arSession != null));
            return;
        }
        try {
            Log.d(TAG, "initializeArSession creating ARCore shared-camera session");
            arSession = new Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA));
            Config config = arSession.getConfig();
            config.setFocusMode(Config.FocusMode.AUTO);
            arSession.configure(config);
            sharedCamera = arSession.getSharedCamera();
            attachArCameraTexture();
            Log.d(TAG, "initializeArSession complete sharedCamera=" + (sharedCamera != null));
        } catch (UnavailableException e) {
            arCoreReady = false;
            Log.w(TAG, "initializeArSession unavailable", e);
            showArCoreUnsupportedMessage();
        } catch (RuntimeException e) {
            arCoreReady = false;
            Log.w(TAG, "initializeArSession runtime failure", e);
            statusText.setText("ARCore unavailable");
            Toast.makeText(this, "ARCore session failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /*
     * Function: resumeArSession
     * Arguments: none.
     * Calls: Session.resume() and attachArCameraTexture().
     * Flow: start ARCore tracking; the GLSurfaceView render loop updates
     * preview, horizon reference samples, guide pose, and capture JPEG data.
     */
    private void resumeArSession() {
        if (!arCoreReady) {
            Log.d(TAG, "resumeArSession skipped because ARCore is not ready");
            return;
        }
        initializeArSession();
        if (arSession == null || arSessionRunning) {
            Log.d(TAG, "resumeArSession skipped hasSession=" + (arSession != null)
                    + " running=" + arSessionRunning);
            return;
        }
        try {
            arSession.resume();
            arSessionRunning = true;
            attachArCameraTexture();
            Log.d(TAG, "resumeArSession complete");
        } catch (CameraNotAvailableException e) {
            arSessionRunning = false;
            Log.w(TAG, "resumeArSession camera unavailable", e);
            statusText.setText("ARCore camera unavailable");
            Toast.makeText(this, "ARCore camera unavailable", Toast.LENGTH_LONG).show();
        }
    }

    /*
     * Function: pauseArSession
     * Arguments: none.
     * Calls: Session.pause().
     * Flow: stop ARCore camera/tracking while paused.
     */
    private void pauseArSession() {
        if (arSession != null && arSessionRunning) {
            arSession.pause();
        }
        arSessionRunning = false;
    }

    /*
     * Function: onArCameraTextureCreated
     * Arguments: textureId is the external OES texture created by the GL renderer.
     * Calls: attachArCameraTexture().
     * Flow: remember the renderer texture and attach it to ARCore as soon as
     * both the texture and session exist.
     */
    private void onArCameraTextureCreated(int textureId) {
        arCameraTextureId = textureId;
        attachArCameraTexture();
    }

    /*
     * Function: attachArCameraTexture
     * Arguments: none.
     * Calls: Session.setCameraTextureNames().
     * Flow: connect ARCore camera output to the renderer's external texture.
     */
    private void attachArCameraTexture() {
        if (arSession == null || arCameraTextureId == 0) {
            Log.d(TAG, "attachArCameraTexture waiting hasSession=" + (arSession != null)
                    + " textureId=" + arCameraTextureId);
            return;
        }
        arSession.setCameraTextureNames(new int[]{arCameraTextureId});
        Log.d(TAG, "attachArCameraTexture textureId=" + arCameraTextureId);
    }

    /*
     * Function: drawArFrame
     * Arguments: renderer draws the external camera texture after update.
     * Calls: Session.update(), Frame.getImageMetadata(),
     * Frame.acquireCameraImage(), acceptArPose(), and updatePreviewFromArImage().
     * Flow: pull the latest ARCore frame from the GL render loop, update pose
     * guidance, cache exposure metadata and a CPU JPEG when available, and draw
     * the camera texture.
     */
    private void drawArFrame(ArCameraRenderer renderer) {
        if (!arSessionRunning || arSession == null || arCameraTextureId == 0) {
            renderer.drawEmpty();
            return;
        }
        try {
            Frame frame = arSession.update();
            TrackingState trackingState = frame.getCamera().getTrackingState();
            synchronized (arFrameLock) {
                arFrameUpdateCount++;
                latestArFrameTimestampNs = frame.getTimestamp();
                latestArTrackingState = trackingState.name();
            }
            if (trackingState == TrackingState.TRACKING) {
                acceptArPose(frame.getCamera().getPose());
            }
            rememberArCoreFrameMetadata(frame);
            try (Image image = frame.acquireCameraImage()) {
                synchronized (arFrameLock) {
                    arImageAcquiredCount++;
                }
                updatePreviewFromArImage(image);
            } catch (NotYetAvailableException ignored) {
                synchronized (arFrameLock) {
                    arImageNotReadyCount++;
                }
                logArPipelineDebug(false);
            }
            renderer.drawCamera();
        } catch (Throwable throwable) {
            synchronized (arFrameLock) {
                arFrameThrowableCount++;
                latestArMetadataFailure = "frame update/draw failed: "
                        + throwable.getClass().getSimpleName()
                        + " " + safeText(throwable.getMessage(), "");
            }
            Log.w(TAG, "AR frame update failed", throwable);
            renderer.drawEmpty();
        }
    }

    /*
     * Function: rememberArCoreFrameMetadata
     * Arguments: frame is the latest ARCore frame.
     * Calls: Frame.getImageMetadata() and captureExposureMetadataJson().
     * Flow: read the camera metadata for the current ARCore frame and store it
     * by sensor timestamp. Capture only uses the metadata later when a CPU image
     * arrives with the same timestamp.
     */
    private void rememberArCoreFrameMetadata(Frame frame) {
        try {
            ImageMetadata metadata = frame.getImageMetadata();
            String exposureJson = captureExposureMetadataJson(metadata, frame);
            synchronized (arFrameLock) {
                arMetadataSuccessCount++;
                latestArMetadataTimestampNs = metadataTimestampNs(exposureJson);
                latestArMetadataFieldSummary = requiredMetadataFieldSummary(exposureJson);
                latestArMetadataFailure = "";
            }
            rememberCaptureMetadataByTimestamp(exposureJson);
            logArPipelineDebug(false);
        } catch (NotYetAvailableException exception) {
            synchronized (arFrameLock) {
                arMetadataNotReadyCount++;
                latestExposureMetadataJson = unavailableExposureJson("metadata not ready", frame);
                latestCaptureMetadataBlocker = requiredCaptureMetadataBlocker(latestExposureMetadataJson);
                latestMetadataAttemptAt = System.currentTimeMillis();
                latestArMetadataFailure = "Frame.getImageMetadata not ready";
            }
            logArPipelineDebug(false);
        } catch (Throwable throwable) {
            synchronized (arFrameLock) {
                arMetadataUnavailableCount++;
                latestExposureMetadataJson = unavailableExposureJson("metadata unavailable", frame);
                latestCaptureMetadataBlocker = requiredCaptureMetadataBlocker(latestExposureMetadataJson);
                latestMetadataAttemptAt = System.currentTimeMillis();
                latestArMetadataFailure = "Frame.getImageMetadata failed: "
                        + throwable.getClass().getSimpleName()
                        + " " + safeText(throwable.getMessage(), "");
            }
            Log.w(TAG, "ARCore image metadata read failed", throwable);
            logArPipelineDebug(true);
        }
    }

    /*
     * Function: captureExposureMetadataJson
     * Arguments: metadata is ARCore's camera metadata snapshot.
     * Calls: ImageMetadata getters and JSONObject.put().
     * Flow: convert the exposure-relevant camera fields into a small structured
     * JSON object that is written beside each draft frame for Phase 5 stitching.
     */
    private String captureExposureMetadataJson(ImageMetadata metadata, Frame frame) {
        try {
            JSONObject json = new JSONObject();
            json.put("available", true);
            json.put("source", "arcore-image-metadata");
            putMetadataKeySummary(json, metadata);
            putLongMetadata(json, "sensorExposureTimeNs", metadata, ImageMetadata.SENSOR_EXPOSURE_TIME);
            putIntMetadata(json, "sensorSensitivityIso", metadata, ImageMetadata.SENSOR_SENSITIVITY);
            putLongMetadata(json, "sensorFrameDurationNs", metadata, ImageMetadata.SENSOR_FRAME_DURATION);
            putLongMetadata(json, "sensorTimestampNs", metadata, ImageMetadata.SENSOR_TIMESTAMP);
            putFloatMetadata(json, "lensAperture", metadata, ImageMetadata.LENS_APERTURE);
            putFloatMetadata(json, "lensFocalLengthMm", metadata, ImageMetadata.LENS_FOCAL_LENGTH);
            if (cameraSensorPhysicalSize == null) {
                json.put("sensorPhysicalWidthMm", JSONObject.NULL);
                json.put("sensorPhysicalHeightMm", JSONObject.NULL);
            } else {
                json.put("sensorPhysicalWidthMm", cameraSensorPhysicalSize.getWidth());
                json.put("sensorPhysicalHeightMm", cameraSensorPhysicalSize.getHeight());
            }
            putImageIntrinsicsMetadata(json, frame);
            putIntMetadata(json, "aeState", metadata, ImageMetadata.CONTROL_AE_STATE);
            putIntMetadata(json, "awbState", metadata, ImageMetadata.CONTROL_AWB_STATE);
            putIntMetadata(json, "aeExposureCompensation", metadata, ImageMetadata.CONTROL_AE_EXPOSURE_COMPENSATION);
            putIntMetadata(json, "aeMode", metadata, ImageMetadata.CONTROL_AE_MODE);
            putIntMetadata(json, "awbMode", metadata, ImageMetadata.CONTROL_AWB_MODE);
            return json.toString();
        } catch (JSONException e) {
            return unavailableExposureJson("metadata encode failed", frame);
        }
    }

    private void rememberCamera2CaptureResult(TotalCaptureResult result) {
        try {
            rememberCaptureMetadataByTimestamp(camera2CaptureResultMetadataJson(result));
        } catch (JSONException ignored) {
            synchronized (arFrameLock) {
                latestExposureMetadataJson = unavailableExposureJson("camera2 metadata encode failed");
                latestCaptureMetadataBlocker = requiredCaptureMetadataBlocker(latestExposureMetadataJson);
                latestMetadataAttemptAt = System.currentTimeMillis();
            }
        }
    }

    private String camera2CaptureResultMetadataJson(TotalCaptureResult result) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("available", true);
        json.put("source", "camera2-total-capture-result");
        putLongCaptureResult(json, "sensorExposureTimeNs", result, CaptureResult.SENSOR_EXPOSURE_TIME);
        putIntCaptureResult(json, "sensorSensitivityIso", result, CaptureResult.SENSOR_SENSITIVITY);
        putLongCaptureResult(json, "sensorFrameDurationNs", result, CaptureResult.SENSOR_FRAME_DURATION);
        putLongCaptureResult(json, "sensorTimestampNs", result, CaptureResult.SENSOR_TIMESTAMP);
        putFloatCaptureResult(json, "lensAperture", result, CaptureResult.LENS_APERTURE);
        putFloatCaptureResult(json, "lensFocalLengthMm", result, CaptureResult.LENS_FOCAL_LENGTH);
        if (cameraSensorPhysicalSize == null) {
            json.put("sensorPhysicalWidthMm", JSONObject.NULL);
            json.put("sensorPhysicalHeightMm", JSONObject.NULL);
        } else {
            json.put("sensorPhysicalWidthMm", cameraSensorPhysicalSize.getWidth());
            json.put("sensorPhysicalHeightMm", cameraSensorPhysicalSize.getHeight());
        }
        json.put("imageFocalLengthXPixels", JSONObject.NULL);
        json.put("imageFocalLengthYPixels", JSONObject.NULL);
        json.put("imagePrincipalPointXPixels", JSONObject.NULL);
        json.put("imagePrincipalPointYPixels", JSONObject.NULL);
        json.put("imageIntrinsicsWidth", JSONObject.NULL);
        json.put("imageIntrinsicsHeight", JSONObject.NULL);
        putIntCaptureResult(json, "aeState", result, CaptureResult.CONTROL_AE_STATE);
        putIntCaptureResult(json, "awbState", result, CaptureResult.CONTROL_AWB_STATE);
        putIntCaptureResult(json, "aeExposureCompensation", result, CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
        putIntCaptureResult(json, "aeMode", result, CaptureResult.CONTROL_AE_MODE);
        putIntCaptureResult(json, "awbMode", result, CaptureResult.CONTROL_AWB_MODE);
        return json.toString();
    }

    private void rememberCaptureMetadataByTimestamp(String exposureJson) {
        long timestamp = metadataTimestampNs(exposureJson);
        synchronized (arFrameLock) {
            latestExposureMetadataJson = exposureJson;
            latestCaptureMetadataBlocker = requiredCaptureMetadataBlocker(exposureJson);
            latestMetadataAttemptAt = System.currentTimeMillis();
            if (timestamp > 0L) {
                captureMetadataByTimestamp.put(timestamp, exposureJson);
                while (captureMetadataByTimestamp.size() > CAPTURE_METADATA_BUFFER_LIMIT) {
                    Long firstKey = captureMetadataByTimestamp.keySet().iterator().next();
                    captureMetadataByTimestamp.remove(firstKey);
                }
                logCaptureDebug("metadata stored source=" + metadataSource(exposureJson)
                        + " ts=" + timestamp
                        + " buffer=" + captureMetadataByTimestamp.size()
                        + " blocker=" + latestCaptureMetadataBlocker);
            } else {
                Log.w(TAG, "metadata missing sensor timestamp blocker=" + latestCaptureMetadataBlocker);
            }
        }
    }

    private static long metadataTimestampNs(String exposureJson) {
        if (exposureJson == null || exposureJson.trim().isEmpty()) {
            return -1L;
        }
        try {
            JSONObject json = new JSONObject(exposureJson);
            return json.optLong("sensorTimestampNs", -1L);
        } catch (JSONException ignored) {
            return -1L;
        }
    }

    private static String requiredMetadataFieldSummary(String exposureJson) {
        if (exposureJson == null || exposureJson.trim().isEmpty()) {
            return "json missing";
        }
        try {
            JSONObject json = new JSONObject(exposureJson);
            StringBuilder missing = new StringBuilder();
            appendMissingMetadataField(missing, json, "sensorExposureTimeNs", true);
            appendMissingMetadataField(missing, json, "sensorSensitivityIso", true);
            appendMissingMetadataField(missing, json, "sensorFrameDurationNs", true);
            appendMissingMetadataField(missing, json, "sensorTimestampNs", true);
            appendMissingMetadataField(missing, json, "lensAperture", true);
            appendMissingMetadataField(missing, json, "lensFocalLengthMm", true);
            appendMissingMetadataField(missing, json, "sensorPhysicalWidthMm", true);
            appendMissingMetadataField(missing, json, "sensorPhysicalHeightMm", true);
            appendMissingMetadataField(missing, json, "imageFocalLengthXPixels", true);
            appendMissingMetadataField(missing, json, "imageFocalLengthYPixels", true);
            appendMissingMetadataField(missing, json, "imagePrincipalPointXPixels", true);
            appendMissingMetadataField(missing, json, "imagePrincipalPointYPixels", true);
            appendMissingMetadataField(missing, json, "imageIntrinsicsWidth", true);
            appendMissingMetadataField(missing, json, "imageIntrinsicsHeight", true);
            appendMissingMetadataField(missing, json, "aeState", false);
            appendMissingMetadataField(missing, json, "awbState", false);
            appendMissingMetadataField(missing, json, "aeExposureCompensation", false);
            appendMissingMetadataField(missing, json, "aeMode", false);
            appendMissingMetadataField(missing, json, "awbMode", false);
            return missing.length() == 0 ? "complete" : "missing/invalid " + missing;
        } catch (JSONException ignored) {
            return "json invalid";
        }
    }

    private static void appendMissingMetadataField(
            StringBuilder missing,
            JSONObject json,
            String field,
            boolean requirePositive) {
        boolean invalid = !json.has(field) || json.isNull(field);
        if (!invalid && requirePositive) {
            double value = json.optDouble(field, 0.0);
            invalid = !(value > 0.0);
        }
        if (invalid) {
            if (missing.length() > 0) {
                missing.append(",");
            }
            missing.append(field);
        }
    }

    private TimestampedMetadata metadataForImageTimestamp(long imageTimestampNs) {
        String exact = captureMetadataByTimestamp.get(imageTimestampNs);
        if (exact != null) {
            return new TimestampedMetadata(imageTimestampNs, exact, 0L);
        }
        long bestTimestamp = -1L;
        long bestDelta = Long.MAX_VALUE;
        String bestMetadata = null;
        for (Map.Entry<Long, String> entry : captureMetadataByTimestamp.entrySet()) {
            long timestamp = entry.getKey();
            long delta = Math.abs(timestamp - imageTimestampNs);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestTimestamp = timestamp;
                bestMetadata = entry.getValue();
            }
        }
        if (bestMetadata == null) {
            return null;
        }
        long toleranceNs = metadataMatchToleranceNs(bestMetadata);
        if (bestDelta <= toleranceNs) {
            return new TimestampedMetadata(bestTimestamp, bestMetadata, bestDelta);
        }
        return null;
    }

    private static long metadataMatchToleranceNs(String exposureJson) {
        long frameDurationNs = CAPTURE_METADATA_DEFAULT_MATCH_TOLERANCE_NS;
        if (exposureJson != null && !exposureJson.trim().isEmpty()) {
            try {
                JSONObject json = new JSONObject(exposureJson);
                frameDurationNs = json.optLong(
                        "sensorFrameDurationNs",
                        CAPTURE_METADATA_DEFAULT_MATCH_TOLERANCE_NS);
            } catch (JSONException ignored) {
                frameDurationNs = CAPTURE_METADATA_DEFAULT_MATCH_TOLERANCE_NS;
            }
        }
        if (frameDurationNs <= 0L) {
            frameDurationNs = CAPTURE_METADATA_DEFAULT_MATCH_TOLERANCE_NS;
        }
        return Math.max(
                CAPTURE_METADATA_MIN_MATCH_TOLERANCE_NS,
                Math.min(CAPTURE_METADATA_MAX_MATCH_TOLERANCE_NS, frameDurationNs));
    }

    private static String metadataSource(String exposureJson) {
        if (exposureJson == null || exposureJson.trim().isEmpty()) {
            return "missing";
        }
        try {
            return new JSONObject(exposureJson).optString("source", "unknown");
        } catch (JSONException ignored) {
            return "invalid";
        }
    }

    private void logCaptureDebug(String message) {
        long now = System.currentTimeMillis();
        if (now - latestCaptureDebugLogAt < 500L) {
            return;
        }
        latestCaptureDebugLogAt = now;
        Log.d(TAG, message);
    }

    private void logArPipelineDebug(boolean force) {
        String message;
        synchronized (arFrameLock) {
            long now = System.currentTimeMillis();
            if (!force && now - latestArDebugLogAt < 1000L) {
                return;
            }
            latestArDebugLogAt = now;
            message = "ar pipeline frames=" + arFrameUpdateCount
                    + " tracking=" + latestArTrackingState
                    + " frameTs=" + latestArFrameTimestampNs
                    + " images ok/not-ready=" + arImageAcquiredCount + "/" + arImageNotReadyCount
                    + " metadata ok/not-ready/fail="
                    + arMetadataSuccessCount + "/" + arMetadataNotReadyCount + "/" + arMetadataUnavailableCount
                    + " metadataTs=" + latestArMetadataTimestampNs
                    + " buffer=" + captureMetadataByTimestamp.size()
                    + " fields=" + safeText(latestArMetadataFieldSummary, "")
                    + " failure=" + safeText(latestArMetadataFailure, "");
        }
        Log.d(TAG, message);
    }

    private static void putImageIntrinsicsMetadata(JSONObject json, Frame frame) throws JSONException {
        try {
            CameraIntrinsics intrinsics = frame.getCamera().getImageIntrinsics();
            float[] focalLength = intrinsics.getFocalLength();
            float[] principalPoint = intrinsics.getPrincipalPoint();
            int[] imageDimensions = intrinsics.getImageDimensions();
            json.put("imageFocalLengthXPixels", focalLength.length > 0 ? focalLength[0] : JSONObject.NULL);
            json.put("imageFocalLengthYPixels", focalLength.length > 1 ? focalLength[1] : JSONObject.NULL);
            json.put("imagePrincipalPointXPixels", principalPoint.length > 0 ? principalPoint[0] : JSONObject.NULL);
            json.put("imagePrincipalPointYPixels", principalPoint.length > 1 ? principalPoint[1] : JSONObject.NULL);
            json.put("imageIntrinsicsWidth", imageDimensions.length > 0 ? imageDimensions[0] : JSONObject.NULL);
            json.put("imageIntrinsicsHeight", imageDimensions.length > 1 ? imageDimensions[1] : JSONObject.NULL);
        } catch (Throwable ignored) {
            json.put("imageFocalLengthXPixels", JSONObject.NULL);
            json.put("imageFocalLengthYPixels", JSONObject.NULL);
            json.put("imagePrincipalPointXPixels", JSONObject.NULL);
            json.put("imagePrincipalPointYPixels", JSONObject.NULL);
            json.put("imageIntrinsicsWidth", JSONObject.NULL);
            json.put("imageIntrinsicsHeight", JSONObject.NULL);
        }
    }

    private static void putMetadataKeySummary(JSONObject json, ImageMetadata metadata) throws JSONException {
        try {
            long[] keys = metadata.getKeys();
            json.put("metadataKeyCount", keys.length);
            StringBuilder builder = new StringBuilder();
            int limit = Math.min(keys.length, 32);
            for (int i = 0; i < limit; i++) {
                if (i > 0) {
                    builder.append(",");
                }
                builder.append(keys[i]);
            }
            if (keys.length > limit) {
                builder.append(",...");
            }
            json.put("metadataKeys", builder.toString());
        } catch (Throwable ignored) {
            json.put("metadataKeyCount", JSONObject.NULL);
            json.put("metadataKeys", JSONObject.NULL);
        }
    }

    private static void putLongMetadata(JSONObject json, String name, ImageMetadata metadata, int key)
            throws JSONException {
        try {
            json.put(name, metadata.getLong(key));
        } catch (MetadataNotFoundException ignored) {
            json.put(name, JSONObject.NULL);
        } catch (IllegalArgumentException ignored) {
            putFallbackLongMetadata(json, name, metadata, key);
        }
    }

    private static void putIntMetadata(JSONObject json, String name, ImageMetadata metadata, int key)
            throws JSONException {
        try {
            json.put(name, metadata.getInt(key));
        } catch (MetadataNotFoundException ignored) {
            json.put(name, JSONObject.NULL);
        } catch (IllegalArgumentException ignored) {
            putFallbackIntMetadata(json, name, metadata, key);
        }
    }

    private static void putFloatMetadata(JSONObject json, String name, ImageMetadata metadata, int key)
            throws JSONException {
        try {
            json.put(name, metadata.getFloat(key));
        } catch (MetadataNotFoundException ignored) {
            json.put(name, JSONObject.NULL);
        } catch (IllegalArgumentException ignored) {
            putFallbackFloatMetadata(json, name, metadata, key);
        }
    }

    private static void putFallbackLongMetadata(JSONObject json, String name, ImageMetadata metadata, int key)
            throws JSONException {
        try {
            json.put(name, metadata.getInt(key));
            return;
        } catch (MetadataNotFoundException ignored) {
            json.put(name, JSONObject.NULL);
            return;
        } catch (IllegalArgumentException ignored) {
            // Try the next scalar representation below.
        }
        try {
            json.put(name, metadata.getByte(key));
        } catch (MetadataNotFoundException ignored) {
            json.put(name, JSONObject.NULL);
        } catch (IllegalArgumentException ignored) {
            json.put(name, JSONObject.NULL);
        }
    }

    private static void putFallbackIntMetadata(JSONObject json, String name, ImageMetadata metadata, int key)
            throws JSONException {
        try {
            json.put(name, (int) metadata.getByte(key));
            return;
        } catch (MetadataNotFoundException ignored) {
            json.put(name, JSONObject.NULL);
            return;
        } catch (IllegalArgumentException ignored) {
            // Try the next scalar representation below.
        }
        try {
            long value = metadata.getLong(key);
            json.put(name, value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? JSONObject.NULL : (int) value);
        } catch (MetadataNotFoundException ignored) {
            json.put(name, JSONObject.NULL);
        } catch (IllegalArgumentException ignored) {
            json.put(name, JSONObject.NULL);
        }
    }

    private static void putFallbackFloatMetadata(JSONObject json, String name, ImageMetadata metadata, int key)
            throws JSONException {
        try {
            json.put(name, metadata.getDouble(key));
            return;
        } catch (MetadataNotFoundException ignored) {
            json.put(name, JSONObject.NULL);
            return;
        } catch (IllegalArgumentException ignored) {
            // Try the next scalar representation below.
        }
        try {
            Rational rational = metadata.getRational(key);
            json.put(name, rational == null ? JSONObject.NULL : rational.floatValue());
        } catch (MetadataNotFoundException ignored) {
            json.put(name, JSONObject.NULL);
        } catch (IllegalArgumentException ignored) {
            json.put(name, JSONObject.NULL);
        }
    }

    private static void putLongCaptureResult(
            JSONObject json,
            String name,
            TotalCaptureResult result,
            CaptureResult.Key<Long> key) throws JSONException {
        Long value = result.get(key);
        json.put(name, value == null ? JSONObject.NULL : value);
    }

    private static void putIntCaptureResult(
            JSONObject json,
            String name,
            TotalCaptureResult result,
            CaptureResult.Key<Integer> key) throws JSONException {
        Integer value = result.get(key);
        json.put(name, value == null ? JSONObject.NULL : value);
    }

    private static void putFloatCaptureResult(
            JSONObject json,
            String name,
            TotalCaptureResult result,
            CaptureResult.Key<Float> key) throws JSONException {
        Float value = result.get(key);
        json.put(name, value == null ? JSONObject.NULL : value);
    }

    /*
     * Function: readBackCameraSensorPhysicalSize
     * Arguments: none.
     * Calls: CameraManager.getCameraIdList(), CameraCharacteristics getters.
     * Flow: find the first back-facing camera and return its physical sensor
     * dimensions so Phase 5 can estimate real FOV from focal length metadata.
     */
    private SizeF readBackCameraSensorPhysicalSize() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null) {
            return null;
        }
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                }
            }
        } catch (CameraAccessException ignored) {
            return null;
        } catch (SecurityException ignored) {
            return null;
        }
        return null;
    }

    private static String unavailableExposureJson(String reason) {
        return unavailableExposureJson(reason, null);
    }

    private static String unavailableExposureJson(String reason, Frame frame) {
        try {
            JSONObject json = new JSONObject();
            json.put("available", false);
            json.put("reason", reason);
            if (frame == null) {
                json.put("imageFocalLengthXPixels", JSONObject.NULL);
                json.put("imageFocalLengthYPixels", JSONObject.NULL);
                json.put("imagePrincipalPointXPixels", JSONObject.NULL);
                json.put("imagePrincipalPointYPixels", JSONObject.NULL);
                json.put("imageIntrinsicsWidth", JSONObject.NULL);
                json.put("imageIntrinsicsHeight", JSONObject.NULL);
            } else {
                putImageIntrinsicsMetadata(json, frame);
            }
            return json.toString();
        } catch (JSONException ignored) {
            return "{\"available\":false,\"reason\":\"metadata unavailable\"}";
        }
    }

    /*
     * Function: updatePreviewFromArImage
     * Arguments: image is the latest ARCore CPU camera image.
     * Calls: yuv420ToJpeg() and BitmapFactory.decodeByteArray().
     * Flow: convert ARCore's YUV frame once, keep a downsampled bitmap for
     * horizon sampling, and expose a separate capture-ready packet only when
     * the same frame has complete downstream metadata.
     */
    private void updatePreviewFromArImage(Image image) {
        long imageTimestampNs = image.getTimestamp();
        byte[] jpeg = yuv420ToJpeg(image, 88);
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) {
            return;
        }
        synchronized (arFrameLock) {
            latestImageTimestampNs = imageTimestampNs;
            latestMetadataAttemptAt = System.currentTimeMillis();
            latestArJpeg = jpeg;
            TimestampedMetadata matchedMetadata = metadataForImageTimestamp(imageTimestampNs);
            String exposureJson;
            if (matchedMetadata == null) {
                exposureJson = unavailableExposureJson("no timestamp-matched camera metadata");
                latestMatchedMetadataTimestampNs = 0L;
                latestTimestampMatchDeltaNs = -1L;
            } else {
                exposureJson = matchedMetadata.exposureJson;
                latestMatchedMetadataTimestampNs = matchedMetadata.timestampNs;
                latestTimestampMatchDeltaNs = matchedMetadata.deltaNs;
            }
            latestExposureMetadataJson = exposureJson;
            String metadataBlocker = requiredCaptureMetadataBlocker(exposureJson);
            latestCaptureMetadataBlocker = metadataBlocker;
            if (metadataBlocker != null) {
                latestCaptureReadyJpeg = null;
                latestCaptureReadyMetadataJson = null;
            } else {
                latestCaptureReadyJpeg = jpeg.clone();
                latestCaptureReadyMetadataJson = exposureJson;
                latestCaptureReadyAt = latestMetadataAttemptAt;
            }
            logCaptureDebug("image ts=" + imageTimestampNs
                    + " matched ts=" + latestMatchedMetadataTimestampNs
                    + " delta=" + latestTimestampMatchDeltaNs
                    + " source=" + metadataSource(exposureJson)
                    + " buffer=" + captureMetadataByTimestamp.size()
                    + " blocker=" + metadataBlocker);
            if (latestArPreviewBitmap != null && latestArPreviewBitmap != bitmap) {
                latestArPreviewBitmap.recycle();
            }
            latestArPreviewBitmap = bitmap;
        }
        runOnUiThread(() -> {
            updateSensorOverlay();
            updateCompassUi();
        });
    }

    private static boolean isOnMainThread() {
        return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }

    /*
     * Function: yuv420ToJpeg
     * Arguments: image is an ARCore YUV_420_888 frame; quality is JPEG quality.
     * Calls: yuv420ToNv21(), YuvImage.compressToJpeg(), and the reused yuvJpegStream.
     * Flow: adapt Android's multi-plane YUV camera image into a compact JPEG used
     * for both live preview and draft frame capture. The ByteArrayOutputStream is
     * reused across frames to reduce per-frame allocation pressure.
     */
    private byte[] yuv420ToJpeg(Image image, int quality) {
        byte[] nv21 = yuv420ToNv21(image);
        YuvImage yuvImage = new YuvImage(
                nv21,
                ImageFormat.NV21,
                image.getWidth(),
                image.getHeight(),
                null);
        if (yuvJpegStream == null) {
            yuvJpegStream = new java.io.ByteArrayOutputStream(nv21.length);
        } else {
            yuvJpegStream.reset();
        }
        yuvImage.compressToJpeg(
                new Rect(0, 0, image.getWidth(), image.getHeight()), quality, yuvJpegStream);
        return yuvJpegStream.toByteArray();
    }

    /*
     * Function: orientCapturedJpegUpright
     * Arguments: jpeg is the raw ARCore camera frame encoded from YUV.
     * Calls: BitmapFactory.decodeByteArray(), Matrix.postRotate(), and
     * Bitmap.compress().
     * Flow: ARCore CPU camera frames arrive in the sensor's landscape buffer
     * orientation, which displays as 90 degrees left in the draft browser. Rotate
     * landscape captures clockwise into upright portrait pixels before saving.
     */
    private byte[] orientCapturedJpegUpright(byte[] jpeg) {
        Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (source == null) {
            return jpeg;
        }
        if (source.getWidth() <= source.getHeight()) {
            source.recycle();
            return jpeg;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(90f);
        Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        rotated.compress(Bitmap.CompressFormat.JPEG, 92, output);
        source.recycle();
        if (rotated != source) {
            rotated.recycle();
        }
        return output.toByteArray();
    }

    /*
     * Function: yuv420ToNv21
     * Arguments: image is an Android YUV_420_888 image.
     * Calls: copyPlane().
     * Flow: copy Y, V, and U plane data into the NV21 byte order expected by
     * YuvImage while respecting row and pixel strides. The output buffer is
     * reused across frames (reallocated only when frame dimensions change) to
     * avoid per-frame allocation on the GL thread.
     */
    private byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int requiredSize = width * height * 3 / 2;
        if (yuvNv21Buffer == null || yuvNv21Buffer.length < requiredSize) {
            yuvNv21Buffer = new byte[requiredSize];
        }
        Image.Plane[] planes = image.getPlanes();
        copyPlane(planes[0].getBuffer(), yuvNv21Buffer, 0, width, height,
                planes[0].getRowStride(), planes[0].getPixelStride(), false);
        int chromaOffset = width * height;
        copyPlane(planes[2].getBuffer(), yuvNv21Buffer, chromaOffset, width / 2, height / 2,
                planes[2].getRowStride(), planes[2].getPixelStride(), true);
        copyPlane(planes[1].getBuffer(), yuvNv21Buffer, chromaOffset + 1, width / 2, height / 2,
                planes[1].getRowStride(), planes[1].getPixelStride(), true);
        return yuvNv21Buffer;
    }

    /*
     * Function: copyPlane
     * Arguments: buffer is one image plane; output receives bytes; outputOffset
     * starts the plane; width/height are plane dimensions; rowStride/pixelStride
     * describe the source layout; interleaved writes chroma bytes every other slot.
     * Calls: ByteBuffer.get(byte[], offset, length) for packed rows (fast path)
     * or ByteBuffer.get(position) for strided/interleaved rows (slow path).
     * Flow: for tightly-packed non-interleaved planes (the common Y-channel case),
     * transfer a whole row with one bulk read per row instead of per-byte calls.
     * For strided or interleaved chroma planes, fall back to the per-byte loop.
     */
    private void copyPlane(
            ByteBuffer buffer,
            byte[] output,
            int outputOffset,
            int width,
            int height,
            int rowStride,
            int pixelStride,
            boolean interleaved) {
        if (!interleaved && pixelStride == 1) {
            // Fast path: packed Y-plane or similarly packed plane — one bulk get() per row.
            int outputIndex = outputOffset;
            for (int row = 0; row < height; row++) {
                buffer.position(row * rowStride);
                buffer.get(output, outputIndex, width);
                outputIndex += width;
            }
        } else {
            // Slow path: strided or interleaved chroma plane.
            int outputIndex = outputOffset;
            for (int row = 0; row < height; row++) {
                int rowStart = row * rowStride;
                for (int column = 0; column < width; column++) {
                    output[outputIndex] = buffer.get(rowStart + column * pixelStride);
                    outputIndex += interleaved ? 2 : 1;
                }
            }
        }
    }

    /*
     * Function: captureFrame overload
     * Arguments: none; invoked by the Capture button.
     * Calls: captureFrame("manual").
     * Flow: route user-initiated capture through the shared capture path while
     * preserving the capture mode for draft metadata.
     */
    private void captureFrame() {
        captureFrame("manual");
    }

    /*
     * Function: captureFrame
     * Arguments: captureMode is "manual" or "auto" for draft metadata.
     * Calls: SpherifyLibrary.createDraftFrameFile(), FileOutputStream,
     * readLocationSummary(), SpherifyLibrary.recordDraftFrame(), Toast, and
     * runOnUiThread().
     * Flow: ensure ARCore/horizon/sweep layer are ready, allocate an output
     * JPEG, write the latest ARCore camera frame, then record metadata and mark
     * the current sweep slice as painted.
     */
    private void captureFrame(String captureMode) {
        if (capturePaused) {
            Toast.makeText(this, "Capture is paused", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isCaptureSetupReady()) {
            String message = "Capture locked until the ARCore horizon sweep completes. "
                    + horizonReferenceProgressText();
            statusText.setText(arCoreReady ? "Horizon sweep needed" : "ARCore required");
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasHeadingReading || !hasPitchRollReading) {
            Toast.makeText(this, "Capture waiting for complete pose metadata", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isSweepLayerPitchAligned()) {
            Toast.makeText(
                    this,
                    String.format(Locale.US, "Tilt to %+d deg for this layer", currentSweepLayerPitch()),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!sweepLayerPaintingActive && ("sweep-auto".equals(captureMode) || "polar-auto".equals(captureMode))) {
            return;
        }
        if (!sweepLayerPaintingActive && currentSweepLayerIndex == 0) {
            Toast.makeText(this, "Use Start/End for the horizon sweep", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isCurrentPolarLayer() && "Move slower".equals(currentSweepSpeedMessage())) {
            Toast.makeText(this, "Move slower before painting this slice", Toast.LENGTH_SHORT).show();
            return;
        }
        int sweepLayerIndex = currentSweepLayerIndex;
        int sweepBin = currentCaptureBin();
        if (sweepBin < 0) {
            return;
        }
        boolean manualBlockCapture = "manual".equals(captureMode);
        if (sweepCaptureBins[sweepLayerIndex][sweepBin]
                && ("sweep-auto".equals(captureMode) || "polar-auto".equals(captureMode))) {
            return;
        }
        CaptureFramePacket packet = currentCaptureFramePacket();
        if (packet == null) {
            beginCaptureMetadataRetry(captureMode, System.currentTimeMillis(), 0);
            return;
        }
        commitCaptureFrame(captureMode, packet, sweepLayerIndex, sweepBin);
    }

    private CaptureFramePacket currentCaptureFramePacket() {
        synchronized (arFrameLock) {
            if (latestCaptureReadyJpeg == null || latestCaptureReadyMetadataJson == null) {
                return null;
            }
            String metadataBlocker = requiredCaptureMetadataBlocker(latestCaptureReadyMetadataJson);
            if (metadataBlocker != null) {
                latestCaptureMetadataBlocker = metadataBlocker;
                return null;
            }
            return new CaptureFramePacket(latestCaptureReadyJpeg.clone(), latestCaptureReadyMetadataJson);
        }
    }

    private void beginCaptureMetadataRetry(String captureMode, long startedAt, int attempt) {
        captureInProgress = true;
        captureMetadataRetryAttempts = attempt;
        String blocker = latestCaptureBlockerSummary();
        Log.w(TAG, "capture metadata retry started mode=" + captureMode + " blocker=" + blocker);
        statusText.setText("Waiting for camera metadata");
        if (attempt == 0 && !"sweep-auto".equals(captureMode) && !"polar-auto".equals(captureMode)) {
            Toast.makeText(this, blocker, Toast.LENGTH_SHORT).show();
        }
        previewView.postDelayed(() -> retryCaptureWhenMetadataReady(captureMode, startedAt, attempt + 1),
                CAPTURE_METADATA_RETRY_INTERVAL_MS);
    }

    private void retryCaptureWhenMetadataReady(String captureMode, long startedAt, int attempt) {
        if (capturePaused || !isCaptureSetupReady() || !hasHeadingReading || !hasPitchRollReading) {
            captureInProgress = false;
            updateSensorOverlay();
            updateCompassUi();
            return;
        }
        if (!isSweepLayerPitchAligned()
                || (!isCurrentPolarLayer() && "Move slower".equals(currentSweepSpeedMessage()))
                || !isKeyframeStable()) {
            captureInProgress = false;
            updateSensorOverlay();
            updateCompassUi();
            return;
        }
        int sweepLayerIndex = currentSweepLayerIndex;
        int sweepBin = currentCaptureBin();
        if (sweepLayerIndex < 0 || sweepLayerIndex >= sweepCaptureBins.length || sweepBin < 0) {
            captureInProgress = false;
            updateSensorOverlay();
            updateCompassUi();
            return;
        }
        CaptureFramePacket packet = currentCaptureFramePacket();
        if (packet != null) {
            Log.d(TAG, "capture metadata ready after retry attempt=" + attempt
                    + " mode=" + captureMode
                    + " matched ts=" + latestMatchedMetadataTimestampNs
                    + " delta=" + latestTimestampMatchDeltaNs);
            commitCaptureFrame(captureMode, packet, sweepLayerIndex, sweepBin);
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        captureMetadataRetryAttempts = attempt;
        if (elapsed >= CAPTURE_METADATA_RETRY_TIMEOUT_MS) {
            captureInProgress = false;
            statusText.setText("Camera metadata unavailable");
            String debugSummary;
            synchronized (arFrameLock) {
                debugSummary = " blocker=" + latestCaptureMetadataBlocker
                        + " image ts=" + latestImageTimestampNs
                        + " matched ts=" + latestMatchedMetadataTimestampNs
                        + " delta=" + latestTimestampMatchDeltaNs
                        + " buffer=" + captureMetadataByTimestamp.size();
            }
            Log.w(TAG, "capture metadata retry timed out mode=" + captureMode
                    + " attempts=" + attempt
                    + debugSummary);
            if (!"sweep-auto".equals(captureMode) && !"polar-auto".equals(captureMode)) {
                Toast.makeText(this, latestCaptureBlockerSummary(), Toast.LENGTH_LONG).show();
            }
            updateSensorOverlay();
            updateCompassUi();
            return;
        }
        previewView.postDelayed(() -> retryCaptureWhenMetadataReady(captureMode, startedAt, attempt + 1),
                CAPTURE_METADATA_RETRY_INTERVAL_MS);
    }

    private void commitCaptureFrame(
            String captureMode,
            CaptureFramePacket packet,
            int sweepLayerIndex,
            int sweepBin) {
        if (sweepCaptureBins[sweepLayerIndex][sweepBin]
                && ("sweep-auto".equals(captureMode) || "polar-auto".equals(captureMode))) {
            captureInProgress = false;
            return;
        }
        Log.d(TAG, "capture commit started mode=" + captureMode
                + " layer=" + sweepLayerIndex
                + " bin=" + sweepBin
                + " source=" + metadataSource(packet.exposureJson)
                + " metadata ts=" + metadataTimestampNs(packet.exposureJson));
        Bitmap previewStrip = createCurrentSweepPreviewStrip();

        File outputFile;
        try {
            outputFile = library.createDraftFrameFile();
        } catch (IOException e) {
            Toast.makeText(this, "Draft file failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        captureInProgress = true;
        captureMetadataRetryAttempts = 0;
        boolean manualBlockCapture = "manual".equals(captureMode);
        int targetYawDegrees = isPolarLayer(sweepLayerIndex)
                ? POLAR_ROLL_SLOT_TARGETS[Math.max(0, polarSlotIndexForSweepBin(sweepBin))]
                : isHighPitchRingLayer(sweepLayerIndex)
                ? HIGH_PITCH_RING_YAW_DEGREES[highPitchRingSlotIndexForSweepBin(sweepBin)]
                : sweepCaptureBinCenterDegrees(sweepBin);
        int targetPitchDegrees = SWEEP_LAYER_PITCH_DEGREES[sweepLayerIndex];
        float relativeHeadingDegrees = isPolarLayer(sweepLayerIndex)
                ? 0f
                : relativeSweepYawDegrees(compassHeadingDegrees);
        float recordedPitchDegrees = isPolarLayer(sweepLayerIndex) ? targetPitchDegrees : pitchDegrees;
        float recordedRollDegrees = isPolarLayer(sweepLayerIndex) ? targetYawDegrees : rollDegrees;
        new Thread(() -> {
            byte[] uprightJpeg = orientCapturedJpegUpright(packet.jpeg);
            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                output.write(uprightJpeg);
            } catch (IOException e) {
                runOnUiThread(() -> {
                    captureInProgress = false;
                    Toast.makeText(
                            CaptureActivity.this,
                            "Capture failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
                Log.w(TAG, "capture jpeg write failed", e);
                return;
            }
            try {
                String location = readLocationSummary();
                library.recordDraftFrame(
                        outputFile,
                        sessionId,
                        location,
                        relativeHeadingDegrees,
                        recordedPitchDegrees,
                        recordedRollDegrees,
                        targetYawDegrees,
                        targetPitchDegrees,
                        manualBlockCapture ? "manual-block" : captureMode,
                        captureProfile,
                        packet.exposureJson);
                runOnUiThread(() -> {
                    captureInProgress = false;
                    sweepCaptureBins[sweepLayerIndex][sweepBin] = true;
                    if (previewStrip != null
                            && sweepLayerIndex > 0
                            && sweepLayerIndex < sweepCapturePreviewFrames.length) {
                        if (sweepCapturePreviewFrames[sweepLayerIndex][sweepBin] != null) {
                            sweepCapturePreviewFrames[sweepLayerIndex][sweepBin].recycle();
                        }
                        sweepCapturePreviewFrames[sweepLayerIndex][sweepBin] = previewStrip;
                        updateSweepLayerPreviewPanorama();
                    } else if (previewStrip != null) {
                        previewStrip.recycle();
                    }
                    lastCapturedSweepLayerIndex = sweepLayerIndex;
                    lastCapturedSweepBin = sweepBin;
                    statusText.setText("Slice painted");
                    updateCompassUi();
                    updateGuideState();
                    if (!"sweep-auto".equals(captureMode) && !"polar-auto".equals(captureMode)) {
                        Toast.makeText(CaptureActivity.this, "Sweep slice saved", Toast.LENGTH_SHORT).show();
                    }
                });
                Log.d(TAG, "capture commit saved mode=" + captureMode
                        + " layer=" + sweepLayerIndex
                        + " bin=" + sweepBin
                        + " file=" + outputFile.getAbsolutePath());
            } catch (IOException e) {
                outputFile.delete();
                runOnUiThread(() -> {
                    captureInProgress = false;
                    Toast.makeText(
                            CaptureActivity.this,
                            "Metadata failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
                Log.w(TAG, "capture metadata write failed", e);
            }
        }).start();
    }

    /*
     * Function: readLocationSummary
     * Arguments: none.
     * Calls: ActivityCompat.checkSelfPermission(), LocationManager.getLastKnownLocation(),
     * and String.format(Locale.US).
     * Flow: if location permission exists, read GPS or network last-known
     * location and return "latitude,longitude"; otherwise return an empty string
     * so draft capture remains usable without location.
     */
    private String readLocationSummary() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }

        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            return "";
        }

        Location location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location == null) {
            location = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }
        if (location == null) {
            return "";
        }
        return String.format(Locale.US, "%.6f,%.6f", location.getLatitude(), location.getLongitude());
    }

    /*
     * Function: buildCaptureTargets
     * Arguments: none.
     * Calls: CaptureTarget constructor.
     * Flow: create the first Phase 4 sphere guide as three pitch bands and eight
     * yaw positions, enough overlap for future stitching experiments without
     * overwhelming this prototype UI.
     */
    private static CaptureTarget[] buildCaptureTargets() {
        int[] pitchRows = {-35, 0, 35};
        int[] yawColumns = {0, 45, 90, 135, 180, 225, 270, 315};
        CaptureTarget[] targets = new CaptureTarget[pitchRows.length * yawColumns.length];
        int index = 0;
        for (int pitch : pitchRows) {
            for (int yaw : yawColumns) {
                targets[index] = new CaptureTarget(yaw, pitch);
                index++;
            }
        }
        return targets;
    }

    /*
     * Function: newSessionId
     * Arguments: none.
     * Calls: SimpleDateFormat.format().
     * Flow: create a readable stable date id for all frames in one guided
     * capture attempt so interrupted drafts can be recognized later.
     */
    private static String newSessionId() {
        return new SimpleDateFormat("yyMMddss-SSS", Locale.US).format(new Date());
    }

    /*
     * Class: CaptureTarget
     * Educational overview:
     * Value object for one target in the Phase 4 guided capture grid. A target is
     * deliberately small: approximate yaw, approximate pitch, and whether the
     * user has already captured a frame for that coverage point.
     */
    private static final class CaptureTarget {
        final int yawDegrees;
        final int pitchDegrees;
        boolean captured;

        /*
         * Function: CaptureTarget constructor
         * Arguments: yawDegrees and pitchDegrees define the desired orientation.
         * Calls: no helpers.
         * Flow: store the orientation target used by TargetGuideView and draft
         * metadata recording.
         */
        CaptureTarget(int yawDegrees, int pitchDegrees) {
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
        }
    }

    private static final class CaptureFramePacket {
        final byte[] jpeg;
        final String exposureJson;

        CaptureFramePacket(byte[] jpeg, String exposureJson) {
            this.jpeg = jpeg;
            this.exposureJson = exposureJson;
        }
    }

    private static final class TimestampedMetadata {
        final long timestampNs;
        final String exposureJson;
        final long deltaNs;

        TimestampedMetadata(long timestampNs, String exposureJson, long deltaNs) {
            this.timestampNs = timestampNs;
            this.exposureJson = exposureJson;
            this.deltaNs = deltaNs;
        }
    }

    private static String requiredCaptureMetadataBlocker(String exposureJson) {
        if (exposureJson == null || exposureJson.trim().isEmpty()) {
            return "Capture waiting for complete camera metadata";
        }
        try {
            JSONObject json = new JSONObject(exposureJson);
            if (!json.optBoolean("available", false)) {
                return "Capture waiting for exposure metadata";
            }
            String[] positiveFloatFields = {
                    "sensorExposureTimeNs",
                    "sensorSensitivityIso",
                    "sensorFrameDurationNs",
                    "sensorTimestampNs",
                    "lensAperture",
                    "lensFocalLengthMm",
                    "sensorPhysicalWidthMm",
                    "sensorPhysicalHeightMm",
                    "imageFocalLengthXPixels",
                    "imageFocalLengthYPixels",
                    "imagePrincipalPointXPixels",
                    "imagePrincipalPointYPixels",
                    "imageIntrinsicsWidth",
                    "imageIntrinsicsHeight"
            };
            for (String field : positiveFloatFields) {
                if (json.isNull(field) || json.optDouble(field, 0.0) <= 0.0) {
                    return "Capture waiting for " + field;
                }
            }
            String[] requiredStateFields = {
                    "aeState",
                    "awbState",
                    "aeExposureCompensation",
                    "aeMode",
                    "awbMode"
            };
            for (String field : requiredStateFields) {
                if (json.isNull(field)) {
                    return "Capture waiting for " + field;
                }
            }
            return null;
        } catch (JSONException ignored) {
            return "Capture waiting for valid camera metadata";
        }
    }

    /*
     * Class: ArCameraRenderer
     * Educational overview:
     * Renders ARCore's camera background texture into the capture preview. ARCore
     * only starts producing a visible camera background after the app provides an
     * external OES texture through Session.setCameraTextureNames().
     */
    private final class ArCameraRenderer implements GLSurfaceView.Renderer {
        private static final String VERTEX_SHADER =
                "attribute vec2 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                "  vTexCoord = aTexCoord;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n";

        private final FloatBuffer vertices = ByteBuffer
                .allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});

        private final FloatBuffer texCoords = ByteBuffer
                .allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(new float[]{1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f});

        private int textureId;
        private int program;
        private int positionHandle;
        private int texCoordHandle;
        private int textureHandle;

        ArCameraRenderer() {
            vertices.position(0);
            texCoords.position(0);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.02f, 0.04f, 0.06f, 1f);
            textureId = createExternalTexture();
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture");
            onArCameraTextureCreated(textureId);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, Math.max(1, width), Math.max(1, height));
            if (arSession != null) {
                arSession.setDisplayGeometry(
                        getWindowManager().getDefaultDisplay().getRotation(),
                        Math.max(1, width),
                        Math.max(1, height));
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            drawArFrame(this);
        }

        void drawCamera() {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(textureHandle, 0);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        void drawEmpty() {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        private int createExternalTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            return textures[0];
        }

        private int buildProgram(String vertexShaderSource, String fragmentShaderSource) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource);
            if (vertexShader == 0 || fragmentShader == 0) {
                if (vertexShader != 0) GLES20.glDeleteShader(vertexShader);
                if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader);
                return 0;
            }
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                android.util.Log.e("ArCameraRenderer", "GL program link failed: " + log);
                runOnUiThread(() -> showArCoreUnsupportedMessage());
                return 0;
            }
            return program;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                android.util.Log.e("ArCameraRenderer", "GL shader compile failed: " + log);
                runOnUiThread(() -> showArCoreUnsupportedMessage());
                return 0;
            }
            return shader;
        }
    }

    /*
     * Class: TargetGuideView
     * Educational overview:
     * Draws the Phase 4 capture guide over the live ARCore preview. It shows a
     * fixed reticle in the middle, nearby yaw/pitch targets around it, captured
     * targets as green dots, the active target as a larger ring with a one-
     * second countdown pie, and a coverage bar so missing areas are obvious
     * before stitching exists.
     *
     * Data flow:
     * Sensor readings -> updateGuideState() -> setGuideState() stores a snapshot
     * -> invalidate() schedules onDraw() -> onDraw() maps angular deltas into
     * preview coordinates and paints the guidance overlay.
     */
    private static final class TargetGuideView extends View {
        private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint capturedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint horizonReferencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint horizonLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tiltWarningPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tiltWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sweepPitchLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sweepBinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highRowArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Rect panoramaSourceRect = new Rect();
        private CaptureTarget[] targets = new CaptureTarget[0];
        private Bitmap horizonReferencePanorama;
        private int activeTargetIndex;
        private float headingDegrees;
        private float pitchDegrees;
        private float coverageProgress;
        private float horizonReferenceProgress;
        private float lockProgress;
        private boolean aligned;
        private boolean stable;
        private boolean paused;
        private boolean horizonSweepStarted;
        private boolean horizonSweepComplete;
        private boolean horizonSweepAwaitingClose;
        private boolean horizonStartAligned;
        private String sweepSpeedMessage = "";
        private float sweepTiltDeltaDegrees;
        private boolean sweepPaintActive;
        private boolean sweepLayerPitchAligned;
        private boolean sweepLayerComplete;
        private boolean sweepLayerPaintingActive;
        private boolean sweepLayerStartAligned;
        private boolean polarLayerActive;
        private boolean previewDragActive;
        private boolean firstLayerDotCaptured;
        private String sweepLayerName = "Horizon";
        private String sweepLayerPrompt = "";
        private int sweepLayerPitchDegrees;
        private int currentSweepBin;
        private int currentHorizonBin;
        private float currentLayerHeadingDegrees;
        private float sweepLayerProgress;
        private boolean[] sweepLayerBins = new boolean[0];
        private boolean[] horizonBins = new boolean[0];
        private final int[] sweepLayerPitchRows = SWEEP_LAYER_PITCH_DEGREES.clone();

        /*
         * Function: TargetGuideView constructor
         * Arguments: context is the Android owner used by the View base class.
         * Calls: Paint setters.
         * Flow: configure reusable paint objects for transparent overlay drawing.
         */
        TargetGuideView(android.content.Context context) {
            super(context);
            setWillNotDraw(false);
            reticlePaint.setColor(0xFFFFFFFF);
            reticlePaint.setStyle(Paint.Style.STROKE);
            reticlePaint.setStrokeWidth(3f);
            targetPaint.setColor(0xAAE2E8F0);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(3f);
            activePaint.setColor(0xFFFACC15);
            activePaint.setStyle(Paint.Style.STROKE);
            activePaint.setStrokeWidth(5f);
            capturedPaint.setColor(0xFF22C55E);
            capturedPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(24f);
            panelPaint.setColor(0x9905070A);
            panelPaint.setStyle(Paint.Style.FILL);
            progressTrackPaint.setColor(0x77334155);
            progressTrackPaint.setStyle(Paint.Style.FILL);
            progressFillPaint.setColor(0xFF38BDF8);
            progressFillPaint.setStyle(Paint.Style.FILL);
            horizonReferencePaint.setAlpha(128);
            horizonReferencePaint.setFilterBitmap(true);
            horizonLinePaint.setColor(0x99F8FAFC);
            horizonLinePaint.setStyle(Paint.Style.STROKE);
            horizonLinePaint.setStrokeWidth(2f);
            tiltWarningPaint.setStyle(Paint.Style.FILL);
            tiltWarningTextPaint.setColor(0xFFFFFFFF);
            tiltWarningTextPaint.setTextAlign(Paint.Align.CENTER);
            tiltWarningTextPaint.setTextSize(26f);
            tiltWarningTextPaint.setFakeBoldText(true);
            sweepPitchLinePaint.setColor(0xDDFACC15);
            sweepPitchLinePaint.setStyle(Paint.Style.STROKE);
            sweepPitchLinePaint.setStrokeWidth(4f);
            sweepBinPaint.setStyle(Paint.Style.FILL);
            highRowArrowPaint.setColor(0xFFFACC15);
            highRowArrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            highRowArrowPaint.setStrokeWidth(4f);
        }

        /*
         * Function: setHorizonReference
         * Arguments: panorama is the assembled low-resolution 360-degree strip;
         * started/completed describe the sweep state; progress is 0..1.
         * Calls: invalidate().
         * Flow: keep a reference to the latest horizon belt samples for
         * translucent overlay drawing above the live camera preview.
         */
        void setHorizonReference(
                Bitmap panorama,
                boolean started,
                boolean completed,
                float progress,
                boolean awaitingClose,
                boolean startAligned,
                String speedMessage,
                float tiltDeltaDegrees,
                boolean[] bins,
                int currentBin) {
            horizonReferencePanorama = panorama;
            horizonSweepStarted = started;
            horizonSweepComplete = completed;
            horizonReferenceProgress = clamp01(progress);
            horizonSweepAwaitingClose = awaitingClose;
            horizonStartAligned = startAligned;
            sweepSpeedMessage = speedMessage == null ? "" : speedMessage;
            sweepTiltDeltaDegrees = tiltDeltaDegrees;
            horizonBins = bins == null ? new boolean[0] : bins.clone();
            currentHorizonBin = currentBin;
            invalidate();
        }

        /*
         * Function: setGuideState
         * Arguments: targets are the capture points; activeTargetIndex is the
         * current target; heading/pitch are the damped guide orientation;
         * aligned/stable describe reticle readiness; lockProgress is the one-
         * second capture countdown; coverageProgress is 0..1; paused gates UI.
         * Calls: invalidate().
         * Flow: store a drawing snapshot from CaptureActivity and request a
         * repaint on the UI thread.
         */
        void setGuideState(
                CaptureTarget[] targets,
                int activeTargetIndex,
                float headingDegrees,
                float pitchDegrees,
                boolean aligned,
                boolean stable,
                float lockProgress,
                float coverageProgress,
                boolean paused) {
            this.targets = targets;
            this.activeTargetIndex = activeTargetIndex;
            this.headingDegrees = headingDegrees;
            this.pitchDegrees = pitchDegrees;
            this.aligned = aligned;
            this.stable = stable;
            this.lockProgress = lockProgress;
            this.coverageProgress = coverageProgress;
            this.paused = paused;
            invalidate();
        }

        /*
         * Function: setSweepPaintState
         * Arguments: active enables sweep-first painting display; layer values
         * describe the current photosphere band and progress.
         * Calls: invalidate().
         * Flow: keep the overlay focused on painted band coverage instead of
         * discrete target points.
         */
        void setSweepPaintState(
                boolean active,
                String layerName,
                String layerPrompt,
                int layerPitchDegrees,
                float layerProgress,
                float totalProgress,
                boolean pitchAligned,
                boolean layerComplete,
                boolean paintingActive,
                boolean startAligned,
                boolean[] layerBins,
                int currentBin,
                float layerHeadingDegrees,
                boolean firstDotCaptured,
                boolean draggingPreview,
                boolean polarActive) {
            sweepPaintActive = active;
            sweepLayerName = layerName == null ? "" : layerName;
            sweepLayerPrompt = layerPrompt == null ? "" : layerPrompt;
            sweepLayerPitchDegrees = layerPitchDegrees;
            sweepLayerProgress = clamp01(layerProgress);
            coverageProgress = clamp01(totalProgress);
            sweepLayerPitchAligned = pitchAligned;
            sweepLayerComplete = layerComplete;
            sweepLayerPaintingActive = paintingActive;
            sweepLayerStartAligned = startAligned;
            currentSweepBin = currentBin;
            currentLayerHeadingDegrees = normalizeHeading(layerHeadingDegrees);
            firstLayerDotCaptured = firstDotCaptured;
            previewDragActive = draggingPreview;
            polarLayerActive = polarActive;
            sweepLayerBins = layerBins == null ? new boolean[0] : layerBins.clone();
            invalidate();
        }

        /*
         * Function: onDraw
         * Arguments: canvas is Android's drawing target.
         * Calls: drawTargets(), drawReticle(), drawProgress(), and Canvas APIs.
         * Flow: paint only lightweight overlay elements so CameraX remains the
         * visual base of the capture experience.
         */
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawTiltWarning(canvas);
            drawHorizonReference(canvas);
            if (polarLayerActive && sweepPaintActive && horizonSweepComplete) {
                drawPolarGuide(canvas);
            } else {
                drawSweepPitchLine(canvas);
            }
            drawSphericalGridDots(canvas);
            drawAlignmentLine(canvas);
            drawReticle(canvas);
            drawProgress(canvas);
        }

        private void drawSphericalGridDots(Canvas canvas) {
            if (!horizonSweepStarted) {
                return;
            }
            if (!horizonSweepComplete) {
                drawCaptureRowDots(canvas, horizonBins, currentHorizonBin, 0, true);
                return;
            }
            if (!sweepPaintActive || polarLayerActive) {
                return;
            }
            if (isHighRowDotLayer()) {
                drawHighRowArrowDots(canvas);
                return;
            }
            drawCaptureRowDots(canvas, sweepLayerBins, currentSweepBin, sweepLayerPitchDegrees, sweepLayerPaintingActive);
        }

        private void drawCaptureRowDots(
                Canvas canvas,
                boolean[] bins,
                int currentBin,
                int rowPitchDegrees,
                boolean showCountdown) {
            if (bins == null || bins.length == 0) {
                return;
            }
            boolean directionalSlots = bins.length == HIGH_PITCH_RING_SWEEP_BINS.length
                    && Math.abs(rowPitchDegrees) >= 60;
            for (int i = 0; i < bins.length; i++) {
                float binYaw = (directionalSlots ? i : i + 0.5f) / bins.length * 360f;
                float yawDelta = !firstLayerDotCaptured && i == 0
                        ? 0f
                        : signedHeadingDelta(binYaw, activeRowHeadingDegrees());
                float pitchDelta = rowPitchDegrees - pitchDegrees;
                if (Math.abs(yawDelta) > 76f || Math.abs(pitchDelta) > 28f) {
                    continue;
                }
                float x = angularToX(yawDelta);
                float y = angularToY(pitchDelta);
                boolean current = i == currentBin;
                if (bins[i]) {
                    canvas.drawCircle(x, y, current ? 12f : 9f, capturedPaint);
                } else if (current) {
                    canvas.drawCircle(x, y, sweepLayerPitchAligned || !horizonSweepComplete ? 24f : 18f, activePaint);
                    if (showCountdown) {
                        drawLockPie(canvas, x, y, 32f);
                    }
                } else {
                    canvas.drawCircle(x, y, 11f, targetPaint);
                }
            }
        }

        private void drawHighRowArrowDots(Canvas canvas) {
            if (sweepLayerBins == null || sweepLayerBins.length == 0) {
                return;
            }
            boolean upper = sweepLayerPitchDegrees > 0;
            float horizonY = angularToY(-pitchDegrees);
            float dotOffset = Math.max(74f, Math.min(150f, getHeight() * 0.18f));
            float dotY = upper ? horizonY - dotOffset : horizonY + dotOffset;
            for (int i = 0; i < sweepLayerBins.length; i++) {
                float binYaw = i / (float) sweepLayerBins.length * 360f;
                float yawDelta = !firstLayerDotCaptured && i == 0
                        ? 0f
                        : signedHeadingDelta(binYaw, activeRowHeadingDegrees());
                if (Math.abs(yawDelta) > 76f) {
                    continue;
                }
                float x = angularToX(yawDelta);
                boolean current = i == currentSweepBin;
                drawHighRowArrow(canvas, x, horizonY, upper, current);
                if (dotY < -40f || dotY > getHeight() + 40f) {
                    continue;
                }
                if (sweepLayerBins[i]) {
                    canvas.drawCircle(x, dotY, current ? 12f : 9f, capturedPaint);
                } else if (current) {
                    canvas.drawCircle(x, dotY, sweepLayerPitchAligned ? 24f : 18f, activePaint);
                    drawLockPie(canvas, x, dotY, 32f);
                } else {
                    canvas.drawCircle(x, dotY, 11f, targetPaint);
                }
                sweepPitchLinePaint.setColor(0x66FACC15);
                canvas.drawLine(x, horizonY, x, dotY, sweepPitchLinePaint);
            }
        }

        private void drawHighRowArrow(Canvas canvas, float x, float y, boolean up, boolean current) {
            highRowArrowPaint.setColor(current ? 0xFFFACC15 : 0xAAE2E8F0);
            float shaft = current ? 36f : 28f;
            float head = current ? 13f : 10f;
            float direction = up ? -1f : 1f;
            canvas.drawLine(x, y, x, y + direction * shaft, highRowArrowPaint);
            Path arrow = new Path();
            arrow.moveTo(x, y + direction * (shaft + head));
            arrow.lineTo(x - head, y + direction * shaft);
            arrow.lineTo(x + head, y + direction * shaft);
            arrow.close();
            canvas.drawPath(arrow, highRowArrowPaint);
        }

        private boolean isHighRowDotLayer() {
            return horizonSweepComplete
                    && sweepPaintActive
                    && Math.abs(sweepLayerPitchDegrees) >= 60
                    && sweepLayerBins.length == HIGH_PITCH_RING_SWEEP_BINS.length;
        }

        private float activeRowHeadingDegrees() {
            if (horizonSweepComplete && sweepLayerBins.length > 0) {
                return currentLayerHeadingDegrees;
            }
            return normalizeHeading(headingDegrees);
        }

        /*
         * Function: drawAlignmentLine
         * Arguments: canvas is Android's drawing target.
         * Calls: Canvas line APIs.
         * Flow: draw a fixed vertical line to compare the real-world center with
         * the draggable painted preview while recovering alignment.
         */
        private void drawAlignmentLine(Canvas canvas) {
            reticlePaint.setColor(0xEE38BDF8);
            reticlePaint.setStrokeWidth(4f);
            float centerX = getWidth() * 0.5f;
            canvas.drawLine(centerX, 0f, centerX, getHeight(), reticlePaint);
            reticlePaint.setStrokeWidth(3f);
        }

        /*
         * Function: drawSweepPitchLine
         * Arguments: canvas is Android's drawing target.
         * Calls: angularToY() and Canvas line/text APIs.
         * Flow: show the exact horizontal pitch band the user should hold while
         * painting upper and lower layers.
         */
        private void drawSweepPitchLine(Canvas canvas) {
            if (!sweepPaintActive || !horizonSweepComplete) {
                return;
            }
            float y = angularToY(sweepLayerPitchDegrees - pitchDegrees);
            if (y < 0f || y > getHeight()) {
                return;
            }
            sweepPitchLinePaint.setColor(sweepLayerPitchAligned ? 0xDD22C55E : 0xDDFACC15);
            canvas.drawLine(20f, y, getWidth() - 20f, y, sweepPitchLinePaint);
            textPaint.setTextSize(20f);
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(sweepLayerName, 28f, Math.max(28f, y - 10f), textPaint);
        }

        /*
         * Function: drawPolarGuide
         * Arguments: canvas is Android's drawing target.
         * Calls: angularToY() and Canvas line/text APIs.
         * Flow: avoid the misleading cylindrical preview at the pole. Show only
         * the vertical pitch target because yaw/roll cues are unreliable when the
         * device points near straight up or down.
         */
        private void drawPolarGuide(Canvas canvas) {
            float y = angularToY(sweepLayerPitchDegrees - pitchDegrees);
            y = Math.max(42f, Math.min(getHeight() - 42f, y));
            sweepPitchLinePaint.setColor(sweepLayerPitchAligned ? 0xDD22C55E : 0xDDFACC15);
            canvas.drawLine(26f, y, getWidth() - 26f, y, sweepPitchLinePaint);

            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            textPaint.setTextSize(20f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(sweepLayerName + " 80 deg", centerX, Math.max(28f, y - 12f), textPaint);
            canvas.drawText(
                    sweepLayerPitchAligned
                            ? sweepLayerPaintingActive ? "Capturing pole" : "Tap Start"
                            : "Align pitch line",
                    centerX,
                    centerY + 54f,
                    textPaint);
        }

        /*
         * Function: drawTiltWarning
         * Arguments: canvas is Android's drawing target.
         * Calls: LinearGradient constructor and Canvas rectangle/text APIs.
         * Flow: warn immediately when the user pitches away from the reference
         * plane during the strip sweep, fading stronger as the drift increases.
         */
        private void drawTiltWarning(Canvas canvas) {
            if (!horizonSweepStarted || horizonSweepComplete || horizonSweepAwaitingClose) {
                return;
            }
            float drift = Math.abs(sweepTiltDeltaDegrees);
            if (drift <= TILT_WARNING_START_DEGREES) {
                return;
            }
            float amount = clamp01((drift - TILT_WARNING_START_DEGREES)
                    / (TILT_WARNING_FULL_DEGREES - TILT_WARNING_START_DEGREES));
            int alpha = Math.round(210f * amount);
            int opaqueRed = (alpha << 24) | 0x00DC2626;
            int transparentRed = 0x00DC2626;
            float barHeight = Math.max(86f, getHeight() * 0.13f);
            boolean tiltUp = sweepTiltDeltaDegrees > 0f;
            if (tiltUp) {
                tiltWarningPaint.setShader(new LinearGradient(
                        0f,
                        getHeight() - barHeight,
                        0f,
                        getHeight(),
                        transparentRed,
                        opaqueRed,
                        Shader.TileMode.CLAMP));
                rect.set(0f, getHeight() - barHeight, getWidth(), getHeight());
                canvas.drawRect(rect, tiltWarningPaint);
                tiltWarningPaint.setShader(null);
                canvas.drawText("Tilt UP", getWidth() * 0.5f, getHeight() - barHeight * 0.34f, tiltWarningTextPaint);
            } else {
                tiltWarningPaint.setShader(new LinearGradient(
                        0f,
                        barHeight,
                        0f,
                        0f,
                        transparentRed,
                        opaqueRed,
                        Shader.TileMode.CLAMP));
                rect.set(0f, 0f, getWidth(), barHeight);
                canvas.drawRect(rect, tiltWarningPaint);
                tiltWarningPaint.setShader(null);
                canvas.drawText("Tilt DOWN", getWidth() * 0.5f, barHeight * 0.62f, tiltWarningTextPaint);
            }
        }

        /*
         * Function: drawHorizonReference
         * Arguments: canvas is Android's drawing target.
         * Calls: drawHorizonReferenceSegment(), angularToY(), and Canvas bitmap/line
         * APIs.
         * Flow: project the current yaw window of every painted preview row at
         * its captured pitch so the overlay reflects the photosphere state.
         */
        private void drawHorizonReference(Canvas canvas) {
            if (!horizonSweepStarted || horizonReferencePanorama == null) {
                return;
            }
            if (polarLayerActive && sweepPaintActive && horizonSweepComplete) {
                return;
            }
            int rowHeight = Math.max(1, horizonReferencePanorama.getHeight() / Math.max(1, sweepLayerPitchRows.length));
            float bandHeight = getHeight() * (previewDragActive ? 0.34f : 0.26f);
            float drawWidth = getWidth() - 36f;
            horizonReferencePaint.setAlpha(previewDragActive ? 215 : 128);
            for (int row = 0; row < sweepLayerPitchRows.length; row++) {
                int rowPitch = sweepLayerPitchRows[row];
                float layerY = angularToY(rowPitch - pitchDegrees);
                if (layerY < -bandHeight || layerY > getHeight() + bandHeight) {
                    continue;
                }
                float sphericalWidth = drawWidth * sphericalLatitudeScale(rowPitch);
                float left = (getWidth() - sphericalWidth) * 0.5f;
                rect.set(
                        left,
                        layerY - bandHeight * 0.5f,
                        left + sphericalWidth,
                        layerY + bandHeight * 0.5f);
                drawHorizonReferenceWindow(canvas, rect, row * rowHeight, rowHeight);
                canvas.drawLine(0f, layerY, getWidth(), layerY, horizonLinePaint);
            }
            horizonReferencePaint.setAlpha(128);
        }

        /*
         * Function: sphericalLatitudeScale
         * Arguments: pitchDegrees is the latitude-like capture row angle.
         * Calls: Math.cos(), Math.toRadians(), Math.abs(), and Math.max().
         * Flow: narrow high-latitude preview rows so the guide reads more like a
         * sphere than a cylinder, especially around the polar capture rows.
         */
        private float sphericalLatitudeScale(float pitchDegrees) {
            return Math.max(0.22f, (float) Math.cos(Math.toRadians(Math.abs(pitchDegrees))));
        }

        /*
         * Function: drawHorizonReferenceWindow
         * Arguments: canvas is Android's drawing target; destination is the
         * screen band that should receive the heading-centered strip.
         * Calls: drawHorizonReferenceSegment() and normalizeHeading().
         * Flow: select a limited yaw window from the 360-degree reference strip,
         * splitting the draw when the current view crosses the 0/360 seam.
         */
        private void drawHorizonReferenceWindow(Canvas canvas, RectF destination, int sourceTop, int sourceHeight) {
            int panoramaWidth = horizonReferencePanorama.getWidth();
            float viewDegrees = previewDragActive
                    ? HORIZON_REFERENCE_VIEW_DEGREES * 0.55f
                    : HORIZON_REFERENCE_VIEW_DEGREES;
            float sourceWindowWidth = panoramaWidth * viewDegrees / 360f;
            float centerX = sweepLayerBins.length > 0
                    ? (currentSweepBin + 0.5f) / sweepLayerBins.length * panoramaWidth
                    : normalizeHeading(headingDegrees) / 360f * panoramaWidth;
            float sourceLeft = centerX - sourceWindowWidth * 0.5f;
            float sourceRight = centerX + sourceWindowWidth * 0.5f;
            if (sourceLeft < 0f) {
                float leftWidth = -sourceLeft;
                float rightWidth = sourceRight;
                float split = leftWidth / sourceWindowWidth;
                RectF leftDestination = new RectF(
                        destination.left,
                        destination.top,
                        destination.left + destination.width() * split,
                        destination.bottom);
                RectF rightDestination = new RectF(
                        leftDestination.right,
                        destination.top,
                        destination.right,
                        destination.bottom);
                drawHorizonReferenceSegment(canvas, panoramaWidth + sourceLeft, panoramaWidth, sourceTop, sourceHeight, leftDestination);
                drawHorizonReferenceSegment(canvas, 0f, rightWidth, sourceTop, sourceHeight, rightDestination);
            } else if (sourceRight > panoramaWidth) {
                float leftWidth = panoramaWidth - sourceLeft;
                float rightWidth = sourceRight - panoramaWidth;
                float split = leftWidth / sourceWindowWidth;
                RectF leftDestination = new RectF(
                        destination.left,
                        destination.top,
                        destination.left + destination.width() * split,
                        destination.bottom);
                RectF rightDestination = new RectF(
                        leftDestination.right,
                        destination.top,
                        destination.right,
                        destination.bottom);
                drawHorizonReferenceSegment(canvas, sourceLeft, panoramaWidth, sourceTop, sourceHeight, leftDestination);
                drawHorizonReferenceSegment(canvas, 0f, rightWidth, sourceTop, sourceHeight, rightDestination);
            } else {
                drawHorizonReferenceSegment(canvas, sourceLeft, sourceRight, sourceTop, sourceHeight, destination);
            }
        }

        /*
         * Function: drawHorizonReferenceSegment
         * Arguments: sourceLeft/sourceRight define a panorama slice; destination
         * defines where that slice is projected on screen.
         * Calls: Canvas.drawBitmap().
         * Flow: draw a single non-wrapping slice of the reference panorama.
         */
        private void drawHorizonReferenceSegment(
                Canvas canvas,
                float sourceLeft,
                float sourceRight,
                int sourceTop,
                int sourceHeight,
                RectF destination) {
            if (destination.width() <= 0f || sourceRight <= sourceLeft) {
                return;
            }
            panoramaSourceRect.set(
                    Math.max(0, Math.round(sourceLeft)),
                    Math.max(0, sourceTop),
                    Math.max(1, Math.round(sourceRight)),
                    Math.max(sourceTop + 1, sourceTop + sourceHeight));
            canvas.drawBitmap(horizonReferencePanorama, panoramaSourceRect, destination, horizonReferencePaint);
        }

        /*
         * Function: drawTargets
         * Arguments: canvas is Android's drawing target.
         * Calls: angularToX(), angularToY(), and Canvas circle APIs.
         * Flow: map each target's angular offset from the current phone
         * orientation into preview space, skipping far-away targets to keep the
         * guide readable.
         */
        private void drawTargets(Canvas canvas) {
            for (int i = 0; i < targets.length; i++) {
                CaptureTarget target = targets[i];
                float yawDelta = signedHeadingDelta(target.yawDegrees, headingDegrees);
                float pitchDelta = target.pitchDegrees - pitchDegrees;
                if (Math.abs(yawDelta) > 85f || Math.abs(pitchDelta) > 70f) {
                    continue;
                }
                float x = angularToX(yawDelta);
                float y = angularToY(pitchDelta);
                if (target.captured) {
                    canvas.drawCircle(x, y, 8f, capturedPaint);
                } else if (i == activeTargetIndex) {
                    float radius = aligned ? 24f : 18f;
                    canvas.drawCircle(x, y, radius, activePaint);
                    if (aligned && !target.captured) {
                        drawLockPie(canvas, x, y, radius + 8f);
                    }
                } else {
                    canvas.drawCircle(x, y, 12f, targetPaint);
                }
            }
        }

        /*
         * Function: drawLockPie
         * Arguments: canvas is the target; x/y center the active dot; radius
         * sizes the countdown ring.
         * Calls: Canvas.drawArc() and RectF.set().
         * Flow: draw a shrinking pie segment over the active target so the user
         * can see the one-second auto-capture lock expire.
         */
        private void drawLockPie(Canvas canvas, float x, float y, float radius) {
            float remaining = Math.max(0f, Math.min(1f, 1f - lockProgress));
            rect.set(x - radius, y - radius, x + radius, y + radius);
            activePaint.setStyle(Paint.Style.FILL);
            activePaint.setColor(stable ? 0x8822C55E : 0x88FACC15);
            canvas.drawArc(rect, -90f, 360f * remaining, true, activePaint);
            activePaint.setStyle(Paint.Style.STROKE);
            activePaint.setColor(0xFFFACC15);
        }

        /*
         * Function: drawReticle
         * Arguments: canvas is Android's drawing target.
         * Calls: Canvas line/circle/text APIs.
         * Flow: keep a stable center reference and plain status text near the
         * reticle without blocking the camera preview.
         */
        private void drawReticle(Canvas canvas) {
            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            reticlePaint.setColor(sweepPaintActive
                    ? sweepLayerPitchAligned ? 0xFF22C55E : 0xFFFACC15
                    : stable ? 0xFF22C55E : aligned ? 0xFFFACC15 : 0xFFFFFFFF);
            canvas.drawCircle(centerX, centerY, 34f, reticlePaint);
            canvas.drawLine(centerX - 48f, centerY, centerX - 18f, centerY, reticlePaint);
            canvas.drawLine(centerX + 18f, centerY, centerX + 48f, centerY, reticlePaint);
            canvas.drawLine(centerX, centerY - 48f, centerX, centerY - 18f, reticlePaint);
            canvas.drawLine(centerX, centerY + 18f, centerX, centerY + 48f, reticlePaint);

            String text = paused
                    ? "Paused"
                    : !horizonSweepStarted
                    ? "Aim at distant start point"
                    : horizonSweepAwaitingClose && !horizonSweepComplete
                    ? horizonStartAligned ? "Aligned - hold steady" : "Return to start point"
                    : !horizonSweepComplete
                    ? sweepSpeedMessage == null || sweepSpeedMessage.isEmpty() ? "Sweep horizon" : sweepSpeedMessage
                    : sweepPaintActive && sweepLayerComplete
                    ? sweepLayerPaintingActive ? "Return to start - tap Stop" : "Layer painted"
                    : sweepPaintActive && !sweepLayerPitchAligned
                    ? "Tilt to " + sweepLayerName
                    : sweepPaintActive && !sweepLayerPaintingActive
                    ? sweepLayerStartAligned ? "Tap Start" : "Align start point"
                    : sweepPaintActive && sweepLayerPrompt.startsWith("Polar")
                    ? "Roll left / middle / right"
                    : sweepPaintActive
                    ? sweepSpeedMessage == null || sweepSpeedMessage.isEmpty() ? "Paint this layer" : sweepSpeedMessage
                    : stable
                    ? "Hold steady - capture ready"
                    : aligned
                    ? "Hold steady"
                    : "Move to target";
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(text, centerX, centerY + 76f, textPaint);
        }

        /*
         * Function: drawProgress
         * Arguments: canvas is Android's drawing target.
         * Calls: Canvas rectangle/text APIs and clamp01().
         * Flow: show total target coverage as a bottom progress bar so missing
         * coverage is visible before any stitching UI exists.
         */
        private void drawProgress(Canvas canvas) {
            float left = 18f;
            float right = getWidth() - 18f;
            float bottom = getHeight() - 184f;
            rect.set(left, bottom, right, bottom + 86f);
            canvas.drawRoundRect(rect, 10f, 10f, panelPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(20f);
            canvas.drawText(
                    String.format(Locale.US, "Painted %.0f%%", coverageProgress * 100f),
                    left + 14f,
                    bottom + 24f,
                    textPaint);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(
                    horizonSweepComplete
                            ? String.format(Locale.US, "%s %.0f%%", sweepLayerName, sweepLayerProgress * 100f)
                            : horizonSweepAwaitingClose
                            ? "Return to start"
                            : String.format(Locale.US, "Reference %.0f%% - %s", horizonReferenceProgress * 100f, sweepSpeedMessage),
                    right - 14f,
                    bottom + 24f,
                    textPaint);
            rect.set(left + 14f, bottom + 34f, right - 14f, bottom + 44f);
            canvas.drawRoundRect(rect, 5f, 5f, progressTrackPaint);
            rect.set(left + 14f, bottom + 34f, left + 14f + (right - left - 28f) * coverageProgress, bottom + 44f);
            canvas.drawRoundRect(rect, 5f, 5f, progressFillPaint);
            drawSweepBins(canvas, left + 14f, bottom + 58f, right - 14f, bottom + 74f);
        }

        /*
         * Function: drawSweepBins
         * Arguments: canvas is Android's drawing target; bounds define the
         * current layer's live yaw coverage strip.
         * Calls: Canvas.drawRoundRect().
         * Flow: show painted/unpainted yaw slices while the user pans around the
         * active layer.
         */
        private void drawSweepBins(Canvas canvas, float left, float top, float right, float bottom) {
            if (!sweepPaintActive || sweepLayerBins.length == 0) {
                return;
            }
            float gap = 2f;
            float binWidth = (right - left - gap * (sweepLayerBins.length - 1)) / sweepLayerBins.length;
            for (int i = 0; i < sweepLayerBins.length; i++) {
                if (i == currentSweepBin) {
                    sweepBinPaint.setColor(0xFFFACC15);
                } else {
                    sweepBinPaint.setColor(sweepLayerBins[i] ? 0xFF22C55E : 0x77334155);
                }
                float x = left + i * (binWidth + gap);
                rect.set(x, top, x + binWidth, bottom);
                canvas.drawRoundRect(rect, 3f, 3f, sweepBinPaint);
            }
        }

        /*
         * Function: angularToX
         * Arguments: yawDelta is signed angular distance from center.
         * Calls: getWidth().
         * Flow: project a horizontal angular offset into preview coordinates.
         */
        private float angularToX(float yawDelta) {
            return getWidth() * 0.5f + yawDelta / 70f * getWidth() * 0.5f;
        }

        /*
         * Function: angularToY
         * Arguments: pitchDelta is signed angular distance from center.
         * Calls: getHeight().
         * Flow: project a vertical angular offset into preview coordinates.
         */
        private float angularToY(float pitchDelta) {
            return getHeight() * 0.5f - pitchDelta / 55f * getHeight() * 0.45f;
        }

        /*
         * Function: signedHeadingDelta
         * Arguments: target and current are headings in degrees.
         * Calls: modulo arithmetic only.
         * Flow: return the shortest signed turn from current heading to target
         * heading, preserving left/right direction for overlay placement.
         */
        private static float signedHeadingDelta(float target, float current) {
            float delta = (target - current + 540f) % 360f - 180f;
            return delta < -180f ? delta + 360f : delta;
        }
    }

    /*
     * Class: CalibrationProgressView
     * Educational overview:
     * Draws a simple graphical checklist for the mandatory ARCore horizon sweep.
     * Rather than showing only noisy headings and sensor numbers, it presents the
     * work as visible progress bars: time, ARCore, steadiness, yaw, and level.
     *
     * Data flow:
     * updateCompassUi() computes progress from filtered sensor data -> 
     * setCalibrationState() stores that progress -> invalidate() schedules
     * onDraw() -> onDraw() paints the current checklist on top of the preview.
     */
    private static final class CalibrationProgressView extends View {
        private static final String[] LABELS = {"Time", "ARCore", "Steady", "Yaw", "Level"};
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint completePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float[] progress = new float[LABELS.length];
        private boolean calibrationStarted;
        private boolean ready;

        /*
         * Function: CalibrationProgressView constructor
         * Arguments: context is the Android owner used by the View base class.
         * Calls: Paint setters.
         * Flow: configure reusable paint objects for panel, bars, and labels.
         */
        CalibrationProgressView(android.content.Context context) {
            super(context);
            panelPaint.setColor(0xD905070A);
            panelPaint.setStyle(Paint.Style.FILL);
            trackPaint.setColor(0x66334155);
            trackPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(0xFF38BDF8);
            fillPaint.setStyle(Paint.Style.FILL);
            completePaint.setColor(0xFF22C55E);
            completePaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(20f);
            textPaint.setTextAlign(Paint.Align.LEFT);
        }

        /*
         * Function: setCalibrationState
         * Arguments: calibrationStarted and ready describe the overall state;
         * time/accuracy/stability/heading/tilt are progress values from 0 to 1.
         * Calls: invalidate().
         * Flow: store the latest checklist state and redraw the overlay.
         */
        void setCalibrationState(
                boolean calibrationStarted,
                boolean ready,
                float time,
                float accuracy,
                float stability,
                float heading,
                float tilt) {
            this.calibrationStarted = calibrationStarted;
            this.ready = ready;
            progress[0] = time;
            progress[1] = accuracy;
            progress[2] = stability;
            progress[3] = heading;
            progress[4] = tilt;
            invalidate();
        }

        /*
         * Function: onDraw
         * Arguments: canvas is Android's drawing target.
         * Calls: Canvas draw APIs and drawStep().
         * Flow: paint a compact dark panel, show one plain-language instruction,
         * then draw each progress bar with completed bars turning green.
         */
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            rect.set(0f, 0f, width, height);
            canvas.drawRoundRect(rect, 10f, 10f, panelPaint);

            textPaint.setTextSize(20f);
            textPaint.setColor(0xFFFFFFFF);
            String title = ready
                    ? "Reference ready - capture unlocked"
                    : calibrationStarted
                    ? "Sweep the horizon"
                    : "Tap Start sweep";
            canvas.drawText(title, 16f, 28f, textPaint);

            float top = 44f;
            float rowHeight = 20f;
            for (int i = 0; i < LABELS.length; i++) {
                drawStep(canvas, i, 16f, top + i * rowHeight, width - 32f, rowHeight - 6f);
            }
        }

        /*
         * Function: drawStep
         * Arguments: canvas is the target; index selects the checklist item; x/y
         * position the row; width/height size the progress bar.
         * Calls: Canvas.drawText(), Canvas.drawRoundRect(), and Math.min().
         * Flow: draw one label, then a track and filled portion for that
         * horizon-sweep requirement.
         */
        private void drawStep(Canvas canvas, int index, float x, float y, float width, float height) {
            float labelWidth = 82f;
            textPaint.setTextSize(16f);
            textPaint.setColor(0xFFE2E8F0);
            canvas.drawText(LABELS[index], x, y + height, textPaint);

            float barX = x + labelWidth;
            float barWidth = Math.max(1f, width - labelWidth);
            rect.set(barX, y, barX + barWidth, y + height);
            canvas.drawRoundRect(rect, height * 0.5f, height * 0.5f, trackPaint);

            float filled = Math.max(0f, Math.min(1f, progress[index]));
            rect.set(barX, y, barX + barWidth * filled, y + height);
            canvas.drawRoundRect(
                    rect,
                    height * 0.5f,
                    height * 0.5f,
                    filled >= 1f ? completePaint : fillPaint);
        }
    }

    /*
     * Class: CompassNeedleView
     * Educational overview:
     * Draws the always-visible north pointer above the ARCore preview. The view
     * receives the device's current compass heading and pitch, then rotates a
     * simple needle so the tip points toward magnetic north in the user's current
     * screen-facing frame.
     *
     * Data flow:
     * SensorManager readings -> updateCompassHeading() -> setHeadingDegrees() ->
     * invalidate() -> onDraw() renders the rotated pointer.
     */
    private static final class CompassNeedleView extends View {
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path needlePath = new Path();
        private float headingDegrees;
        private boolean screenFlippedTowardUser;

        /*
         * Function: CompassNeedleView constructor
         * Arguments: context is the Android owner used by the View base class.
         * Calls: Paint setters.
         * Flow: configure ring, needle, and text paint once for repeated drawing.
         */
        CompassNeedleView(android.content.Context context) {
            super(context);
            ringPaint.setColor(0xCC05070A);
            ringPaint.setStyle(Paint.Style.FILL);
            needlePaint.setColor(0xFFE11D48);
            needlePaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(22f);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        /*
         * Function: setHeadingDegrees
         * Arguments: headingDegrees is the phone-top bearing relative to magnetic
         * north; pitchDegrees decides whether the user is looking up at the
         * screen enough to invert screen interpretation.
         * Calls: invalidate().
         * Flow: store the heading, flip only for the upward-looking screen frame,
         * and schedule a redraw so the needle remains live while the user moves.
         */
        void setHeadingDegrees(float headingDegrees, float pitchDegrees) {
            this.headingDegrees = headingDegrees;
            this.screenFlippedTowardUser = pitchDegrees > COMPASS_SCREEN_FLIP_PITCH_DEGREES;
            invalidate();
        }

        /*
         * Function: onDraw
         * Arguments: canvas is Android's drawing target.
         * Calls: Canvas/Paint/Path drawing APIs.
         * Flow: draw a dark circular backing, optionally mirror the upward-looking
         * screen frame, rotate opposite heading, draw the red north needle, then
         * restore orientation and label it N.
         */
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            float radius = Math.min(getWidth(), getHeight()) * 0.45f;
            canvas.drawCircle(centerX, centerY, radius, ringPaint);
            canvas.save();
            if (screenFlippedTowardUser) {
                canvas.scale(1f, -1f, centerX, centerY);
            }
            canvas.rotate(-headingDegrees, centerX, centerY);
            needlePath.reset();
            needlePath.moveTo(centerX, centerY - radius + 10f);
            needlePath.lineTo(centerX - 12f, centerY + 12f);
            needlePath.lineTo(centerX + 12f, centerY + 12f);
            needlePath.close();
            canvas.drawPath(needlePath, needlePaint);
            canvas.restore();
            canvas.drawText("N", centerX, centerY + radius + 2f, textPaint);
        }
    }
}
