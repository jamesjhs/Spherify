package com.spherify.app;

/*
 * CaptureTarget.java
 *
 * Educational overview:
 * CaptureTarget is the small shared value used by the ARCore live capture UI
 * and the debug exporter. Keeping it separate makes the planned target sequence
 * reproducible from persisted capture-session metadata.
 */
final class CaptureTarget {
    int yawDegrees;
    int pitchDegrees;
    final CaptureTargetPhase phase;
    boolean captured;
    boolean weak;

    CaptureTarget(int yawDegrees, int pitchDegrees, CaptureTargetPhase phase) {
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.phase = phase;
    }
}
