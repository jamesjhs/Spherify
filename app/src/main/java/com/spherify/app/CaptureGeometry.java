package com.spherify.app;

/*
 * CaptureGeometry centralizes the angular budgets used by capture guidance,
 * persisted target coverage, overlap prediction, and final graph checks.
 */
final class CaptureGeometry {
    static final float FIXED_36_YAW_STEP_DEGREES = 30f;
    static final float FIXED_36_ROW_STEP_DEGREES = 45f;

    /*
     * A portrait phone often has only about 45 degrees of effective horizontal
     * FOV. With 30 degree yaw spacing, preserving roughly 20% overlap leaves a
     * per-frame yaw error budget near 3 degrees. We keep live capture slightly
     * wider for AR noise, and keep persisted target credit below half a lattice
     * cell so adjacent targets cannot collapse together.
     */
    static final float LIVE_CAPTURE_YAW_TOLERANCE_DEGREES = 5.5f;
    static final float LIVE_CAPTURE_PITCH_TOLERANCE_DEGREES = 6.5f;
    static final float TARGET_CREDIT_YAW_TOLERANCE_DEGREES = 7.5f;
    static final float TARGET_CREDIT_PITCH_TOLERANCE_DEGREES = 7.5f;

    static final float SAME_ROW_MAX_PITCH_DELTA_DEGREES = 12f;
    static final float HORIZONTAL_OVERLAP_MIN_YAW_DELTA_DEGREES = 16f;
    static final float HORIZONTAL_OVERLAP_MAX_YAW_DELTA_DEGREES = 60f;
    static final float ADJACENT_ROW_MIN_PITCH_DELTA_DEGREES = 16f;
    static final float ADJACENT_ROW_MAX_PITCH_DELTA_DEGREES = 52f;

    static final float PREDICTED_NEIGHBOR_MAX_YAW_DELTA_DEGREES = 62f;
    static final float PREDICTED_NEIGHBOR_MAX_PITCH_DELTA_DEGREES = 52f;
    static final float POSE_ONLY_BRACKET_MAX_YAW_DELTA_DEGREES = 38f;
    static final float POSE_ONLY_BRACKET_MAX_PITCH_DELTA_DEGREES = 52f;

    private CaptureGeometry() {
    }

    static boolean isWithinLiveCaptureTolerance(
            int targetYawDegrees,
            int targetPitchDegrees,
            float capturedYawDegrees,
            float capturedPitchDegrees) {
        return Math.abs(signedHeadingDelta(targetYawDegrees, capturedYawDegrees)) <= LIVE_CAPTURE_YAW_TOLERANCE_DEGREES
                && Math.abs(targetPitchDegrees - capturedPitchDegrees) <= LIVE_CAPTURE_PITCH_TOLERANCE_DEGREES;
    }

    static boolean isSameTargetCell(
            int targetYawDegrees,
            int targetPitchDegrees,
            int capturedTargetYawDegrees,
            int capturedTargetPitchDegrees) {
        return Math.abs(signedHeadingDelta(targetYawDegrees, capturedTargetYawDegrees)) <= TARGET_CREDIT_YAW_TOLERANCE_DEGREES
                && Math.abs(targetPitchDegrees - capturedTargetPitchDegrees) <= TARGET_CREDIT_PITCH_TOLERANCE_DEGREES;
    }

    static float signedHeadingDelta(float targetDegrees, float currentDegrees) {
        float delta = targetDegrees - currentDegrees;
        while (delta > 180f) {
            delta -= 360f;
        }
        while (delta < -180f) {
            delta += 360f;
        }
        return delta;
    }
}
