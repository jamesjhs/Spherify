package com.spherify.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/*
 * CaptureDebugCsvWriter.java
 *
 * Educational overview:
 * Debug builds use this helper as the single CSV writer for capture-session
 * metadata. The CLI activity and the automatic capture hook both call here so
 * field diagnosis always uses the same column set and target-planner replay.
 */
final class CaptureDebugCsvWriter {
    private static final String TAG = "SpherifyDebugCsv";

    private CaptureDebugCsvWriter() {
    }

    static File export(Context context, String sessionId, boolean keepExisting) throws IOException, JSONException {
        SpherifyLibrary library = new SpherifyLibrary(context);
        CaptureSessionRecord session = sessionId == null || sessionId.isEmpty()
                ? library.latestCaptureSession()
                : library.findCaptureSession(sessionId);
        if (session == null) {
            throw new IOException("no capture session found");
        }
        File outputDir = new File(context.getExternalFilesDir(null), "debug");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("could not create debug output directory");
        }
        if (!keepExisting) {
            deleteOldDebugCsv(outputDir);
        }
        File outputFile = new File(outputDir, "capture-debug-" + stamp() + ".csv");
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            output.write(buildCsv(session).getBytes(StandardCharsets.UTF_8));
        }
        return outputFile;
    }

    private static String buildCsv(CaptureSessionRecord session) throws JSONException {
        StringBuilder csv = new StringBuilder();
        appendHeader(csv);
        ArrayList<CaptureFrameRecord> acceptedPrefix = new ArrayList<>();
        for (int i = 0; i < session.frames.size(); i++) {
            CaptureFrameRecord frame = session.frames.get(i);
            if (frame.role == CaptureFrameRole.ACCEPTED) {
                acceptedPrefix.add(frame);
            }
            CaptureTarget nextTarget = CaptureTargetPlanner.nextTargetForAcceptedFrames(acceptedPrefix);
            appendRow(csv, session, frame, nextTarget, i);
        }
        return csv.toString();
    }

    private static void appendHeader(StringBuilder csv) {
        String[] columns = {
                "debug_format",
                "export_generated_epoch_ns",
                "row_index",
                "session_id",
                "session_status",
                "session_created_at_ms",
                "session_updated_at_ms",
                "frame_id",
                "frame_role",
                "frame_file_path",
                "frame_timestamp_ms",
                "camera_sensor_timestamp_ns",
                "target_yaw_deg",
                "target_pitch_deg",
                "captured_yaw_deg",
                "captured_pitch_deg",
                "captured_roll_deg",
                "captured_pose_available",
                "target_to_capture_yaw_delta_deg",
                "target_to_capture_pitch_delta_deg",
                "intrinsics_available",
                "intrinsics_fx_px",
                "intrinsics_fy_px",
                "intrinsics_cx_px",
                "intrinsics_cy_px",
                "intrinsics_width_px",
                "intrinsics_height_px",
                "lens_focal_length_mm",
                "sensor_physical_width_mm",
                "sensor_physical_height_mm",
                "lens_aperture",
                "sensor_exposure_time_ns",
                "sensor_frame_duration_ns",
                "sensor_sensitivity_iso",
                "ae_state",
                "awb_state",
                "af_state",
                "ae_mode",
                "awb_mode",
                "af_mode",
                "ae_exposure_compensation",
                "blur_score",
                "exposure_score",
                "texture_score",
                "opencv_ransac_result",
                "inlier_count",
                "residual_score",
                "confidence",
                "parallax_risk_hint",
                "rejection_reason",
                "next_target_complete",
                "next_target_yaw_deg",
                "next_target_pitch_deg",
                "next_target_phase",
                "current_to_next_yaw_delta_deg",
                "current_to_next_pitch_delta_deg",
                "device_id",
                "camera_id",
                "location_summary",
                "capture_profile",
                "raw_intrinsics_json",
                "raw_camera_properties_json",
                "raw_analysis_json",
                "raw_frame_json"
        };
        appendCells(csv, columns);
    }

    private static void appendRow(
            StringBuilder csv,
            CaptureSessionRecord session,
            CaptureFrameRecord frame,
            CaptureTarget nextTarget,
            int rowIndex) throws JSONException {
        JSONObject exposure = frame.rawFacts.exposure;
        JSONObject intrinsics = frame.rawFacts.intrinsics;
        JSONObject analysis = frame.analysisFacts.toJson();
        String[] cells = {
                "spherify.capture.timeline.v1",
                Long.toString(System.currentTimeMillis() * 1_000_000L),
                Integer.toString(rowIndex),
                session.id,
                session.status.storageValue,
                Long.toString(session.createdAt),
                Long.toString(session.updatedAt),
                frame.id,
                frame.role.storageValue,
                frame.rawFacts.filePath,
                Long.toString(frame.rawFacts.timestampMillis),
                exposureString(exposure, "sensorTimestampNs"),
                Integer.toString(frame.rawFacts.targetYawDegrees),
                Integer.toString(frame.rawFacts.targetPitchDegrees),
                Float.toString(frame.rawFacts.capturedYawDegrees),
                Float.toString(frame.rawFacts.capturedPitchDegrees),
                Float.toString(frame.rawFacts.capturedRollDegrees),
                Boolean.toString(frame.rawFacts.capturedPoseAvailable),
                Float.toString(signedDelta(frame.rawFacts.capturedYawDegrees, frame.rawFacts.targetYawDegrees)),
                Float.toString(frame.rawFacts.capturedPitchDegrees - frame.rawFacts.targetPitchDegrees),
                Boolean.toString(intrinsics.optBoolean("available", false)),
                numberString(intrinsics, "focalLengthXPixels"),
                numberString(intrinsics, "focalLengthYPixels"),
                numberString(intrinsics, "principalPointXPixels"),
                numberString(intrinsics, "principalPointYPixels"),
                integerString(intrinsics, "width"),
                integerString(intrinsics, "height"),
                numberString(exposure, "lensFocalLengthMm"),
                numberString(exposure, "sensorPhysicalWidthMm"),
                numberString(exposure, "sensorPhysicalHeightMm"),
                numberString(exposure, "lensAperture"),
                exposureString(exposure, "sensorExposureTimeNs"),
                exposureString(exposure, "sensorFrameDurationNs"),
                integerString(exposure, "sensorSensitivityIso"),
                integerString(exposure, "aeState"),
                integerString(exposure, "awbState"),
                integerString(exposure, "afState"),
                integerString(exposure, "aeMode"),
                integerString(exposure, "awbMode"),
                integerString(exposure, "afMode"),
                integerString(exposure, "aeExposureCompensation"),
                Double.toString(frame.analysisFacts.blurScore),
                Double.toString(frame.analysisFacts.exposureScore),
                Double.toString(frame.analysisFacts.textureScore),
                frame.analysisFacts.opencvRansacResult,
                Integer.toString(frame.analysisFacts.inlierCount),
                Double.toString(frame.analysisFacts.residualScore),
                Double.toString(frame.analysisFacts.confidence),
                frame.analysisFacts.parallaxRiskHint,
                frame.analysisFacts.rejectionReason,
                Boolean.toString(nextTarget == null),
                nextTarget == null ? "" : Integer.toString(nextTarget.yawDegrees),
                nextTarget == null ? "" : Integer.toString(nextTarget.pitchDegrees),
                nextTarget == null ? "" : nextTarget.phase.name().toLowerCase(Locale.US),
                nextTarget == null ? "" : Float.toString(signedDelta(nextTarget.yawDegrees, frame.rawFacts.targetYawDegrees)),
                nextTarget == null ? "" : Integer.toString(nextTarget.pitchDegrees - frame.rawFacts.targetPitchDegrees),
                frame.rawFacts.deviceId,
                frame.rawFacts.cameraId,
                frame.rawFacts.locationSummary,
                frame.rawFacts.captureProfile,
                intrinsics.toString(),
                exposure.toString(),
                analysis.toString(),
                frame.toJson().toString()
        };
        appendCells(csv, cells);
    }

    private static void appendCells(StringBuilder csv, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(cells[i]));
        }
        csv.append('\n');
    }

    private static String escape(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private static String numberString(JSONObject json, String key) {
        return json == null || !json.has(key) ? "" : Double.toString(json.optDouble(key, 0.0));
    }

    private static String integerString(JSONObject json, String key) {
        return json == null || !json.has(key) ? "" : Integer.toString(json.optInt(key, 0));
    }

    private static String exposureString(JSONObject json, String key) {
        return json == null || !json.has(key) ? "" : Long.toString(json.optLong(key, 0L));
    }

    private static void deleteOldDebugCsv(File outputDir) {
        File[] files = outputDir.listFiles((dir, name) -> name.startsWith("capture-debug-") && name.endsWith(".csv"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.delete()) {
                Log.w(TAG, "Could not delete old debug CSV: " + file.getAbsolutePath());
            }
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
    }

    private static float signedDelta(float target, float current) {
        float delta = (target - current + 540f) % 360f - 180f;
        return delta < -180f ? delta + 360f : delta;
    }
}
