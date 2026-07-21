/*
 * CaptureActivity.java
 *
 * Educational overview:
 * CaptureActivity is the Phase 3 capture shell. It shows a live CameraX preview,
 * captures draft JPEG frames, reads available motion/orientation sensors, and
 * optionally displays those sensor readings as an overlay on top of the preview.
 * It does not stitch a full Photo Sphere yet; instead it creates draft frame
 * files and draft metadata that later phases can use for guided capture,
 * alignment, and review.
 *
 * Data flow:
 * User taps Capture in MainActivity -> MainActivity opens this Activity after
 * camera permission -> CameraX streams preview pixels into PreviewView -> user
 * taps Capture Frame -> SpherifyLibrary creates a draft file -> ImageCapture
 * writes a JPEG -> readLocationSummary() optionally adds last-known location ->
 * SpherifyLibrary appends draft metadata to drafts.json. In parallel, Android's
 * SensorManager sends accelerometer/gyroscope/magnetometer/rotation-vector
 * events to onSensorChanged(), which refreshes the overlay TextView.
 *
 * External files/functions:
 * Uses CameraX classes from androidx.camera.* to bind preview and image capture.
 * Uses Android SensorManager for device motion/orientation readiness.
 * Uses SpherifyLibrary for local draft frame paths and metadata writes.
 * Uses LocationManager for optional coarse/fine last-known location summaries.
 *
 * Key variables:
 * previewView: CameraX preview surface shown inside the preview frame.
 * statusText: top status label for camera/capture progress.
 * sensorOverlayText/sensorOverlayButton: overlay UI and its toggle button.
 * imageCapture: CameraX still-image capture use case.
 * cameraExecutor: background thread for image capture callbacks.
 * library: local storage helper for draft files and metadata.
 * sensorManager and sensor fields: Android motion/orientation sensor handles.
 * sensorOverlayVisible: current overlay visibility state.
 * compassAccuracy: most recent magnetometer calibration status.
 * *Reading strings: latest formatted sensor values or "waiting".
 */
