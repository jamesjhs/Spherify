package com.spherify.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;

/*
 * CaptureDebugExportActivity.java
 *
 * Educational overview:
 * Debug-build command endpoint for exporting the same capture-session CSV that
 * the production capture screen writes automatically in debug builds.
 *
 * CLI:
 * adb shell am start -n com.spherify.app/.CaptureDebugExportActivity
 * adb shell am start -n com.spherify.app/.CaptureDebugExportActivity --es sessionId SESSION
 *
 * Output:
 * /sdcard/Android/data/com.spherify.app/files/debug/capture-debug-*.csv
 */
public final class CaptureDebugExportActivity extends Activity {
    private static final String TAG = "SpherifyDebugExport";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            String sessionId = getIntent().getStringExtra("sessionId");
            boolean keepExisting = getIntent().getBooleanExtra("keepExisting", false);
            File output = CaptureDebugCsvWriter.export(this, sessionId, keepExisting);
            String message = "Capture debug CSV: " + output.getAbsolutePath();
            Log.i(TAG, message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Capture debug export failed", e);
            Toast.makeText(this, "Capture debug export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }
}
