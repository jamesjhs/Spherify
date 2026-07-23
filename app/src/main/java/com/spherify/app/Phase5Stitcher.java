/*
 * Phase5Stitcher.java
 *
 * Educational overview:
 * Phase 5 is no longer a custom Java renderer. The Android OpenCV dependency
 * in this repository exposes feature/matching APIs used by capture validation,
 * but it does not expose or link the stitching/detail optimizer symbols needed
 * for a production master. This class therefore refuses master creation until
 * a real OpenCV-detail or Ceres-backed native dependency is added.
 */
package com.spherify.app;

import java.io.File;
import java.io.IOException;
import java.util.List;

final class Phase5Stitcher {
    private Phase5Stitcher() {
    }

    static Result stitch(
            CaptureSessionRecord graphSession,
            File outputFile,
            String movementSensitivityMode,
            String renderModeName,
            SpherifyLibrary.ProgressReporter progress) throws IOException {
        report(progress, "input", true, "Validated graph input accepted by 0.4.1");
        report(progress, "opencv", false, "OpenCV stitch/detail module unavailable in the packaged Android dependency");
        throw new IOException("Spherify 0.4.1 has removed the old custom stitch renderer. The current OpenCV Android AAR supports capture-time feature matching, but does not expose/link OpenCV stitching/detail or Ceres optimizer symbols, so production master export is disabled instead of generating a non-compliant sphere.");
    }

    private static void report(SpherifyLibrary.ProgressReporter progress, String stepKey, boolean complete, String message) {
        if (progress != null) {
            progress.onProgress(stepKey, complete, message);
        }
    }

    static final class Result {
        final int renderedFrames;
        final int coveragePercent;
        final int missingExposureFrames;
        final String reviewState;
        final String validationSummary;
        final List<String> warnings;

        Result(
                int renderedFrames,
                int coveragePercent,
                int missingExposureFrames,
                String reviewState,
                String validationSummary,
                List<String> warnings) {
            this.renderedFrames = renderedFrames;
            this.coveragePercent = coveragePercent;
            this.missingExposureFrames = missingExposureFrames;
            this.reviewState = reviewState;
            this.validationSummary = validationSummary;
            this.warnings = warnings;
        }
    }
}
