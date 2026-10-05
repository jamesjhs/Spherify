package com.spherify.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/*
 * CaptureTargetPlanner.java
 *
 * Educational overview:
 * The capture lattice is a deterministic sampling plan on the viewing sphere.
 * SharedCameraCaptureActivity uses it to guide the user; the debug CLI uses it
 * to report the next target from the persisted graph. This avoids a common
 * debugging trap where the UI and diagnostics silently describe different
 * geometries.
 *
 * Method:
 * The first target is user-anchored. The accepted first view defines the local
 * yaw origin. The public default then uses the Stanford-style 36 target
 * profile: one horizon row and two tilted rows, each with twelve columns. The
 * previous FOV-adaptive profile remains available as a field-test fallback.
 */
final class CaptureTargetPlanner {
    static final String DEFAULT_PROFILE_ID = CaptureProfile.STANFORD_FIXED_36_ID;
    static final int FIXED_36_TARGET_COUNT = 36;

    private static final double DEFAULT_HORIZONTAL_FOV_DEGREES = 75.0;
    private static final double DEFAULT_VERTICAL_FOV_DEGREES = 60.0;
    private static final double HORIZONTAL_TARGET_OVERLAP = 0.52;
    private static final double VERTICAL_TARGET_OVERLAP = 0.40;
    private static final int MIN_STEP_DEGREES = 18;
    private static final int MAX_HORIZONTAL_STEP_DEGREES = 36;
    private static final int MAX_VERTICAL_STEP_DEGREES = 45;

    private CaptureTargetPlanner() {
    }

    static ArrayList<CaptureTarget> initialTargets() {
        ArrayList<CaptureTarget> targets = new ArrayList<>();
        targets.add(new CaptureTarget(0, 0, CaptureTargetPhase.START));
        return targets;
    }

    static ArrayList<CaptureTarget> anchoredTargets(int anchorYawDegrees, int anchorPitchDegrees) {
        return fixed36Targets(anchorYawDegrees, anchorPitchDegrees);
    }

    static ArrayList<CaptureTarget> fixed36Targets(int anchorYawDegrees, int anchorPitchDegrees) {
        ArrayList<CaptureTarget> targets = new ArrayList<>();
        CaptureProfile profile = CaptureProfile.stanfordFixed36();
        for (CaptureProfile.CaptureTargetSpec spec : profile.targetSpecs) {
            addTargetIfMissing(
                    targets,
                    spec.index,
                    anchorYawDegrees + spec.localYawDegrees,
                    spec.localPitchDegrees,
                    spec.phase);
        }
        return targets;
    }

    static ArrayList<CaptureTarget> adaptiveFovTargets(int anchorYawDegrees, int anchorPitchDegrees) {
        return adaptiveFovTargets(anchorYawDegrees, anchorPitchDegrees, DEFAULT_HORIZONTAL_FOV_DEGREES, DEFAULT_VERTICAL_FOV_DEGREES);
    }

