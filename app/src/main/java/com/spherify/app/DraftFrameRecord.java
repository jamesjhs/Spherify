/*
 * DraftFrameRecord.java
 *
 * Educational overview:
 * DraftFrameRecord is the typed Phase 5 view of one captured draft frame. The
 * raw capture index is stored as JSON, but stitching code needs predictable
 * fields instead of repeatedly reading opt* values from JSONObject instances.
 */
package com.spherify.app;

import java.io.File;

final class DraftFrameRecord {
    final File imageFile;
    final String sessionId;
    final long createdAt;
    final String location;
    final float headingDegrees;
    final float pitchDegrees;
    final float rollDegrees;
    final boolean capturedPoseAvailable;
    final String captureProfile;
    final int targetYawDegrees;
    final int targetPitchDegrees;
    final String captureMode;
    final boolean exposureAvailable;
    final float lensFocalLengthMm;
    final float sensorPhysicalWidthMm;
    final float sensorPhysicalHeightMm;
    final float imageFocalLengthXPixels;
    final float imageFocalLengthYPixels;
    final float imagePrincipalPointXPixels;
    final float imagePrincipalPointYPixels;
    final int imageIntrinsicsWidth;
    final int imageIntrinsicsHeight;

    DraftFrameRecord(
            File imageFile,
            String sessionId,
            long createdAt,
            String location,
            float headingDegrees,
            float pitchDegrees,
            float rollDegrees,
            boolean capturedPoseAvailable,
            String captureProfile,
            int targetYawDegrees,
            int targetPitchDegrees,
            String captureMode,
            boolean exposureAvailable,
            float lensFocalLengthMm,
            float sensorPhysicalWidthMm,
            float sensorPhysicalHeightMm,
            float imageFocalLengthXPixels,
            float imageFocalLengthYPixels,
            float imagePrincipalPointXPixels,
            float imagePrincipalPointYPixels,
            int imageIntrinsicsWidth,
            int imageIntrinsicsHeight) {
        this.imageFile = imageFile;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.location = location;
        this.headingDegrees = headingDegrees;
        this.pitchDegrees = pitchDegrees;
        this.rollDegrees = rollDegrees;
        this.capturedPoseAvailable = capturedPoseAvailable;
        this.captureProfile = captureProfile;
        this.targetYawDegrees = targetYawDegrees;
        this.targetPitchDegrees = targetPitchDegrees;
        this.captureMode = captureMode;
        this.exposureAvailable = exposureAvailable;
        this.lensFocalLengthMm = lensFocalLengthMm;
        this.sensorPhysicalWidthMm = sensorPhysicalWidthMm;
        this.sensorPhysicalHeightMm = sensorPhysicalHeightMm;
        this.imageFocalLengthXPixels = imageFocalLengthXPixels;
        this.imageFocalLengthYPixels = imageFocalLengthYPixels;
        this.imagePrincipalPointXPixels = imagePrincipalPointXPixels;
        this.imagePrincipalPointYPixels = imagePrincipalPointYPixels;
        this.imageIntrinsicsWidth = imageIntrinsicsWidth;
        this.imageIntrinsicsHeight = imageIntrinsicsHeight;
    }
}
