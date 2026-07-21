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
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.util.List;

import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_IMAGE = 1001;
    private static final int REQUEST_CAMERA_PERMISSION = 1002;
    private static final int REQUEST_LOCATION_PERMISSION = 1003;
    private static final String PREFS = "spherify";
    private static final String PREF_SETUP_COMPLETE = "setupComplete";

    private GLProjectionView projectionView;
    private SpherifyLibrary library;
    private LibraryItem currentItem;
    private TextView statusText;
    private Button modeButton;
    private Button recenterButton;
    private AlertDialog activeLibraryDialog;
    private boolean startCaptureAfterCameraPermission;
    private boolean continueSetupAfterLocationPermission;
    private static final float GALLERY_DELETE_SWIPE_DISTANCE = 160f;
    private static final float GALLERY_DELETE_VERTICAL_SLOP = 90f;
    private static final int GALLERY_DELETE_HANDLE_WIDTH = 110;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            library = new SpherifyLibrary(this);
            try (InputStream input = getAssets().open("tinyplanet.jpg")) {
                library.ensureBundledMaster(input);
            }
            List<LibraryItem> items = library.list(LibraryItem.FILTER_ALL);
            currentItem = items.isEmpty() ? null : items.get(0);
        } catch (IOException e) {
            throw new IllegalStateException("could not initialize Spherify library", e);
        }

        projectionView = new GLProjectionView(this);
        loadCurrentItem();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF071018);

        TextView title = new TextView(this);
        title.setText("Spherify 0.2.2");
        title.setTextColor(0xFFF8FAFC);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(18, 14, 18, 8);
        root.addView(title, new LinearLayout.LayoutParams(
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

        recenterButton = makeButton("Recentre");
        recenterButton.setOnClickListener(v -> {
            projectionView.recentre();
            updateLabels();
            Toast.makeText(this, "Current alignment is now the centre", Toast.LENGTH_SHORT).show();
        });

        Button resetButton = makeButton("Reset");
        resetButton.setOnClickListener(v -> {
            projectionView.resetView();
            updateLabels();
        });

        Button exportButton = makeButton("Export");
        exportButton.setOnClickListener(v -> exportCurrentView());

        controls.addView(modeButton);
        controls.addView(recenterButton);
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

    @Override
    protected void onPause() {
        super.onPause();
        projectionView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        projectionView.onResume();
    }

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

    private void showReadinessSetup() {
        new AlertDialog.Builder(this)
                .setTitle("A few things make the sphere work")
                .setMessage("Camera captures draft frames, motion sensors guide future capture, location can prepare map-ready images, and cloud publishing can be connected later.")
                .setNegativeButton("Choose one by one", (dialog, which) -> showSensorSetup())
                .setPositiveButton("Grant essentials", (dialog, which) -> requestCameraSetup())
                .show();
    }

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

    private void showSensorSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Motion tracking")
                .setMessage(sensorSummary())
                .setNegativeButton("Continue anyway", (dialog, which) -> requestLocationSetup())
                .setPositiveButton("Location next", (dialog, which) -> requestLocationSetup())
                .show();
    }

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

    private void showLocalStorageSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Local library")
                .setMessage("Spherify saves masters, variants, thumbnails, and draft capture frames on this device first.")
                .setPositiveButton("Create local library", (dialog, which) -> showAccountSetup())
                .show();
    }

    private void showAccountSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Google setup")
                .setMessage("Cloud upload and direct map publishing are future integrations. Local save and Android share already work.")
                .setNegativeButton("Skip cloud setup", (dialog, which) -> showSetupComplete())
                .setPositiveButton("Continue", (dialog, which) -> showSetupComplete())
                .show();
    }

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

    private void markSetupComplete() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SETUP_COMPLETE, true)
                .apply();
    }

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

    private String sensorSummaryCompact() {
        SensorManager manager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (manager == null) {
            return "unavailable";
        }
        return sensorStatus(manager, Sensor.TYPE_GYROSCOPE) + " gyro, "
                + sensorStatus(manager, Sensor.TYPE_ROTATION_VECTOR) + " rotation";
    }

    private String sensorStatus(SensorManager manager, int type) {
        return manager.getDefaultSensor(type) == null ? "missing" : "ready";
    }

    private String permissionStatus(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
                ? "ready"
                : "skipped";
    }

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

    private void saveCurrentExport() {
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            openFlatViewer(currentItem);
            return;
        }
        try {
            ProjectionExport export = projectionView.exportProjection();
            String projection = projectionView.getMode() == GLProjectionView.Mode.TINY_PLANET
                    ? "tinyplanet"
                    : "sphere";
            currentItem = library.saveVariant(currentItem, export, projection);
            Toast.makeText(
                    this,
                    "Saved variant, thumbnail, and gallery export",
                    Toast.LENGTH_LONG).show();
            openFlatViewer(currentItem);
        } catch (IOException e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareCurrentExport() {
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            openFlatViewer(currentItem);
            return;
        }
        try {
            ProjectionExport export = projectionView.exportProjection();
            shareFile(export.imageFile);
        } catch (IOException e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

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

    private void chooseImportedImageType(Uri uri) {
        String[] labels = {"PhotoSphere", "Tiny Planet", "Flat"};
        String[] projections = {"sphere", "tinyplanet", "flat"};
        new AlertDialog.Builder(this)
                .setTitle("Image type")
                .setItems(labels, (dialog, which) -> finishImageImport(uri, projections[which]))
                .show();
    }

    private void finishImageImport(Uri uri, String projection) {
        try {
            currentItem = library.importImage(uri, projection);
            openCurrentItem();
            Toast.makeText(this, "Imported to local library", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

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

    private void importFromDeviceFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT_IMAGE);
    }

    private void showBrowseFilters() {
        String[] labels = {"All", "Masters", "Tiny Planets", "Imports", "Saved", "Draft Frames"};
        String[] filters = {
                LibraryItem.FILTER_ALL,
                LibraryItem.FILTER_MASTERS,
                LibraryItem.FILTER_TINY_PLANETS,
                LibraryItem.FILTER_IMPORTS,
                LibraryItem.FILTER_SAVED
        };

        new AlertDialog.Builder(this)
                .setTitle("Browse")
                .setItems(labels, (dialog, which) -> {
                    if (which == 5) {
                        showDraftFrames();
                    } else {
                        showLibrary(filters[which], labels[which]);
                    }
                })
                .show();
    }

    private void showDraftFrames() {
        List<File> drafts = library.listDraftFrames();
        if (drafts.isEmpty()) {
            Toast.makeText(this, "No draft frames captured yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[drafts.size()];
        for (int i = 0; i < drafts.size(); i++) {
            labels[i] = drafts.get(i).getName();
        }
        new AlertDialog.Builder(this)
                .setTitle("Draft Frames")
                .setItems(labels, (dialog, which) -> openExternalApp(drafts.get(which)))
                .show();
    }

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

    private void openGalleryItem(LibraryItem item) {
        if (activeLibraryDialog != null) {
            activeLibraryDialog.dismiss();
            activeLibraryDialog = null;
        }
        if (LibraryItem.TYPE_VARIANT.equals(item.type)) {
            showSavedOpenChoices(item);
        } else {
            currentItem = item;
            openCurrentItem();
        }
    }

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

    private void openFlatViewer(LibraryItem item) {
        Intent intent = new Intent(this, FlatImageActivity.class);
        intent.putExtra(FlatImageActivity.EXTRA_IMAGE_PATH, item.imagePath);
        startActivity(intent);
    }

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

    private void shareFile(File file) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, contentUriFor(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share export"));
    }

    private Uri contentUriFor(File file) {
        return FileProvider.getUriForFile(this, getPackageName() + ".files", file);
    }

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

    private void openCurrentItem() {
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            updateLabels();
            openFlatViewer(currentItem);
            return;
        }
        loadCurrentItem();
    }

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

    private void deleteCurrentItem() {
        if (currentItem == null) {
            Toast.makeText(this, "No selected library item", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Delete " + currentItem.title + " from the local Spherify library?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    try {
                        library.delete(currentItem);
                        List<LibraryItem> items = library.list(LibraryItem.FILTER_ALL);
                        currentItem = items.isEmpty() ? null : items.get(0);
                        loadCurrentItem();
                    } catch (IOException e) {
                        Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void deleteGalleryItem(LibraryItem item, BaseAdapter adapter, List<LibraryItem> visibleItems) {
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
                loadCurrentItem();
            }
            Toast.makeText(this, "Deleted " + item.title, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadCurrentItem() {
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
        Bitmap bitmap = BitmapFactory.decodeFile(currentItem.imagePath);
        if (bitmap == null) {
            Toast.makeText(this, "Could not load " + currentItem.title, Toast.LENGTH_LONG).show();
            return;
        }
        projectionView.setMode("tinyplanet".equals(currentItem.projection)
                ? GLProjectionView.Mode.TINY_PLANET
                : GLProjectionView.Mode.SPHERE);
        projectionView.setPanorama(bitmap, currentItem.projection);
        projectionView.resetView();
        if (modeButton != null) {
            updateLabels();
        }
    }

    private void updateLabels() {
        if (modeButton == null || recenterButton == null || statusText == null) {
            return;
        }
        if (currentItem != null && "flat".equals(currentItem.projection)) {
            modeButton.setText("Open Flat");
            recenterButton.setText("Recentre");
            statusText.setText(currentItem.title + "  |  Flat image");
            return;
        }
        modeButton.setText(projectionView.getMode() == GLProjectionView.Mode.SPHERE
                ? "Tiny Planet"
                : "PhotoSphere");
        recenterButton.setText("Recentre");
        String title = currentItem == null ? "No item selected" : currentItem.title;
        statusText.setText(title + "  |  " + projectionView.getStatusText());
    }

    private final class LibraryAdapter extends BaseAdapter {
        private final List<LibraryItem> items;

        LibraryAdapter(List<LibraryItem> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public LibraryItem getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

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
            rowParams.setMargins(0, 0, GALLERY_DELETE_HANDLE_WIDTH, 0);
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
                            if (!deleted[0] && dx <= -GALLERY_DELETE_SWIPE_DISTANCE) {
                                deleted[0] = true;
                                row.animate().translationX(-view.getWidth()).alpha(0.2f).setDuration(120).start();
                                deleteGalleryItem(item, LibraryAdapter.this, items);
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
