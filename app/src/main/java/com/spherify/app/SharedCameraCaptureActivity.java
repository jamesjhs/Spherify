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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    private static final float FRONTIER_TARGET_MAX_DEGREES = 52f;
    private static final float ACTIVE_TARGET_HYSTERESIS_DEGREES = 24f;
    private static final int MAX_VISIBLE_FRONTIER_TARGETS = 8;
    private static final float MAX_TRANSLATION_FROM_ANCHOR_METERS = 0.08f;
    private static final long MIN_CAPTURE_INTERVAL_MS = 1100L;
    private static final long REQUIRED_ALIGNED_MS = 850L;
    private static final long LOW_TEXTURE_INITIAL_POSE_STABLE_MS = 650L;
    private static final float LOW_TEXTURE_INITIAL_POSE_STABLE_DEGREES = 2.5f;
    private static final long TEXTURE_HINT_INTERVAL_MS = 1200L;
    private static final int TEXTURE_HINT_GRID_COLUMNS = 5;
    private static final int TEXTURE_HINT_GRID_ROWS = 5;
    private static final int REFERENCE_PREVIEW_ROTATION_CORRECTION_DEGREES = 270;
    private static final boolean REFERENCE_PREVIEW_MIRROR_CORRECTION = true;
    private static final int MAX_VISIBLE_REFERENCE_OVERLAYS = 3;
    private static final int MAX_RETAINED_REFERENCE_OVERLAYS = 40;
    private static final float REFERENCE_OVERLAY_FADE_START_DEGREES = 14f;
    private static final float REFERENCE_OVERLAY_FADE_END_DEGREES = 52f;
    private static final int REFERENCE_OVERLAY_MAX_ALPHA = 138;
    private static final int REFERENCE_OVERLAY_MIN_ALPHA = 42;
    private static final String CAPTURE_PROFILE = "arcore_shared_camera";
    private static final String TAG = "SpherifySharedCamera";

    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();
    private final CandidateQualityScorer qualityScorer = new CandidateQualityScorer();
    private final OpenCvOverlapValidator overlapValidator = new OpenCvOverlapValidator();
    private final TreeMap<Long, TotalCaptureResult> camera2ResultsByTimestamp = new TreeMap<>();
    private final ArrayList<CaptureTarget> targets = new ArrayList<>();
    private final ArrayList<Integer> selectableTargetIndices = new ArrayList<>();
    private final ArrayList<CapturedReferenceFrame> capturedReferenceFrames = new ArrayList<>();
    private final AtomicBoolean frameAvailable = new AtomicBoolean();
    private final float[] latestProjectionMatrix = new float[16];
    private final float[] latestViewMatrix = new float[16];

    private GLSurfaceView glSurfaceView;
    private TargetOverlayView overlayView;
    private TextView statusText;
    private ProgressBar captureProgressBar;
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
    private boolean finishGuidanceActive;
    private String fieldComment = "";
    private int activeTargetIndex;
    private int anchorYawDegrees;
    private int anchorPitchDegrees;
    private float[] anchorTranslationMeters;
    private long alignedSinceMs;
    private long initialPoseStableSinceMs;
    private float initialPoseStableYawDegrees;
    private float initialPoseStablePitchDegrees;
    private long lastCaptureAtMs;
    private int cameraTextureId;
    private int viewportWidth;
    private int viewportHeight;
    private long lastTextureHintAtMs;
    private android.util.Size selectedCpuImageSize = new android.util.Size(0, 0);
    private android.util.Size selectedGpuTextureSize = new android.util.Size(0, 0);
    private String cameraConfigSummary = "";
    private AlertDialog completionDialog;
    private TextView completionText;
    private final CameraBackgroundRenderer backgroundRenderer = new CameraBackgroundRenderer();
    private ArFrameState latestFrameState = ArFrameState.notReady("tracking not started");
    private TextureHint latestTextureHint = TextureHint.unavailable();
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
        for (CapturedReferenceFrame reference : capturedReferenceFrames) {
            reference.recycle();
        }
        capturedReferenceFrames.clear();
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

        captureProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        captureProgressBar.setIndeterminate(false);
        captureProgressBar.setMax(1);
        captureProgressBar.setProgress(0);
        captureProgressBar.setContentDescription("Capture progress");
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                8);
        progressParams.setMargins(16, 0, 16, 8);
        root.addView(captureProgressBar, progressParams);

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
        Button noteButton = makeButton("Note");
        noteButton.setOnClickListener(v -> editFieldComment());
        Button finishButton = makeButton("Finish Sphere");
        finishButton.setOnClickListener(v -> finishCapture());
        controls.addView(captureButton);
        controls.addView(noteButton);
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

    private void editFieldComment() {
        ensureSession(false);
        CaptureSessionRecord session = library.findCaptureSession(sessionId);
        if (session != null) {
            fieldComment = session.fieldComment;
        }
        EditText input = new EditText(this);
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(fieldComment);
        input.setSelection(input.getText().length());
        input.setHint("Scene, device position, issue observed, screenshot names...");
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(this)
                .setTitle("Capture field note")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", (dialog, which) -> saveFieldComment(""))
                .setPositiveButton("Save", (dialog, which) -> saveFieldComment(input.getText().toString()))
                .show();
    }

    private void saveFieldComment(String comment) {
        fieldComment = comment == null ? "" : comment.trim();
        try {
            library.updateCaptureSessionFieldComment(sessionId, fieldComment);
            requestAutomaticDebugCsvDump();
            Toast.makeText(
                    this,
                    fieldComment.isEmpty() ? "Capture note cleared" : "Capture note saved",
                    Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Could not save capture note: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
                    2);
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
            if (cameraFacts.supportsHighQualityDistortionCorrection()) {
                previewRequestBuilder.set(
                        CaptureRequest.DISTORTION_CORRECTION_MODE,
                        CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY);
            }
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
                updateTextureHint(image);
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

    private void updateTextureHint(Image image) {
        long now = System.currentTimeMillis();
        if (now - lastTextureHintAtMs < TEXTURE_HINT_INTERVAL_MS || image.getFormat() != ImageFormat.YUV_420_888) {
            return;
        }
        lastTextureHintAtMs = now;
        TextureHint hint = TextureHint.from(
                image,
                displayRotationDegrees(),
                cameraFacts.sensorOrientationDegrees,
                cameraFacts.frontFacing);
        latestTextureHint = hint;
        if (overlayView != null) {
            runOnUiThread(() -> {
                overlayView.setTextureHint(hint);
                if (!latestFrameState.ready && "Scan detailed area to lock tracking".equals(latestFrameState.blocker)) {
                    refreshUi();
                }
            });
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
                    exposure,
                    state.ready,
                    target.yawDegrees,
                    target.pitchDegrees,
                    !captureAnchored,
                    state.ready);
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
            File acceptedImageFile = imageFile;
            ArFrameState captureState = state;
            runOnUiThread(() -> handleAnalysis(target, finalAnalysis, acceptedImageFile, captureState));
        } catch (IOException | JSONException e) {
            runOnUiThread(() -> rejectInUi(e.getMessage()));
        }
    }

    private void handleAnalysis(
            CaptureTarget target,
            CandidateAnalysisResult analysis,
            File imageFile,
            ArFrameState captureState) {
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
            addAdaptiveHorizontalTargetsForWeakOverlap(acceptedYaw, acceptedPitch, analysis);
            addCapturedReferenceFrame(imageFile, acceptedYaw, acceptedPitch, captureState);
            finishGuidanceActive = false;
            target.weak = false;
            updateActiveTarget();
            ensureSession(true);
            overlayView.showCaptureResult(true);
            captureButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Toast.makeText(this, acceptedCaptureMessage(analysis, "Accepted - AR anchor"), Toast.LENGTH_SHORT).show();
            if (activeTarget() == null) {
                startIntegratedSpherification();
                return;
            }
        } else {
            target.weak = true;
            overlayView.showCaptureResult(false);
            captureButton.performHapticFeedback(HapticFeedbackConstants.REJECT);
            Toast.makeText(this, rejectionMessage(analysis), Toast.LENGTH_LONG).show();
        }
        requestAutomaticDebugCsvDump();
        refreshUi();
    }

    private static String acceptedCaptureMessage(CandidateAnalysisResult analysis, String anchorMessage) {
        if ("low_texture_pose_only".equals(analysis.validationCategory)) {
            return "Low detail here, continue slowly";
        }
        return analysis.inlierCount > 0 ? "Accepted - overlap valid" : anchorMessage;
    }

    private static String rejectionMessage(CandidateAnalysisResult analysis) {
        if ("Need more visual detail".equals(analysis.rejectionReason)) {
            return "Low detail here, add visual detail";
        }
        return analysis.rejectionReason.isEmpty() ? "Recapture this area" : analysis.rejectionReason;
    }

    private void addCapturedReferenceFrame(File imageFile, int yawDegrees, int pitchDegrees, ArFrameState captureState) {
        float referenceYaw = captureState.ready ? captureState.yawDegrees : yawDegrees;
        float referencePitch = captureState.ready ? captureState.pitchDegrees : pitchDegrees;
        CapturedReferenceFrame reference = CapturedReferenceFrame.from(imageFile, referenceYaw, referencePitch, captureState);
        if (reference == null) {
            return;
        }
        capturedReferenceFrames.add(reference);
        while (capturedReferenceFrames.size() > MAX_RETAINED_REFERENCE_OVERLAYS) {
            CapturedReferenceFrame removed = capturedReferenceFrames.remove(0);
            removed.recycle();
        }
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
        json.put("cameraSensorOrientationDegrees", cameraFacts.sensorOrientationDegrees);
        json.put("cameraFrontFacing", cameraFacts.frontFacing);
        json.put("preCorrectionActiveArrayWidth", cameraFacts.preCorrectionActiveArrayWidth);
        json.put("preCorrectionActiveArrayHeight", cameraFacts.preCorrectionActiveArrayHeight);
        putIntArrayIfPresent(json, "availableDistortionCorrectionModes", cameraFacts.availableDistortionCorrectionModes);
        Integer distortionCorrectionMode = metadata.get(CaptureResult.DISTORTION_CORRECTION_MODE);
        putIfPresent(json, "distortionCorrectionMode", distortionCorrectionMode);
        json.put("manualLensUndistortionRequired", distortionCorrectionMode != null
                && distortionCorrectionMode == CaptureResult.DISTORTION_CORRECTION_MODE_OFF);
        putFloatArrayIfPresent(json, "lensIntrinsicCalibration", cameraFacts.lensIntrinsicCalibration);
        putFloatArrayIfPresent(json, "lensDistortion", cameraFacts.lensDistortion);
        if (cameraFacts.lensDistortion.length >= 5) {
            json.put("lensDistortionModel", "android_brown_conrady_pre_correction_normalized");
        }
        json.put("sensorToDisplayRotationDegrees", state.sensorToDisplayRotationDegrees);
        json.put("mirrorForDisplay", state.mirrorForDisplay);
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

    private static void putFloatArrayIfPresent(JSONObject json, String key, float[] values) throws JSONException {
        if (values == null || values.length == 0) {
            return;
        }
        JSONArray array = new JSONArray();
        for (float value : values) {
            array.put(value);
        }
        json.put(key, array);
    }

    private static void putIntArrayIfPresent(JSONObject json, String key, int[] values) throws JSONException {
        if (values == null || values.length == 0) {
            return;
        }
        JSONArray array = new JSONArray();
        for (int value : values) {
            array.put(value);
        }
        json.put(key, array);
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

    private void addAdaptiveHorizontalTargetsForWeakOverlap(
            int acceptedYaw,
            int acceptedPitch,
            CandidateAnalysisResult analysis) {
        if (analysis == null || (!"pose_guided_overlap".equals(analysis.validationCategory) && analysis.confidence >= 0.35)) {
            return;
        }
        ArrayList<Integer> midpointYaws = new ArrayList<>();
        for (CaptureTarget captured : targets) {
            if (!captured.captured || captured.pitchDegrees != acceptedPitch || captured.yawDegrees == normalizeDegrees(acceptedYaw)) {
                continue;
            }
            float delta = signedHeadingDelta(acceptedYaw, captured.yawDegrees);
            float absDelta = Math.abs(delta);
            if (absDelta < 26f || absDelta > 60f) {
                continue;
            }
            int midpointYaw = normalizeDegrees(Math.round(captured.yawDegrees + delta * 0.5f));
            if (!targetExists(midpointYaw, acceptedPitch) && !midpointYaws.contains(midpointYaw)) {
                midpointYaws.add(midpointYaw);
            }
        }
        for (Integer midpointYaw : midpointYaws) {
            addTargetIfMissing(midpointYaw, acceptedPitch, CaptureTargetPhase.HORIZON, true);
        }
    }

    private void addTargetIfMissing(int yawDegrees, int pitchDegrees, CaptureTargetPhase phase, boolean weak) {
        int yaw = normalizeDegrees(yawDegrees);
        for (CaptureTarget target : targets) {
            if (target.yawDegrees == yaw && target.pitchDegrees == pitchDegrees) {
                target.weak = target.weak || weak;
                return;
            }
        }
        CaptureTarget target = new CaptureTarget(yaw, pitchDegrees, phase);
        target.weak = weak;
        targets.add(target);
    }

    private boolean targetExists(int yawDegrees, int pitchDegrees) {
        int yaw = normalizeDegrees(yawDegrees);
        for (CaptureTarget target : targets) {
            if (target.yawDegrees == yaw && target.pitchDegrees == pitchDegrees) {
                return true;
            }
        }
        return false;
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
        if (!captureAnchored && state.featurePointCount < MIN_TRACKING_FEATURE_POINTS
                && !initialPoseStableForLowTexture(state)) {
            return "Hold steady to lock tracking";
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
        String blocker = target == null ? "" : captureBlocker(target, state);
        boolean canCapture = target != null
                && blocker.isEmpty()
                && !captureInProgress
                && !completionInProgress;
        boolean autoCaptureReady = canCapture && captureAnchored;
        if (autoCaptureReady) {
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
        int acceptedCount = acceptedTargetCount();
        int requiredCount = Math.max(1, targets.size());
        captureProgressBar.setMax(requiredCount);
        captureProgressBar.setProgress(Math.min(acceptedCount, requiredCount));
        captureProgressBar.setContentDescription(String.format(
                Locale.US,
                "Capture progress %d of %d",
                acceptedCount,
                targets.size()));
        overlayView.setState(targets, activeTargetIndex, selectableTargetIndices, state, capturedReferenceFrames);
        overlayView.setTextureHint(latestTextureHint);
        String text = completionInProgress
                ? "Solving PhotoSphere"
                : captureInProgress
                ? "Validating AR frame"
                : finishGuidanceActive
                ? "Capture required missing view"
                : target == null
                ? "Ready to solve PhotoSphere"
                : !state.ready
                ? textureGuidanceText(state)
                : !blocker.isEmpty()
                ? blocker
                : !isAligned(target, state)
                ? selectableTargetIndices.size() > 1 ? "Choose nearby target" : "Move to target"
                : !state.parallaxWarning.isEmpty()
                ? state.parallaxWarning
                : !captureAnchored && state.featurePointCount < MIN_TRACKING_FEATURE_POINTS
                ? "Low visual detail - press Capture slowly"
                : !captureAnchored
                ? "Press Capture to start"
                : "Hold steady - capture ready";
        statusText.setText(String.format(Locale.US, "%s  |  %d/%d", text, acceptedCount, targets.size()));
    }

    private String textureGuidanceText(ArFrameState state) {
        if ("Scan detailed area to lock tracking".equals(state.blocker) && latestTextureHint.available) {
            float dx = latestTextureHint.viewX - 0.5f;
            float dy = latestTextureHint.viewY - 0.5f;
            if (Math.abs(dx) < 0.12f && Math.abs(dy) < 0.12f) {
                return "Hold on detailed area";
            }
            String horizontal = Math.abs(dx) < 0.12f ? "" : dx < 0f ? "left" : "right";
            String vertical = Math.abs(dy) < 0.12f ? "" : dy < 0f ? "up" : "down";
            if (horizontal.isEmpty()) {
                return "Aim " + vertical + " for detail";
            }
            if (vertical.isEmpty()) {
                return "Aim " + horizontal + " for detail";
            }
            return "Aim " + vertical + "-" + horizontal + " for detail";
        }
        return state.blocker;
    }

    private boolean initialPoseStableForLowTexture(ArFrameState state) {
        if (captureAnchored || state.featurePointCount >= MIN_TRACKING_FEATURE_POINTS) {
            initialPoseStableSinceMs = 0L;
            return true;
        }
        long now = System.currentTimeMillis();
        if (initialPoseStableSinceMs == 0L
                || Math.abs(signedHeadingDelta(state.yawDegrees, initialPoseStableYawDegrees))
                > LOW_TEXTURE_INITIAL_POSE_STABLE_DEGREES
                || Math.abs(state.pitchDegrees - initialPoseStablePitchDegrees)
                > LOW_TEXTURE_INITIAL_POSE_STABLE_DEGREES) {
            initialPoseStableSinceMs = now;
            initialPoseStableYawDegrees = state.yawDegrees;
            initialPoseStablePitchDegrees = state.pitchDegrees;
            return false;
        }
        return now - initialPoseStableSinceMs >= LOW_TEXTURE_INITIAL_POSE_STABLE_MS;
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
        if (!minimumRequiredCaptureComplete()) {
            guideMissingRequiredCapture();
            return;
        }
        startIntegratedSpherification();
    }

    private boolean minimumRequiredCaptureComplete() {
        return captureAnchored && acceptedTargetCount() >= 30 && activeTarget() == null;
    }

    private void guideMissingRequiredCapture() {
        finishGuidanceActive = true;
        updateActiveTarget();
        refreshUi();
        Toast.makeText(this, "Capture the highlighted required view before finishing", Toast.LENGTH_LONG).show();
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
            latestFrameState = ArFrameState.from(
                    frame,
                    captureAnchored,
                    anchorPoseTranslation(),
                    displayRotationDegrees(),
                    cameraFacts);
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

    private int displayRotationDegrees() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private static int sensorToDisplayRotationDegrees(
            int sensorOrientationDegrees,
            int displayRotationDegrees,
            boolean frontFacing) {
        if (frontFacing) {
            return (sensorOrientationDegrees + displayRotationDegrees) % 360;
        }
        return (sensorOrientationDegrees - displayRotationDegrees + 360) % 360;
    }

    private static void writeJpegFromYuv(Image image, File outputFile) throws IOException {
        byte[] nv21 = yuv420ToNv21(image);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            if (!yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 92, output)) {
                throw new IOException("could not encode ARCore CPU image");
            }
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
        final int sensorOrientationDegrees;
        final boolean frontFacing;
        final int preCorrectionActiveArrayWidth;
        final int preCorrectionActiveArrayHeight;
        final int[] availableDistortionCorrectionModes;
        final float[] lensIntrinsicCalibration;
        final float[] lensDistortion;

        CameraFacts(
                boolean available,
                float sensorWidthMm,
                float sensorHeightMm,
                int sensorOrientationDegrees,
                boolean frontFacing,
                int preCorrectionActiveArrayWidth,
                int preCorrectionActiveArrayHeight,
                int[] availableDistortionCorrectionModes,
                float[] lensIntrinsicCalibration,
                float[] lensDistortion) {
            this.available = available;
            this.sensorWidthMm = sensorWidthMm;
            this.sensorHeightMm = sensorHeightMm;
            this.sensorOrientationDegrees = sensorOrientationDegrees;
            this.frontFacing = frontFacing;
            this.preCorrectionActiveArrayWidth = preCorrectionActiveArrayWidth;
            this.preCorrectionActiveArrayHeight = preCorrectionActiveArrayHeight;
            this.availableDistortionCorrectionModes = availableDistortionCorrectionModes == null
                    ? new int[0]
                    : availableDistortionCorrectionModes.clone();
            this.lensIntrinsicCalibration = lensIntrinsicCalibration == null ? new float[0] : lensIntrinsicCalibration.clone();
            this.lensDistortion = lensDistortion == null ? new float[0] : lensDistortion.clone();
        }

        static CameraFacts unavailable() {
            return new CameraFacts(false, 0f, 0f, 0, false, 0, 0, null, null, null);
        }

        boolean supportsHighQualityDistortionCorrection() {
            for (int mode : availableDistortionCorrectionModes) {
                if (mode == CameraCharacteristics.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) {
                    return true;
                }
            }
            return false;
        }

        static CameraFacts from(CameraCharacteristics characteristics) {
            android.util.SizeF size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            android.graphics.Rect preCorrectionActiveArray =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
            return size == null ? unavailable() : new CameraFacts(
                    true,
                    size.getWidth(),
                    size.getHeight(),
                    sensorOrientation == null ? 0 : sensorOrientation,
                    facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT,
                    preCorrectionActiveArray == null ? 0 : preCorrectionActiveArray.width(),
                    preCorrectionActiveArray == null ? 0 : preCorrectionActiveArray.height(),
                    characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),
                    characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
                    characteristics.get(CameraCharacteristics.LENS_DISTORTION));
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
        final int sensorToDisplayRotationDegrees;
        final boolean mirrorForDisplay;
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
                int sensorToDisplayRotationDegrees,
                boolean mirrorForDisplay,
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
            this.sensorToDisplayRotationDegrees = sensorToDisplayRotationDegrees;
            this.mirrorForDisplay = mirrorForDisplay;
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
            return new ArFrameState(
                    false, blocker, "not_tracking", 0f, 0f, 0f, 0, 0f, 0f, "",
                    0f, 0f, 0f, 0f, 0, 0, 0, false, null);
        }

        static ArFrameState from(
                Frame frame,
                boolean anchored,
                float[] anchorTranslation,
                int displayRotationDegrees,
                CameraFacts cameraFacts) {
            com.google.ar.core.Camera camera = frame.getCamera();
            TrackingState tracking = camera.getTrackingState();
            Pose pose = camera.getDisplayOrientedPose();
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
                    ? "Rotate around the lens"
                    : "";
            com.google.ar.core.CameraIntrinsics intrinsics = camera.getImageIntrinsics();
            float[] focal = intrinsics.getFocalLength();
            float[] principal = intrinsics.getPrincipalPoint();
            int[] dimensions = intrinsics.getImageDimensions();
            boolean intrinsicsReady = focal[0] > 0f && focal[1] > 0f && dimensions[0] > 0 && dimensions[1] > 0;
            boolean ready = tracking == TrackingState.TRACKING
                    && intrinsicsReady;
            String blocker = tracking != TrackingState.TRACKING
                    ? "Scan detailed area to lock tracking"
                    : !intrinsicsReady
                    ? "Waiting for camera calibration"
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
                    sensorToDisplayRotationDegrees(
                            cameraFacts.sensorOrientationDegrees,
                            displayRotationDegrees,
                            cameraFacts.frontFacing),
                    cameraFacts.frontFacing,
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
            return projectYawPitch(target.yawDegrees, target.pitchDegrees, width, height);
        }

        float[] projectYawPitch(float yawDegrees, float pitchDegrees, int width, int height) {
            float[] direction = directionFromYawPitch(yawDegrees, pitchDegrees);
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

    private static final class CapturedReferenceFrame {
        private static final int MAX_REFERENCE_SIZE = 360;
        private static final int MESH_WIDTH = 10;
        private static final int MESH_HEIGHT = 6;

        final Bitmap bitmap;
        final float centerYawDegrees;
        final float centerPitchDegrees;
        final float focalX;
        final float focalY;
        final float centerX;
        final float centerY;
        final int sourceWidth;
        final int sourceHeight;
        final int meshRotationCorrectionDegrees;
        final boolean meshMirrorCorrection;
        final float[] meshVertices = new float[(MESH_WIDTH + 1) * (MESH_HEIGHT + 1) * 2];

        private CapturedReferenceFrame(
                Bitmap bitmap,
                float centerYawDegrees,
                float centerPitchDegrees,
                float focalX,
                float focalY,
                float centerX,
                float centerY,
                int sourceWidth,
                int sourceHeight,
                int meshRotationCorrectionDegrees,
                boolean meshMirrorCorrection) {
            this.bitmap = bitmap;
            this.centerYawDegrees = centerYawDegrees;
            this.centerPitchDegrees = centerPitchDegrees;
            this.focalX = focalX;
            this.focalY = focalY;
            this.centerX = centerX;
            this.centerY = centerY;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.meshRotationCorrectionDegrees = meshRotationCorrectionDegrees;
            this.meshMirrorCorrection = meshMirrorCorrection;
        }

        static CapturedReferenceFrame from(File imageFile, float yawDegrees, float pitchDegrees, ArFrameState captureState) {
            Bitmap bitmap = decodeReferenceBitmap(imageFile);
            if (bitmap == null) {
                return null;
            }
            float sourceWidth = captureState.imageWidth > 0 ? captureState.imageWidth : bitmap.getWidth();
            float sourceHeight = captureState.imageHeight > 0 ? captureState.imageHeight : bitmap.getHeight();
            Bitmap corrected = transformReferencePreview(
                    bitmap,
                    REFERENCE_PREVIEW_ROTATION_CORRECTION_DEGREES,
                    REFERENCE_PREVIEW_MIRROR_CORRECTION);
            DisplayIntrinsics displayIntrinsics = DisplayIntrinsics.from(
                    sourceWidth,
                    sourceHeight,
                    captureState.imageFx,
                    captureState.imageFy,
                    captureState.imageCx,
                    captureState.imageCy,
                    REFERENCE_PREVIEW_ROTATION_CORRECTION_DEGREES,
                    REFERENCE_PREVIEW_MIRROR_CORRECTION,
                    corrected.getWidth(),
                    corrected.getHeight());
            float fx = displayIntrinsics.fx > 0f ? displayIntrinsics.fx
                    : corrected.getWidth() / (2f * (float) Math.tan(Math.toRadians(captureState.horizontalFovDegrees() * 0.5f)));
            float fy = displayIntrinsics.fy > 0f ? displayIntrinsics.fy
                    : corrected.getHeight() / (2f * (float) Math.tan(Math.toRadians(captureState.verticalFovDegrees() * 0.5f)));
            float cx = displayIntrinsics.cx > 0f ? displayIntrinsics.cx : corrected.getWidth() * 0.5f;
            float cy = displayIntrinsics.cy > 0f ? displayIntrinsics.cy : corrected.getHeight() * 0.5f;
            return new CapturedReferenceFrame(
                    corrected,
                    yawDegrees,
                    pitchDegrees,
                    fx,
                    fy,
                    cx,
                    cy,
                    corrected.getWidth(),
                    corrected.getHeight(),
                    0,
                    false);
        }

        boolean updateMesh(ArFrameState frameState, int viewWidth, int viewHeight) {
            if (bitmap.isRecycled() || viewWidth <= 0 || viewHeight <= 0 || focalX <= 0f || focalY <= 0f) {
                return false;
            }
            int offset = 0;
            int visible = 0;
            for (int y = 0; y <= MESH_HEIGHT; y++) {
                float sourceY = sourceHeight * y / (float) MESH_HEIGHT;
                for (int x = 0; x <= MESH_WIDTH; x++) {
                    float sourceX = sourceWidth * x / (float) MESH_WIDTH;
                    float yawOffset = (float) Math.toDegrees(Math.atan((sourceX - centerX) / focalX));
                    float pitchOffset = -(float) Math.toDegrees(Math.atan((sourceY - centerY) / focalY));
                    float[] point = frameState.projectYawPitch(
                            normalize(centerYawDegrees + yawOffset),
                            clamp(centerPitchDegrees + pitchOffset, -89f, 89f),
                            viewWidth,
                            viewHeight);
                    if (Float.isFinite(point[0]) && Float.isFinite(point[1])) {
                        visible++;
                    } else {
                        point[0] = -10000f;
                        point[1] = -10000f;
                    }
                    meshVertices[offset++] = point[0];
                    meshVertices[offset++] = point[1];
                }
            }
            return visible >= 4;
        }

        float angularDistanceFrom(ArFrameState frameState) {
            float yaw = signedHeadingDelta(centerYawDegrees, frameState.yawDegrees);
            float pitch = centerPitchDegrees - frameState.pitchDegrees;
            return (float) Math.sqrt(yaw * yaw + pitch * pitch);
        }

        void recycle() {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }

        private static Bitmap decodeReferenceBitmap(File imageFile) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / sample > MAX_REFERENCE_SIZE || bounds.outHeight / sample > MAX_REFERENCE_SIZE) {
                sample *= 2;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            decode.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(imageFile.getAbsolutePath(), decode);
        }

        private static Bitmap transformReferencePreview(Bitmap bitmap, int rotationDegrees, boolean mirror) {
            if (bitmap == null || (rotationDegrees == 0 && !mirror)) {
                return bitmap;
            }
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            if (mirror) {
                matrix.postScale(-1f, 1f);
            }
            if (rotationDegrees != 0) {
                matrix.postRotate(rotationDegrees);
            }
            Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return transformed;
        }

        private static final class DisplayIntrinsics {
            final float fx;
            final float fy;
            final float cx;
            final float cy;

            DisplayIntrinsics(float fx, float fy, float cx, float cy) {
                this.fx = fx;
                this.fy = fy;
                this.cx = cx;
                this.cy = cy;
            }

            static DisplayIntrinsics from(
                    float sourceWidth,
                    float sourceHeight,
                    float sourceFx,
                    float sourceFy,
                    float sourceCx,
                    float sourceCy,
                    int rotationDegrees,
                    boolean mirror,
                    int bitmapWidth,
                    int bitmapHeight) {
                float fx = sourceFx;
                float fy = sourceFy;
                float cx = sourceCx > 0f ? sourceCx : sourceWidth * 0.5f;
                float cy = sourceCy > 0f ? sourceCy : sourceHeight * 0.5f;
                if (rotationDegrees == 90) {
                    float oldFx = fx;
                    fx = fy;
                    fy = oldFx;
                    float oldCx = cx;
                    cx = sourceHeight - cy;
                    cy = oldCx;
                } else if (rotationDegrees == 180) {
                    cx = sourceWidth - cx;
                    cy = sourceHeight - cy;
                } else if (rotationDegrees == 270) {
                    float oldFx = fx;
                    fx = fy;
                    fy = oldFx;
                    float oldCx = cx;
                    cx = cy;
                    cy = sourceWidth - oldCx;
                }
                float rotatedWidth = rotationDegrees == 90 || rotationDegrees == 270 ? sourceHeight : sourceWidth;
                float rotatedHeight = rotationDegrees == 90 || rotationDegrees == 270 ? sourceWidth : sourceHeight;
                if (mirror) {
                    cx = rotatedWidth - cx;
                }
                float scaleX = bitmapWidth / Math.max(1f, rotatedWidth);
                float scaleY = bitmapHeight / Math.max(1f, rotatedHeight);
                return new DisplayIntrinsics(fx * scaleX, fy * scaleY, cx * scaleX, cy * scaleY);
            }
        }

        private static float normalize(float degrees) {
            float result = degrees % 360f;
            return result < 0f ? result + 360f : result;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class TextureHint {
        final boolean available;
        final float viewX;
        final float viewY;
        final float score;

        TextureHint(boolean available, float viewX, float viewY, float score) {
            this.available = available;
            this.viewX = viewX;
            this.viewY = viewY;
            this.score = score;
        }

        static TextureHint unavailable() {
            return new TextureHint(false, 0.5f, 0.5f, 0f);
        }

        static TextureHint from(Image image, int displayRotationDegrees, int sensorOrientationDegrees, boolean frontFacing) {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int cellWidth = Math.max(8, width / TEXTURE_HINT_GRID_COLUMNS);
            int cellHeight = Math.max(8, height / TEXTURE_HINT_GRID_ROWS);
            double bestScore = 0.0;
            int bestX = width / 2;
            int bestY = height / 2;
            for (int row = 0; row < TEXTURE_HINT_GRID_ROWS; row++) {
                for (int col = 0; col < TEXTURE_HINT_GRID_COLUMNS; col++) {
                    int left = col * width / TEXTURE_HINT_GRID_COLUMNS;
                    int top = row * height / TEXTURE_HINT_GRID_ROWS;
                    int right = Math.min(width - 2, left + cellWidth);
                    int bottom = Math.min(height - 2, top + cellHeight);
                    double score = textureScore(buffer, rowStride, pixelStride, left, top, right, bottom);
                    double centerBias = 1.0 - 0.22 * Math.hypot(col - 2.0, row - 2.0);
                    score *= Math.max(0.45, centerBias);
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = (left + right) / 2;
                        bestY = (top + bottom) / 2;
                    }
                }
            }
            float[] view = sensorPointToDisplayNormalized(
                    bestX / (float) Math.max(1, width),
                    bestY / (float) Math.max(1, height),
                    sensorToDisplayRotationDegrees(sensorOrientationDegrees, displayRotationDegrees, frontFacing),
                    frontFacing);
            return new TextureHint(bestScore > 7.0, view[0], view[1], (float) bestScore);
        }

        private static double textureScore(
                ByteBuffer buffer,
                int rowStride,
                int pixelStride,
                int left,
                int top,
                int right,
                int bottom) {
            double total = 0.0;
            int count = 0;
            int stepX = Math.max(2, (right - left) / 18);
            int stepY = Math.max(2, (bottom - top) / 18);
            for (int y = top + 1; y < bottom; y += stepY) {
                for (int x = left + 1; x < right; x += stepX) {
                    int center = yValue(buffer, rowStride, pixelStride, x, y);
                    int rightValue = yValue(buffer, rowStride, pixelStride, x + 1, y);
                    int downValue = yValue(buffer, rowStride, pixelStride, x, y + 1);
                    total += Math.abs(rightValue - center) + Math.abs(downValue - center);
                    count++;
                }
            }
            return count <= 0 ? 0.0 : total / count;
        }

        private static int yValue(ByteBuffer buffer, int rowStride, int pixelStride, int x, int y) {
            return buffer.get(y * rowStride + x * pixelStride) & 0xFF;
        }

        private static float[] sensorPointToDisplayNormalized(float x, float y, int rotationDegrees, boolean mirror) {
            float outX;
            float outY;
            if (rotationDegrees == 90) {
                outX = 1f - y;
                outY = x;
            } else if (rotationDegrees == 180) {
                outX = 1f - x;
                outY = 1f - y;
            } else if (rotationDegrees == 270) {
                outX = y;
                outY = 1f - x;
            } else {
                outX = x;
                outY = y;
            }
            if (mirror) {
                outX = 1f - outX;
            }
            return new float[]{clamp01(outX), clamp01(outY)};
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }

    private static final class TargetOverlayView extends View {
        private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint coveragePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint feedbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path cuePath = new Path();
        private final Path horizonPath = new Path();
        private List<CaptureTarget> targets = new ArrayList<>();
        private List<Integer> selectableIndices = new ArrayList<>();
        private List<CapturedReferenceFrame> referenceFrames = new ArrayList<>();
        private final ArrayList<ReferenceOverlayCandidate> referenceOverlayCandidates = new ArrayList<>();
        private int activeTargetIndex = -1;
        private ArFrameState frameState = ArFrameState.notReady("tracking not started");
        private TextureHint textureHint = TextureHint.unavailable();
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
                ArFrameState frameState,
                List<CapturedReferenceFrame> referenceFrames) {
            this.targets = targets;
            this.activeTargetIndex = activeTargetIndex;
            this.selectableIndices = new ArrayList<>(selectableIndices);
            this.frameState = frameState;
            this.referenceFrames = referenceFrames == null ? new ArrayList<>() : new ArrayList<>(referenceFrames);
            invalidate();
        }

        void setTextureHint(TextureHint textureHint) {
            this.textureHint = textureHint == null ? TextureHint.unavailable() : textureHint;
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
            drawCapturedReferenceFrames(canvas);
            drawHorizon(canvas);
            reticlePaint.setColor(frameState.ready ? 0xFFFFFFFF : 0xFF94A3B8);
            reticlePaint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(cx, cy, 34f, reticlePaint);
            drawCoverageMap(canvas);
            if (!frameState.ready) {
                drawTextureHint(canvas);
                return;
            }
            drawCapturedTargets(canvas);
            drawSelectableTargets(canvas);
            if (activeTargetIndex < 0 || activeTargetIndex >= targets.size()) {
                return;
            }
            drawActiveTarget(canvas);
        }

        private void drawHorizon(Canvas canvas) {
            if (!frameState.ready) {
                return;
            }
            horizonPath.reset();
            boolean drawing = false;
            for (int step = -12; step <= 12; step++) {
                float yaw = frameState.yawDegrees + step * 7.5f;
                float[] point = frameState.projectYawPitch(yaw, 0f, getWidth(), getHeight());
                boolean visible = Float.isFinite(point[0]) && Float.isFinite(point[1])
                        && point[0] > -getWidth() && point[0] < getWidth() * 2f
                        && point[1] > -getHeight() && point[1] < getHeight() * 2f;
                if (!visible) {
                    drawing = false;
                    continue;
                }
                if (!drawing) {
                    horizonPath.moveTo(point[0], point[1]);
                    drawing = true;
                } else {
                    horizonPath.lineTo(point[0], point[1]);
                }
            }
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(3f);
            targetPaint.setColor(0xCC38BDF8);
            canvas.drawPath(horizonPath, targetPaint);
            targetPaint.setStrokeWidth(1.5f);
            targetPaint.setColor(0x88FFFFFF);
            canvas.drawPath(horizonPath, targetPaint);
        }

        private void drawTextureHint(Canvas canvas) {
            if (textureHint == null || !textureHint.available || !"Scan detailed area to lock tracking".equals(frameState.blocker)) {
                return;
            }
            float x = textureHint.viewX * getWidth();
            float y = textureHint.viewY * getHeight();
            reticlePaint.setColor(0xFFE2E8F0);
            reticlePaint.setStyle(Paint.Style.STROKE);
            reticlePaint.setStrokeWidth(4f);
            canvas.drawCircle(x, y, 30f, reticlePaint);
            reticlePaint.setStrokeWidth(2f);
            canvas.drawCircle(x, y, 42f, reticlePaint);
            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            targetPaint.setColor(0xFFE2E8F0);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(5f);
            canvas.drawLine(centerX, centerY, x, y, targetPaint);
        }

        private void drawCapturedReferenceFrames(Canvas canvas) {
            if (!frameState.ready || referenceFrames.isEmpty()) {
                return;
            }
            referenceOverlayCandidates.clear();
            for (CapturedReferenceFrame reference : referenceFrames) {
                float distance = reference.angularDistanceFrom(frameState);
                if (distance <= REFERENCE_OVERLAY_FADE_END_DEGREES) {
                    referenceOverlayCandidates.add(new ReferenceOverlayCandidate(reference, distance));
                }
            }
            Collections.sort(referenceOverlayCandidates, (left, right) -> Float.compare(left.distanceDegrees, right.distanceDegrees));
            targetPaint.setStyle(Paint.Style.FILL);
            int count = Math.min(MAX_VISIBLE_REFERENCE_OVERLAYS, referenceOverlayCandidates.size());
            for (int i = count - 1; i >= 0; i--) {
                ReferenceOverlayCandidate candidate = referenceOverlayCandidates.get(i);
                CapturedReferenceFrame reference = candidate.reference;
                if (!reference.updateMesh(frameState, getWidth(), getHeight())) {
                    continue;
                }
                int alpha = referenceOverlayAlpha(candidate.distanceDegrees);
                targetPaint.setAlpha(alpha);
                canvas.drawBitmapMesh(
                        reference.bitmap,
                        CapturedReferenceFrame.MESH_WIDTH,
                        CapturedReferenceFrame.MESH_HEIGHT,
                        reference.meshVertices,
                        0,
                        null,
                        0,
                        targetPaint);
                drawReferenceFootprint(canvas, reference, Math.min(210, alpha + 70));
            }
            targetPaint.setAlpha(255);
            targetPaint.setStyle(Paint.Style.STROKE);
        }

        private int referenceOverlayAlpha(float distanceDegrees) {
            float t = (distanceDegrees - REFERENCE_OVERLAY_FADE_START_DEGREES)
                    / Math.max(1f, REFERENCE_OVERLAY_FADE_END_DEGREES - REFERENCE_OVERLAY_FADE_START_DEGREES);
            t = Math.max(0f, Math.min(1f, t));
            return Math.round(REFERENCE_OVERLAY_MAX_ALPHA * (1f - t) + REFERENCE_OVERLAY_MIN_ALPHA * t);
        }

        private void drawReferenceFootprint(Canvas canvas, CapturedReferenceFrame reference, int alpha) {
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(4f);
            targetPaint.setColor((Math.max(0, Math.min(255, alpha)) << 24) | 0x0038BDF8);
            float[] vertices = reference.meshVertices;
            int row = (CapturedReferenceFrame.MESH_WIDTH + 1) * 2;
            int bottom = CapturedReferenceFrame.MESH_HEIGHT * row;
            drawMeshLine(canvas, vertices, 0, CapturedReferenceFrame.MESH_WIDTH * 2);
            drawMeshLine(canvas, vertices, bottom, bottom + CapturedReferenceFrame.MESH_WIDTH * 2);
            drawMeshLine(canvas, vertices, 0, bottom);
            drawMeshLine(canvas, vertices, CapturedReferenceFrame.MESH_WIDTH * 2, bottom + CapturedReferenceFrame.MESH_WIDTH * 2);
            targetPaint.setStyle(Paint.Style.FILL);
        }

        private static final class ReferenceOverlayCandidate {
            final CapturedReferenceFrame reference;
            final float distanceDegrees;

            ReferenceOverlayCandidate(CapturedReferenceFrame reference, float distanceDegrees) {
                this.reference = reference;
                this.distanceDegrees = distanceDegrees;
            }
        }

        private void drawMeshLine(Canvas canvas, float[] vertices, int from, int to) {
            float x1 = vertices[from];
            float y1 = vertices[from + 1];
            float x2 = vertices[to];
            float y2 = vertices[to + 1];
            if (Float.isFinite(x1) && Float.isFinite(y1) && Float.isFinite(x2) && Float.isFinite(y2)
                    && x1 > -9999f && x2 > -9999f) {
                canvas.drawLine(x1, y1, x2, y2, targetPaint);
            }
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
            float size = Math.min(124f, getWidth() * 0.26f);
            float radius = size * 0.5f;
            float centerX = getWidth() - radius - 16f;
            float centerY = 16f + radius;
            coveragePaint.setStyle(Paint.Style.FILL);
            coveragePaint.setColor(0x66000000);
            canvas.drawCircle(centerX, centerY, radius, coveragePaint);
            coveragePaint.setStyle(Paint.Style.STROKE);
            coveragePaint.setStrokeWidth(2f);
            coveragePaint.setColor(0x99E2E8F0);
            canvas.drawCircle(centerX, centerY, radius, coveragePaint);
            drawMiniGlobeGrid(canvas, centerX, centerY, radius);
            for (int i = 0; i < targets.size(); i++) {
                CaptureTarget target = targets.get(i);
                float[] point = miniGlobePoint(target.yawDegrees, target.pitchDegrees, centerX, centerY, radius);
                if (!Float.isFinite(point[0]) || point[2] < -0.12f) {
                    continue;
                }
                float depth = Math.max(0.35f, 0.65f + point[2] * 0.35f);
                if (target.captured) {
                    coveragePaint.setColor(colorWithAlpha(0xFF34D399, depth));
                } else if (target.weak) {
                    coveragePaint.setColor(colorWithAlpha(0xFFF97316, depth));
                } else if (i == activeTargetIndex) {
                    coveragePaint.setColor(colorWithAlpha(0xFFFFFFFF, depth));
                } else {
                    coveragePaint.setColor(colorWithAlpha(0xFF64748B, depth));
                }
                coveragePaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(point[0], point[1], i == activeTargetIndex ? 6f : 4f, coveragePaint);
            }
        }

        private void drawMiniGlobeGrid(Canvas canvas, float centerX, float centerY, float radius) {
            coveragePaint.setStyle(Paint.Style.STROKE);
            coveragePaint.setStrokeWidth(1.2f);
            coveragePaint.setColor(0x55E2E8F0);
            canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, coveragePaint);
            canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, coveragePaint);
            canvas.drawCircle(centerX, centerY, radius * 0.55f, coveragePaint);
        }

        private float[] miniGlobePoint(float yawDegrees, float pitchDegrees, float centerX, float centerY, float radius) {
            double yaw = Math.toRadians(yawDegrees);
            double pitch = Math.toRadians(pitchDegrees);
            double currentYaw = Math.toRadians(frameState.ready ? frameState.yawDegrees : 0f);
            double currentPitch = Math.toRadians(frameState.ready ? frameState.pitchDegrees : 0f);
            float x = (float) (Math.cos(pitch) * Math.sin(yaw));
            float y = (float) Math.sin(pitch);
            float z = (float) (Math.cos(pitch) * Math.cos(yaw));
            float cy = (float) Math.cos(-currentYaw);
            float sy = (float) Math.sin(-currentYaw);
            float yawX = x * cy + z * sy;
            float yawZ = -x * sy + z * cy;
            float cp = (float) Math.cos(-currentPitch);
            float sp = (float) Math.sin(-currentPitch);
            float pitchY = y * cp - yawZ * sp;
            float pitchZ = y * sp + yawZ * cp;
            return new float[]{
                    centerX + yawX * radius * 0.86f,
                    centerY - pitchY * radius * 0.86f,
                    pitchZ
            };
        }

        private static int colorWithAlpha(int color, float alphaScale) {
            int alpha = Math.max(35, Math.min(255, Math.round(((color >>> 24) & 0xFF) * alphaScale)));
            return (color & 0x00FFFFFF) | (alpha << 24);
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
