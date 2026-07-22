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
    final int targetYawDegrees;
    final int targetPitchDegrees;
    final String captureMode;
    final boolean exposureAvailable;
    final float lensFocalLengthMm;
    final float sensorPhysicalWidthMm;
    final float sensorPhysicalHeightMm;

    DraftFrameRecord(
            File imageFile,
            String sessionId,
            long createdAt,
            String location,
            float headingDegrees,
            float pitchDegrees,
            float rollDegrees,
            int targetYawDegrees,
            int targetPitchDegrees,
            String captureMode,
            boolean exposureAvailable,
            float lensFocalLengthMm,
            float sensorPhysicalWidthMm,
            float sensorPhysicalHeightMm) {
        this.imageFile = imageFile;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.location = location;
        this.headingDegrees = headingDegrees;
        this.pitchDegrees = pitchDegrees;
        this.rollDegrees = rollDegrees;
        this.targetYawDegrees = targetYawDegrees;
        this.targetPitchDegrees = targetPitchDegrees;
        this.captureMode = captureMode;
        this.exposureAvailable = exposureAvailable;
        this.lensFocalLengthMm = lensFocalLengthMm;
        this.sensorPhysicalWidthMm = sensorPhysicalWidthMm;
        this.sensorPhysicalHeightMm = sensorPhysicalHeightMm;
    }
}
