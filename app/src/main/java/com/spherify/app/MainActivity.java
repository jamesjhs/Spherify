/*
 * MainActivity.java
 *
 * Educational overview:
 * MainActivity is the app's home screen and coordinator. It owns the main
 * projection viewer, setup dialogs, library browsing, import/export choices,
 * permission flow, metadata dialogs, and deletion/rename actions. Rendering
 * itself lives in GLProjectionView, while file persistence lives in
 * SpherifyLibrary. MainActivity's job is to connect user actions to those
 * specialized classes.
 *
 * Data flow:
 * On launch, SpherifyLibrary loads metadata and ensures the bundled demo image.
 * MainActivity chooses a current LibraryItem, decodes its image into a Bitmap,
 * and passes it to GLProjectionView. User adjustments update GLProjectionView.
 * Export asks GLProjectionView for a ProjectionExport, then SpherifyLibrary
 * stores the variant. Import uses Android photo/file picker Uris, then
 * SpherifyLibrary copies the selected image into the local library. Capture
 * starts CaptureActivity after camera permission. Gallery actions mutate
 * LibraryItem records through SpherifyLibrary.
 *
 * External files/functions:
 * Reads app asset tinyplanet.jpg through AssetManager.
 * Opens Android photo picker or document picker for user imports.
 * Uses FileProvider to share/open local files outside the app.
 * Uses Android permissions for camera and location setup.
 * Uses Android SensorManager only for readiness summaries; live sensor reading
 * happens in CaptureActivity.
 *
 * Imports/dependencies:
 * Android Activity/Dialog/Intent/View widgets build the UI programmatically.
 * BitmapFactory decodes current images and gallery thumbnails.
 * MediaStore and Uri support photo import.
 * FileProvider converts local files to shareable content:// Uris.
 * GLProjectionView, SpherifyLibrary, LibraryItem, ProjectionExport, and
 * FlatImageActivity are the app-local collaborators.
 *
 * Key variables:
 * REQUEST_* constants: request codes for activity/permission callbacks.
 * PREFS/PREF_SETUP_COMPLETE: first-run setup completion storage.
 * STATE_* constants: keys for preserving selected item and projection state.
 * projectionView: OpenGL viewer for sphere/tiny-planet projections.
 * library: local persistence helper.
 * currentItem: selected LibraryItem currently shown or acted on.
 * statusText/modeButton/adjustButton: primary UI labels/controls.
 * activeLibraryDialog: currently displayed gallery dialog, if any.
 * startCaptureAfterCameraPermission/continueSetupAfterLocationPermission:
 * booleans that remember why a permission request was launched.
 * GALLERY_DELETE_* constants: swipe threshold tuning for gallery row deletion.
 */
package com.spherify.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.util.List;
import java.util.Locale;

