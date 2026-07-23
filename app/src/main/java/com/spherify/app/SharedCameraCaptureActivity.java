/*
 * SharedCameraCaptureActivity.java
 *
 * Educational overview:
 * This is the production capture backend for Spherify. It uses ARCore
 * SharedCamera with Camera2 so every accepted source image can be tied to the
 * same camera stream as ARCore visual-inertial tracking. That is the contract
 * the panorama literature implies: target placement is registered through the
 * tracked camera, and each frame carries pose, tracking, feature-confidence,
 * intrinsics, exposure, and parallax facts before it reaches the stitch graph.
 *
 * Data flow:
 * MainActivity -> SharedCameraCaptureActivity -> ARCore shared camera session
 * -> Camera2 repeating request with ARCore surfaces plus a YUV CPU ImageReader
 * -> ARCore pose/tracking/point-cloud state on the GL thread -> user aligns a
 * projected target -> next timestamp-matched CPU image and TotalCaptureResult
 * become a solver source candidate -> quality/OpenCV overlap validation ->
 * persistent capture graph -> native OpenCV stitch/export before the activity
 * returns to the library.
 */
package com.spherify.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SharedCameraCaptureActivity extends Activity
        implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener, ImageReader.OnImageAvailableListener {
    private static final float TARGET_YAW_TOLERANCE_DEGREES = 6.5f;
    private static final float TARGET_PITCH_TOLERANCE_DEGREES = 6.0f;
    private static final int MIN_CPU_IMAGE_WIDTH = 1280;
    private static final int MIN_TRACKING_FEATURE_POINTS = 12;
    private static final int LOW_CONFIDENCE_FEATURE_POINTS = 30;
    private static final float FRONTIER_TARGET_MAX_DEGREES = 38f;
    private static final float ACTIVE_TARGET_HYSTERESIS_DEGREES = 24f;
    private static final int MAX_VISIBLE_FRONTIER_TARGETS = 8;
    private static final float MAX_TRANSLATION_FROM_ANCHOR_METERS = 0.12f;
    private static final long MIN_CAPTURE_INTERVAL_MS = 1100L;
    private static final long REQUIRED_ALIGNED_MS = 850L;
    private static final String CAPTURE_PROFILE = "arcore_shared_camera";
    private static final String TAG = "SpherifySharedCamera";

    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();
    private final CandidateQualityScorer qualityScorer = new CandidateQualityScorer();
    private final OpenCvOverlapValidator overlapValidator = new OpenCvOverlapValidator();
    private final TreeMap<Long, TotalCaptureResult> camera2ResultsByTimestamp = new TreeMap<>();
    private final ArrayList<CaptureTarget> targets = new ArrayList<>();
    private final ArrayList<Integer> selectableTargetIndices = new ArrayList<>();
    private final AtomicBoolean frameAvailable = new AtomicBoolean();
    private final float[] latestProjectionMatrix = new float[16];
    private final float[] latestViewMatrix = new float[16];

    private GLSurfaceView glSurfaceView;
    private TargetOverlayView overlayView;
    private TextView statusText;
    private Button captureButton;
    private SpherifyLibrary library;
    private Session arSession;
    private SharedCamera sharedCamera;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader cpuImageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String cameraId = "";
    private String sessionId;
    private boolean surfaceCreated;
    private boolean arRunning;
    private boolean captureAnchored;
    private boolean capturePending;
    private boolean captureInProgress;
    private boolean completionInProgress;
    private int activeTargetIndex;
    private int anchorYawDegrees;
    private int anchorPitchDegrees;
    private float[] anchorTranslationMeters;
    private long alignedSinceMs;
    private long lastCaptureAtMs;
    private int cameraTextureId;
    private int viewportWidth;
    private int viewportHeight;
    private android.util.Size selectedCpuImageSize = new android.util.Size(0, 0);
    private android.util.Size selectedGpuTextureSize = new android.util.Size(0, 0);
    private String cameraConfigSummary = "";
    private AlertDialog completionDialog;
    private TextView completionText;
    private final CameraBackgroundRenderer backgroundRenderer = new CameraBackgroundRenderer();
    private ArFrameState latestFrameState = ArFrameState.notReady("tracking not started");
    private CameraFacts cameraFacts = CameraFacts.unavailable();

    private final CameraDevice.StateCallback cameraDeviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            runOnUiThread(() -> showFatal("Camera error " + error));
        }
    };

    private final CameraCaptureSession.StateCallback cameraSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            captureSession = session;
            try {
                captureSession.setRepeatingRequest(previewRequestBuilder.build(), cameraCaptureCallback, cameraHandler);
            } catch (CameraAccessException e) {
                showFatal("Could not start shared-camera request: " + e.getMessage());
                return;
            }
            try {
                arSession.resume();
                arRunning = true;
                sharedCamera.setCaptureCallback(cameraCaptureCallback, cameraHandler);
                runOnUiThread(() -> {
                    glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
                    glSurfaceView.requestRender();
                });
            } catch (CameraNotAvailableException e) {
                showFatal("ARCore camera unavailable: " + e.getMessage());
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            showFatal("Could not configure ARCore SharedCamera session");
        }
    };

    private final CameraCaptureSession.CaptureCallback cameraCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(
                @NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull TotalCaptureResult result) {
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (timestamp == null) {
                return;
            }
            synchronized (camera2ResultsByTimestamp) {
                camera2ResultsByTimestamp.put(timestamp, result);
                while (camera2ResultsByTimestamp.size() > 90) {
                    camera2ResultsByTimestamp.pollFirstEntry();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showFatal("Camera permission is required for ARCore SharedCamera capture");
            return;
        }
        try {
            library = new SpherifyLibrary(this);
        } catch (IOException e) {
            showFatal("Could not open local library: " + e.getMessage());
            return;
        }
        sessionId = new SimpleDateFormat("yyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        targets.addAll(CaptureTargetPlanner.initialTargets());
        buildUi();
        ensureSession(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        glSurfaceView.onResume();
        if (surfaceCreated) {
            openSharedCamera();
        }
    }

    @Override
    protected void onPause() {
        closeSharedCamera();
        glSurfaceView.onPause();
        stopCameraThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        captureExecutor.shutdownNow();
        if (arSession != null) {
            arSession.close();
            arSession = null;
        }
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF05070A);

        statusText = new TextView(this);
        statusText.setTextColor(0xFFE2E8F0);
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(16, 12, 16, 10);
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout previewFrame = new FrameLayout(this);
        glSurfaceView = new GLSurfaceView(this);
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setPreserveEGLContextOnPause(true);
        glSurfaceView.setRenderer(this);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        previewFrame.addView(glSurfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlayView = new TargetOverlayView(this);
        previewFrame.addView(overlayView, new FrameLayout.LayoutParams(
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
        captureButton.setOnClickListener(v -> requestCapture());
        Button finishButton = makeButton("Finish Sphere");
        finishButton.setOnClickListener(v -> finishCapture());
        controls.addView(captureButton);
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
        button.setContentDescription(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(6, 0, 6, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("spherify-shared-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openSharedCamera() {
        if (cameraDevice != null || cameraHandler == null) {
            return;
        }
        try {
            ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
            if (!availability.isSupported()) {
                showFatal("ARCore is not supported or not installed on this device");
                return;
            }
            if (arSession == null) {
                arSession = new Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA));
                CameraConfig selectedConfig = selectBestCameraConfig(arSession);
                arSession.setCameraConfig(selectedConfig);
                selectedCpuImageSize = selectedConfig.getImageSize();
                selectedGpuTextureSize = selectedConfig.getTextureSize();
                Config config = arSession.getConfig();
                config.setFocusMode(Config.FocusMode.AUTO);
                arSession.configure(config);
                updateDisplayGeometry();
            }
            sharedCamera = arSession.getSharedCamera();
            cameraId = arSession.getCameraConfig().getCameraId();
            android.util.Size cpuSize = arSession.getCameraConfig().getImageSize();
            if (cpuSize.getWidth() < MIN_CPU_IMAGE_WIDTH) {
                showFatal("ARCore CPU image stream is too small for production PhotoSphere capture: "
                        + cpuSize.getWidth() + "x" + cpuSize.getHeight()
                        + "\n\nAvailable ARCore camera configs:\n" + cameraConfigSummary);
                return;
            }
            cpuImageReader = ImageReader.newInstance(
                    cpuSize.getWidth(),
                    cpuSize.getHeight(),
                    ImageFormat.YUV_420_888,
                    4);
            cpuImageReader.setOnImageAvailableListener(this, cameraHandler);
            sharedCamera.setAppSurfaces(cameraId, Arrays.asList(cpuImageReader.getSurface()));

            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                showFatal("Camera manager unavailable");
                return;
            }
            cameraFacts = CameraFacts.from(manager.getCameraCharacteristics(cameraId));
            CameraDevice.StateCallback wrapped = sharedCamera.createARDeviceStateCallback(cameraDeviceCallback, cameraHandler);
            manager.openCamera(cameraId, wrapped, cameraHandler);
        } catch (Exception e) {
            showFatal("Could not start ARCore SharedCamera: " + e.getMessage());
        }
    }

    private CameraConfig selectBestCameraConfig(Session session) throws IOException {
        CameraConfigFilter preferred = new CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30));
        List<CameraConfig> configs = session.getSupportedCameraConfigs(preferred);
        if (configs.isEmpty()) {
            CameraConfigFilter fallback = new CameraConfigFilter(session)
                    .setFacingDirection(CameraConfig.FacingDirection.BACK);
            configs = session.getSupportedCameraConfigs(fallback);
        }
        if (configs.isEmpty()) {
            throw new IOException("No rear-facing ARCore SharedCamera configs are available");
        }
        cameraConfigSummary = describeCameraConfigs(configs);
        CameraConfig best = configs.get(0);
        long bestScore = cameraConfigScore(best);
        for (int i = 1; i < configs.size(); i++) {
            CameraConfig candidate = configs.get(i);
            long score = cameraConfigScore(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static long cameraConfigScore(CameraConfig config) {
        android.util.Size cpu = config.getImageSize();
        android.util.Size gpu = config.getTextureSize();
        long cpuArea = (long) cpu.getWidth() * (long) cpu.getHeight();
        long gpuArea = (long) gpu.getWidth() * (long) gpu.getHeight();
        int fpsPenalty = config.getFpsRange().getUpper() > 30 ? 1 : 0;
        return cpuArea * 1_000_000L + gpuArea - fpsPenalty;
    }

    private static String describeCameraConfigs(List<CameraConfig> configs) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < configs.size(); i++) {
            CameraConfig config = configs.get(i);
            android.util.Size cpu = config.getImageSize();
            android.util.Size gpu = config.getTextureSize();
            if (i > 0) {
                result.append('\n');
            }
            result.append(i + 1)
                    .append(". cpu=")
                    .append(cpu.getWidth())
                    .append('x')
                    .append(cpu.getHeight())
                    .append(", gpu=")
                    .append(gpu.getWidth())
                    .append('x')
                    .append(gpu.getHeight())
                    .append(", fps=")
                    .append(config.getFpsRange().getLower())
                    .append('-')
                    .append(config.getFpsRange().getUpper());
        }
        return result.toString();
    }

    private void createCameraSession() {
        try {
            arSession.setCameraTextureName(cameraTextureId);
            sharedCamera.getSurfaceTexture().setOnFrameAvailableListener(this);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>(sharedCamera.getArCoreSurfaces());
            surfaces.add(cpuImageReader.getSurface());
            for (Surface surface : surfaces) {
                previewRequestBuilder.addTarget(surface);
            }
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            CameraCaptureSession.StateCallback wrapped =
                    sharedCamera.createARSessionStateCallback(cameraSessionCallback, cameraHandler);
            cameraDevice.createCaptureSession(surfaces, wrapped, cameraHandler);
        } catch (CameraAccessException e) {
            showFatal("Could not create Camera2 shared session: " + e.getMessage());
        }
    }

    private void closeSharedCamera() {
        capturePending = false;
        captureInProgress = false;
        if (arRunning && arSession != null) {
            arSession.pause();
            arRunning = false;
        }
        if (glSurfaceView != null) {
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        }
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (cpuImageReader != null) {
            cpuImageReader.close();
            cpuImageReader = null;
        }
    }

    private void requestCapture() {
        CaptureTarget target = activeTarget();
        ArFrameState state = latestFrameState;
        if (target == null || captureInProgress || completionInProgress) {
            return;
        }
        String blocker = captureBlocker(target, state);
        if (!blocker.isEmpty()) {
            Toast.makeText(this, blocker, Toast.LENGTH_LONG).show();
            return;
        }
        if (!captureAnchored) {
            target.yawDegrees = Math.round(state.yawDegrees);
            target.pitchDegrees = Math.round(state.pitchDegrees);
        }
        capturePending = true;
        captureInProgress = true;
        lastCaptureAtMs = System.currentTimeMillis();
        refreshUi();
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            if (!capturePending) {
                return;
            }
            capturePending = false;
            CaptureTarget target = activeTarget();
            ArFrameState state = latestFrameState;
            if (target == null) {
                captureInProgress = false;
                return;
            }
            TotalCaptureResult metadata = camera2MetadataFor(image.getTimestamp());
            if (metadata == null) {
                runOnUiThread(() -> rejectInUi("Camera2 metadata did not match image timestamp"));
                return;
            }
            File outputFile = library.createDraftFrameFile();
            writeJpegFromYuv(image, outputFile);
            captureExecutor.submit(() -> validateAndRecord(outputFile, target, state, metadata));
        } catch (IOException e) {
            runOnUiThread(() -> rejectInUi(e.getMessage()));
        } finally {
            image.close();
        }
    }

    private void validateAndRecord(File imageFile, CaptureTarget target, ArFrameState state, TotalCaptureResult metadata) {
        try {
            JSONObject exposure = exposureJsonFor(metadata, state);
            CandidateQualityReport quality = qualityScorer.score(imageFile, 0.0, 0.0, 0.0);
            List<CaptureFrameRecord> neighbors = library.predictedAcceptedNeighbors(
                    sessionId,
                    target.yawDegrees,
                    target.pitchDegrees);
            CandidateAnalysisResult analysis = overlapValidator.analyze(
                    imageFile,
                    quality,
                    neighbors,
                    target.pitchDegrees,
                    !captureAnchored);
            if (!state.parallaxWarning.isEmpty()) {
                analysis = new CandidateAnalysisResult(
                        false,
                        quality,
                        new JSONArray(),
                        "arcore_parallax_gate",
                        0,
                        -1.0,
                        0.0,
                        state.parallaxWarning,
                        "Too close",
                        "",
                        new JSONArray());
            }
            library.recordAnalyzedCandidateFrame(
                    imageFile,
                    sessionId,
                    "",
                    state.yawDegrees,
                    state.pitchDegrees,
                    state.rollDegrees,
                    target.yawDegrees,
                    target.pitchDegrees,
                    "arcore-shared-camera",
                    CAPTURE_PROFILE,
                    exposure.toString(),
                    analysis);
            CandidateAnalysisResult finalAnalysis = analysis;
            runOnUiThread(() -> handleAnalysis(target, finalAnalysis));
        } catch (IOException | JSONException e) {
            runOnUiThread(() -> rejectInUi(e.getMessage()));
        }
    }

    private void handleAnalysis(CaptureTarget target, CandidateAnalysisResult analysis) {
        captureInProgress = false;
        if (analysis.accepted) {
            int acceptedYaw = target.yawDegrees;
            int acceptedPitch = target.pitchDegrees;
            if (!captureAnchored) {
                captureAnchored = true;
                anchorYawDegrees = acceptedYaw;
                anchorPitchDegrees = acceptedPitch;
                anchorTranslationMeters = new float[]{latestFrameState.poseTx, latestFrameState.poseTy, latestFrameState.poseTz};
                rebuildAnchoredTargets(acceptedYaw, acceptedPitch);
            }
            markTargetAccepted(acceptedYaw, acceptedPitch);
            target.weak = false;
            updateActiveTarget();
            ensureSession(true);
            overlayView.showCaptureResult(true);
            captureButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Toast.makeText(this, analysis.inlierCount > 0 ? "Accepted - overlap valid" : "Accepted - AR anchor", Toast.LENGTH_SHORT).show();
            if (activeTarget() == null) {
                startIntegratedSpherification();
                return;
            }
        } else {
            target.weak = true;
            overlayView.showCaptureResult(false);
            captureButton.performHapticFeedback(HapticFeedbackConstants.REJECT);
            Toast.makeText(this, analysis.rejectionReason.isEmpty() ? "Recapture this area" : analysis.rejectionReason, Toast.LENGTH_LONG).show();
        }
        requestAutomaticDebugCsvDump();
        refreshUi();
    }

    private void rejectInUi(String reason) {
        captureInProgress = false;
        refreshUi();
        Toast.makeText(this, reason == null || reason.isEmpty() ? "Frame rejected" : reason, Toast.LENGTH_LONG).show();
        requestAutomaticDebugCsvDump();
    }

    private JSONObject exposureJsonFor(TotalCaptureResult metadata, ArFrameState state) throws JSONException, IOException {
        Long exposureTime = metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer sensitivity = metadata.get(CaptureResult.SENSOR_SENSITIVITY);
        Long timestamp = metadata.get(CaptureResult.SENSOR_TIMESTAMP);
        Float focal = metadata.get(CaptureResult.LENS_FOCAL_LENGTH);
        if (exposureTime == null || sensitivity == null || timestamp == null || focal == null || focal <= 0f) {
            throw new IOException("Camera2 metadata incomplete");
        }
        JSONObject json = new JSONObject();
        json.put("available", true);
        json.put("source", "arcore-shared-camera-camera2-total-capture-result");
        json.put("sensorExposureTimeNs", exposureTime);
        json.put("sensorSensitivityIso", sensitivity);
        putIfPresent(json, "sensorFrameDurationNs", metadata.get(CaptureResult.SENSOR_FRAME_DURATION));
        json.put("sensorTimestampNs", timestamp);
        putIfPresent(json, "lensAperture", metadata.get(CaptureResult.LENS_APERTURE));
        json.put("lensFocalLengthMm", focal);
        json.put("sensorPhysicalWidthMm", cameraFacts.sensorWidthMm);
        json.put("sensorPhysicalHeightMm", cameraFacts.sensorHeightMm);
        json.put("arCoreSelectedCpuImageWidth", selectedCpuImageSize.getWidth());
        json.put("arCoreSelectedCpuImageHeight", selectedCpuImageSize.getHeight());
        json.put("arCoreSelectedGpuTextureWidth", selectedGpuTextureSize.getWidth());
        json.put("arCoreSelectedGpuTextureHeight", selectedGpuTextureSize.getHeight());
        json.put("imageFocalLengthXPixels", state.imageFx);
        json.put("imageFocalLengthYPixels", state.imageFy);
        json.put("imagePrincipalPointXPixels", state.imageCx);
        json.put("imagePrincipalPointYPixels", state.imageCy);
        json.put("imageIntrinsicsWidth", state.imageWidth);
        json.put("imageIntrinsicsHeight", state.imageHeight);
        json.put("arCoreTrackingState", state.trackingState);
        json.put("arCoreFeaturePointCount", state.featurePointCount);
        json.put("arCoreFeatureConfidence", state.featureConfidence);
        json.put("arCoreAnchorTranslationMeters", state.translationFromAnchorMeters);
        json.put("arCoreParallaxWarning", state.parallaxWarning);
        json.put("arCorePoseTx", state.poseTx);
        json.put("arCorePoseTy", state.poseTy);
        json.put("arCorePoseTz", state.poseTz);
        json.put("arCorePoseQx", state.poseQx);
        json.put("arCorePoseQy", state.poseQy);
        json.put("arCorePoseQz", state.poseQz);
        json.put("arCorePoseQw", state.poseQw);
        json.put("arCoreProjectionMatrix", matrixJson(state.projectionMatrix));
        json.put("arCoreViewMatrix", matrixJson(state.viewMatrix));
        putIfPresent(json, "aeState", metadata.get(CaptureResult.CONTROL_AE_STATE));
        putIfPresent(json, "awbState", metadata.get(CaptureResult.CONTROL_AWB_STATE));
        putIfPresent(json, "afState", metadata.get(CaptureResult.CONTROL_AF_STATE));
        putIfPresent(json, "aeExposureCompensation", metadata.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION));
        putIfPresent(json, "aeMode", metadata.get(CaptureResult.CONTROL_AE_MODE));
        putIfPresent(json, "awbMode", metadata.get(CaptureResult.CONTROL_AWB_MODE));
        putIfPresent(json, "afMode", metadata.get(CaptureResult.CONTROL_AF_MODE));
        return json;
    }

    private static void putIfPresent(JSONObject json, String key, Object value) throws JSONException {
        if (value != null) {
            json.put(key, value);
        }
    }

    private static JSONArray matrixJson(float[] matrix) throws JSONException {
        JSONArray array = new JSONArray();
        for (float value : matrix) {
            array.put(value);
        }
        return array;
    }

    private TotalCaptureResult camera2MetadataFor(long imageTimestampNs) {
        synchronized (camera2ResultsByTimestamp) {
            Long floor = camera2ResultsByTimestamp.floorKey(imageTimestampNs);
            Long ceiling = camera2ResultsByTimestamp.ceilingKey(imageTimestampNs);
            Long best = floor == null ? ceiling : ceiling == null ? floor
                    : Math.abs(imageTimestampNs - floor) <= Math.abs(ceiling - imageTimestampNs) ? floor : ceiling;
            if (best == null || Math.abs(imageTimestampNs - best) > 4_000_000L) {
                return null;
            }
            return camera2ResultsByTimestamp.get(best);
        }
    }

    private void ensureSession(boolean capturing) {
        try {
            JSONObject readiness = new JSONObject();
            readiness.put("cameraPermission", true);
            readiness.put("arCoreTracking", "tracking".equals(latestFrameState.trackingState));
            readiness.put("arCoreSharedCameraBackend", true);
            readiness.put("arCoreDepthOrFeatureConfidence", latestFrameState.featurePointCount >= MIN_TRACKING_FEATURE_POINTS);
            readiness.put("arCoreFeatureLowConfidence", latestFrameState.featurePointCount < LOW_CONFIDENCE_FEATURE_POINTS);
            readiness.put("arCorePoseFeedsCaptureGraph", latestFrameState.ready);
            readiness.put("parallaxWarningBeforeCapture", latestFrameState.parallaxWarning.isEmpty());
            readiness.put("cameraIntrinsicsAvailable", latestFrameState.imageFx > 0f && latestFrameState.imageFy > 0f);
            readiness.put("arCoreCpuImageWidth", selectedCpuImageSize.getWidth());
            readiness.put("arCoreCpuImageHeight", selectedCpuImageSize.getHeight());
            readiness.put("arCoreGpuTextureWidth", selectedGpuTextureSize.getWidth());
            readiness.put("arCoreGpuTextureHeight", selectedGpuTextureSize.getHeight());
            readiness.put("storageAvailable", true);
            readiness.put("phase4Method", "arcore_shared_camera_guided_still_capture");
            library.ensureCaptureSession(sessionId, CaptureMode.HAND_HELD, readiness);
            library.updateCaptureSessionReadiness(sessionId, readiness, capturing);
        } catch (IOException | JSONException e) {
            Toast.makeText(this, "Session record failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void rebuildAnchoredTargets(int acceptedYaw, int acceptedPitch) {
        ArrayList<CaptureTarget> anchored = CaptureTargetPlanner.anchoredTargets(
                anchorYawDegrees,
                anchorPitchDegrees,
                latestFrameState.horizontalFovDegrees(),
                latestFrameState.verticalFovDegrees());
        targets.clear();
        targets.addAll(anchored);
        markTargetAccepted(acceptedYaw, acceptedPitch);
    }

    private void markTargetAccepted(int yawDegrees, int pitchDegrees) {
        int yaw = normalizeDegrees(yawDegrees);
        for (CaptureTarget candidate : targets) {
            if (candidate.yawDegrees == yaw && candidate.pitchDegrees == pitchDegrees) {
                candidate.captured = true;
                candidate.weak = false;
                return;
            }
        }
    }

    private static int normalizeDegrees(int degrees) {
        int normalized = degrees % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private void updateActiveTarget() {
        selectableTargetIndices.clear();
        if (!captureAnchored && latestFrameState.ready && !targets.isEmpty()) {
            CaptureTarget first = targets.get(0);
            first.yawDegrees = Math.round(latestFrameState.yawDegrees);
            first.pitchDegrees = Math.round(latestFrameState.pitchDegrees);
            selectableTargetIndices.add(0);
            activeTargetIndex = 0;
            return;
        }
        ArrayList<Integer> frontier = connectedFrontierTargets();
        if (frontier.isEmpty()) {
            activeTargetIndex = -1;
            return;
        }
        Collections.sort(frontier, (left, right) -> Float.compare(
                viewDistance(targets.get(left), latestFrameState),
                viewDistance(targets.get(right), latestFrameState)));
        int count = Math.min(MAX_VISIBLE_FRONTIER_TARGETS, frontier.size());
        selectableTargetIndices.addAll(frontier.subList(0, count));
        if (selectableTargetIndices.contains(activeTargetIndex)
                && viewDistance(targets.get(activeTargetIndex), latestFrameState) <= ACTIVE_TARGET_HYSTERESIS_DEGREES) {
            return;
        }
        activeTargetIndex = selectableTargetIndices.get(0);
    }

    private ArrayList<Integer> connectedFrontierTargets() {
        ArrayList<Integer> frontier = new ArrayList<>();
        if (targets.isEmpty()) {
            return frontier;
        }
        boolean hasCaptured = false;
        for (CaptureTarget target : targets) {
            hasCaptured |= target.captured;
        }
        for (int i = 0; i < targets.size(); i++) {
            CaptureTarget target = targets.get(i);
            if (target.captured) {
                continue;
            }
            if (!hasCaptured || distanceToAcceptedTarget(target) <= FRONTIER_TARGET_MAX_DEGREES) {
                frontier.add(i);
            }
        }
        if (frontier.isEmpty()) {
            for (int i = 0; i < targets.size(); i++) {
                if (!targets.get(i).captured) {
                    frontier.add(i);
                    break;
                }
            }
        }
        return frontier;
    }

    private float distanceToAcceptedTarget(CaptureTarget target) {
        float best = Float.MAX_VALUE;
        for (CaptureTarget accepted : targets) {
            if (accepted.captured) {
                best = Math.min(best, targetDistance(target, accepted));
            }
        }
        return best;
    }

    private static float viewDistance(CaptureTarget target, ArFrameState state) {
        if (!state.ready) {
            return Float.MAX_VALUE;
        }
        float yaw = signedHeadingDelta(target.yawDegrees, state.yawDegrees);
        float pitch = target.pitchDegrees - state.pitchDegrees;
        return (float) Math.sqrt(yaw * yaw + pitch * pitch);
    }

    private static float targetDistance(CaptureTarget left, CaptureTarget right) {
        float yaw = signedHeadingDelta(left.yawDegrees, right.yawDegrees);
        float pitch = left.pitchDegrees - right.pitchDegrees;
        return (float) Math.sqrt(yaw * yaw + pitch * pitch);
    }

    private CaptureTarget activeTarget() {
        return activeTargetIndex >= 0 && activeTargetIndex < targets.size() ? targets.get(activeTargetIndex) : null;
    }

    private String captureBlocker(CaptureTarget target, ArFrameState state) {
        if (!state.ready) {
            return state.blocker;
        }
        if (!isAligned(target, state)) {
            return "Move to target";
        }
        if (!state.parallaxWarning.isEmpty()) {
            return state.parallaxWarning;
        }
        if (System.currentTimeMillis() - lastCaptureAtMs < MIN_CAPTURE_INTERVAL_MS) {
            return "Hold steady";
        }
        return "";
    }

    private boolean isAligned(CaptureTarget target, ArFrameState state) {
        return Math.abs(signedHeadingDelta(target.yawDegrees, state.yawDegrees)) <= TARGET_YAW_TOLERANCE_DEGREES
                && Math.abs(target.pitchDegrees - state.pitchDegrees) <= TARGET_PITCH_TOLERANCE_DEGREES;
    }

    private void refreshUi() {
        updateActiveTarget();
        CaptureTarget target = activeTarget();
        ArFrameState state = latestFrameState;
        boolean canCapture = target != null
                && captureBlocker(target, state).isEmpty()
                && !captureInProgress
                && !completionInProgress;
        if (canCapture) {
            if (alignedSinceMs == 0L) {
                alignedSinceMs = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - alignedSinceMs >= REQUIRED_ALIGNED_MS) {
                requestCapture();
            }
        } else {
            alignedSinceMs = 0L;
        }
        captureButton.setEnabled(canCapture);
        overlayView.setState(targets, activeTargetIndex, selectableTargetIndices, state);
        String text = completionInProgress
                ? "Solving PhotoSphere"
                : captureInProgress
                ? "Validating AR frame"
                : target == null
                ? "Ready to solve PhotoSphere"
                : !state.ready
                ? state.blocker
                : !state.parallaxWarning.isEmpty()
                ? state.parallaxWarning
                : !isAligned(target, state)
                ? selectableTargetIndices.size() > 1 ? "Choose nearby target" : "Move to target"
                : "Hold steady - capture ready";
        statusText.setText(String.format(Locale.US, "%s  |  %d/%d", text, acceptedTargetCount(), targets.size()));
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

    private void finishCapture() {
        ensureSession(false);
        startIntegratedSpherification();
    }

    private void startIntegratedSpherification() {
        if (completionInProgress) {
            return;
        }
        completionInProgress = true;
        capturePending = false;
        captureInProgress = false;
        captureButton.setEnabled(false);
        refreshUi();
        showCompletionDialog("Preparing capture graph for native stitching");
        captureExecutor.submit(() -> {
            try {
                StitchMasterResult result = library.createMasterFromCaptureSession(
                        sessionId,
                        "normal",
                        "blended",
                        (stepKey, complete, message) -> runOnUiThread(() ->
                                updateCompletionDialog((complete ? "Done: " : "Working: ") + message)));
                exportAutomaticDebugCsvDump();
                runOnUiThread(() -> finishIntegratedSpherification(result));
            } catch (IOException e) {
                exportAutomaticDebugCsvDump();
                runOnUiThread(() -> failIntegratedSpherification(e.getMessage()));
            }
        });
    }

    private void requestAutomaticDebugCsvDump() {
        if (!isDebuggableBuild() || completionInProgress) {
            return;
        }
        captureExecutor.submit(this::exportAutomaticDebugCsvDump);
    }

    private void exportAutomaticDebugCsvDump() {
        if (!isDebuggableBuild()) {
            return;
        }
        try {
            Class<?> writer = Class.forName("com.spherify.app.CaptureDebugCsvWriter");
            java.lang.reflect.Method export = writer.getDeclaredMethod(
                    "export",
                    Context.class,
                    String.class,
                    boolean.class);
            export.setAccessible(true);
            File output = (File) export.invoke(null, this, sessionId, false);
            Log.i(TAG, "Automatic capture debug CSV: " + output.getAbsolutePath());
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Automatic capture debug CSV writer is unavailable", e);
        } catch (Throwable e) {
            Log.w(TAG, "Automatic capture debug CSV export failed", e);
        }
    }

    private boolean isDebuggableBuild() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void showCompletionDialog(String message) {
        completionText = new TextView(this);
        completionText.setText(message);
        completionText.setTextSize(14f);
        completionText.setPadding(32, 24, 32, 24);
        completionDialog = new AlertDialog.Builder(this)
                .setTitle("Creating PhotoSphere")
                .setView(completionText)
                .setCancelable(false)
                .show();
    }

    private void updateCompletionDialog(String message) {
        if (completionText != null) {
            completionText.setText(message);
        }
    }

    private void finishIntegratedSpherification(StitchMasterResult result) {
        if (completionDialog != null) {
            completionDialog.dismiss();
            completionDialog = null;
        }
        getSharedPreferences("spherify", MODE_PRIVATE)
                .edit()
                .putString("lastIntegratedMasterId", result.item.id)
                .apply();
        Toast.makeText(this, "PhotoSphere created: " + result.item.title, Toast.LENGTH_LONG).show();
        finish();
    }

    private void failIntegratedSpherification(String message) {
        if (completionDialog != null) {
            completionDialog.dismiss();
            completionDialog = null;
        }
        completionInProgress = false;
        refreshUi();
        new AlertDialog.Builder(this)
                .setTitle("Capture needs more evidence")
                .setMessage(message == null || message.isEmpty()
                        ? "The capture graph is not strong enough to create a seamless PhotoSphere."
                        : message)
                .setNegativeButton("Close Capture", (dialog, which) -> finish())
                .setPositiveButton("Continue Capture", null)
                .show();
    }

    private void showFatal(String message) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Capture unavailable")
                .setMessage(message)
                .setPositiveButton("Close", (dialog, which) -> finish())
                .show());
    }

    @Override
    public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        surfaceCreated = true;
        cameraTextureId = backgroundRenderer.createOnGlThread();
        openSharedCamera();
    }

    @Override
    public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        GLES20.glViewport(0, 0, width, height);
        updateDisplayGeometry();
    }

    @Override
    public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (!arRunning || arSession == null) {
            return;
        }
        try {
            Frame frame = arSession.update();
            backgroundRenderer.updateDisplayGeometry(frame);
            if (frameAvailable.getAndSet(false)) {
                SurfaceTexture texture = sharedCamera.getSurfaceTexture();
                texture.updateTexImage();
            }
            backgroundRenderer.draw();
            latestFrameState = ArFrameState.from(frame, captureAnchored, anchorPoseTranslation());
            frame.getCamera().getProjectionMatrix(latestProjectionMatrix, 0, 0.1f, 100f);
            frame.getCamera().getViewMatrix(latestViewMatrix, 0);
            System.arraycopy(latestProjectionMatrix, 0, latestFrameState.projectionMatrix, 0, 16);
            System.arraycopy(latestViewMatrix, 0, latestFrameState.viewMatrix, 0, 16);
            runOnUiThread(this::refreshUi);
        } catch (Throwable e) {
            Log.w(TAG, "ARCore draw/update failed", e);
            latestFrameState = ArFrameState.notReady("ARCore frame unavailable");
            runOnUiThread(this::refreshUi);
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        frameAvailable.set(true);
        glSurfaceView.requestRender();
    }

    private float[] anchorPoseTranslation() {
        return anchorTranslationMeters;
    }

    private void updateDisplayGeometry() {
        if (arSession == null || viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        arSession.setDisplayGeometry(rotation, viewportWidth, viewportHeight);
    }

    private static void writeJpegFromYuv(Image image, File outputFile) throws IOException {
        byte[] nv21 = yuv420ToNv21(image);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        try (FileOutputStream output = new FileOutputStream(outputFile);
             ByteArrayOutputStream jpeg = new ByteArrayOutputStream()) {
            if (!yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 94, jpeg)) {
                throw new IOException("could not encode ARCore CPU image");
            }
            jpeg.writeTo(output);
        }
    }

    private static byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] output = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        copyPlane(planes[0], width, height, output, 0, 1);
        int chromaOffset = width * height;
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int rowStride = planes[1].getRowStride();
        int pixelStride = planes[1].getPixelStride();
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int source = row * rowStride + col * pixelStride;
                output[chromaOffset++] = v.get(source);
                output[chromaOffset++] = u.get(source);
            }
        }
        return output;
    }

    private static void copyPlane(Image.Plane plane, int width, int height, byte[] output, int offset, int pixelStrideOut) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int outputOffset = offset;
        for (int row = 0; row < height; row++) {
            int rowOffset = row * rowStride;
            for (int col = 0; col < width; col++) {
                output[outputOffset] = buffer.get(rowOffset + col * pixelStride);
                outputOffset += pixelStrideOut;
            }
        }
    }

    private static float signedHeadingDelta(float target, float current) {
        float delta = (target - current + 540f) % 360f - 180f;
        return delta < -180f ? delta + 360f : delta;
    }

    private static final class CameraFacts {
        final boolean available;
        final float sensorWidthMm;
        final float sensorHeightMm;

        CameraFacts(boolean available, float sensorWidthMm, float sensorHeightMm) {
            this.available = available;
            this.sensorWidthMm = sensorWidthMm;
            this.sensorHeightMm = sensorHeightMm;
        }

        static CameraFacts unavailable() {
            return new CameraFacts(false, 0f, 0f);
        }

        static CameraFacts from(CameraCharacteristics characteristics) {
            android.util.SizeF size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            return size == null ? unavailable() : new CameraFacts(true, size.getWidth(), size.getHeight());
        }
    }

    private static final class ArFrameState {
        final boolean ready;
        final String blocker;
        final String trackingState;
        final float yawDegrees;
        final float pitchDegrees;
        final float rollDegrees;
        final int featurePointCount;
        final float featureConfidence;
        final float translationFromAnchorMeters;
        final String parallaxWarning;
        final float imageFx;
        final float imageFy;
        final float imageCx;
        final float imageCy;
        final int imageWidth;
        final int imageHeight;
        final float poseTx;
        final float poseTy;
        final float poseTz;
        final float poseQx;
        final float poseQy;
        final float poseQz;
        final float poseQw;
        final float anchorTx;
        final float anchorTy;
        final float anchorTz;
        final float[] projectionMatrix = new float[16];
        final float[] viewMatrix = new float[16];

        ArFrameState(
                boolean ready,
                String blocker,
                String trackingState,
                float yawDegrees,
                float pitchDegrees,
                float rollDegrees,
                int featurePointCount,
                float featureConfidence,
                float translationFromAnchorMeters,
                String parallaxWarning,
                float imageFx,
                float imageFy,
                float imageCx,
                float imageCy,
                int imageWidth,
                int imageHeight,
                Pose pose) {
            this.ready = ready;
            this.blocker = blocker;
            this.trackingState = trackingState;
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
            this.featurePointCount = featurePointCount;
            this.featureConfidence = featureConfidence;
            this.translationFromAnchorMeters = translationFromAnchorMeters;
            this.parallaxWarning = parallaxWarning;
            this.imageFx = imageFx;
            this.imageFy = imageFy;
            this.imageCx = imageCx;
            this.imageCy = imageCy;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.poseTx = pose == null ? 0f : pose.tx();
            this.poseTy = pose == null ? 0f : pose.ty();
            this.poseTz = pose == null ? 0f : pose.tz();
            this.poseQx = pose == null ? 0f : pose.qx();
            this.poseQy = pose == null ? 0f : pose.qy();
            this.poseQz = pose == null ? 0f : pose.qz();
            this.poseQw = pose == null ? 1f : pose.qw();
            this.anchorTx = this.poseTx;
            this.anchorTy = this.poseTy;
            this.anchorTz = this.poseTz;
        }

        static ArFrameState notReady(String blocker) {
            return new ArFrameState(false, blocker, "not_tracking", 0f, 0f, 0f, 0, 0f, 0f, "", 0f, 0f, 0f, 0f, 0, 0, null);
        }

        static ArFrameState from(Frame frame, boolean anchored, float[] anchorTranslation) {
            com.google.ar.core.Camera camera = frame.getCamera();
            TrackingState tracking = camera.getTrackingState();
            Pose pose = camera.getPose();
            float[] matrix = new float[16];
            pose.toMatrix(matrix, 0);
            float forwardX = -matrix[8];
            float forwardY = -matrix[9];
            float forwardZ = -matrix[10];
            float yaw = normalize((float) Math.toDegrees(Math.atan2(forwardX, forwardZ)));
            float pitch = clamp((float) Math.toDegrees(Math.asin(clamp(forwardY, -1f, 1f))), -89f, 89f);
            float roll = 0f;
            int featurePoints = 0;
            try (PointCloud pointCloud = frame.acquirePointCloud()) {
                featurePoints = pointCloud.getPoints().remaining() / 4;
            } catch (Throwable ignored) {
                featurePoints = 0;
            }
            float confidence = Math.min(1f, featurePoints / 120f);
            float translation = 0f;
            if (anchored && anchorTranslation != null) {
                float dx = pose.tx() - anchorTranslation[0];
                float dy = pose.ty() - anchorTranslation[1];
                float dz = pose.tz() - anchorTranslation[2];
                translation = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            String parallax = translation > MAX_TRANSLATION_FROM_ANCHOR_METERS
                    ? "Too close - rotate around one point"
                    : "";
            com.google.ar.core.CameraIntrinsics intrinsics = camera.getImageIntrinsics();
            float[] focal = intrinsics.getFocalLength();
            float[] principal = intrinsics.getPrincipalPoint();
            int[] dimensions = intrinsics.getImageDimensions();
            boolean intrinsicsReady = focal[0] > 0f && focal[1] > 0f && dimensions[0] > 0 && dimensions[1] > 0;
            boolean ready = tracking == TrackingState.TRACKING
                    && intrinsicsReady
                    && featurePoints >= MIN_TRACKING_FEATURE_POINTS;
            String blocker = tracking != TrackingState.TRACKING
                    ? "Waiting for AR tracking"
                    : !intrinsicsReady
                    ? "Waiting for camera calibration"
                    : featurePoints < MIN_TRACKING_FEATURE_POINTS
                    ? "Point at textured detail"
                    : "";
            return new ArFrameState(
                    ready,
                    blocker,
                    tracking.name().toLowerCase(Locale.US),
                    yaw,
                    pitch,
                    roll,
                    featurePoints,
                    confidence,
                    translation,
                    parallax,
                    focal[0],
                    focal[1],
                    principal[0],
                    principal[1],
                    dimensions[0],
                    dimensions[1],
                    pose);
        }

        float horizontalFovDegrees() {
            return imageFx <= 0f || imageWidth <= 0
                    ? 75f
                    : (float) Math.toDegrees(2.0 * Math.atan(imageWidth / (2.0 * imageFx)));
        }

        float verticalFovDegrees() {
            return imageFy <= 0f || imageHeight <= 0
                    ? 60f
                    : (float) Math.toDegrees(2.0 * Math.atan(imageHeight / (2.0 * imageFy)));
        }

        float[] project(CaptureTarget target, int width, int height) {
            float[] direction = directionFromYawPitch(target.yawDegrees, target.pitchDegrees);
            float[] world = {poseTx + direction[0] * 2f, poseTy + direction[1] * 2f, poseTz + direction[2] * 2f, 1f};
            float[] eye = new float[4];
            float[] clip = new float[4];
            Matrix.multiplyMV(eye, 0, viewMatrix, 0, world, 0);
            if (eye[2] > -0.05f) {
                return new float[]{Float.NaN, Float.NaN};
            }
            Matrix.multiplyMV(clip, 0, projectionMatrix, 0, eye, 0);
            if (clip[3] == 0f) {
                return new float[]{Float.NaN, Float.NaN};
            }
            float ndcX = clip[0] / clip[3];
            float ndcY = clip[1] / clip[3];
            return new float[]{
                    width * (ndcX * 0.5f + 0.5f),
                    height * (0.5f - ndcY * 0.5f)
            };
        }

        private static float[] directionFromYawPitch(float yawDegrees, float pitchDegrees) {
            double yaw = Math.toRadians(yawDegrees);
            double pitch = Math.toRadians(pitchDegrees);
            return new float[]{
                    (float) (Math.cos(pitch) * Math.sin(yaw)),
                    (float) Math.sin(pitch),
                    (float) (Math.cos(pitch) * Math.cos(yaw))
            };
        }

        private static float normalize(float degrees) {
            float result = degrees % 360f;
            return result < 0f ? result + 360f : result;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class TargetOverlayView extends View {
        private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint coveragePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint feedbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path cuePath = new Path();
        private List<CaptureTarget> targets = new ArrayList<>();
        private List<Integer> selectableIndices = new ArrayList<>();
        private int activeTargetIndex = -1;
        private ArFrameState frameState = ArFrameState.notReady("tracking not started");
        private long feedbackUntilMs;
        private boolean feedbackAccepted;

        TargetOverlayView(Context context) {
            super(context);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(4f);
            reticlePaint.setStyle(Paint.Style.STROKE);
            reticlePaint.setStrokeWidth(4f);
            coveragePaint.setStyle(Paint.Style.FILL);
        }

        void setState(
                List<CaptureTarget> targets,
                int activeTargetIndex,
                List<Integer> selectableIndices,
                ArFrameState frameState) {
            this.targets = targets;
            this.activeTargetIndex = activeTargetIndex;
            this.selectableIndices = new ArrayList<>(selectableIndices);
            this.frameState = frameState;
            invalidate();
        }

        void showCaptureResult(boolean accepted) {
            feedbackAccepted = accepted;
            feedbackUntilMs = System.currentTimeMillis() + 420L;
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawFeedbackFlash(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            reticlePaint.setColor(frameState.ready ? 0xFFFFFFFF : 0xFF94A3B8);
            reticlePaint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(cx, cy, 34f, reticlePaint);
            drawCoverageMap(canvas);
            if (!frameState.ready) {
                return;
            }
            drawCapturedTargets(canvas);
            drawSelectableTargets(canvas);
            if (activeTargetIndex < 0 || activeTargetIndex >= targets.size()) {
                return;
            }
            drawActiveTarget(canvas);
        }

        private void drawCapturedTargets(Canvas canvas) {
            for (CaptureTarget target : targets) {
                if (target.captured) {
                    drawProjectedMarker(canvas, target, 0xFF34D399, 9f, 3f, false);
                }
            }
        }

        private void drawSelectableTargets(Canvas canvas) {
            for (Integer index : selectableIndices) {
                if (index == null || index < 0 || index >= targets.size() || index == activeTargetIndex) {
                    continue;
                }
                CaptureTarget target = targets.get(index);
                int color = target.weak ? 0xFFF97316 : 0xBFE2E8F0;
                drawProjectedMarker(canvas, target, color, target.weak ? 17f : 14f, target.weak ? 4f : 3f, true);
            }
        }

        private void drawActiveTarget(Canvas canvas) {
            CaptureTarget target = targets.get(activeTargetIndex);
            float[] point = frameState.project(target, getWidth(), getHeight());
            int color = target.weak ? 0xFFF97316 : 0xFFE2E8F0;
            targetPaint.setColor(color);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(target.weak ? 6f : 5f);
            float margin = 42f;
            if (!Float.isFinite(point[0]) || !Float.isFinite(point[1])) {
                drawOffscreenCueFromAngles(canvas, target);
                return;
            }
            if (point[0] < margin || point[0] > getWidth() - margin
                    || point[1] < margin || point[1] > getHeight() - margin) {
                drawOffscreenCue(canvas, point[0], point[1]);
                return;
            }
            canvas.drawCircle(point[0], point[1], 22f, targetPaint);
            targetPaint.setStrokeWidth(2f);
            canvas.drawCircle(point[0], point[1], 32f, targetPaint);
            drawFeedbackTargetPulse(canvas, point[0], point[1]);
        }

        private void drawProjectedMarker(
                Canvas canvas,
                CaptureTarget target,
                int color,
                float radius,
                float strokeWidth,
                boolean hollow) {
            float[] point = frameState.project(target, getWidth(), getHeight());
            float margin = radius + strokeWidth + 4f;
            if (!Float.isFinite(point[0]) || !Float.isFinite(point[1])
                    || point[0] < margin || point[0] > getWidth() - margin
                    || point[1] < margin || point[1] > getHeight() - margin) {
                return;
            }
            targetPaint.setColor(color);
            targetPaint.setStrokeWidth(strokeWidth);
            targetPaint.setStyle(hollow ? Paint.Style.STROKE : Paint.Style.FILL);
            canvas.drawCircle(point[0], point[1], radius, targetPaint);
            if (!hollow) {
                targetPaint.setColor(0xEFFFFFFF);
                targetPaint.setStyle(Paint.Style.STROKE);
                targetPaint.setStrokeWidth(2f);
                canvas.drawCircle(point[0], point[1], radius + 5f, targetPaint);
            }
        }

        private void drawFeedbackFlash(Canvas canvas) {
            long remaining = feedbackUntilMs - System.currentTimeMillis();
            if (remaining <= 0L) {
                return;
            }
            float fraction = Math.max(0f, Math.min(1f, remaining / 420f));
            int alpha = Math.round(70f * fraction);
            feedbackPaint.setColor((alpha << 24) | (feedbackAccepted ? 0x0034D399 : 0x00F97316));
            feedbackPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), feedbackPaint);
            postInvalidateOnAnimation();
        }

        private void drawFeedbackTargetPulse(Canvas canvas, float x, float y) {
            long remaining = feedbackUntilMs - System.currentTimeMillis();
            if (remaining <= 0L) {
                return;
            }
            float elapsed = 1f - Math.max(0f, Math.min(1f, remaining / 420f));
            targetPaint.setColor(feedbackAccepted ? 0xFF34D399 : 0xFFF97316);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(6f * (1f - elapsed) + 2f);
            canvas.drawCircle(x, y, 24f + 42f * elapsed, targetPaint);
            postInvalidateOnAnimation();
        }

        private void drawCoverageMap(Canvas canvas) {
            if (targets.isEmpty()) {
                return;
            }
            float width = Math.min(160f, getWidth() * 0.34f);
            float height = width * 0.52f;
            float left = getWidth() - width - 16f;
            float top = 16f;
            coveragePaint.setStyle(Paint.Style.FILL);
            coveragePaint.setColor(0x66000000);
            canvas.drawRoundRect(left, top, left + width, top + height, 8f, 8f, coveragePaint);
            coveragePaint.setStyle(Paint.Style.STROKE);
            coveragePaint.setStrokeWidth(2f);
            coveragePaint.setColor(0x99E2E8F0);
            canvas.drawRoundRect(left, top, left + width, top + height, 8f, 8f, coveragePaint);
            for (int i = 0; i < targets.size(); i++) {
                CaptureTarget target = targets.get(i);
                float x = left + 8f + (width - 16f) * (target.yawDegrees / 360f);
                float y = top + height * 0.5f - (height - 16f) * (target.pitchDegrees / 170f);
                if (target.captured) {
                    coveragePaint.setColor(0xFF34D399);
                } else if (target.weak) {
                    coveragePaint.setColor(0xFFF97316);
                } else if (i == activeTargetIndex) {
                    coveragePaint.setColor(0xFFFFFFFF);
                } else {
                    coveragePaint.setColor(0xFF64748B);
                }
                coveragePaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, y, i == activeTargetIndex ? 5.5f : 3.8f, coveragePaint);
            }
        }

        private void drawOffscreenCue(Canvas canvas, float targetX, float targetY) {
            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            float dx = Float.isFinite(targetX) ? targetX - centerX : 1f;
            float dy = Float.isFinite(targetY) ? targetY - centerY : 0f;
            float scaleX = dx == 0f ? Float.POSITIVE_INFINITY : (getWidth() * 0.5f - 32f) / Math.abs(dx);
            float scaleY = dy == 0f ? Float.POSITIVE_INFINITY : (getHeight() * 0.5f - 32f) / Math.abs(dy);
            float scale = Math.min(scaleX, scaleY);
            float edgeX = centerX + dx * scale;
            float edgeY = centerY + dy * scale;
            float angle = (float) Math.atan2(dy, dx);
            cuePath.reset();
            cuePath.moveTo(edgeX + (float) Math.cos(angle) * 18f, edgeY + (float) Math.sin(angle) * 18f);
            cuePath.lineTo(edgeX + (float) Math.cos(angle + 2.45f) * 20f, edgeY + (float) Math.sin(angle + 2.45f) * 20f);
            cuePath.lineTo(edgeX + (float) Math.cos(angle - 2.45f) * 20f, edgeY + (float) Math.sin(angle - 2.45f) * 20f);
            cuePath.close();
            targetPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(cuePath, targetPaint);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(5f);
            canvas.drawCircle(edgeX, edgeY, 28f, targetPaint);
        }

        private void drawOffscreenCueFromAngles(Canvas canvas, CaptureTarget target) {
            float yawDelta = signedHeadingDelta(target.yawDegrees, frameState.yawDegrees);
            float pitchDelta = target.pitchDegrees - frameState.pitchDegrees;
            drawOffscreenCue(
                    canvas,
                    getWidth() * 0.5f + yawDelta * getWidth() / Math.max(1f, frameState.horizontalFovDegrees()),
                    getHeight() * 0.5f - pitchDelta * getHeight() / Math.max(1f, frameState.verticalFovDegrees()));
        }
    }

    private static final class CameraBackgroundRenderer {
        private static final String VERTEX_SHADER =
                "attribute vec4 a_Position;"
                        + "attribute vec2 a_TexCoord;"
                        + "uniform mat4 u_TextureTransform;"
                        + "varying vec2 v_TexCoord;"
                        + "void main(){"
                        + "  gl_Position = a_Position;"
                        + "  v_TexCoord = (u_TextureTransform * vec4(a_TexCoord, 0.0, 1.0)).xy;"
                        + "}";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;"
                        + "uniform samplerExternalOES u_Texture;"
                        + "varying vec2 v_TexCoord;"
                        + "void main(){"
                        + "  gl_FragColor = texture2D(u_Texture, v_TexCoord);"
                        + "}";
        private final float[] vertices = {
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
        };
        private final float[] ndcCoordinates = {
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
        };
        private final float[] textureCoordinates = {
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
        };
        private final float[] textureTransform = new float[16];
        private java.nio.FloatBuffer vertexBuffer;
        private int textureId;
        private int program;
        private int positionAttrib;
        private int texCoordAttrib;
        private int textureTransformUniform;

        int createOnGlThread() {
            Matrix.setIdentityM(textureTransform, 0);
            vertexBuffer = java.nio.ByteBuffer
                    .allocateDirect(vertices.length * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vertexBuffer.put(vertices).position(0);
            textureId = createExternalTexture();
            int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            positionAttrib = GLES20.glGetAttribLocation(program, "a_Position");
            texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord");
            textureTransformUniform = GLES20.glGetUniformLocation(program, "u_TextureTransform");
            return textureId;
        }

        void updateDisplayGeometry(Frame frame) {
            if (frame == null || !frame.hasDisplayGeometryChanged()) {
                return;
            }
            frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    ndcCoordinates,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    textureCoordinates);
            for (int i = 0; i < 4; i++) {
                vertices[i * 4 + 2] = textureCoordinates[i * 2];
                vertices[i * 4 + 3] = textureCoordinates[i * 2 + 1];
            }
            vertexBuffer.position(0);
            vertexBuffer.put(vertices).position(0);
        }

        void setTextureTransform(float[] matrix) {
            if (matrix != null && matrix.length >= 16) {
                System.arraycopy(matrix, 0, textureTransform, 0, 16);
            }
        }

        void draw() {
            if (program == 0 || vertexBuffer == null) {
                return;
            }
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniformMatrix4fv(textureTransformUniform, 1, false, textureTransform, 0);
            vertexBuffer.position(0);
            GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
            GLES20.glEnableVertexAttribArray(positionAttrib);
            vertexBuffer.position(2);
            GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
            GLES20.glEnableVertexAttribArray(texCoordAttrib);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionAttrib);
            GLES20.glDisableVertexAttribArray(texCoordAttrib);
        }

        private static int createExternalTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return textures[0];
        }

        private static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }
    }
}
