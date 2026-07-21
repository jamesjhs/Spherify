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
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
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
    private PreviewView previewView;
    private TextView statusText;
    private TextView sensorOverlayText;
    private Button sensorOverlayButton;
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

        Button captureButton = makeButton("Capture Frame");
        captureButton.setOnClickListener(v -> captureFrame());
        sensorOverlayButton = makeButton("Show Sensors");
        sensorOverlayButton.setOnClickListener(v -> toggleSensorOverlay());
        Button finishButton = makeButton("Finish");
        finishButton.setOnClickListener(v -> finish());
        controls.addView(captureButton);
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
                accelerometerReading = formatVector(event.values, 3);
                break;
            case Sensor.TYPE_GYROSCOPE:
                gyroscopeReading = formatVector(event.values, 3);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                magnetometerReading = formatVector(event.values, 3);
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                rotationVectorReading = formatVector(event.values, Math.min(event.values.length, 4));
                break;
            default:
                return;
        }
        updateSensorOverlay();
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
            updateSensorOverlay();
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
                + "Compass calibration: " + compassCalibrationStatus());
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
}