import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_IMAGE = 1001;
    private static final int REQUEST_CAMERA_PERMISSION = 1002;
    private static final int REQUEST_LOCATION_PERMISSION = 1003;
    private static final String PREFS = "spherify";
    private static final String PREF_SETUP_COMPLETE = "setupComplete";
    private static final String STATE_CURRENT_ITEM_ID = "currentItemId";
    private static final String STATE_PROJECTION_PREFIX = "projection.";

    private GLProjectionView projectionView;
    private SpherifyLibrary library;
    private LibraryItem currentItem;
    private TextView statusText;
    private Button modeButton;
    private Button adjustButton;
    private Button exportButton;
    private AlertDialog activeLibraryDialog;
    private boolean startCaptureAfterCameraPermission;
    private boolean continueSetupAfterLocationPermission;
    private static final float GALLERY_DELETE_SWIPE_DISTANCE = 160f;
    private static final float GALLERY_DELETE_VERTICAL_SLOP = 90f;

    /*
     * Function: onCreate
     * Arguments: savedInstanceState is Android lifecycle state containing the
     * selected item id and projection controls after recreation.
     * Calls: SpherifyLibrary, AssetManager.open(), restoreCurrentItem(),
     * loadCurrentItem(), many Android view constructors, setup dialog functions,
     * and updateLabels().
     * Flow: initialize the library/demo asset, create the GL viewer, build the
     * whole home UI programmatically, wire button listeners, restore content,
     * apply window insets, update labels, and optionally start first-run setup.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            library = new SpherifyLibrary(this);
            try (InputStream input = getAssets().open("tinyplanet.jpg")) {
                library.ensureBundledMaster(input);
            }
            List<LibraryItem> items = library.list(LibraryItem.FILTER_ALL);
            currentItem = restoreCurrentItem(items, savedInstanceState);
        } catch (IOException e) {
            throw new IllegalStateException("could not initialize Spherify library", e);
        }

        projectionView = new GLProjectionView(this);
        loadCurrentItem(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF071018);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(18, 14, 18, 8);

        TextView title = new TextView(this);
        title.setText("Spherify");
        title.setTextColor(0xFFF8FAFC);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView version = new TextView(this);
        version.setText("0.5.0");
        version.setTextColor(0x8894A3B8);
        version.setTextSize(12);
        version.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        versionParams.setMargins(8, 3, 0, 0);
        titleRow.addView(version, versionParams);

        root.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setTextColor(0xFFCBD5E1);
        statusText.setTextSize(13);
        statusText.setPadding(18, 0, 18, 10);
        statusText.setOnLongClickListener(v -> {
            showCurrentItemActions();
            return true;
        });
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(projectionView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(10, 10, 10, 14);

        modeButton = makeButton("Tiny Planet");
        modeButton.setOnClickListener(v -> {
            if (currentItem != null && "flat".equals(currentItem.projection)) {
                openFlatViewer(currentItem);
                return;
            }
            projectionView.toggleMode();
            updateLabels();
        });

        adjustButton = makeButton("Adjust");
        adjustButton.setOnClickListener(v -> showAdjustDialog());

        Button resetButton = makeButton("Reset");
        resetButton.setOnClickListener(v -> {
            projectionView.resetView();
            updateLabels();
        });

        exportButton = makeButton("Export");
        exportButton.setOnClickListener(v -> exportCurrentView());

        controls.addView(modeButton);
        controls.addView(adjustButton);
        controls.addView(resetButton);
        controls.addView(exportButton);
        root.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout libraryControls = new LinearLayout(this);
        libraryControls.setOrientation(LinearLayout.HORIZONTAL);
        libraryControls.setGravity(Gravity.CENTER);
        libraryControls.setPadding(10, 0, 10, 14);

        Button captureButton = makeButton("Capture");
        captureButton.setOnClickListener(v -> startCaptureFlow());

        Button importButton = makeButton("Import");
        importButton.setOnClickListener(v -> importImage());

        Button browseButton = makeButton("Browse");
        browseButton.setOnClickListener(v -> showBrowseFilters());

        Button infoButton = makeButton("Info");
        infoButton.setOnClickListener(v -> showMetadata());

        libraryControls.addView(captureButton);
        libraryControls.addView(importButton);
        libraryControls.addView(browseButton);
        libraryControls.addView(infoButton);
        root.addView(libraryControls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
        updateLabels();
        showSetupFlowIfNeeded();
    }

    /*
     * Function: onSaveInstanceState
     * Arguments: outState is the Bundle Android will retain across recreation.
     * Calls: Bundle.putString() and GLProjectionView.saveProjectionState().
     * Flow: preserve which library item is selected and the current projection
     * orientation/zoom so rotation or process recreation keeps the view stable.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentItem != null) {
            outState.putString(STATE_CURRENT_ITEM_ID, currentItem.id);
        }
        if (projectionView != null) {
            projectionView.saveProjectionState(outState, STATE_PROJECTION_PREFIX);
        }
    }

    /*
     * Function: onRequestPermissionsResult
     * Arguments: requestCode identifies the permission request; permissions and
     * grantResults describe Android's response.
     * Calls: super, Toast, startActivity(), showSensorSetup(), and
     * showLocalStorageSetup().
     * Flow: continue the capture/setup branch that requested permission, or show
     * user feedback when camera/location permission is denied.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (startCaptureAfterCameraPermission) {
                    startActivity(new Intent(this, CaptureActivity.class));
                } else {
                    showSensorSetup();
                }
            } else {
                Toast.makeText(this, "Capture is unavailable without camera permission", Toast.LENGTH_LONG).show();
                if (!startCaptureAfterCameraPermission) {
                    showSensorSetup();
                }
            }
            startCaptureAfterCameraPermission = false;
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            Toast.makeText(this, "Location preference updated", Toast.LENGTH_SHORT).show();
            if (continueSetupAfterLocationPermission) {
                showLocalStorageSetup();
            }
            continueSetupAfterLocationPermission = false;
        }
    }

    /*
     * Function: onPause
     * Arguments: none beyond Android lifecycle dispatch.
     * Calls: super.onPause() and projectionView.onPause().
     * Flow: pause the GL surface when the Activity leaves foreground.
     */
    @Override
    protected void onPause() {
        super.onPause();
        projectionView.onPause();
    }

    /*
     * Function: onResume
     * Arguments: none beyond Android lifecycle dispatch.
     * Calls: super.onResume() and projectionView.onResume().
     * Flow: resume the GL surface when returning to the foreground.
     */
    @Override
    protected void onResume() {
        super.onResume();
        projectionView.onResume();
    }

    /*
     * Function: onActivityResult
     * Arguments: requestCode/resultCode identify picker completion; data holds
     * the selected image Uri.
     * Calls: super, Intent.getData(), Toast, and chooseImportedImageType().
     * Flow: receive photo/file picker output, validate a Uri exists, and ask the
     * user which projection type to assign before importing.
     */
    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show();
                return;
            }
            chooseImportedImageType(uri);
        }
    }

    /*
     * Function: makeButton
     * Arguments: text is the visible button label.
     * Calls: Button setters and LinearLayout.LayoutParams.
     * Flow: create an equal-width button with consistent text/capitalization for
     * the hand-built control rows.
     */
    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xFF0F172A);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        params.setMargins(5, 0, 5, 0);
        button.setLayoutParams(params);
        return button;
    }

    /*
     * Function: showSetupFlowIfNeeded
     * Arguments: none.
     * Calls: getSharedPreferences(), AlertDialog.Builder, markSetupComplete(),
     * and showReadinessSetup().
     * Flow: if first-run setup is incomplete, show the opening setup dialog and
     * route the user either into setup or directly into browsing.
     */
    private void showSetupFlowIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_SETUP_COMPLETE, false)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Create PhotoSpheres and Tiny Planets")
                .setMessage("Capture, reproject, save, and share 360 images from your phone.")
                .setNegativeButton("Browse without setup", (dialog, which) -> markSetupComplete())
                .setPositiveButton("Set up Spherify", (dialog, which) -> showReadinessSetup())
                .show();
    }

    /*
     * Function: showReadinessSetup
     * Arguments: none.
     * Calls: AlertDialog.Builder, showSensorSetup(), and requestCameraSetup().
     * Flow: explain the major capture capabilities and let the user either grant
     * essentials first or walk through setup one item at a time.
     */
    private void showReadinessSetup() {
        new AlertDialog.Builder(this)
                .setTitle("A few things make the sphere work")
                .setMessage("Camera captures draft frames, motion sensors guide future capture, location can prepare map-ready images, and cloud publishing can be connected later.")
                .setNegativeButton("Choose one by one", (dialog, which) -> showSensorSetup())
                .setPositiveButton("Grant essentials", (dialog, which) -> requestCameraSetup())
                .show();
    }

    /*
     * Function: requestCameraSetup
     * Arguments: none.
     * Calls: ContextCompat.checkSelfPermission(), AlertDialog.Builder,
     * requestPermissions(), and showSensorSetup().
     * Flow: if camera permission is already present, advance setup; otherwise
     * explain why camera helps and request permission if the user agrees.
     */
    private void requestCameraSetup() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            showSensorSetup();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Camera")
                .setMessage("Camera access lets Spherify capture draft frames for PhotoSphere experiments.")
                .setNegativeButton("Skip for now", (dialog, which) -> showSensorSetup())
                .setPositiveButton("Allow camera", (dialog, which) -> {
                    startCaptureAfterCameraPermission = false;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                })
                .show();
    }

    /*
     * Function: showSensorSetup
     * Arguments: none.
     * Calls: sensorSummary(), AlertDialog.Builder, and requestLocationSetup().
     * Flow: display hardware readiness for motion sensors before continuing to
     * location setup.
     */
    private void showSensorSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Motion tracking")
                .setMessage(sensorSummary())
                .setNegativeButton("Continue anyway", (dialog, which) -> requestLocationSetup())
                .setPositiveButton("Location next", (dialog, which) -> requestLocationSetup())
                .show();
    }

    /*
     * Function: requestLocationSetup
     * Arguments: none.
     * Calls: ContextCompat.checkSelfPermission(), AlertDialog.Builder,
     * requestPermissions(), and showLocalStorageSetup().
     * Flow: request optional fine location permission for future map-ready
     * metadata, or continue without it.
     */
    private void requestLocationSetup() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            showLocalStorageSetup();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Location")
                .setMessage("Location can tag draft captures for future map-ready exports. You can still create images without it.")
                .setNegativeButton("Use without location", (dialog, which) -> showLocalStorageSetup())
                .setPositiveButton("Allow location", (dialog, which) -> {
                    continueSetupAfterLocationPermission = true;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
                })
                .show();
    }

    /*
     * Function: showLocalStorageSetup
     * Arguments: none.
     * Calls: AlertDialog.Builder and showAccountSetup().
     * Flow: explain that the app uses local device storage first, then continue
     * to the future cloud-account step.
     */
    private void showLocalStorageSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Local library")
                .setMessage("Spherify saves masters, variants, thumbnails, and draft capture frames on this device first.")
                .setPositiveButton("Create local library", (dialog, which) -> showAccountSetup())
                .show();
    }

    /*
     * Function: showAccountSetup
     * Arguments: none.
     * Calls: AlertDialog.Builder and showSetupComplete().
     * Flow: describe future cloud publishing and finish setup regardless of
     * whether the user chooses Continue or Skip.
     */
    private void showAccountSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Google setup")
                .setMessage("Cloud upload and direct map publishing are future integrations. Local save and Android share already work.")
                .setNegativeButton("Skip cloud setup", (dialog, which) -> showSetupComplete())
                .setPositiveButton("Continue", (dialog, which) -> showSetupComplete())
                .show();
    }

    /*
     * Function: showSetupComplete
     * Arguments: none.
     * Calls: markSetupComplete(), permissionStatus(), sensorSummaryCompact(),
     * AlertDialog.Builder, startCaptureFlow(), and showBrowseFilters().
     * Flow: persist setup completion, summarize readiness, then let the user
     * start capture or open the library.
     */
    private void showSetupComplete() {
        markSetupComplete();
        new AlertDialog.Builder(this)
                .setTitle("Ready to make a sphere")
                .setMessage("Camera: " + permissionStatus(Manifest.permission.CAMERA)
                        + "\nMotion: " + sensorSummaryCompact()
                        + "\nLocal library: ready"
                        + "\nLocation: " + permissionStatus(Manifest.permission.ACCESS_FINE_LOCATION))
                .setPositiveButton("Capture PhotoSphere", (dialog, which) -> startCaptureFlow())
                .setNegativeButton("Open Library", (dialog, which) -> showBrowseFilters())
                .show();
    }

    /*
     * Function: markSetupComplete
     * Arguments: none.
     * Calls: getSharedPreferences().edit().putBoolean().apply().
     * Flow: store the setup-complete flag so first-run dialogs do not repeat.
     */
    private void markSetupComplete() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SETUP_COMPLETE, true)
                .apply();
    }

    /*
     * Function: sensorSummary
     * Arguments: none.
     * Calls: getSystemService(SENSOR_SERVICE) and sensorStatus().
     * Flow: query core motion/orientation sensor availability for the setup
     * dialog.
     */
    private String sensorSummary() {
        SensorManager manager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (manager == null) {
            return "Sensor manager unavailable.";
        }
        return "Gyroscope: " + sensorStatus(manager, Sensor.TYPE_GYROSCOPE)
                + "\nAccelerometer: " + sensorStatus(manager, Sensor.TYPE_ACCELEROMETER)
                + "\nCompass: " + sensorStatus(manager, Sensor.TYPE_MAGNETIC_FIELD)
                + "\nRotation vector: " + sensorStatus(manager, Sensor.TYPE_ROTATION_VECTOR);
    }

    /*
     * Function: sensorSummaryCompact
     * Arguments: none.
     * Calls: getSystemService(SENSOR_SERVICE) and sensorStatus().
     * Flow: produce the short readiness summary used in the final setup dialog.
     */
    private String sensorSummaryCompact() {
        SensorManager manager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (manager == null) {
            return "unavailable";
        }
        return sensorStatus(manager, Sensor.TYPE_GYROSCOPE) + " gyro, "
                + sensorStatus(manager, Sensor.TYPE_ROTATION_VECTOR) + " rotation";
    }

    /*
     * Function: sensorStatus
     * Arguments: manager is Android's SensorManager; type is a Sensor.TYPE_* id.
     * Calls: SensorManager.getDefaultSensor().
     * Flow: return "ready" when the device has the requested sensor, otherwise
     * "missing".
     */
    private String sensorStatus(SensorManager manager, int type) {
        return manager.getDefaultSensor(type) == null ? "missing" : "ready";
    }

    /*
     * Function: permissionStatus
     * Arguments: permission is an Android manifest permission string.
     * Calls: ContextCompat.checkSelfPermission().
     * Flow: convert permission grant state into compact setup status text.
     */
    private String permissionStatus(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
                ? "ready"
                : "skipped";
    }

    /*
     * Function: startCaptureFlow
     * Arguments: none.
     * Calls: ContextCompat.checkSelfPermission(), startActivity(),
     * AlertDialog.Builder, and requestPermissions().
     * Flow: launch CaptureActivity immediately when camera permission exists, or
     * request camera permission and remember to launch after approval.
     */
    private void startCaptureFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, CaptureActivity.class));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Allow camera")
                .setMessage("Capture needs camera access. Imported images and saved variants still work without it.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Allow", (dialog, which) -> {
                    startCaptureAfterCameraPermission = true;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                })
                .show();
    }

    /*
     * Function: exportCurrentView
     * Arguments: none.
     * Calls: openFlatViewer(), Toast, AlertDialog.Builder, shareCurrentExport(),
     * and saveCurrentExport().
     * Flow: flat images are opened rather than reprojected; projected images ask
     * the user whether to share or save the current GLProjectionView render.
     */
    private void exportCurrentView() {
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            openFlatViewer(currentItem);
            Toast.makeText(this, "Flat images open in the flat viewer", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Export")
                .setItems(new String[]{"Share", "Save"}, (dialog, which) -> {
                    if (which == 0) {
                        shareCurrentExport();
                    } else {
                        saveCurrentExport();
                    }
                })
                .show();
    }

    /*
     * Function: showAdjustDialog
     * Arguments: none.
     * Calls: openFlatViewer(), addAdjustmentControl(), projectionView getters and
     * setters, AlertDialog.Builder, resetView(), and updateLabels().
     * Flow: build a dialog of SeekBar controls that directly update projection
     * camera distance, image rotation, and horizon offset.
     */
    private void showAdjustDialog() {
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            openFlatViewer(currentItem);
            return;
        }

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(28, 14, 28, 0);

        addAdjustmentControl(
                controls,
                "Camera distance",
                35,
                250,
                Math.round(projectionView.getCameraDistancePercent()),
                "%",
                value -> projectionView.setCameraDistancePercent(value));
        addAdjustmentControl(
                controls,
                "Image rotation",
                -180,
                180,
                Math.round(projectionView.getImageRotationDegrees()),
                "deg",
                value -> projectionView.setImageRotationDegrees(value));

        addAdjustmentControl(
                controls,
                "Horizon reference",
                -75,
                75,
                Math.round(projectionView.getEyeElevationDegrees()),
                "deg",
                value -> projectionView.setEyeElevationDegrees(value));

        new AlertDialog.Builder(this)
                .setTitle("Adjust")
                .setView(controls)
                .setNegativeButton("Reset", (dialog, which) -> {
                    projectionView.resetView();
                    updateLabels();
                })
                .setPositiveButton("Done", (dialog, which) -> updateLabels())
                .show();
    }

    /*
     * Function: addAdjustmentControl
     * Arguments: parent receives the UI; label names the control; min/max bound
     * values; current initializes the SeekBar; suffix labels units; handler
     * receives value changes.
     * Calls: TextView/SeekBar constructors, clamp(), handler.onValueChanged(),
     * updateLabels(), and LinearLayout.addView().
     * Flow: create a label and SeekBar pair, translate SeekBar progress into the
     * requested value range, update the label, and invoke the provided callback.
     */
    private void addAdjustmentControl(
            LinearLayout parent,
            String label,
            int min,
            int max,
            int current,
            String suffix,
            AdjustmentHandler handler) {
        TextView valueLabel = new TextView(this);
        valueLabel.setTextColor(0xFF0F172A);
        valueLabel.setTextSize(14);
        parent.addView(valueLabel);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max - min);
        seekBar.setProgress(clamp(current, min, max) - min);
        parent.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Runnable updateValueLabel = () -> valueLabel.setText(String.format(
                Locale.US,
                "%s: %d %s",
                label,
                min + seekBar.getProgress(),
                suffix));
        updateValueLabel.run();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /*
             * Function: onProgressChanged
             * Arguments: seekBar is the changed control; progress is zero-based
             * within min/max; fromUser identifies user-initiated changes.
             * Calls: TextView.setText(), AdjustmentHandler.onValueChanged(), and
             * updateLabels().
             * Flow: map progress back to a real value, update visible text, pass
             * that value into GLProjectionView through the handler, and refresh
             * the main status label.
             */
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = min + progress;
                valueLabel.setText(String.format(Locale.US, "%s: %d %s", label, value, suffix));
                handler.onValueChanged(value);
                updateLabels();
            }

            /*
             * Function: onStartTrackingTouch
             * Arguments: seekBar is the touched control.
             * Calls: no external functions.
             * Flow: required by the SeekBar listener interface; no custom work is
             * needed when dragging starts.
             */
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            /*
             * Function: onStopTrackingTouch
             * Arguments: seekBar is the released control.
             * Calls: no external functions.
             * Flow: required by the SeekBar listener interface; updates already
             * happen during progress changes.
             */
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    /*
     * Function: saveCurrentExport
     * Arguments: none.
     * Calls: projectionView.exportProjection() on a background thread, library.saveVariant(),
     * openFlatViewer(), and Toast.
     * Flow: disable controls, render the current projection off the UI thread, persist
     * the variant, re-enable controls, open the flat viewer, and report success/failure.
     */
    private void saveCurrentExport() {
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            openFlatViewer(currentItem);
            return;
        }
        final LibraryItem sourceItem = currentItem;
        final String exportProjection = projectionView.getMode() == GLProjectionView.Mode.TINY_PLANET
                ? "tinyplanet" : "sphere";
        setExportControlsEnabled(false);
        new Thread(() -> {
            try {
                ProjectionExport export = projectionView.exportProjection();
                LibraryItem saved = library.saveVariant(sourceItem, export, exportProjection);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    setExportControlsEnabled(true);
                    currentItem = saved;
                    Toast.makeText(
                            MainActivity.this,
                            "Saved variant, thumbnail, and gallery export",
                            Toast.LENGTH_LONG).show();
                    openFlatViewer(currentItem);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    setExportControlsEnabled(true);
                    Toast.makeText(MainActivity.this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /*
     * Function: shareCurrentExport
     * Arguments: none.
     * Calls: projectionView.exportProjection() on a background thread, shareFile(), and Toast.
     * Flow: disable controls, render the current projection off the UI thread, re-enable
     * controls, launch Android's share sheet for the generated image.
     */
    private void shareCurrentExport() {
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            openFlatViewer(currentItem);
            return;
        }
        setExportControlsEnabled(false);
        new Thread(() -> {
            try {
                ProjectionExport export = projectionView.exportProjection();
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    setExportControlsEnabled(true);
                    shareFile(export.imageFile);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    setExportControlsEnabled(true);
                    Toast.makeText(MainActivity.this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /*
     * Function: setExportControlsEnabled
     * Arguments: enabled controls whether export/interaction is allowed.
     * Calls: Button.setEnabled() and GLProjectionView.setEnabled().
     * Flow: disable the export button and touch interaction on the projection view
     * while a background render is in progress, and re-enable when it completes.
     */
    private void setExportControlsEnabled(boolean enabled) {
        if (exportButton != null) exportButton.setEnabled(enabled);
        if (projectionView != null) projectionView.setEnabled(enabled);
    }

    /*
     * Function: importImage
     * Arguments: none.
     * Calls: AlertDialog.Builder, importFromPhotos(), and importFromDeviceFiles().
     * Flow: ask which Android picker source to use before requesting an image.
     */
    private void importImage() {
        new AlertDialog.Builder(this)
                .setTitle("Import")
                .setItems(new String[]{"Photos", "Device files"}, (dialog, which) -> {
                    if (which == 0) {
                        importFromPhotos();
                    } else {
                        importFromDeviceFiles();
                    }
                })
                .show();
    }

    /*
     * Function: chooseImportedImageType
     * Arguments: uri is the selected image from Android's picker.
     * Calls: AlertDialog.Builder and finishImageImport().
     * Flow: ask the user how to interpret the imported image and pass the
     * selected projection label into the library import step.
     */
    private void chooseImportedImageType(Uri uri) {
        String[] labels = {"Photo Sphere", "Tiny Planet", "Flat"};
        String[] projections = {"sphere", "tinyplanet", "flat"};
        new AlertDialog.Builder(this)
                .setTitle("Image type")
                .setItems(labels, (dialog, which) -> finishImageImport(uri, projections[which]))
                .show();
    }

    /*
     * Function: finishImageImport
     * Arguments: uri points to the selected image; projection is the chosen
     * interpretation string.
     * Calls: library.importImage(), openCurrentItem(), and Toast.
     * Flow: copy the selected image into the local library, make it current, and
     * open it in the appropriate viewer.
     */
    private void finishImageImport(Uri uri, String projection) {
        try {
            currentItem = library.importImage(uri, projection);
            openCurrentItem();
            Toast.makeText(this, "Imported to local library", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /*
     * Function: importFromPhotos
     * Arguments: none.
     * Calls: Intent constructors, MediaStore.ACTION_PICK_IMAGES on Android 13+,
     * ACTION_PICK fallback on older versions, and startActivityForResult().
     * Flow: launch the best available Android photo picker for image selection.
     */
    private void importFromPhotos() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
        } else {
            intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
        }
        startActivityForResult(intent, REQUEST_IMPORT_IMAGE);
    }

    /*
     * Function: importFromDeviceFiles
     * Arguments: none.
     * Calls: ACTION_OPEN_DOCUMENT Intent setup and startActivityForResult().
     * Flow: launch Android's document picker for image files and request read
     * access to the chosen Uri.
     */
    private void importFromDeviceFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT_IMAGE);
    }

    /*
     * Function: showBrowseFilters
     * Arguments: none.
     * Calls: AlertDialog.Builder, showDraftFrames(), and showLibrary().
     * Flow: present gallery filter choices and route the selection to either
     * draft frame browsing or LibraryItem browsing.
     */
    private void showBrowseFilters() {
        String[] labels = {"All", "Masters", "Tiny Planets", "Imports", "Drafts", "Saved", "Draft Frames"};
        String[] filters = {
                LibraryItem.FILTER_ALL,
                LibraryItem.FILTER_MASTERS,
                LibraryItem.FILTER_TINY_PLANETS,
                LibraryItem.FILTER_IMPORTS,
                LibraryItem.FILTER_DRAFTS,
                LibraryItem.FILTER_SAVED
        };

        new AlertDialog.Builder(this)
                .setTitle("Browse")
                .setItems(labels, (dialog, which) -> {
                    if (which == 6) {
                        showDraftFrames();
                    } else {
                        showLibrary(filters[which], labels[which]);
                    }
                })
                .show();
    }

    /*
     * Function: showDraftFrames
     * Arguments: none.
     * Calls: library.listDraftFrames(), Toast, DraftFrameAdapter, ListView, and
     * AlertDialog.Builder.
     * Flow: list CameraX draft JPEGs captured by CaptureActivity, render them
     * with the same tap/open and left-swipe deletion pattern used by gallery
     * images, offer a confirmed bulk-remove action, and keep a dialog reference
     * for empty-list dismissal.
     */
    private void showDraftFrames() {
        List<File> drafts = library.listDraftFrames();
        if (drafts.isEmpty()) {
            Toast.makeText(this, "No draft frames captured yet", Toast.LENGTH_SHORT).show();
            return;
        }

        ListView listView = new ListView(this);
        DraftFrameAdapter adapter = new DraftFrameAdapter(drafts);
        listView.setAdapter(adapter);
        listView.setDividerHeight(1);

        activeLibraryDialog = new AlertDialog.Builder(this)
                .setTitle("Draft Frames - swipe left to delete")
                .setView(listView)
                .setNegativeButton("Close", null)
                .setPositiveButton("Remove All Drafts", null)
                .show();
        activeLibraryDialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> confirmDeleteAllDraftFrames());
        Toast.makeText(this, "Swipe left on a draft frame to delete it", Toast.LENGTH_SHORT).show();
    }

    /*
     * Function: confirmDeleteAllDraftFrames
     * Arguments: none.
     * Calls: library.listDraftFrames(), AlertDialog.Builder, and
     * deleteAllDraftFrames().
     * Flow: ask for explicit confirmation before bulk-deleting raw draft frames,
     * including the current count so the user understands the scope.
     */
    private void confirmDeleteAllDraftFrames() {
        int count = library.listDraftFrames().size();
        if (count == 0) {
            Toast.makeText(this, "No draft frames to delete", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Remove all draft frames?")
                .setMessage("This will delete " + count
                        + " draft frame" + (count == 1 ? "" : "s")
                        + " from app storage. This action cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove All", (dialog, which) -> deleteAllDraftFrames())
                .show();
    }

    /*
     * Function: deleteAllDraftFrames
     * Arguments: none.
     * Calls: library.deleteAllDraftFrames(), AlertDialog.dismiss(), and Toast.
     * Flow: clear every draft JPEG and draft metadata row, close the draft list
     * because it is now empty, and show a concise completion message.
     */
    private void deleteAllDraftFrames() {
        try {
            library.deleteAllDraftFrames();
            if (activeLibraryDialog != null) {
                activeLibraryDialog.dismiss();
                activeLibraryDialog = null;
            }
            Toast.makeText(this, "All draft frames removed", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /*
     * Function: showLibrary
     * Arguments: filter is a LibraryItem.FILTER_* value; title labels the dialog.
     * Calls: library.list(), ListView/LibraryAdapter setup, AlertDialog.Builder,
     * and Toast.
     * Flow: fetch filtered library records, render them through LibraryAdapter,
     * keep a reference to the dialog for dismissal, and teach swipe deletion.
     */
    private void showLibrary(String filter, String title) {
        List<LibraryItem> items = library.list(filter);
        if (items.isEmpty()) {
            Toast.makeText(this, "No library items for this filter", Toast.LENGTH_SHORT).show();
            return;
        }

        ListView listView = new ListView(this);
        LibraryAdapter adapter = new LibraryAdapter(items);
        listView.setAdapter(adapter);
        listView.setDividerHeight(1);

        activeLibraryDialog = new AlertDialog.Builder(this)
                .setTitle(title + " - swipe left to delete")
                .setView(listView)
                .show();
        Toast.makeText(this, "Swipe left on a gallery entry to delete it", Toast.LENGTH_SHORT).show();
    }

    /*
     * Function: openGalleryItem
     * Arguments: item is the selected row's LibraryItem.
     * Calls: AlertDialog.dismiss(), showSavedOpenChoices(), and openCurrentItem().
     * Flow: close the gallery dialog, then either ask how to open saved variants
     * or select/open master images directly.
     */
    private void openGalleryItem(LibraryItem item) {
        if (activeLibraryDialog != null) {
            activeLibraryDialog.dismiss();
            activeLibraryDialog = null;
        }
        if (LibraryItem.TYPE_DRAFT_SESSION.equals(item.type)) {
            showDraftSession(item);
        } else if (LibraryItem.TYPE_VARIANT.equals(item.type)) {
            showSavedOpenChoices(item);
        } else {
            currentItem = item;
            openCurrentItem();
        }
    }

    /*
     * Function: showSavedOpenChoices
     * Arguments: item is a saved variant LibraryItem.
     * Calls: AlertDialog.Builder, openFlatViewer(), openExternalApp(), and
     * LibraryItem.imageFile().
     * Flow: variants are already rendered images, so offer either the in-app flat
     * viewer or an external Android image app.
     */
    private void showSavedOpenChoices(LibraryItem item) {
        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(new String[]{"Open flat", "Open external app"}, (dialog, which) -> {
                    if (which == 0) {
                        openFlatViewer(item);
                    } else {
                        openExternalApp(item.imageFile());
                    }
                })
                .show();
    }

    /*
     * Function: openFlatViewer
     * Arguments: item supplies the imagePath extra.
     * Calls: Intent constructor, Intent.putExtra(), and startActivity().
     * Flow: launch FlatImageActivity with the selected image path.
     */
    private void openFlatViewer(LibraryItem item) {
        Intent intent = new Intent(this, FlatImageActivity.class);
        intent.putExtra(FlatImageActivity.EXTRA_IMAGE_PATH, item.imagePath);
        startActivity(intent);
    }

    /*
     * Function: openFlatViewer
     * Arguments: file is a local image file, such as a draft frame.
     * Calls: Intent constructor, Intent.putExtra(), and startActivity().
     * Flow: launch FlatImageActivity directly so draft frames always open inside
     * Spherify instead of being handed to an external Android image app.
     */
    private void openFlatViewer(File file) {
        Intent intent = new Intent(this, FlatImageActivity.class);
        intent.putExtra(FlatImageActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        startActivity(intent);
    }

    /*
     * Function: showDraftSession
     * Arguments: item is a first-class draft capture LibraryItem.
     * Calls: library.listDraftFrames(), DraftFrameAdapter, AlertDialog, and
     * stitchDraftSession().
     * Flow: open only the frames that belong to the selected draft session, so
     * draft captures behave like browseable library records instead of loose
     * files, with a Phase 5 action to generate a first equirectangular master.
     */
    private void showDraftSession(LibraryItem item) {
        List<File> drafts = library.listDraftFrames(item.id);
        if (drafts.isEmpty()) {
            Toast.makeText(this, "This draft has no remaining frames", Toast.LENGTH_SHORT).show();
            return;
        }

        ListView listView = new ListView(this);
        DraftFrameAdapter adapter = new DraftFrameAdapter(drafts);
        listView.setAdapter(adapter);
        listView.setDividerHeight(1);

        activeLibraryDialog = new AlertDialog.Builder(this)
                .setTitle(item.title + " - swipe left to delete")
                .setView(listView)
                .setNegativeButton("Close", null)
                .setPositiveButton("Spherify", null)
                .show();
        activeLibraryDialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> stitchDraftSession(item));
    }

    /*
     * Function: stitchDraftSession
     * Arguments: item is the selected draft-session LibraryItem.
     * Calls: SpherifyLibrary.createMasterFromDraftSession() on a background
     * thread, loadCurrentItem(), and showStitchSummary().
     * Flow: close the draft browser, run the experimental Phase 5 stitch without
     * blocking UI input, then make the generated equirectangular master current.
     */
    private void stitchDraftSession(LibraryItem item) {
        if (activeLibraryDialog != null) {
            activeLibraryDialog.dismiss();
            activeLibraryDialog = null;
        }
        Toast.makeText(this, "Generating Phase 5 master...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                StitchMasterResult result = library.createMasterFromDraftSession(item);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    currentItem = result.item;
                    loadCurrentItem(null);
                    showStitchSummary(result);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(MainActivity.this, "Spherify failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /*
     * Function: showStitchSummary
     * Arguments: result contains the new master and Phase 5 quality summary.
     * Calls: AlertDialog.Builder and StringBuilder.
     * Flow: tell the user what the first-pass pipeline rendered and identify
     * known weak areas instead of treating every generated master as map-ready.
     */
    private void showStitchSummary(StitchMasterResult result) {
        StringBuilder message = new StringBuilder();
        message.append("Created ").append(result.item.title)
                .append("\nFrames used: ").append(result.stitch.renderedFrames)
                .append("\nEstimated coverage: ").append(result.stitch.coveragePercent).append("%")
                .append("\nMissing exposure references: ").append(result.stitch.missingExposureFrames);
        if (!result.stitch.warnings.isEmpty()) {
            message.append("\n\nWarnings:");
            for (String warning : result.stitch.warnings) {
                message.append("\n- ").append(warning);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Phase 5 master created")
                .setMessage(message.toString())
                .setPositiveButton("Open", null)
                .show();
    }

    /*
     * Function: openExternalApp
     * Arguments: file is the local image to view externally.
     * Calls: contentUriFor(), Intent.createChooser(), startActivity(), and Toast.
     * Flow: convert a private file path to a content Uri with read permission and
     * let Android route it to an installed image viewer.
     */
    private void openExternalApp(File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(contentUriFor(file), "image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "Open with"));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this image", Toast.LENGTH_LONG).show();
        }
    }

    /*
     * Function: shareFile
     * Arguments: file is the generated PNG to share.
     * Calls: contentUriFor(), Intent.createChooser(), and startActivity().
     * Flow: create an ACTION_SEND Intent with a FileProvider Uri so other apps
     * can receive the export without direct filesystem access.
     */
    private void shareFile(File file) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, contentUriFor(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share export"));
    }

    /*
     * Function: contentUriFor
     * Arguments: file is an app-private file.
     * Calls: FileProvider.getUriForFile() and getPackageName().
     * Flow: translate a File into the content:// Uri authorized by AndroidManifest
     * FileProvider configuration.
     */
    private Uri contentUriFor(File file) {
        return FileProvider.getUriForFile(this, getPackageName() + ".files", file);
    }

    /*
     * Function: clamp
     * Arguments: value is bounded between minimum and maximum.
     * Calls: Math.max() and Math.min().
     * Flow: keep integer UI values inside SeekBar-supported ranges.
     */
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /*
     * Function: restoreCurrentItem
     * Arguments: items is the current library list; savedInstanceState may carry
     * STATE_CURRENT_ITEM_ID.
     * Calls: Bundle.getString() and LibraryItem id comparisons.
     * Flow: restore the previously selected item when possible, otherwise choose
     * the first available library item.
     */
    private LibraryItem restoreCurrentItem(List<LibraryItem> items, Bundle savedInstanceState) {
        if (items.isEmpty()) {
            return null;
        }
        if (savedInstanceState != null) {
            String savedItemId = savedInstanceState.getString(STATE_CURRENT_ITEM_ID);
            if (savedItemId != null) {
                for (LibraryItem item : items) {
                    if (savedItemId.equals(item.id)) {
                        return item;
                    }
                }
            }
        }
        return items.get(0);
    }

    /*
     * Function: showMetadata
     * Arguments: none.
     * Calls: library.describe(), AlertDialog.Builder, and Toast.
     * Flow: show metadata for the current item or explain that nothing is
     * selected.
     */
    private void showMetadata() {
        if (currentItem == null) {
            Toast.makeText(this, "No selected library item", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Metadata")
                .setMessage(library.describe(currentItem))
                .setPositiveButton("Close", null)
                .show();
    }

    /*
     * Function: openCurrentItem
     * Arguments: none.
     * Calls: updateLabels(), openFlatViewer(), and loadCurrentItem().
     * Flow: route flat images to FlatImageActivity; otherwise load the current
     * item into GLProjectionView.
     */
    private void openCurrentItem() {
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            updateLabels();
            openFlatViewer(currentItem);
            return;
        }
        loadCurrentItem(null);
    }

    /*
     * Function: showCurrentItemActions
     * Arguments: none.
     * Calls: AlertDialog.Builder, renameCurrentItem(), deleteCurrentItem(), and
     * Toast.
     * Flow: present long-press actions for the current status item.
     */
    private void showCurrentItemActions() {
        if (currentItem == null) {
            Toast.makeText(this, "No selected library item", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(currentItem.title)
                .setItems(new String[]{"Rename", "Delete"}, (dialog, which) -> {
                    if (which == 0) {
                        renameCurrentItem();
                    } else {
                        deleteCurrentItem();
                    }
                })
                .show();
    }

    /*
     * Function: renameCurrentItem
     * Arguments: none.
     * Calls: EditText setup, AlertDialog.Builder, library.rename(), updateLabels(),
     * and Toast.
     * Flow: collect a new non-empty title for currentItem, persist it through the
     * library, and refresh UI labels.
     */
    private void renameCurrentItem() {
        if (currentItem == null) {
            Toast.makeText(this, "No selected library item", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentItem.title);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        String title = input.getText().toString().trim();
                        if (!title.isEmpty()) {
                            library.rename(currentItem, title);
                            updateLabels();
                        }
                    } catch (IOException e) {
                        Toast.makeText(this, "Rename failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    /*
     * Function: deleteCurrentItem
     * Arguments: none.
     * Calls: AlertDialog.Builder, library.delete(), library.list(), loadCurrentItem(),
     * and Toast.
     * Flow: confirm deletion, remove the current item and files, select a fallback
     * library item, and reload the viewer.
     */
    private void deleteCurrentItem() {
        if (currentItem == null) {
            Toast.makeText(this, "No selected library item", Toast.LENGTH_SHORT).show();
            return;
        }
        LibraryItem item = currentItem;
        new AlertDialog.Builder(this)
                .setTitle("Delete image")
                .setMessage("Are you sure you want to delete? This action cannot be undone.\n\n" + item.title)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    try {
                        library.delete(item);
                        List<LibraryItem> items = library.list(LibraryItem.FILTER_ALL);
                        currentItem = items.isEmpty() ? null : items.get(0);
                        loadCurrentItem(null);
                    } catch (IOException e) {
                        Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    /*
     * Function: deleteGalleryItem overload
     * Arguments: item is the target; adapter/visibleItems back the gallery list.
     * Calls: the full deleteGalleryItem overload with no swiped row.
     * Flow: convenience wrapper for delete actions that do not need row reset
     * animation context.
     */
    private void deleteGalleryItem(LibraryItem item, BaseAdapter adapter, List<LibraryItem> visibleItems) {
        deleteGalleryItem(item, adapter, visibleItems, null);
    }

    /*
     * Function: deleteDraftFrame
     * Arguments: draft is the frame file; adapter/visibleDrafts back the list;
     * swipedRow is the animated row to reset if deletion is canceled.
     * Calls: AlertDialog.Builder, resetSwipedRow(), and performDraftDelete().
     * Flow: mirror normal gallery deletion for draft frames, including a clear
     * confirmation before removing local capture data.
     */
    private void deleteDraftFrame(
            File draft,
            BaseAdapter adapter,
            List<File> visibleDrafts,
            View swipedRow) {
        new AlertDialog.Builder(this)
                .setTitle("Delete draft frame")
                .setMessage("Are you sure you want to delete? This action cannot be undone.\n\n" + draft.getName())
                .setNegativeButton("Cancel", (dialog, which) -> resetSwipedRow(swipedRow))
                .setOnCancelListener(dialog -> resetSwipedRow(swipedRow))
                .setPositiveButton("Delete", (dialog, which) -> performDraftDelete(draft, adapter, visibleDrafts))
                .show();
    }

    /*
     * Function: deleteGalleryItem
     * Arguments: item is the target; adapter refreshes the ListView; visibleItems
     * is the mutable list shown by the adapter; swipedRow is the animated row
     * view to reset if deletion is canceled.
     * Calls: AlertDialog.Builder, resetSwipedRow(), and performGalleryDelete().
     * Flow: ask for confirmation after a swipe/delete action, reset UI on cancel,
     * and perform the actual library deletion on confirmation.
     */
    private void deleteGalleryItem(
            LibraryItem item,
            BaseAdapter adapter,
            List<LibraryItem> visibleItems,
            View swipedRow) {
        new AlertDialog.Builder(this)
                .setTitle("Delete image")
                .setMessage("Are you sure you want to delete? This action cannot be undone.\n\n" + item.title)
                .setNegativeButton("Cancel", (dialog, which) -> resetSwipedRow(swipedRow))
                .setOnCancelListener(dialog -> resetSwipedRow(swipedRow))
                .setPositiveButton("Delete", (dialog, which) -> performGalleryDelete(item, adapter, visibleItems))
                .show();
    }

    /*
     * Function: resetSwipedRow
     * Arguments: row is the gallery row view that had been translated left.
     * Calls: View.animate() chain.
     * Flow: if a delete is canceled, animate the row back to normal position and
     * opacity.
     */
    private void resetSwipedRow(View row) {
        if (row != null) {
            row.animate().translationX(0f).alpha(1f).setDuration(120).start();
        }
    }

    /*
     * Function: performGalleryDelete
     * Arguments: item is the record to remove; adapter and visibleItems keep the
     * displayed ListView in sync.
     * Calls: library.delete(), adapter.notifyDataSetChanged(), library.list(),
     * loadCurrentItem(), AlertDialog.dismiss(), and Toast.
     * Flow: delete files/metadata, remove the row from the visible list, close an
     * empty gallery, update the main selected item if needed, and show feedback.
     */
    private void performGalleryDelete(LibraryItem item, BaseAdapter adapter, List<LibraryItem> visibleItems) {
        try {
            boolean deletingCurrent = currentItem != null && currentItem.id.equals(item.id);
            library.delete(item);
            visibleItems.remove(item);
            adapter.notifyDataSetChanged();
            if (visibleItems.isEmpty() && activeLibraryDialog != null) {
                activeLibraryDialog.dismiss();
                activeLibraryDialog = null;
            }
            if (deletingCurrent) {
                List<LibraryItem> allItems = library.list(LibraryItem.FILTER_ALL);
                currentItem = allItems.isEmpty() ? null : allItems.get(0);
                loadCurrentItem(null);
            }
            Toast.makeText(this, "Deleted " + item.title, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /*
     * Function: performDraftDelete
     * Arguments: draft is the file to remove; adapter and visibleDrafts keep the
     * Draft Frames ListView synchronized.
     * Calls: library.deleteDraftFrame(), adapter.notifyDataSetChanged(),
     * AlertDialog.dismiss(), and Toast.
     * Flow: remove the JPEG plus matching draft metadata, delete the row from
     * the visible list, close the dialog if no drafts remain, and show feedback.
     */
    private void performDraftDelete(File draft, BaseAdapter adapter, List<File> visibleDrafts) {
        try {
            library.deleteDraftFrame(draft);
            visibleDrafts.remove(draft);
            adapter.notifyDataSetChanged();
            if (visibleDrafts.isEmpty() && activeLibraryDialog != null) {
                activeLibraryDialog.dismiss();
                activeLibraryDialog = null;
            }
            Toast.makeText(this, "Deleted " + draft.getName(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /*
     * Function: loadCurrentItem overload
     * Arguments: none.
     * Calls: loadCurrentItem(null).
     * Flow: convenience entry point when there is no saved projection state to
     * restore.
     */
    private void loadCurrentItem() {
        loadCurrentItem(null);
    }

    /*
     * Function: loadCurrentItem
     * Arguments: savedProjectionState optionally contains GLProjectionView state.
     * Calls: updateLabels(), openFlatViewer(), BitmapFactory.decodeFile() on a
     * background thread, SpherifyLibrary.applyExifRotation(), projectionView.setMode(),
     * projectionView.setPanorama(), resetView(), restoreProjectionState(), and Toast.
     * Flow: validate a current item, route flat images away from GL, decode source
     * pixels on a background thread with EXIF rotation correction, load them into
     * GLProjectionView on the UI thread, restore or reset projection controls, and
     * refresh labels.
     */
    private void loadCurrentItem(Bundle savedProjectionState) {
        if (currentItem == null) {
            updateLabels();
            Toast.makeText(this, "The local library is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("flat".equals(currentItem.projection)) {
            updateLabels();
            openFlatViewer(currentItem);
            return;
        }
        final LibraryItem item = currentItem;
        final Bundle projectionState = savedProjectionState;
        if (statusText != null) {
            statusText.setText(item.title + "  |  Loading\u2026");
        }
        new Thread(() -> {
            Bitmap bmp = BitmapFactory.decodeFile(item.imagePath);
            if (bmp != null) {
                bmp = SpherifyLibrary.applyExifRotation(bmp, item.imagePath);
            }
            final Bitmap finalBitmap = bmp;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    if (finalBitmap != null) finalBitmap.recycle();
                    return;
                }
                if (finalBitmap == null) {
                    Toast.makeText(MainActivity.this, "Could not load " + item.title, Toast.LENGTH_LONG).show();
                    return;
                }
                projectionView.setMode("tinyplanet".equals(item.projection)
                        ? GLProjectionView.Mode.TINY_PLANET
                        : GLProjectionView.Mode.SPHERE);
                projectionView.setPanorama(finalBitmap, item.projection);
                if (projectionState == null) {
                    projectionView.resetView();
                } else {
                    projectionView.restoreProjectionState(projectionState, STATE_PROJECTION_PREFIX);
                }
                if (modeButton != null) {
                    updateLabels();
                }
            });
        }).start();
    }

    /*
     * Function: updateLabels
     * Arguments: none.
     * Calls: Button.setText(), TextView.setText(), projectionView.getMode(), and
     * projectionView.getStatusText().
     * Flow: keep mode/adjust/status UI synchronized with currentItem and the
     * active projection mode.
     */
    private void updateLabels() {
        if (modeButton == null || adjustButton == null || statusText == null) {
            return;
        }
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            modeButton.setText("Open Flat");
            adjustButton.setText("Adjust");
            statusText.setText(currentItem.title + "  |  Flat image");
            return;
        }
        modeButton.setText(projectionView.getMode() == GLProjectionView.Mode.SPHERE
                ? "Tiny Planet"
                : "Photo Sphere");
        adjustButton.setText("Adjust");
        String title = currentItem == null ? "No item selected" : currentItem.title;
        statusText.setText(title + "  |  " + projectionView.getStatusText());
    }

    /*
     * Interface: AdjustmentHandler
     * Function: onValueChanged
     * Arguments: value is the integer selected in an adjustment SeekBar.
     * Calls: implemented by lambdas in showAdjustDialog().
     * Flow: lets addAdjustmentControl remain generic while callers decide which
     * GLProjectionView setter receives the value.
     */
    private interface AdjustmentHandler {
        /*
         * Function: onValueChanged
         * Arguments: value is the integer selected in the adjustment control.
         * Calls: caller-provided GLProjectionView setter implementations.
         * Flow: addAdjustmentControl invokes this whenever a SeekBar changes so
         * the generic control can update a specific projection property.
         */
        void onValueChanged(int value);
    }

    /*
     * Class: DraftFrameAdapter
     * Educational overview:
     * Adapter that renders raw CameraX draft JPEG files in the Draft Frames
     * browser. Drafts are not full LibraryItem records yet, but the user should
     * still be able to inspect and delete them with the same list behavior as
     * normal gallery images.
     *
     * Data flow:
     * showDraftFrames() provides a mutable file list -> getView() renders each
     * row -> tap opens the JPEG in FlatImageActivity -> left swipe calls deleteDraftFrame()
     * -> performDraftDelete() mutates the same list and notifies this adapter.
     *
     * Key variables:
     * drafts: mutable list currently visible in the Draft Frames dialog.
     */
    private final class DraftFrameAdapter extends BaseAdapter {
        private final List<File> drafts;

        /*
         * Function: DraftFrameAdapter constructor
         * Arguments: drafts is the visible draft-file list.
         * Calls: no external functions.
         * Flow: store the list reference so row rendering and deletion stay in
         * sync with showDraftFrames().
         */
        DraftFrameAdapter(List<File> drafts) {
            this.drafts = drafts;
        }

        /*
         * Function: getCount
         * Arguments: none.
         * Calls: List.size().
         * Flow: tell ListView how many draft rows to request.
         */
        @Override
        public int getCount() {
            return drafts.size();
        }

        /*
         * Function: getItem
         * Arguments: position is the row index requested by ListView.
         * Calls: List.get().
         * Flow: return the draft File for a row.
         */
        @Override
        public File getItem(int position) {
            return drafts.get(position);
        }

        /*
         * Function: getItemId
         * Arguments: position is the row index requested by ListView.
         * Calls: no external functions.
         * Flow: use the row position as a simple id for this in-memory draft
         * dialog list.
         */
        @Override
        public long getItemId(int position) {
            return position;
        }

        /*
         * Function: getView
         * Arguments: position identifies the row; convertView is unused because
         * this custom row is rebuilt; parent is the ListView container.
         * Calls: getItem(), BitmapFactory.decodeFile(), openFlatViewer(),
         * deleteDraftFrame(), and animation APIs.
         * Flow: build a thumbnail/name/detail row layered over a red delete
         * background, open on tap, reveal delete on left swipe, and confirm once
         * the swipe passes the threshold.
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            File draft = getItem(position);
            FrameLayout container = new FrameLayout(MainActivity.this);
            container.setBackgroundColor(0xFFB91C1C);
            container.setMinimumHeight(92);

            TextView deleteLabel = new TextView(MainActivity.this);
            deleteLabel.setText("Delete");
            deleteLabel.setTextColor(0xFFFFFFFF);
            deleteLabel.setTextSize(15);
            deleteLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            deleteLabel.setPadding(18, 0, 24, 0);
            deleteLabel.setAlpha(0f);
            container.addView(deleteLabel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(18, 10, 18, 10);
            row.setBackgroundColor(0xFFFFFFFF);
            row.setMinimumHeight(92);

            ImageView thumbnail = new ImageView(MainActivity.this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bitmap = BitmapFactory.decodeFile(draft.getAbsolutePath());
            if (bitmap != null) {
                thumbnail.setImageBitmap(bitmap);
            }
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(72, 72);
            imageParams.setMargins(0, 0, 16, 0);
            row.addView(thumbnail, imageParams);

            LinearLayout labels = new LinearLayout(MainActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(MainActivity.this);
            title.setText(draft.getName());
            title.setTextColor(0xFF0F172A);
            title.setTextSize(16);

            TextView detail = new TextView(MainActivity.this);
            detail.setText("Draft frame  |  " + draft.length() / 1024L + " KB");
            detail.setTextColor(0xFF475569);
            detail.setTextSize(12);

            labels.addView(title);
            labels.addView(detail);
            row.addView(labels, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            container.addView(row, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));

            float[] downX = new float[1];
            float[] downY = new float[1];
            boolean[] swiping = new boolean[1];
            boolean[] deleted = new boolean[1];
            container.setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX[0] = event.getRawX();
                        downY[0] = event.getRawY();
                        swiping[0] = false;
                        deleted[0] = false;
                        deleteLabel.setAlpha(0f);
                        row.setAlpha(1f);
                        row.setTranslationX(0f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX[0];
                        float dy = event.getRawY() - downY[0];
                        if (dx < 0 && Math.abs(dy) < GALLERY_DELETE_VERTICAL_SLOP) {
                            swiping[0] = true;
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                            row.setTranslationX(Math.max(dx, -GALLERY_DELETE_SWIPE_DISTANCE));
                            deleteLabel.setAlpha(Math.min(1f, Math.max(0f, (-dx - 24f) / 80f)));
                            if (!deleted[0] && dx <= -GALLERY_DELETE_SWIPE_DISTANCE) {
                                deleted[0] = true;
                                row.animate().translationX(-GALLERY_DELETE_SWIPE_DISTANCE).alpha(0.9f).setDuration(120).start();
                                deleteDraftFrame(draft, DraftFrameAdapter.this, drafts, row);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float totalDx = event.getRawX() - downX[0];
                        float totalDy = event.getRawY() - downY[0];
                        if (!swiping[0] && !deleted[0]
                                && Math.abs(totalDx) < 24f
                                && Math.abs(totalDy) < 24f) {
                            openFlatViewer(draft);
                        } else if (!deleted[0]) {
                            row.animate().translationX(0f).alpha(1f).setDuration(120).start();
                            deleteLabel.animate().alpha(0f).setDuration(120).start();
                        }
                        return true;
                    default:
                        return true;
                }
            });
            return container;
        }
    }

    /*
     * Class: LibraryAdapter
     * Educational overview:
     * Adapter that converts LibraryItem records into ListView rows for the Browse
     * dialog. Each row contains a thumbnail, title, metadata text, tap-to-open
     * behavior, and custom left-swipe deletion behavior.
     *
     * Data flow:
     * showLibrary() provides a mutable item list -> getView() renders each row ->
     * tap opens via openGalleryItem() -> left swipe calls deleteGalleryItem() ->
     * performGalleryDelete() mutates the same list and notifies this adapter.
     *
     * Key variables:
     * items: mutable list currently visible in the Browse dialog.
     */
    private final class LibraryAdapter extends BaseAdapter {
        private final List<LibraryItem> items;

        /*
         * Function: LibraryAdapter constructor
         * Arguments: items is the visible gallery list.
         * Calls: no external functions.
         * Flow: store the list reference so row rendering and deletion stay in
         * sync with showLibrary().
         */
        LibraryAdapter(List<LibraryItem> items) {
            this.items = items;
        }

        /*
         * Function: getCount
         * Arguments: none.
         * Calls: List.size().
         * Flow: tell ListView how many rows to request.
         */
        @Override
        public int getCount() {
            return items.size();
        }

        /*
         * Function: getItem
         * Arguments: position is the row index requested by ListView.
         * Calls: List.get().
         * Flow: return the LibraryItem for a row.
         */
        @Override
        public LibraryItem getItem(int position) {
            return items.get(position);
        }

        /*
         * Function: getItemId
         * Arguments: position is the row index requested by ListView.
         * Calls: no external functions.
         * Flow: use the row position as a simple stable-enough id for this small
         * in-memory dialog list.
         */
        @Override
        public long getItemId(int position) {
            return position;
        }

        /*
         * Function: getView
         * Arguments: position identifies the row; convertView is unused because
         * this custom row is rebuilt; parent is the ListView container.
         * Calls: getItem(), BitmapFactory.decodeFile(), Android view constructors,
         * openGalleryItem(), deleteGalleryItem(), and animation APIs.
         * Flow: build a thumbnail/title/detail row layered over a red delete
         * background, track touch deltas, open on tap, reveal delete on left swipe,
         * and request confirmation once the swipe passes the threshold.
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LibraryItem item = getItem(position);
            FrameLayout container = new FrameLayout(MainActivity.this);
            container.setBackgroundColor(0xFFB91C1C);
            container.setMinimumHeight(92);

            TextView deleteLabel = new TextView(MainActivity.this);
            deleteLabel.setText("Delete");
            deleteLabel.setTextColor(0xFFFFFFFF);
            deleteLabel.setTextSize(15);
            deleteLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            deleteLabel.setPadding(18, 0, 24, 0);
            deleteLabel.setAlpha(0f);
            container.addView(deleteLabel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(18, 10, 18, 10);
            row.setBackgroundColor(0xFFFFFFFF);
            row.setMinimumHeight(92);

            ImageView thumbnail = new ImageView(MainActivity.this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bitmap = BitmapFactory.decodeFile(item.thumbnailPath);
            if (bitmap != null) {
                thumbnail.setImageBitmap(bitmap);
            }
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(72, 72);
            imageParams.setMargins(0, 0, 16, 0);
            row.addView(thumbnail, imageParams);

            LinearLayout labels = new LinearLayout(MainActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(MainActivity.this);
            title.setText(item.title);
            title.setTextColor(0xFF0F172A);
            title.setTextSize(16);

            TextView detail = new TextView(MainActivity.this);
            detail.setText(item.type + "  |  " + item.source + "  |  " + item.projection);
            detail.setTextColor(0xFF475569);
            detail.setTextSize(12);

            labels.addView(title);
            labels.addView(detail);
            row.addView(labels, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            container.addView(row, rowParams);

            float[] downX = new float[1];
            float[] downY = new float[1];
            boolean[] swiping = new boolean[1];
            boolean[] deleted = new boolean[1];
            container.setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX[0] = event.getRawX();
                        downY[0] = event.getRawY();
                        swiping[0] = false;
                        deleted[0] = false;
                        deleteLabel.setAlpha(0f);
                        row.setAlpha(1f);
                        row.setTranslationX(0f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX[0];
                        float dy = event.getRawY() - downY[0];
                        if (dx < 0 && Math.abs(dy) < GALLERY_DELETE_VERTICAL_SLOP) {
                            swiping[0] = true;
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                            row.setTranslationX(Math.max(dx, -GALLERY_DELETE_SWIPE_DISTANCE));
                            deleteLabel.setAlpha(Math.min(1f, Math.max(0f, (-dx - 24f) / 80f)));
                            if (!deleted[0] && dx <= -GALLERY_DELETE_SWIPE_DISTANCE) {
                                deleted[0] = true;
                                row.animate().translationX(-GALLERY_DELETE_SWIPE_DISTANCE).alpha(0.9f).setDuration(120).start();
                                deleteGalleryItem(item, LibraryAdapter.this, items, row);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float totalDx = event.getRawX() - downX[0];
                        float totalDy = event.getRawY() - downY[0];
                        if (!swiping[0] && !deleted[0]
                                && Math.abs(totalDx) < 24f
                                && Math.abs(totalDy) < 24f) {
                            openGalleryItem(item);
                        } else if (!deleted[0]) {
                            row.animate().translationX(0f).alpha(1f).setDuration(120).start();
                            deleteLabel.animate().alpha(0f).setDuration(120).start();
                        }
                        return true;
                    default:
                        return true;
                }
            });
            return container;
        }
    }
}
