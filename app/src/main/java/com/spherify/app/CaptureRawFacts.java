package com.spherify.app;

import org.json.JSONException;
import org.json.JSONObject;

final class CaptureRawFacts {
    final String filePath;
    final long timestampMillis;
    final int targetYawDegrees;
    final int targetPitchDegrees;
    final float capturedYawDegrees;
    final float capturedPitchDegrees;
    final float capturedRollDegrees;
    final boolean capturedPoseAvailable;
    final JSONObject intrinsics;
    final JSONObject exposure;
    final String locationSummary;
    final String captureProfile;
    final String deviceId;
    final String cameraId;

    CaptureRawFacts(
            String filePath,
            long timestampMillis,
            int targetYawDegrees,
            int targetPitchDegrees,
            float capturedYawDegrees,
            float capturedPitchDegrees,
            float capturedRollDegrees,
            boolean capturedPoseAvailable,
            JSONObject intrinsics,
            JSONObject exposure,
            String locationSummary,
            String captureProfile,
            String deviceId,
            String cameraId) {
        this.filePath = filePath;
        this.timestampMillis = timestampMillis;
        this.targetYawDegrees = targetYawDegrees;
        this.targetPitchDegrees = targetPitchDegrees;
        this.capturedYawDegrees = capturedYawDegrees;
        this.capturedPitchDegrees = capturedPitchDegrees;
        this.capturedRollDegrees = capturedRollDegrees;
        this.capturedPoseAvailable = capturedPoseAvailable;
        this.intrinsics = intrinsics == null ? new JSONObject() : intrinsics;
        this.exposure = exposure == null ? new JSONObject() : exposure;
        this.locationSummary = locationSummary == null ? "" : locationSummary;
        this.captureProfile = captureProfile == null ? "handheld" : captureProfile;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.cameraId = cameraId == null ? "" : cameraId;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("filePath", filePath);
        json.put("timestampMillis", timestampMillis);
        json.put("targetYawDegrees", targetYawDegrees);
        json.put("targetPitchDegrees", targetPitchDegrees);
        json.put("capturedYawDegrees", capturedYawDegrees);
        json.put("capturedPitchDegrees", capturedPitchDegrees);
        json.put("capturedRollDegrees", capturedRollDegrees);
        json.put("capturedPoseAvailable", capturedPoseAvailable);
        json.put("intrinsics", intrinsics);
        json.put("focusExposureWhiteBalance", exposure);
        json.put("locationSummary", locationSummary);
        json.put("captureProfile", captureProfile);
        json.put("deviceId", deviceId);
        json.put("cameraId", cameraId);
        return json;
    }

    static CaptureRawFacts fromJson(JSONObject json) {
        return new CaptureRawFacts(
                json.optString("filePath", ""),
                json.optLong("timestampMillis", 0L),
                json.optInt("targetYawDegrees", 0),
                json.optInt("targetPitchDegrees", 0),
                (float) json.optDouble("capturedYawDegrees", 0.0),
                (float) json.optDouble("capturedPitchDegrees", 0.0),
                (float) json.optDouble("capturedRollDegrees", 0.0),
                json.optBoolean("capturedPoseAvailable", false),
                json.optJSONObject("intrinsics"),
                json.optJSONObject("focusExposureWhiteBalance"),
                json.optString("locationSummary", ""),
                json.optString("captureProfile", "handheld"),
                json.optString("deviceId", ""),
                json.optString("cameraId", ""));
    }
}
