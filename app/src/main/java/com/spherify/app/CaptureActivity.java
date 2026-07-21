package com.spherify.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
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

public class CaptureActivity extends ComponentActivity {
    private PreviewView previewView;
    private TextView statusText;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private SpherifyLibrary library;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        try {
            library = new SpherifyLibrary(this);
        } catch (IOException e) {
            throw new IllegalStateException("could not open capture library", e);
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
        root.addView(previewView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(10, 10, 10, 14);

        Button captureButton = makeButton("Capture Frame");
        captureButton.setOnClickListener(v -> captureFrame());
        Button finishButton = makeButton("Finish");
        finishButton.setOnClickListener(v -> finish());
        controls.addView(captureButton);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

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

            @Override
            public void onError(ImageCaptureException exception) {
                runOnUiThread(() -> Toast.makeText(
                        CaptureActivity.this,
                        "Capture failed: " + exception.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

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
