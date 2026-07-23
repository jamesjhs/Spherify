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
 * yaw and pitch origin. Remaining targets expand from local neighbours toward
 * wider horizon closure, then upper/lower rows, then poles.
 */
final class CaptureTargetPlanner {
    private static final double DEFAULT_HORIZONTAL_FOV_DEGREES = 75.0;
    private static final double DEFAULT_VERTICAL_FOV_DEGREES = 60.0;
    private static final double TARGET_OVERLAP = 0.58;
    private static final int MIN_STEP_DEGREES = 18;
    private static final int MAX_STEP_DEGREES = 34;

    private CaptureTargetPlanner() {
    }

    static ArrayList<CaptureTarget> initialTargets() {
        ArrayList<CaptureTarget> targets = new ArrayList<>();
        targets.add(new CaptureTarget(0, 0, CaptureTargetPhase.START));
        return targets;
    }

    static ArrayList<CaptureTarget> anchoredTargets(int anchorYawDegrees, int anchorPitchDegrees) {
        return anchoredTargets(anchorYawDegrees, anchorPitchDegrees, DEFAULT_HORIZONTAL_FOV_DEGREES, DEFAULT_VERTICAL_FOV_DEGREES);
    }

    static ArrayList<CaptureTarget> anchoredTargets(
            int anchorYawDegrees,
            int anchorPitchDegrees,
            double horizontalFovDegrees,
            double verticalFovDegrees) {
        int yawStep = captureStep(horizontalFovDegrees);
        int pitchStep = captureStep(verticalFovDegrees);
        int row1 = pitchStep;
        int row2 = pitchStep * 2;
        int row3 = Math.min(75, pitchStep * 3);
        ArrayList<CaptureTarget> targets = new ArrayList<>();
        int anchorPitch = clampPitch(anchorPitchDegrees);
        targets.add(new CaptureTarget(normalize(anchorYawDegrees), anchorPitch, CaptureTargetPhase.START));
        addTargetIfMissing(targets, anchorYawDegrees + yawStep, anchorPitch, CaptureTargetPhase.HORIZON);
        addTargetIfMissing(targets, anchorYawDegrees - yawStep, anchorPitch, CaptureTargetPhase.HORIZON);
        addTargetIfMissing(targets, anchorYawDegrees, anchorPitch + row1, CaptureTargetPhase.MID);
        addTargetIfMissing(targets, anchorYawDegrees, anchorPitch - row1, CaptureTargetPhase.MID);
        int columns = Math.max(8, (int) Math.ceil(360.0 / yawStep));
        for (int column = 2; column <= columns / 2; column++) {
            addTargetIfMissing(targets, anchorYawDegrees + column * yawStep, anchorPitch, CaptureTargetPhase.HORIZON);
            addTargetIfMissing(targets, anchorYawDegrees - column * yawStep, anchorPitch, CaptureTargetPhase.HORIZON);
        }
        for (int column = 1; column <= columns / 2; column++) {
            int offset = column * yawStep;
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch + row1, CaptureTargetPhase.MID);
            addTargetIfMissing(targets, anchorYawDegrees - offset, anchorPitch + row1, CaptureTargetPhase.MID);
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch - row1, CaptureTargetPhase.MID);
            addTargetIfMissing(targets, anchorYawDegrees - offset, anchorPitch - row1, CaptureTargetPhase.MID);
        }
        for (int column = 0; column < columns; column += 2) {
            int offset = column * yawStep;
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch + row2, CaptureTargetPhase.HIGH);
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch - row2, CaptureTargetPhase.HIGH);
        }
        for (int column = 0; column < columns; column += 3) {
            int offset = column * yawStep;
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch + row3, CaptureTargetPhase.POLE);
            addTargetIfMissing(targets, anchorYawDegrees + offset, anchorPitch - row3, CaptureTargetPhase.POLE);
        }
        addTargetIfMissing(targets, anchorYawDegrees, 85, CaptureTargetPhase.POLE);
        addTargetIfMissing(targets, anchorYawDegrees, -85, CaptureTargetPhase.POLE);
        return targets;
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
        ArrayList<CaptureTarget> targets = anchoredTargets(
                anchor.rawFacts.targetYawDegrees,
                anchor.rawFacts.targetPitchDegrees,
                horizontalFovDegrees(anchor),
                verticalFovDegrees(anchor));
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
        int yaw = normalize(yawDegrees);
        int pitch = clampPitch(pitchDegrees);
        for (CaptureTarget target : targets) {
            if (target.yawDegrees == yaw && target.pitchDegrees == pitch) {
                return;
            }
        }
        targets.add(new CaptureTarget(yaw, pitch, phase));
    }

    private static int normalize(int degrees) {
        int normalized = degrees % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private static int clampPitch(int degrees) {
        return Math.max(-85, Math.min(85, degrees));
    }

    private static int captureStep(double fovDegrees) {
        double fov = Double.isFinite(fovDegrees) && fovDegrees > 0.0 ? fovDegrees : DEFAULT_HORIZONTAL_FOV_DEGREES;
        return (int) Math.max(MIN_STEP_DEGREES, Math.min(MAX_STEP_DEGREES, Math.round(fov * (1.0 - TARGET_OVERLAP))));
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
}