    static ArrayList<CaptureTarget> adaptiveFovTargets(
            int anchorYawDegrees,
            int anchorPitchDegrees,
            double horizontalFovDegrees,
            double verticalFovDegrees) {
        int yawStep = horizontalCaptureStep(horizontalFovDegrees);
        int columnCount = Math.max(8, (int) Math.ceil(360.0 / yawStep));
        int row1 = roundToNearestFive(verticalCaptureStep(verticalFovDegrees));
        int row2 = Math.min(70, roundToNearestFive(row1 * 2));
        ArrayList<CaptureTarget> targets = new ArrayList<>();
        int anchorPitch = clampPitch(anchorPitchDegrees);
        targets.add(new CaptureTarget(normalize(anchorYawDegrees), anchorPitch, CaptureTargetPhase.START));
        for (int column = 1; column < columnCount; column++) {
            int offset = Math.round(column * 360f / columnCount);
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch, CaptureTargetPhase.HORIZON);
        }
        for (int column = 0; column < columnCount; column++) {
            int offset = Math.round(column * 360f / columnCount);
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch + row1, CaptureTargetPhase.MID);
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch - row1, CaptureTargetPhase.MID);
        }
        int highColumnCount = Math.max(4, (int) Math.ceil(columnCount / 2.0));
        for (int column = 0; column < highColumnCount; column++) {
            int offset = Math.round((column + 0.5f) * 360f / highColumnCount);
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch + row2, CaptureTargetPhase.HIGH);
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch - row2, CaptureTargetPhase.HIGH);
        }
        addTargetIfMissing(targets, anchorYawDegrees, 85, CaptureTargetPhase.POLE);
        addTargetIfMissing(targets, anchorYawDegrees, -85, CaptureTargetPhase.POLE);
        return targets;
    }

    static ArrayList<CaptureTarget> anchoredTargets(
            int anchorYawDegrees,
            int anchorPitchDegrees,
            double horizontalFovDegrees,
            double verticalFovDegrees) {
        return fixed36Targets(anchorYawDegrees, anchorPitchDegrees);
    }

    static TargetCoverage coverageForDraftRecords(List<DraftFrameRecord> records) {
        if (records == null || records.isEmpty()) {
            return new TargetCoverage(0, 0);
        }
        DraftFrameRecord anchor = records.get(0);
        ArrayList<CaptureTarget> targets = fixed36Targets(
                anchor.targetYawDegrees,
                anchor.targetPitchDegrees);
        for (DraftFrameRecord record : records) {
            markCaptured(targets, record.targetYawDegrees, record.targetPitchDegrees);
        }
        int captured = 0;
        for (CaptureTarget target : targets) {
            if (target.captured) {
                captured++;
            }
        }
        return new TargetCoverage(targets.size(), captured);
    }

    static TargetCoverage coverageForAcceptedFrames(List<CaptureFrameRecord> frames) {
        CaptureFrameRecord anchor = firstAcceptedFrame(frames);
        if (anchor == null) {
            return new TargetCoverage(0, 0);
        }
        ArrayList<CaptureTarget> targets = fixed36Targets(
                anchor.rawFacts.targetYawDegrees,
                anchor.rawFacts.targetPitchDegrees);
        for (CaptureFrameRecord frame : frames) {
            if (frame.role == CaptureFrameRole.ACCEPTED) {
                markCaptured(targets, frame.rawFacts.targetYawDegrees, frame.rawFacts.targetPitchDegrees);
            }
        }
        int captured = 0;
        for (CaptureTarget target : targets) {
            if (target.captured) {
                captured++;
            }
        }
        return new TargetCoverage(targets.size(), captured);
    }

    static int expectedTargetCountForAcceptedFrames(List<CaptureFrameRecord> frames) {
        return coverageForAcceptedFrames(frames).expectedTargets;
    }

    static CaptureTarget nextTargetFor(CaptureSessionRecord session) {
        if (session == null) {
            return null;
        }
        return nextTargetForAcceptedFrames(session.frames);
    }

    static CaptureTarget nextTargetForAcceptedFrames(List<CaptureFrameRecord> frames) {
        CaptureFrameRecord anchor = firstAcceptedFrame(frames);
        if (anchor == null) {
            return null;
        }
        ArrayList<CaptureTarget> targets = fixed36Targets(
                anchor.rawFacts.targetYawDegrees,
                anchor.rawFacts.targetPitchDegrees);
        for (CaptureFrameRecord frame : frames) {
            if (frame.role == CaptureFrameRole.ACCEPTED) {
                markCaptured(targets, frame.rawFacts.targetYawDegrees, frame.rawFacts.targetPitchDegrees);
            }
        }
        for (CaptureTarget target : targets) {
            if (!target.captured) {
                return target;
            }
        }
        return null;
    }

    static JSONObject toJson(CaptureTarget target) throws JSONException {
        if (target == null) {
            return new JSONObject().put("complete", true);
        }
        JSONObject json = new JSONObject();
        json.put("complete", false);
        json.put("yawDegrees", target.yawDegrees);
        json.put("pitchDegrees", target.pitchDegrees);
        json.put("phase", target.phase.name().toLowerCase());
        return json;
    }

    private static CaptureFrameRecord firstAcceptedFrame(List<CaptureFrameRecord> frames) {
        for (CaptureFrameRecord frame : frames) {
            if (frame.role == CaptureFrameRole.ACCEPTED) {
                return frame;
            }
        }
        return null;
    }

    private static void markCaptured(ArrayList<CaptureTarget> targets, int yawDegrees, int pitchDegrees) {
        int yaw = normalize(yawDegrees);
        int pitch = clampPitch(pitchDegrees);
        for (CaptureTarget target : targets) {
            if (target.yawDegrees == yaw && target.pitchDegrees == pitch) {
                target.captured = true;
                return;
            }
        }
    }

    private static void addTargetIfMissing(ArrayList<CaptureTarget> targets, int yawDegrees, int pitchDegrees, CaptureTargetPhase phase) {
        addTargetIfMissing(targets, -1, yawDegrees, pitchDegrees, phase);
    }

    private static void addTargetIfMissing(
            ArrayList<CaptureTarget> targets,
            int index,
            int yawDegrees,
            int pitchDegrees,
            CaptureTargetPhase phase) {
        int yaw = normalize(yawDegrees);
        int pitch = clampPitch(pitchDegrees);
        for (CaptureTarget target : targets) {
            if (target.yawDegrees == yaw && target.pitchDegrees == pitch) {
                return;
            }
        }
        targets.add(new CaptureTarget(index, yaw, pitch, phase));
    }

    private static int normalize(int degrees) {
        int normalized = degrees % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private static int clampPitch(int degrees) {
        return Math.max(-85, Math.min(85, degrees));
    }

    private static int horizontalCaptureStep(double fovDegrees) {
        double fov = Double.isFinite(fovDegrees) && fovDegrees > 0.0 ? fovDegrees : DEFAULT_HORIZONTAL_FOV_DEGREES;
        return (int) Math.max(
                MIN_STEP_DEGREES,
                Math.min(MAX_HORIZONTAL_STEP_DEGREES, Math.round(fov * (1.0 - HORIZONTAL_TARGET_OVERLAP))));
    }

    private static int verticalCaptureStep(double fovDegrees) {
        double fov = Double.isFinite(fovDegrees) && fovDegrees > 0.0 ? fovDegrees : DEFAULT_VERTICAL_FOV_DEGREES;
        return (int) Math.max(
                MIN_STEP_DEGREES,
                Math.min(MAX_VERTICAL_STEP_DEGREES, Math.round(fov * (1.0 - VERTICAL_TARGET_OVERLAP))));
    }

    private static int roundToNearestFive(int degrees) {
        return Math.max(MIN_STEP_DEGREES, Math.round(degrees / 5f) * 5);
    }

    private static double horizontalFovDegrees(CaptureFrameRecord frame) {
        return fovDegrees(
                frame.rawFacts.intrinsics.optDouble("focalLengthXPixels", 0.0),
                frame.rawFacts.intrinsics.optInt("width", 0));
    }

    private static double verticalFovDegrees(CaptureFrameRecord frame) {
        return fovDegrees(
                frame.rawFacts.intrinsics.optDouble("focalLengthYPixels", 0.0),
                frame.rawFacts.intrinsics.optInt("height", 0));
    }

    private static double fovDegrees(double focalPixels, int imagePixels) {
        if (focalPixels <= 0.0 || imagePixels <= 0) {
            return DEFAULT_HORIZONTAL_FOV_DEGREES;
        }
        return Math.toDegrees(2.0 * Math.atan(imagePixels / (2.0 * focalPixels)));
    }

    static final class TargetCoverage {
        final int expectedTargets;
        final int capturedTargets;

        TargetCoverage(int expectedTargets, int capturedTargets) {
            this.expectedTargets = expectedTargets;
            this.capturedTargets = capturedTargets;
        }

        boolean complete() {
            return expectedTargets > 0 && capturedTargets >= expectedTargets;
        }

        int percent() {
            if (expectedTargets <= 0) {
                return 0;
            }
            return Math.round(Math.min(capturedTargets, expectedTargets) * 100f / expectedTargets);
        }
    }
}