package com.spherify.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CaptureActivity extends ComponentActivity implements SensorEventListener {
    private static final long REQUIRED_COMPASS_HIGH_ACCURACY_MS = 2000L;
    private static final long REQUIRED_CALIBRATION_DURATION_MS = 8000L;
    private static final long REQUIRED_HEADING_STABILITY_MS = 1000L;
    private static final int REQUIRED_HEADING_BINS = 8;
    private static final float MAX_STABLE_HEADING_DELTA_DEGREES = 4f;
    private static final float REQUIRED_TILT_VARIATION = 0.55f;
    private static final int HEADING_BIN_COUNT = 12;
    private static final float SENSOR_LOW_PASS_ALPHA = 0.18f;
    private static final float HEADING_SMOOTHING_ALPHA = 0.14f;

    private PreviewView previewView;
    private TextView statusText;
    private TextView sensorOverlayText;
    private TextView compassStatusText;
    private CompassNeedleView compassNeedleView;
    private CalibrationProgressView calibrationProgressView;
    private Button captureButton;
    private Button sensorOverlayButton;
    private Button calibrationButton;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private SpherifyLibrary library;
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
    private boolean calibrationStarted;
    private long calibrationStartedAt;
    private long highAccuracyStartedAt;
    private long stableHeadingStartedAt;
    private float compassHeadingDegrees;
    private float rawCompassHeadingDegrees;
    private float lastCompassHeadingDegrees;
    private float minAccelX = Float.MAX_VALUE;
    private float maxAccelX = -Float.MAX_VALUE;
    private float minAccelY = Float.MAX_VALUE;
    private float maxAccelY = -Float.MAX_VALUE;
    private float minAccelZ = Float.MAX_VALUE;
    private float maxAccelZ = -Float.MAX_VALUE;

    /*
     * Function: onCreate
     * Arguments: savedInstanceState is Android lifecycle state; this Activity
     * currently builds fresh UI and does not restore custom state from it.
     * Calls: SpherifyLibrary constructor, SensorManager.getDefaultSensor(),
     * Android view constructors, toggle/capture click handlers, setContentView(),
     * requestApplyInsets(), and startCamera().
     * Flow: initialize storage and sensors, build a vertical layout with status,
     * camera preview, overlay, and controls, then start the CameraX preview.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        try {
            library = new SpherifyLibrary(this);
        } catch (IOException e) {
            throw new IllegalStateException("could not open capture library", e);
        }
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
        statusText.setText("Capture draft frames");
        root.addView(statusText);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);

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

        captureButton = makeButton("Capture Frame");
        captureButton.setOnClickListener(v -> captureFrame());
        sensorOverlayButton = makeButton("Show Sensors");
        sensorOverlayButton.setOnClickListener(v -> toggleSensorOverlay());
        calibrationButton = makeButton("Calibrate");
        calibrationButton.setOnClickListener(v -> startCompassCalibration());
        Button finishButton = makeButton("Finish");
        finishButton.setOnClickListener(v -> finish());
        controls.addView(captureButton);
        controls.addView(calibrationButton);
        controls.addView(sensorOverlayButton);
        controls.addView(finishButton);
        root.addView(controls);

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
        startCamera();
        updateCompassUi();
        showCompassCalibrationInstructions();
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
        super.onPause();
    }

    /*
     * Function: onDestroy
     * Arguments: none beyond Android lifecycle dispatch.
     * Calls: super.onDestroy() and ExecutorService.shutdown().
     * Flow: release the capture callback executor when the Activity is ending.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
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
        sensorOverlayButton.setText(sensorOverlayVisible ? "Hide Sensors" : "Show Sensors");
        updateSensorOverlay();
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
        if (sensorOverlayText == null) {
            return;
        }
        sensorOverlayText.setText("Sensor overlay\n"
                + "Accelerometer: " + sensorLine(accelerometer, accelerometerReading) + "\n"
                + "Gyroscope: " + sensorLine(gyroscope, gyroscopeReading) + "\n"
                + "Compass/magnetometer: " + sensorLine(magnetometer, magnetometerReading) + "\n"
                + "Rotation vector: " + sensorLine(rotationVector, rotationVectorReading) + "\n"
                + "Compass calibration: " + compassCalibrationStatus() + "\n"
                + "Calibration progress: " + compassCalibrationProgress());
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
     * Function: startCompassCalibration
     * Arguments: none; invoked by the Calibrate button.
     * Calls: resetCompassCalibration(), updateCompassUi(), and Toast.
     * Flow: begin the mandatory calibration session and reset the collected
     * coverage data so only the current deliberate user motion can unlock capture.
     */
    private void startCompassCalibration() {
        resetCompassCalibration();
        calibrationStarted = true;
        calibrationStartedAt = System.currentTimeMillis();
        Toast.makeText(
                this,
                "Move in figure-eights, rotate fully, and tilt through several angles.",
                Toast.LENGTH_LONG).show();
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
        SensorManager.getRotationMatrixFromVector(rotationVectorMatrix, values);
        SensorManager.getOrientation(rotationVectorMatrix, orientationValues);
        acceptCompassHeading((float) Math.toDegrees(orientationValues[0]));
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
        updateHeadingStability();
        recordHeadingCoverage();
        if (compassNeedleView != null) {
            compassNeedleView.setHeadingDegrees(compassHeadingDegrees);
        }
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
     * Calls: compassCalibrationProgress(), isCompassCalibrationReady(), and view
     * setters.
     * Flow: keep the north pointer label, calibration button, capture enabled
     * state, and status text synchronized with the calibration gate.
     */
    private void updateCompassUi() {
        boolean ready = isCompassCalibrationReady();
        if (compassStatusText != null) {
            compassStatusText.setText(String.format(
                    Locale.US,
                    "N %.0f deg\n%s",
                    compassHeadingDegrees,
                    ready ? "ready" : "calibrate"));
        }
        if (captureButton != null) {
            captureButton.setEnabled(ready);
        }
        if (calibrationButton != null) {
            calibrationButton.setText(ready ? "Recalibrate" : "Calibrate");
        }
        if (calibrationProgressView != null) {
            calibrationProgressView.setCalibrationState(
                    calibrationStarted,
                    ready,
                    timeProgress(),
                    accuracyProgress(),
                    stabilityProgress(),
                    headingProgress(),
                    tiltProgress());
        }
        if (statusText != null && imageCapture != null && !ready) {
            statusText.setText("Compass calibration required before capture. " + compassCalibrationProgress());
        }
    }

    /*
     * Function: isCompassCalibrationReady
     * Arguments: none.
     * Calls: headingBinCount(), tiltCoverageCount(), and System.currentTimeMillis().
     * Flow: enforce the capture gate by requiring a deliberate calibration
     * session with sensor availability, heading readings, high accuracy,
     * minimum duration, broad yaw coverage, and meaningful tilt coverage.
     */
    private boolean isCompassCalibrationReady() {
        if (!calibrationStarted || magnetometer == null || accelerometer == null || !hasHeadingReading) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean highAccuracyHeld = highAccuracyStartedAt > 0L
                && now - highAccuracyStartedAt >= REQUIRED_COMPASS_HIGH_ACCURACY_MS;
        boolean headingStable = stableHeadingStartedAt > 0L
                && now - stableHeadingStartedAt >= REQUIRED_HEADING_STABILITY_MS;
        boolean longEnough = now - calibrationStartedAt >= REQUIRED_CALIBRATION_DURATION_MS;
        return highAccuracyHeld
                && headingStable
                && longEnough
                && headingBinCount() >= REQUIRED_HEADING_BINS
                && tiltCoverageCount() >= 2;
    }

    /*
     * Function: compassCalibrationProgress
     * Arguments: none.
     * Calls: headingBinCount(), tiltCoverageCount(), and System.currentTimeMillis().
     * Flow: produce concise progress text explaining which parts of the mandatory
     * calibration gate are complete or still needed.
     */
    private String compassCalibrationProgress() {
        if (magnetometer == null || accelerometer == null) {
            return "Compass sensors missing.";
        }
        if (!calibrationStarted) {
            return "Tap Calibrate, then rotate and tilt the phone.";
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - calibrationStartedAt);
        return String.format(
                Locale.US,
                "%ds/%ds, accuracy %s, stable %s, yaw %d/%d, tilt %d/2",
                elapsed / 1000L,
                REQUIRED_CALIBRATION_DURATION_MS / 1000L,
                compassCalibrationStatus(),
                stableHeadingStartedAt > 0L ? "yes" : "no",
                headingBinCount(),
                REQUIRED_HEADING_BINS,
                tiltCoverageCount());
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
        return clamp01(headingBinCount() / (float) REQUIRED_HEADING_BINS);
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
     * Function: showCompassCalibrationInstructions
     * Arguments: none.
     * Calls: AlertDialog.Builder.
     * Flow: explain the stronger-than-panorama calibration requirement as soon as
     * capture opens, before the user tries to save a frame.
     */
    private void showCompassCalibrationInstructions() {
        new AlertDialog.Builder(this)
                .setTitle("Why calibrate the compass?")
                .setMessage("Spherify needs to know which way your phone is pointing.\n\n"
                        + "If the compass is wrong, photos may line up badly later and north may be in the wrong place.\n\n"
                        + "Calibration helps the phone find a steady, trustworthy north before you capture.")
                .setPositiveButton("Start calibration", (dialog, which) -> startCompassCalibration())
                .setNegativeButton("Close", null)
                .show();
    }

    /*
     * Function: startCamera
     * Arguments: none.
     * Calls: ContextCompat.checkSelfPermission(), ProcessCameraProvider.getInstance(),
     * Preview/ImageCapture builders, bindToLifecycle(), and statusText.setText().
     * Flow: verify camera permission, asynchronously acquire CameraX provider,
     * bind preview plus still capture to the back camera, and update status.
     */
    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            statusText.setText("Camera permission is needed for capture.");
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture);
                statusText.setText("Preview ready. Capture frames for a draft session.");
                updateCompassUi();
            } catch (Exception e) {
                statusText.setText("Camera failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /*
     * Function: captureFrame
     * Arguments: none; invoked by the Capture Frame button.
     * Calls: SpherifyLibrary.createDraftFrameFile(), ImageCapture.takePicture(),
     * readLocationSummary(), SpherifyLibrary.recordDraftFrame(), Toast, and
     * runOnUiThread().
     * Flow: ensure CameraX is ready, allocate an output JPEG, capture into it on
     * the background executor, then record metadata and notify the UI.
     */
    private void captureFrame() {
        if (!isCompassCalibrationReady()) {
            String message = "Capture locked until compass calibration completes. "
                    + compassCalibrationProgress();
            statusText.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        if (imageCapture == null) {
            Toast.makeText(this, "Camera is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }

        File outputFile;
        try {
            outputFile = library.createDraftFrameFile();
        } catch (IOException e) {
            Toast.makeText(this, "Draft file failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(outputFile).build();
        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            /*
             * Function: onImageSaved
             * Arguments: outputFileResults is CameraX's completion object; this
             * implementation uses the already-known outputFile path instead.
             * Calls: readLocationSummary(), library.recordDraftFrame(), runOnUiThread(),
             * statusText.setText(), and Toast.
             * Flow: after CameraX writes the JPEG, append draft metadata and
             * report success on the UI thread.
             */
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                String location = readLocationSummary();
                try {
                    library.recordDraftFrame(outputFile, location);
                    runOnUiThread(() -> {
                        statusText.setText("Saved draft frame: " + outputFile.getName());
                        Toast.makeText(CaptureActivity.this, "Draft frame saved", Toast.LENGTH_SHORT).show();
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> Toast.makeText(
                            CaptureActivity.this,
                            "Metadata failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show());
                }
            }

            /*
             * Function: onError
             * Arguments: exception describes the CameraX capture failure.
             * Calls: runOnUiThread() and Toast.makeText().
             * Flow: marshal the error back to the UI thread and show a readable
             * failure message.
             */
            @Override
            public void onError(ImageCaptureException exception) {
                runOnUiThread(() -> Toast.makeText(
                        CaptureActivity.this,
                        "Capture failed: " + exception.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
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
     * Class: CalibrationProgressView
     * Educational overview:
     * Draws a simple graphical checklist for the mandatory compass calibration.
     * Rather than showing only noisy headings and sensor numbers, it presents the
     * work as visible progress bars: time, accuracy, steadiness, rotate, and tilt.
     *
     * Data flow:
     * updateCompassUi() computes progress from filtered sensor data -> 
     * setCalibrationState() stores that progress -> invalidate() schedules
     * onDraw() -> onDraw() paints the current checklist on top of the preview.
     */
    private static final class CalibrationProgressView extends View {
        private static final String[] LABELS = {"Time", "Accuracy", "Steady", "Rotate", "Tilt"};
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
                    ? "Compass ready - capture unlocked"
                    : calibrationStarted
                    ? "Move in figure-eights, rotate, then tilt"
                    : "Tap Calibrate to begin";
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
         * calibration requirement.
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
     * Draws the always-visible north pointer above the CameraX preview. The view
     * receives the device's current compass heading and rotates a simple needle
     * so the tip points toward magnetic north relative to the phone's portrait
     * screen orientation.
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
         * north in degrees.
         * Calls: invalidate().
         * Flow: store the heading and schedule a redraw so the needle remains
         * live while the user moves.
         */
        void setHeadingDegrees(float headingDegrees) {
            this.headingDegrees = headingDegrees;
            invalidate();
        }

        /*
         * Function: onDraw
         * Arguments: canvas is Android's drawing target.
         * Calls: Canvas/Paint/Path drawing APIs.
         * Flow: draw a dark circular backing, rotate the canvas opposite the
         * heading, draw a red north needle, restore orientation, then label it N.
         */
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            float radius = Math.min(getWidth(), getHeight()) * 0.45f;
            canvas.drawCircle(centerX, centerY, radius, ringPaint);
            canvas.save();
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
