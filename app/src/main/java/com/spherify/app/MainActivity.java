package com.spherify.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {
    private GLProjectionView projectionView;
    private TextView statusText;
    private Button modeButton;
    private Button invertButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bitmap panorama = loadBundledPanorama();
        projectionView = new GLProjectionView(this);
        projectionView.setPanorama(panorama);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF071018);

        TextView title = new TextView(this);
        title.setText("Spherify Phase 1");
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

        modeButton = makeButton("Tiny World");
        modeButton.setOnClickListener(v -> {
            projectionView.toggleMode();
            updateLabels();
        });

        invertButton = makeButton("Invert");
        invertButton.setOnClickListener(v -> {
            projectionView.toggleInverted();
            updateLabels();
        });

        Button resetButton = makeButton("Reset");
        resetButton.setOnClickListener(v -> {
            projectionView.resetView();
            updateLabels();
        });

        Button exportButton = makeButton("Export");
        exportButton.setOnClickListener(v -> exportCurrentView());

        controls.addView(modeButton);
        controls.addView(invertButton);
        controls.addView(resetButton);
        controls.addView(exportButton);
        root.addView(controls, new LinearLayout.LayoutParams(
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
    }

    private Bitmap loadBundledPanorama() {
        try (InputStream input = getAssets().open("tinyworld.jpg")) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) {
                throw new IllegalStateException("tinyworld.jpg could not be decoded");
            }
            return bitmap;
        } catch (IOException e) {
            throw new IllegalStateException("tinyworld.jpg is missing from assets", e);
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

    private void exportCurrentView() {
        try {
            ProjectionExport export = projectionView.exportProjection();
            Toast.makeText(
                    this,
                    "Saved " + export.imageFile.getName() + " and thumbnail",
                    Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateLabels() {
        modeButton.setText(projectionView.getMode() == GLProjectionView.Mode.SPHERE
                ? "Tiny World"
                : "PhotoSphere");
        invertButton.setText(projectionView.isInverted() ? "Normal" : "Invert");
        statusText.setText(projectionView.getStatusText());
    }
}
