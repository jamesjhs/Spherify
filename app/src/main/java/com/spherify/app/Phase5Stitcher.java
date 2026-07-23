/*
 * Phase5Stitcher.java
 *
 * Educational overview:
 * Phase5Stitcher is the experimental stitching pipeline. It does not yet run a
 * full bundle-adjusted feature stitch, but it now treats the guided capture
 * plan as a prior, uses OpenCV/RANSAC control points to relax a frame pose
 * graph, and renders the normal master from sharp source-selected pixels.
 */
package com.spherify.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.media.ExifInterface;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.features.BFMatcher;
import org.opencv.features.ORB;
import org.opencv.geometry.Geometry;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Phase5Stitcher {
    private static final int OUTPUT_WIDTH = 4096;
    private static final int OUTPUT_HEIGHT = OUTPUT_WIDTH / 2;
    private static final float DEFAULT_HORIZONTAL_FOV_DEGREES = 62f;
    private static final float MIN_VERTICAL_FOV_DEGREES = 42f;
    private static final float MAX_VERTICAL_FOV_DEGREES = 76f;
    private static final float POLAR_HORIZONTAL_FOV_DEGREES = 70f;
    private static final float POLAR_VERTICAL_FOV_DEGREES = 52f;
    private static final float FEATHER_FRACTION = 0.18f;
    private static final float RADIAL_COMPENSATION_K1 = -0.08f;
    private static final float RADIAL_COMPENSATION_K2 = 0.02f;
    private static final int CALIBRATION_MAX_DIMENSION = 320;
    private static final int OVERLAP_SEARCH_X_PIXELS = 24;
    private static final int OVERLAP_SEARCH_Y_PIXELS = 18;
    private static final int OVERLAP_SEARCH_ROLL_PIXELS = 8;
    private static final int OVERLAP_STRIP_WIDTH = 36;
    private static final int FEATURE_MAX_POINTS = 140;
    private static final int FEATURE_DESCRIPTOR_RADIUS = 8;
    private static final int FEATURE_MIN_MATCHES = 10;
    private static final int RANSAC_TRANSLATION_TOLERANCE_PIXELS = 10;
    private static final int COVERAGE_COLUMNS = 48;
    private static final int COVERAGE_ROWS = 24;
    private static final int[] CONTRIBUTOR_COLORS = {
            0xFFE11D48, 0xFF2563EB, 0xFF16A34A, 0xFFF59E0B,
            0xFF7C3AED, 0xFF0891B2, 0xFFDB2777, 0xFF65A30D,
            0xFFEA580C, 0xFF4F46E5, 0xFF0D9488, 0xFF9333EA
    };
    private static final boolean OPENCV_AVAILABLE = initializeOpenCv();

    private Phase5Stitcher() {
    }

    private static boolean initializeOpenCv() {
        try {
            return OpenCVLoader.initLocal();
        } catch (Throwable localFailure) {
            try {
                return OpenCVLoader.initDebug();
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    static Result stitch(
            List<DraftFrameRecord> records,
            File outputFile,
            String movementSensitivityMode,
            String renderModeName) throws IOException {
        return stitch(records, outputFile, movementSensitivityMode, renderModeName, null);
    }

    static Result stitch(
            List<DraftFrameRecord> records,
            File outputFile,
            String movementSensitivityMode,
            String renderModeName,
            SpherifyLibrary.ProgressReporter progress) throws IOException {
        return stitch(records, null, outputFile, movementSensitivityMode, renderModeName, progress);
    }

    static Result stitch(
            CaptureSessionRecord graphSession,
            File outputFile,
            String movementSensitivityMode,
            String renderModeName,
            SpherifyLibrary.ProgressReporter progress) throws IOException {
        if (graphSession == null) {
            throw new IOException("Spherify 0.7.3 requires a validated capture graph");
        }
        report(progress, "input", false, "Loading accepted frames from validated capture graph " + graphSession.id);
        ArrayList<DraftFrameRecord> records = acceptedDraftRecordsFromGraphSession(graphSession);
        if (records.isEmpty()) {
            throw new IOException("capture graph has no readable accepted frames");
        }
        return stitch(records, graphSession, outputFile, movementSensitivityMode, renderModeName, progress);
    }

    private static Result stitch(
            List<DraftFrameRecord> records,
            CaptureSessionRecord graphSession,
            File outputFile,
            String movementSensitivityMode,
            String renderModeName,
            SpherifyLibrary.ProgressReporter progress) throws IOException {
        report(progress, "input", false, "Loading draft frames from the selected session");
        ArrayList<DraftFrameRecord> usable = new ArrayList<>();
        for (DraftFrameRecord record : records) {
            if (record.imageFile.exists()) {
                usable.add(record);
            }
        }
        usable.sort(Comparator.comparingLong(record -> record.createdAt));
        if (usable.isEmpty()) {
            throw new IOException("draft session has no readable frames");
        }
        report(progress, "input", true, "Loaded " + usable.size() + " readable frames from one session");
        report(progress, "lens", false, "Estimating lens FOV, radial compensation, and capture profile");
        MovementSensitivity movementSensitivity = MovementSensitivity.from(movementSensitivityMode);
        RenderMode renderMode = RenderMode.from(renderModeName);
        CaptureProfile captureProfile = CaptureProfile.from(usable);
        CameraLensPriors cameraLensPriors = CameraLensPriors.from(usable);
        report(progress, "lens", false, "Phase 5B camera priors: " + cameraLensPriors.summary(usable.size()));
        Calibration calibration = graphSession == null
                ? calibrate(usable, movementSensitivity, captureProfile, progress)
                : calibrateGraphSession(usable, graphSession, captureProfile, progress);
        report(progress, "lens", true, String.format(
                Locale.US,
                "Optimized lens model ready: %.1f x %.1f FOV, matched overlaps %d (%d OpenCV)",
                calibration.lensModel.horizontalFovDegrees,
                calibration.lensModel.verticalFovDegrees,
                calibration.matchedOverlapCount,
                calibration.openCvMatchedOverlapCount));

        report(progress, "render", false, "Allocating 4096 x 2048 equirectangular buffers");
        float[] red = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        float[] green = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        float[] blue = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        float[] weights = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        int[] seamSource = new int[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        float[] seamScores = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        boolean[][] coverage = new boolean[COVERAGE_ROWS][COVERAGE_COLUMNS];
        SeamFinder seamFinder = new SeamFinder(calibration);
        int rendered = 0;
        int missingExposure = 0;

        report(progress, "render", false, renderMode.diagnostic
                ? "Projecting diagnostic frame-selection render from optimized poses"
                : "Projecting optimized frames into the equirectangular master");
        for (DraftFrameRecord record : usable) {
            Bitmap frame = decodeFrame(record.imageFile);
            if (frame == null) {
                report(progress, "render", false, "Skipped unreadable frame " + record.imageFile.getName());
                continue;
            }
            if (!record.exposureAvailable) {
                missingExposure++;
            }
            FrameProjection projection = projectionFor(record, calibration);
            float exposureGain = calibration.exposureGains.getOrDefault(record.imageFile.getAbsolutePath(), 1f);
            markCoverage(coverage, projection.bounds);
            blendWrapped(
                    frame,
                    projection,
                    calibration.lensModel,
                    renderMode,
                    rendered,
                    exposureGain,
                    red,
                    green,
                    blue,
                    weights,
                    seamSource,
                    seamScores,
                    seamFinder);
            frame.recycle();
            rendered++;
            if (rendered == 1 || rendered == usable.size() || rendered % 5 == 0) {
                report(progress, "render", false, "Projected " + rendered + "/" + usable.size() + " frames");
            }
        }

        if (rendered == 0) {
            throw new IOException("could not decode any draft frames");
        }
        report(progress, "render", true, "Projected " + rendered + " frames into the master");
        SeamReport seamReport = seamFinder.report(seamScores);
        report(progress, "render", true, "Selected seams: low-confidence penalties "
                + seamReport.lowConfidenceSeamPixels + ", exposure penalties " + seamReport.exposurePenaltyPixels);

        report(progress, "write", false, "Composing final bitmap and writing JPEG");
        Bitmap output = composeOutput(red, green, blue, weights, progress);
        if (!renderMode.diagnostic) {
            output = MultibandBlender.cosmeticBlend(output, weights, progress);
        }
        ExportValidationReport exportValidation = ExportValidator.validate(
                output,
                weights,
                coverage,
                calibration,
                seamReport,
                usable);
        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            if (!output.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new IOException("could not write stitched master");
            }
        } finally {
            output.recycle();
        }

        int coveragePercent = Math.round(100f * coveredCells(coverage) / (COVERAGE_COLUMNS * COVERAGE_ROWS));
        report(progress, "write", true, "Wrote stitched JPEG with estimated coverage " + coveragePercent + "%");
        report(progress, "metadata", false, "Writing Phase 5 diagnostics into image metadata");
        writeMetadata(outputFile, usable.get(0).sessionId, rendered, coveragePercent, missingExposure, calibration, movementSensitivity, renderMode, seamReport, exportValidation);
        PhotoSphereXmp.write(outputFile, usable, coveragePercent);
        report(progress, "metadata", true, "Embedded stitch diagnostics and warning summary");
        return new Result(
                rendered,
                coveragePercent,
                missingExposure,
                exportValidation.reviewState,
                exportValidation.summary,
                warnings(rendered, coveragePercent, missingExposure, calibration, movementSensitivity, exportValidation, seamReport));
    }

    private static void report(SpherifyLibrary.ProgressReporter progress, String stepKey, boolean complete, String message) {
        if (progress != null) {
            progress.onProgress(stepKey, complete, message);
        }
    }

    private static ArrayList<DraftFrameRecord> acceptedDraftRecordsFromGraphSession(CaptureSessionRecord session) {
        ArrayList<DraftFrameRecord> records = new ArrayList<>();
        for (CaptureFrameRecord frame : session.frames) {
            if (frame.role != CaptureFrameRole.ACCEPTED) {
                continue;
            }
            File file = new File(frame.rawFacts.filePath);
            if (!file.exists()) {
                continue;
            }
            JSONObject exposure = frame.rawFacts.exposure;
            JSONObject intrinsics = frame.rawFacts.intrinsics;
            float imageFocalLengthXPixels = (float) firstPositive(
                    exposure.optDouble("imageFocalLengthXPixels", 0.0),
                    intrinsics.optDouble("imageFocalLengthXPixels", 0.0),
                    intrinsics.optDouble("fx", 0.0));
            float imageFocalLengthYPixels = (float) firstPositive(
                    exposure.optDouble("imageFocalLengthYPixels", 0.0),
                    intrinsics.optDouble("imageFocalLengthYPixels", 0.0),
                    intrinsics.optDouble("fy", 0.0));
            records.add(new DraftFrameRecord(
                    file,
                    session.id,
                    frame.rawFacts.timestampMillis,
                    frame.rawFacts.locationSummary,
                    frame.rawFacts.capturedYawDegrees,
                    frame.rawFacts.capturedPitchDegrees,
                    frame.rawFacts.capturedRollDegrees,
                    frame.rawFacts.capturedPoseAvailable,
                    frame.rawFacts.captureProfile,
                    frame.rawFacts.targetYawDegrees,
                    frame.rawFacts.targetPitchDegrees,
                    session.captureMode.storageValue,
                    exposure.optBoolean("available", false),
                    (float) exposure.optDouble("lensFocalLengthMm", 0.0),
                    (float) exposure.optDouble("sensorPhysicalWidthMm", 0.0),
                    (float) exposure.optDouble("sensorPhysicalHeightMm", 0.0),
                    imageFocalLengthXPixels,
                    imageFocalLengthYPixels,
                    (float) firstPositive(
                            exposure.optDouble("imagePrincipalPointXPixels", 0.0),
                            intrinsics.optDouble("imagePrincipalPointXPixels", 0.0),
                            intrinsics.optDouble("cx", 0.0)),
                    (float) firstPositive(
                            exposure.optDouble("imagePrincipalPointYPixels", 0.0),
                            intrinsics.optDouble("imagePrincipalPointYPixels", 0.0),
                            intrinsics.optDouble("cy", 0.0)),
                    (int) firstPositive(
                            exposure.optDouble("imageIntrinsicsWidth", 0.0),
                            intrinsics.optDouble("imageIntrinsicsWidth", 0.0),
                            intrinsics.optDouble("width", 0.0)),
                    (int) firstPositive(
                            exposure.optDouble("imageIntrinsicsHeight", 0.0),
                            intrinsics.optDouble("imageIntrinsicsHeight", 0.0),
                            intrinsics.optDouble("height", 0.0))));
        }
        records.sort(Comparator.comparingLong(record -> record.createdAt));
        return records;
    }

    private static double firstPositive(double first, double second, double third) {
        if (first > 0.0) {
            return first;
        }
        if (second > 0.0) {
            return second;
        }
        return Math.max(0.0, third);
    }

    private static Bitmap decodeFrame(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        int sample = 1;
        while ((options.outWidth / sample) > 1200 || (options.outHeight / sample) > 1200) {
            sample *= 2;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
        return bitmap == null ? null : SpherifyLibrary.applyExifRotation(bitmap, file.getAbsolutePath());
    }

    private static Calibration calibrate(
            List<DraftFrameRecord> records,
            MovementSensitivity movementSensitivity,
            CaptureProfile captureProfile,
            SpherifyLibrary.ProgressReporter progress) {
        LensModel lensModel = estimateLensModel(records);
        ArrayList<CalibrationFrame> frames = new ArrayList<>();
        report(progress, "lens", false, "Decoding low-resolution calibration frames");
        int decodedCandidates = 0;
        for (DraftFrameRecord record : records) {
            CalibrationFrame frame = CalibrationFrame.decode(record, lensModel);
            decodedCandidates++;
            if (frame != null && !frame.polar) {
                frames.add(frame);
            }
            if (decodedCandidates == 1 || decodedCandidates == records.size() || decodedCandidates % 5 == 0) {
                report(progress, "lens", false, "Calibration decode " + decodedCandidates + "/" + records.size()
                        + " candidates, " + frames.size() + " non-polar frames");
            }
        }
        frames.sort((left, right) -> {
            int pitchCompare = Integer.compare(left.basePitchDegrees, right.basePitchDegrees);
            return pitchCompare != 0 ? pitchCompare : Float.compare(left.baseYawDegrees, right.baseYawDegrees);
        });
        report(progress, "lens", false, "Sorted " + frames.size() + " calibration frames by row and yaw");

        HashMap<String, PoseCorrection> corrections = new HashMap<>();
        ArrayList<FeatureMatchEdge> matchEdges = new ArrayList<>();
        int matchedPairs = 0;
        int openCvMatchedPairs = 0;
        float confidenceTotal = 0f;
        List<FramePair> overlapPairs = predictedOverlapPairs(frames, lensModel);
        report(progress, "lens", false, "Predicted " + overlapPairs.size() + " candidate overlap pairs");
        int processedPairs = 0;
        int rejectedPairs = 0;
        for (FramePair framePair : overlapPairs) {
            processedPairs++;
            CalibrationFrame left = framePair.left;
            CalibrationFrame right = framePair.right;
            PairAdjustment adjustment = matchFeatureOverlap(left, right, movementSensitivity);
            if (adjustment == null) {
                adjustment = correlateOverlap(left, right, movementSensitivity);
            }
            if (adjustment == null || adjustment.confidence < movementSensitivity.minimumConfidence) {
                rejectedPairs++;
                if (processedPairs == 1 || processedPairs == overlapPairs.size() || processedPairs % 8 == 0) {
                    report(progress, "lens", false, "Overlap pair " + processedPairs + "/" + overlapPairs.size()
                            + ": accepted " + matchedPairs + ", rejected " + rejectedPairs);
                }
                continue;
            }
            float yawCorrection = -adjustment.horizontalOffsetPixels
                    / Math.max(1f, right.width)
                    * lensModel.horizontalFovDegrees
                    * movementSensitivity.correctionScale;
            float pitchCorrection = -adjustment.verticalOffsetPixels
                    / Math.max(1f, right.height)
                    * lensModel.verticalFovDegrees
                    * movementSensitivity.correctionScale;
            float rollCorrection = 0f;
            if (adjustment.confidence >= movementSensitivity.rollMinimumConfidence) {
                rollCorrection = adjustment.rollPixels
                        / Math.max(1f, right.height)
                        * lensModel.verticalFovDegrees
                        * movementSensitivity.rollCorrectionScale;
            }
            matchEdges.add(new FeatureMatchEdge(
                    left.record.imageFile.getAbsolutePath(),
                    right.record.imageFile.getAbsolutePath(),
                    yawCorrection,
                    pitchCorrection,
                    rollCorrection,
                    adjustment.confidence,
                    adjustment.score,
                    adjustment.featureBased,
                    adjustment.inlierControlPoints));
            PoseCorrection correction = corrections.computeIfAbsent(
                    right.record.imageFile.getAbsolutePath(),
                    ignored -> new PoseCorrection());
            correction.add(yawCorrection, pitchCorrection, rollCorrection, adjustment.confidence);
            matchedPairs++;
            if (adjustment.featureBased) {
                openCvMatchedPairs++;
            }
            confidenceTotal += adjustment.confidence;
            if (processedPairs == 1 || processedPairs == overlapPairs.size() || processedPairs % 8 == 0) {
                report(progress, "lens", false, "Overlap pair " + processedPairs + "/" + overlapPairs.size()
                        + ": accepted " + matchedPairs + ", OpenCV " + openCvMatchedPairs);
            }
        }
        report(progress, "lens", false, "Averaging " + corrections.size() + " pose corrections");
        for (PoseCorrection correction : corrections.values()) {
            correction.averageAndClamp();
        }
        FeatureMatchGraph featureMatchGraph = new FeatureMatchGraph(matchEdges, true);
        GlobalPoseOptimization poseOptimization = GlobalPoseOptimization.from(featureMatchGraph, corrections);
        report(progress, "lens", false, "Running residual pose-graph optimizer");
        poseOptimization.apply();
        report(progress, "lens", false, "Estimating sparse parallax hints and exposure gains");
        SparseDepthHints sparseDepthHints = SparseDepthHints.from(featureMatchGraph, captureProfile);
        return new Calibration(
                lensModel,
                corrections,
                matchedPairs,
                openCvMatchedPairs,
                matchedPairs == 0 ? 0f : confidenceTotal / matchedPairs,
                captureProfile,
                sparseDepthHints,
                CameraLensPriors.from(records),
                estimateExposureGains(frames));
    }

    private static Calibration calibrateGraphSession(
            List<DraftFrameRecord> records,
            CaptureSessionRecord session,
            CaptureProfile captureProfile,
            SpherifyLibrary.ProgressReporter progress) throws IOException {
        LensModel lensModel = estimateLensModel(records);
        report(progress, "lens", false, "Decoding graph calibration frames");
        ArrayList<CalibrationFrame> frames = new ArrayList<>();
        HashMap<String, DraftFrameRecord> acceptedRecordsById = new HashMap<>();
        HashMap<String, CalibrationFrame> calibrationById = new HashMap<>();
        for (CaptureFrameRecord frameRecord : session.frames) {
            if (frameRecord.role != CaptureFrameRole.ACCEPTED) {
                continue;
            }
            DraftFrameRecord draft = draftRecordFor(frameRecord, records);
            if (draft == null) {
                continue;
            }
            acceptedRecordsById.put(frameRecord.id, draft);
            CalibrationFrame frame = CalibrationFrame.decode(draft, lensModel);
            if (frame != null) {
                calibrationById.put(frameRecord.id, frame);
                frames.add(frame);
            }
        }
        GraphBuildResult graph = GraphBuildResult.from(session, acceptedRecordsById, calibrationById, lensModel);
        if (!graph.ready()) {
            throw new IOException("graph-readiness gate failed; " + graph.failureReason());
        }
        report(progress, "lens", false, "Filtered graph edges: accepted " + graph.edges.size()
                + ", rejected " + graph.rejectedEdges + " (" + graph.rejectedReasons + ")");
        GraphBundleSolver solver = GraphBundleSolver.from(records, graph.edges, lensModel);
        report(progress, "lens", false, "Running global graph optimization over yaw, pitch, roll, FOV, and radial distortion");
        OptimizedCameraGraph optimized = solver.solve(progress).withRejectedEdges(graph.rejectedEdges);
        report(progress, "lens", false, String.format(
                Locale.US,
                "Graph solve residuals: mean %.2f px, max %.2f px, closure %.2f deg",
                optimized.diagnostics.meanResidualPixels,
                optimized.diagnostics.maxResidualPixels,
                optimized.diagnostics.closureErrorDegrees));
        SparseDepthHints sparseDepthHints = SparseDepthHints.from(new FeatureMatchGraph(graph.toFeatureEdges(), true), captureProfile);
        return new Calibration(
                optimized.lensModel,
                optimized.toPoseCorrections(records),
                optimized.diagnostics.edgesUsed,
                optimized.diagnostics.edgesUsed,
                optimized.diagnostics.meanConfidence,
                captureProfile,
                sparseDepthHints,
                CameraLensPriors.from(records),
                estimateExposureGains(frames),
                optimized.diagnostics);
    }

    private static DraftFrameRecord draftRecordFor(CaptureFrameRecord frame, List<DraftFrameRecord> records) {
        String path = new File(frame.rawFacts.filePath).getAbsolutePath();
        for (DraftFrameRecord record : records) {
            if (path.equals(record.imageFile.getAbsolutePath())) {
                return record;
            }
        }
        return null;
    }

    private static Map<String, Float> estimateExposureGains(List<CalibrationFrame> frames) {
        HashMap<String, Float> gains = new HashMap<>();
        float total = 0f;
        int count = 0;
        for (CalibrationFrame frame : frames) {
            if (frame.meanLuminance > 8f) {
                total += frame.meanLuminance;
                count++;
            }
        }
        if (count == 0) {
            return gains;
        }
        float target = total / count;
        for (CalibrationFrame frame : frames) {
            if (frame.meanLuminance > 8f) {
                gains.put(
                        frame.record.imageFile.getAbsolutePath(),
                        clamp(target / frame.meanLuminance, 0.72f, 1.38f));
            }
        }
        return gains;
    }

    private static LensModel estimateLensModel(List<DraftFrameRecord> records) {
        float focalLengthTotal = 0f;
        float physicalHorizontalTotal = 0f;
        float physicalVerticalTotal = 0f;
        int focalLengthCount = 0;
        int physicalSizeCount = 0;
        for (DraftFrameRecord record : records) {
            if (record.lensFocalLengthMm > 0.5f && record.lensFocalLengthMm < 12f) {
                focalLengthTotal += record.lensFocalLengthMm;
                if (record.sensorPhysicalWidthMm > 0f && record.sensorPhysicalHeightMm > 0f) {
                    physicalHorizontalTotal += Math.min(record.sensorPhysicalWidthMm, record.sensorPhysicalHeightMm);
                    physicalVerticalTotal += Math.max(record.sensorPhysicalWidthMm, record.sensorPhysicalHeightMm);
                    physicalSizeCount++;
                }
                focalLengthCount++;
            }
        }
        float focalLength = focalLengthCount == 0 ? 0f : focalLengthTotal / focalLengthCount;
        float physicalHorizontal = physicalSizeCount == 0 ? 0f : physicalHorizontalTotal / physicalSizeCount;
        float physicalVertical = physicalSizeCount == 0 ? 0f : physicalVerticalTotal / physicalSizeCount;
        float horizontalFov = DEFAULT_HORIZONTAL_FOV_DEGREES;
        float verticalFov = clamp(horizontalFov * 1.25f, MIN_VERTICAL_FOV_DEGREES, MAX_VERTICAL_FOV_DEGREES);
        LensModel intrinsicsModel = estimateIntrinsicsLensModel(records, focalLength);
        if (intrinsicsModel != null) {
            return intrinsicsModel;
        }
        if (focalLength > 0f) {
            if (physicalHorizontal > 0f && physicalVertical > 0f) {
                horizontalFov = clamp(
                        (float) Math.toDegrees(2.0 * Math.atan(physicalHorizontal / (2.0 * focalLength))),
                        42f,
                        86f);
                verticalFov = clamp(
                        (float) Math.toDegrees(2.0 * Math.atan(physicalVertical / (2.0 * focalLength))),
                        MIN_VERTICAL_FOV_DEGREES,
                        MAX_VERTICAL_FOV_DEGREES);
            } else if (focalLength <= 2.2f) {
                horizontalFov = 78f;
            } else if (focalLength <= 3.2f) {
                horizontalFov = 70f;
            } else if (focalLength <= 4.5f) {
                horizontalFov = 62f;
            } else {
                horizontalFov = 54f;
            }
            if (physicalHorizontal <= 0f || physicalVertical <= 0f) {
                verticalFov = clamp(horizontalFov * 1.25f, MIN_VERTICAL_FOV_DEGREES, MAX_VERTICAL_FOV_DEGREES);
            }
        }
        int largestSameLayerCount = largestSameLayerCount(records);
        if (largestSameLayerCount >= 16) {
            horizontalFov = clamp(horizontalFov, 56f, 74f);
        }
        float k1 = focalLength > 0f && focalLength <= 3.2f ? -0.12f : RADIAL_COMPENSATION_K1;
        float k2 = focalLength > 0f && focalLength <= 3.2f ? 0.035f : RADIAL_COMPENSATION_K2;
        return new LensModel(horizontalFov, verticalFov, k1, k2, focalLength);
    }

    private static LensModel estimateIntrinsicsLensModel(List<DraftFrameRecord> records, float focalLengthMm) {
        float horizontalTotal = 0f;
        float verticalTotal = 0f;
        int count = 0;
        for (DraftFrameRecord record : records) {
            if (record.imageFocalLengthXPixels <= 0f
                    || record.imageFocalLengthYPixels <= 0f
                    || record.imageIntrinsicsWidth <= 0
                    || record.imageIntrinsicsHeight <= 0) {
                continue;
            }
            float rawHorizontal = (float) Math.toDegrees(
                    2.0 * Math.atan(record.imageIntrinsicsWidth / (2.0 * record.imageFocalLengthXPixels)));
            float rawVertical = (float) Math.toDegrees(
                    2.0 * Math.atan(record.imageIntrinsicsHeight / (2.0 * record.imageFocalLengthYPixels)));
            horizontalTotal += Math.min(rawHorizontal, rawVertical);
            verticalTotal += Math.max(rawHorizontal, rawVertical);
            count++;
        }
        if (count == 0) {
            return null;
        }
        float horizontalFov = clamp(horizontalTotal / count, 42f, 86f);
        float verticalFov = clamp(verticalTotal / count, MIN_VERTICAL_FOV_DEGREES, MAX_VERTICAL_FOV_DEGREES);
        float k1 = focalLengthMm > 0f && focalLengthMm <= 3.2f ? -0.12f : RADIAL_COMPENSATION_K1;
        float k2 = focalLengthMm > 0f && focalLengthMm <= 3.2f ? 0.035f : RADIAL_COMPENSATION_K2;
        return new LensModel(horizontalFov, verticalFov, k1, k2, focalLengthMm);
    }

    private static int largestSameLayerCount(List<DraftFrameRecord> records) {
        HashMap<Integer, Integer> counts = new HashMap<>();
        for (DraftFrameRecord record : records) {
            if (Math.abs(record.targetPitchDegrees) >= 70) {
                continue;
            }
            int pitch = record.targetPitchDegrees;
            counts.put(pitch, counts.getOrDefault(pitch, 0) + 1);
        }
        int largest = 0;
        for (int count : counts.values()) {
            largest = Math.max(largest, count);
        }
        return largest;
    }

    private static CalibrationFrame nextFrameInLayer(List<CalibrationFrame> frames, int index) {
        CalibrationFrame current = frames.get(index);
        CalibrationFrame best = null;
        float bestDelta = Float.MAX_VALUE;
        for (CalibrationFrame candidate : frames) {
            if (candidate == current || candidate.basePitchDegrees != current.basePitchDegrees) {
                continue;
            }
            float delta = normalizeDegrees(candidate.baseYawDegrees - current.baseYawDegrees);
            if (delta > 0.1f && delta < bestDelta && delta <= 32f) {
                best = candidate;
                bestDelta = delta;
            }
        }
        return best;
    }

    private static List<FramePair> predictedOverlapPairs(List<CalibrationFrame> frames, LensModel lensModel) {
        ArrayList<FramePair> pairs = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            CalibrationFrame left = frames.get(i);
            for (int j = i + 1; j < frames.size(); j++) {
                CalibrationFrame right = frames.get(j);
                float yawDelta = headingDeltaDegrees(left.baseYawDegrees, right.baseYawDegrees);
                float pitchDelta = Math.abs(left.basePitchDegrees - right.basePitchDegrees);
                boolean sameRow = pitchDelta <= 2f
                        && yawDelta <= lensModel.horizontalFovDegrees * 0.72f;
                boolean adjacentRow = pitchDelta <= lensModel.verticalFovDegrees * 0.82f
                        && yawDelta <= lensModel.horizontalFovDegrees * 0.55f;
                if (sameRow || adjacentRow) {
                    pairs.add(new FramePair(left, right));
                }
            }
        }
        return pairs;
    }

    private static PairAdjustment matchFeatureOverlap(
            CalibrationFrame left,
            CalibrationFrame right,
            MovementSensitivity movementSensitivity) {
        PairAdjustment openCvAdjustment = matchOpenCvFeatureOverlap(left, right, movementSensitivity);
        if (openCvAdjustment != null) {
            return openCvAdjustment;
        }
        List<ImageFeature> leftFeatures = detectOverlapFeatures(left, true);
        List<ImageFeature> rightFeatures = detectOverlapFeatures(right, false);
        if (leftFeatures.size() < FEATURE_MIN_MATCHES || rightFeatures.size() < FEATURE_MIN_MATCHES) {
            return null;
        }
        ArrayList<FeaturePair> matches = new ArrayList<>();
        for (ImageFeature leftFeature : leftFeatures) {
            ImageFeature best = null;
            int bestDistance = Integer.MAX_VALUE;
            int secondDistance = Integer.MAX_VALUE;
            for (ImageFeature rightFeature : rightFeatures) {
                int distance = descriptorDistance(leftFeature.descriptor, rightFeature.descriptor);
                if (distance < bestDistance) {
                    secondDistance = bestDistance;
                    bestDistance = distance;
                    best = rightFeature;
                } else if (distance < secondDistance) {
                    secondDistance = distance;
                }
            }
            if (best != null && bestDistance < 92 && bestDistance * 100 < secondDistance * 78) {
                matches.add(new FeaturePair(leftFeature, best, bestDistance));
            }
        }
        if (matches.size() < FEATURE_MIN_MATCHES) {
            return null;
        }

        int bestInliers = 0;
        float bestDx = 0f;
        float bestDy = 0f;
        float bestResidual = Float.MAX_VALUE;
        for (FeaturePair seed : matches) {
            float dx = seed.right.matchX - seed.left.matchX;
            float dy = seed.right.matchY - seed.left.matchY;
            int inliers = 0;
            float residual = 0f;
            for (FeaturePair candidate : matches) {
                float candidateDx = candidate.right.matchX - candidate.left.matchX;
                float candidateDy = candidate.right.matchY - candidate.left.matchY;
                float error = Math.abs(candidateDx - dx) + Math.abs(candidateDy - dy);
                if (error <= RANSAC_TRANSLATION_TOLERANCE_PIXELS) {
                    inliers++;
                    residual += error;
                }
            }
            if (inliers > bestInliers || (inliers == bestInliers && residual < bestResidual)) {
                bestInliers = inliers;
                bestDx = dx;
                bestDy = dy;
                bestResidual = residual;
            }
        }
        if (bestInliers < FEATURE_MIN_MATCHES) {
            return null;
        }
        float refinedDx = 0f;
        float refinedDy = 0f;
        float descriptorTotal = 0f;
        int refinedInliers = 0;
        for (FeaturePair candidate : matches) {
            float candidateDx = candidate.right.matchX - candidate.left.matchX;
            float candidateDy = candidate.right.matchY - candidate.left.matchY;
            float error = Math.abs(candidateDx - bestDx) + Math.abs(candidateDy - bestDy);
            if (error <= RANSAC_TRANSLATION_TOLERANCE_PIXELS) {
                refinedDx += candidateDx;
                refinedDy += candidateDy;
                descriptorTotal += candidate.distance;
                refinedInliers++;
            }
        }
        if (refinedInliers == 0) {
            return null;
        }
        refinedDx /= refinedInliers;
        refinedDy /= refinedInliers;
        float inlierFraction = refinedInliers / (float) Math.max(1, matches.size());
        float meanDescriptorDistance = descriptorTotal / refinedInliers;
        float confidence = clamp(inlierFraction * (1f - meanDescriptorDistance / 128f), 0f, 1f);
        if (confidence < movementSensitivity.minimumConfidence) {
            return null;
        }
        return new PairAdjustment(
                Math.round(refinedDx),
                Math.round(refinedDy),
                0,
                bestResidual / Math.max(1, refinedInliers),
                confidence);
    }

    private static PairAdjustment matchOpenCvFeatureOverlap(
            CalibrationFrame left,
            CalibrationFrame right,
            MovementSensitivity movementSensitivity) {
        if (!OPENCV_AVAILABLE) {
            return null;
        }
        Mat leftMat = null;
        Mat rightMat = null;
        Mat leftOverlap = null;
        Mat rightOverlap = null;
        MatOfKeyPoint leftKeypoints = new MatOfKeyPoint();
        MatOfKeyPoint rightKeypoints = new MatOfKeyPoint();
        Mat leftDescriptors = new Mat();
        Mat rightDescriptors = new Mat();
        try {
            leftMat = left.toOpenCvMat();
            rightMat = right.toOpenCvMat();
            float yawDelta = headingDeltaDegrees(left.baseYawDegrees, right.baseYawDegrees);
            float pitchDelta = Math.abs(left.basePitchDegrees - right.basePitchDegrees);
            if (pitchDelta > yawDelta * 0.7f) {
                int leftStartY = left.basePitchDegrees > right.basePitchDegrees
                        ? Math.max(0, left.height - left.height / 2)
                        : 0;
                int rightStartY = left.basePitchDegrees > right.basePitchDegrees
                        ? 0
                        : Math.max(0, right.height - right.height / 2);
                int leftHeight = Math.max(1, left.height / 2);
                int rightHeight = Math.max(1, right.height / 2);
                leftOverlap = leftMat.submat(new org.opencv.core.Rect(0, leftStartY, left.width, leftHeight));
                rightOverlap = rightMat.submat(new org.opencv.core.Rect(0, rightStartY, right.width, rightHeight));
            } else {
                boolean rightIsClockwise = normalizeDegrees(right.baseYawDegrees - left.baseYawDegrees) <= 180f;
                int leftStartX = rightIsClockwise ? Math.max(0, left.width - left.width / 2) : 0;
                int rightStartX = rightIsClockwise ? 0 : Math.max(0, right.width - right.width / 2);
                int leftWidth = Math.max(1, left.width / 2);
                int rightWidth = Math.max(1, right.width / 2);
                leftOverlap = leftMat.submat(new org.opencv.core.Rect(leftStartX, 0, leftWidth, left.height));
                rightOverlap = rightMat.submat(new org.opencv.core.Rect(rightStartX, 0, rightWidth, right.height));
            }

            ORB orb = ORB.create(500);
            orb.detectAndCompute(leftOverlap, new Mat(), leftKeypoints, leftDescriptors);
            orb.detectAndCompute(rightOverlap, new Mat(), rightKeypoints, rightDescriptors);
            if (leftDescriptors.empty() || rightDescriptors.empty()) {
                return null;
            }

            List<MatOfDMatch> knnLeftToRight = new ArrayList<>();
            List<MatOfDMatch> knnRightToLeft = new ArrayList<>();
            BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);
            matcher.knnMatch(leftDescriptors, rightDescriptors, knnLeftToRight, 2);
            matcher.knnMatch(rightDescriptors, leftDescriptors, knnRightToLeft, 2);
            boolean[][] reverseAccepted = acceptedReverseMatches(knnRightToLeft);
            KeyPoint[] leftPoints = leftKeypoints.toArray();
            KeyPoint[] rightPoints = rightKeypoints.toArray();
            ArrayList<OpenCvFeaturePair> accepted = new ArrayList<>();
            for (MatOfDMatch pair : knnLeftToRight) {
                DMatch[] matches = pair.toArray();
                if (matches.length < 2) {
                    continue;
                }
                DMatch best = matches[0];
                DMatch second = matches[1];
                if (best.distance >= second.distance * 0.78f) {
                    continue;
                }
                if (best.queryIdx < 0 || best.queryIdx >= leftPoints.length
                        || best.trainIdx < 0 || best.trainIdx >= rightPoints.length
                        || best.trainIdx >= reverseAccepted.length
                        || best.queryIdx >= reverseAccepted[best.trainIdx].length
                        || !reverseAccepted[best.trainIdx][best.queryIdx]) {
                    continue;
                }
                accepted.add(new OpenCvFeaturePair(leftPoints[best.queryIdx].pt, rightPoints[best.trainIdx].pt, best.distance));
            }
            if (accepted.size() < FEATURE_MIN_MATCHES) {
                return null;
            }

            MatOfPoint2f leftControlPoints = new MatOfPoint2f();
            MatOfPoint2f rightControlPoints = new MatOfPoint2f();
            Point[] leftArray = new Point[accepted.size()];
            Point[] rightArray = new Point[accepted.size()];
            for (int i = 0; i < accepted.size(); i++) {
                leftArray[i] = accepted.get(i).left;
                rightArray[i] = accepted.get(i).right;
            }
            leftControlPoints.fromArray(leftArray);
            rightControlPoints.fromArray(rightArray);
            Mat inlierMask = new Mat();
            Mat homography = Geometry.findHomography(
                    leftControlPoints,
                    rightControlPoints,
                    Geometry.RANSAC,
                    RANSAC_TRANSLATION_TOLERANCE_PIXELS,
                    inlierMask);
            if (homography.empty() || inlierMask.empty()) {
                return null;
            }

            byte[] mask = new byte[(int) inlierMask.total()];
            inlierMask.get(0, 0, mask);
            float dxTotal = 0f;
            float dyTotal = 0f;
            float descriptorTotal = 0f;
            float residualTotal = 0f;
            int inliers = 0;
            ArrayList<ControlPointPair> inlierControlPoints = new ArrayList<>();
            for (int i = 0; i < mask.length && i < accepted.size(); i++) {
                if (mask[i] == 0) {
                    continue;
                }
                OpenCvFeaturePair pair = accepted.get(i);
                float dx = (float) (pair.right.x - pair.left.x);
                float dy = (float) (pair.right.y - pair.left.y);
                dxTotal += dx;
                dyTotal += dy;
                descriptorTotal += pair.distance;
                inlierControlPoints.add(new ControlPointPair(
                        (float) pair.left.x,
                        (float) pair.left.y,
                        (float) pair.right.x,
                        (float) pair.right.y,
                        pair.distance));
                inliers++;
            }
            if (inliers < FEATURE_MIN_MATCHES) {
                return null;
            }
            float meanDx = dxTotal / inliers;
            float meanDy = dyTotal / inliers;
            for (int i = 0; i < mask.length && i < accepted.size(); i++) {
                if (mask[i] == 0) {
                    continue;
                }
                OpenCvFeaturePair pair = accepted.get(i);
                residualTotal += Math.abs((float) (pair.right.x - pair.left.x) - meanDx)
                        + Math.abs((float) (pair.right.y - pair.left.y) - meanDy);
            }
            float meanDescriptor = descriptorTotal / inliers;
            float inlierFraction = inliers / (float) Math.max(1, accepted.size());
            float confidence = clamp(inlierFraction * (1f - meanDescriptor / 96f), 0f, 1f);
            if (confidence < movementSensitivity.minimumConfidence) {
                return null;
            }
            return new PairAdjustment(
                    Math.round(meanDx),
                    Math.round(meanDy),
                    0,
                    residualTotal / Math.max(1, inliers),
                    confidence,
                    true,
                    inlierControlPoints);
        } catch (Throwable ignored) {
            return null;
        } finally {
            releaseMat(leftMat);
            releaseMat(rightMat);
            releaseMat(leftOverlap);
            releaseMat(rightOverlap);
            releaseMat(leftKeypoints);
            releaseMat(rightKeypoints);
            releaseMat(leftDescriptors);
            releaseMat(rightDescriptors);
        }
    }

    private static boolean[][] acceptedReverseMatches(List<MatOfDMatch> knnMatches) {
        boolean[][] accepted = new boolean[knnMatches.size()][];
        for (int query = 0; query < knnMatches.size(); query++) {
            DMatch[] matches = knnMatches.get(query).toArray();
            int maxTrain = 0;
            for (DMatch match : matches) {
                maxTrain = Math.max(maxTrain, match.trainIdx);
            }
            accepted[query] = new boolean[Math.max(0, maxTrain + 1)];
            if (matches.length < 2) {
                continue;
            }
            DMatch best = matches[0];
            DMatch second = matches[1];
            if (best.trainIdx >= 0 && best.distance < second.distance * 0.78f) {
                accepted[query][best.trainIdx] = true;
            }
        }
        return accepted;
    }

    private static void releaseMat(Mat mat) {
        if (mat != null) {
            mat.release();
        }
    }

    private static List<ImageFeature> detectOverlapFeatures(CalibrationFrame frame, boolean rightSide) {
        ArrayList<ImageFeature> features = new ArrayList<>();
        int startX = rightSide ? Math.max(1, frame.width - frame.width / 2) : 1;
        int endX = rightSide ? frame.width - 1 : Math.min(frame.width - 1, frame.width / 2);
        int step = Math.max(3, Math.min(frame.width, frame.height) / 80);
        for (int y = FEATURE_DESCRIPTOR_RADIUS + 1; y < frame.height - FEATURE_DESCRIPTOR_RADIUS - 1; y += step) {
            for (int x = startX + FEATURE_DESCRIPTOR_RADIUS; x < endX - FEATURE_DESCRIPTOR_RADIUS; x += step) {
                int gx = frame.grayAt(x + 1, y) - frame.grayAt(x - 1, y);
                int gy = frame.grayAt(x, y + 1) - frame.grayAt(x, y - 1);
                int diagonalA = frame.grayAt(x + 1, y + 1) - frame.grayAt(x - 1, y - 1);
                int diagonalB = frame.grayAt(x + 1, y - 1) - frame.grayAt(x - 1, y + 1);
                int score = Math.abs(gx) + Math.abs(gy) + Math.abs(diagonalA) + Math.abs(diagonalB);
                if (score < 72) {
                    continue;
                }
                float matchX = rightSide ? x - startX : x;
                insertFeature(features, new ImageFeature(x, y, matchX, y, score, buildBriefDescriptor(frame, x, y)));
            }
        }
        return features;
    }

    private static void insertFeature(ArrayList<ImageFeature> features, ImageFeature feature) {
        int index = 0;
        while (index < features.size() && features.get(index).score >= feature.score) {
            index++;
        }
        features.add(index, feature);
        if (features.size() > FEATURE_MAX_POINTS) {
            features.remove(features.size() - 1);
        }
    }

    private static long[] buildBriefDescriptor(CalibrationFrame frame, int x, int y) {
        long first = 0L;
        long second = 0L;
        for (int i = 0; i < 64; i++) {
            if (sampleBriefBit(frame, x, y, i)) {
                first |= 1L << i;
            }
            if (sampleBriefBit(frame, x, y, i + 64)) {
                second |= 1L << i;
            }
        }
        return new long[]{first, second};
    }

    private static boolean sampleBriefBit(CalibrationFrame frame, int x, int y, int index) {
        int ax = ((index * 37 + 11) % 17) - 8;
        int ay = ((index * 19 + 7) % 17) - 8;
        int bx = ((index * 29 + 5) % 17) - 8;
        int by = ((index * 43 + 13) % 17) - 8;
        return frame.grayAt(x + ax, y + ay) > frame.grayAt(x + bx, y + by);
    }

    private static int descriptorDistance(long[] left, long[] right) {
        return Long.bitCount(left[0] ^ right[0]) + Long.bitCount(left[1] ^ right[1]);
    }

    private static PairAdjustment correlateOverlap(
            CalibrationFrame left,
            CalibrationFrame right,
            MovementSensitivity movementSensitivity) {
        PairAdjustment best = null;
        int strip = Math.min(OVERLAP_STRIP_WIDTH, Math.min(left.width, right.width) / 4);
        if (strip < 8) {
            return null;
        }
        for (int dx = -OVERLAP_SEARCH_X_PIXELS; dx <= OVERLAP_SEARCH_X_PIXELS; dx += 4) {
            for (int dy = -OVERLAP_SEARCH_Y_PIXELS; dy <= OVERLAP_SEARCH_Y_PIXELS; dy += 3) {
                for (int roll = -OVERLAP_SEARCH_ROLL_PIXELS; roll <= OVERLAP_SEARCH_ROLL_PIXELS; roll += 4) {
                    PairAdjustment candidate = scoreOverlap(left, right, strip, dx, dy, roll, movementSensitivity);
                    if (candidate != null && (best == null || candidate.score < best.score)) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static PairAdjustment scoreOverlap(
            CalibrationFrame left,
            CalibrationFrame right,
            int strip,
            int dx,
            int dy,
            int roll,
            MovementSensitivity movementSensitivity) {
        float difference = 0f;
        float contrast = 0f;
        int samples = 0;
        int rejectedSamples = 0;
        int yStep = Math.max(1, left.height / 56);
        int xStep = Math.max(1, strip / 10);
        for (int y = yStep; y < left.height - yStep; y += yStep) {
            for (int sx = 0; sx < strip; sx += xStep) {
                int leftX = left.width - strip + sx;
                int leftValue = left.grayAt(leftX, y);
                float rollSlope = (sx / Math.max(1f, strip - 1f)) - 0.5f;
                int rightX = clamp(sx + dx, 0, right.width - 1);
                int rightY = clamp(Math.round(y + dy + roll * rollSlope), 0, right.height - 1);
                int rightValue = right.grayAt(rightX, rightY);
                int leftGradientX = left.grayAt(Math.min(left.width - 1, leftX + xStep), y)
                        - left.grayAt(Math.max(0, leftX - xStep), y);
                int rightGradientX = right.grayAt(Math.min(right.width - 1, rightX + xStep), rightY)
                        - right.grayAt(Math.max(0, rightX - xStep), rightY);
                int leftGradientY = left.grayAt(leftX, Math.min(left.height - 1, y + yStep))
                        - left.grayAt(leftX, Math.max(0, y - yStep));
                int rightGradientY = right.grayAt(rightX, Math.min(right.height - 1, rightY + yStep))
                        - right.grayAt(rightX, Math.max(0, rightY - yStep));
                int gradientDelta = Math.abs(leftGradientX - rightGradientX)
                        + Math.abs(leftGradientY - rightGradientY);
                int luminanceDelta = Math.abs(leftValue - rightValue);
                if (gradientDelta > movementSensitivity.gradientOutlierThreshold
                        || luminanceDelta > movementSensitivity.luminanceOutlierThreshold) {
                    rejectedSamples++;
                    continue;
                }
                float centralWeight = centerWeight(sx / Math.max(1f, strip - 1f), y / Math.max(1f, left.height - 1f));
                difference += (gradientDelta * gradientDelta + luminanceDelta * luminanceDelta * 0.15f)
                        * centralWeight;
                contrast += (Math.abs(leftGradientX) + Math.abs(rightGradientX)
                        + Math.abs(leftGradientY) + Math.abs(rightGradientY))
                        * centralWeight;
                samples++;
            }
        }
        int totalSamples = samples + rejectedSamples;
        if (samples == 0 || totalSamples == 0
                || rejectedSamples / (float) totalSamples > movementSensitivity.maximumRejectedFraction) {
            return null;
        }
        float meanDifference = difference / samples;
        float confidence = contrast / (samples * 255f);
        float rejectionPenalty = 1f + rejectedSamples / (float) Math.max(1, totalSamples);
        return new PairAdjustment(dx, dy, roll, meanDifference * rejectionPenalty / Math.max(0.05f, confidence), confidence);
    }

    private static FrameProjection projectionFor(DraftFrameRecord record, Calibration calibration) {
        boolean polar = Math.abs(record.targetPitchDegrees) >= 70;
        float baseYaw = record.capturedPoseAvailable
                ? record.headingDegrees
                : record.targetYawDegrees;
        float basePitch = record.capturedPoseAvailable
                ? record.pitchDegrees
                : record.targetPitchDegrees;
        float baseRoll = record.capturedPoseAvailable
                ? record.rollDegrees
                : 0f;
        float pitch = clamp(basePitch, -89f, 89f);
        float horizontalFov = polar ? POLAR_HORIZONTAL_FOV_DEGREES : calibration.lensModel.horizontalFovDegrees;
        float verticalFov = polar
                ? POLAR_VERTICAL_FOV_DEGREES
                : clamp(calibration.lensModel.verticalFovDegrees, MIN_VERTICAL_FOV_DEGREES, MAX_VERTICAL_FOV_DEGREES);
        PoseCorrection correction = calibration.corrections.get(record.imageFile.getAbsolutePath());
        float yaw = normalizeDegrees(baseYaw + (correction == null ? 0f : correction.yawDegrees));
        pitch = clamp(pitch + (correction == null ? 0f : correction.pitchDegrees), -89f, 89f);
        float roll = polar ? 0f : clamp(baseRoll * 0.10f + (correction == null ? 0f : correction.rollDegrees), -5f, 5f);
        float centerX = yaw / 360f * OUTPUT_WIDTH;
        float centerY = (90f - pitch) / 180f * OUTPUT_HEIGHT;
        float width = horizontalFov / 360f * OUTPUT_WIDTH;
        float height = verticalFov / 180f * OUTPUT_HEIGHT;
        RectF bounds = new RectF(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f);
        return new FrameProjection(yaw, pitch, roll, horizontalFov, verticalFov, bounds);
    }

    private static void blendWrapped(
            Bitmap frame,
            FrameProjection projection,
            LensModel lensModel,
            RenderMode renderMode,
            int frameIndex,
            float exposureGain,
            float[] red,
            float[] green,
            float[] blue,
            float[] weights,
            int[] seamSource,
            float[] seamScores,
            SeamFinder seamFinder) {
        blendOne(frame, projection, lensModel, renderMode, frameIndex, exposureGain, projection.bounds, red, green, blue, weights, seamSource, seamScores, seamFinder);
        if (projection.bounds.left < 0f) {
            RectF shifted = new RectF(projection.bounds);
            shifted.offset(OUTPUT_WIDTH, 0f);
            blendOne(frame, projection, lensModel, renderMode, frameIndex, exposureGain, shifted, red, green, blue, weights, seamSource, seamScores, seamFinder);
        }
        if (projection.bounds.right > OUTPUT_WIDTH) {
            RectF shifted = new RectF(projection.bounds);
            shifted.offset(-OUTPUT_WIDTH, 0f);
            blendOne(frame, projection, lensModel, renderMode, frameIndex, exposureGain, shifted, red, green, blue, weights, seamSource, seamScores, seamFinder);
        }
    }

    private static void blendOne(
            Bitmap frame,
            FrameProjection projection,
            LensModel lensModel,
            RenderMode renderMode,
            int frameIndex,
            float exposureGain,
            RectF bounds,
            float[] red,
            float[] green,
            float[] blue,
            float[] weights,
            int[] seamSource,
            float[] seamScores,
            SeamFinder seamFinder) {
        int startX = clamp((int) Math.floor(bounds.left), 0, OUTPUT_WIDTH - 1);
        int endX = clamp((int) Math.ceil(bounds.right), 0, OUTPUT_WIDTH - 1);
        int startY = clamp((int) Math.floor(bounds.top), 0, OUTPUT_HEIGHT - 1);
        int endY = clamp((int) Math.ceil(bounds.bottom), 0, OUTPUT_HEIGHT - 1);
        if (endX <= startX || endY <= startY) {
            return;
        }

        int frameWidth = frame.getWidth();
        int frameHeight = frame.getHeight();
        int[] pixels = new int[frameWidth * frameHeight];
        frame.getPixels(pixels, 0, frameWidth, 0, 0, frameWidth, frameHeight);

        CameraBasis basis = CameraBasis.from(projection.yawDegrees, projection.pitchDegrees, projection.rollDegrees);
        float tanHalfHorizontal = (float) Math.tan(Math.toRadians(projection.horizontalFovDegrees / 2f));
        float tanHalfVertical = (float) Math.tan(Math.toRadians(projection.verticalFovDegrees / 2f));
        for (int y = startY; y <= endY; y++) {
            float outputPitch = 90f - ((y + 0.5f) / OUTPUT_HEIGHT * 180f);
            int outputRow = y * OUTPUT_WIDTH;
            for (int x = startX; x <= endX; x++) {
                float outputYaw = (x + 0.5f) / OUTPUT_WIDTH * 360f;
                Ray ray = Ray.from(outputYaw, outputPitch);
                float localX = basis.dotRight(ray);
                float localY = basis.dotUp(ray);
                float localZ = basis.dotForward(ray);
                if (localZ <= 0.001f) {
                    continue;
                }

                float planeX = localX / (localZ * tanHalfHorizontal);
                float planeY = localY / (localZ * tanHalfVertical);
                if (planeX < -1f || planeX > 1f || planeY < -1f || planeY > 1f) {
                    continue;
                }

                float[] compensated = compensateRadial(planeX, planeY, lensModel);
                float sourceU = (compensated[0] + 1f) * 0.5f;
                float sourceV = (1f - compensated[1]) * 0.5f;
                if (sourceU < 0f || sourceU > 1f || sourceV < 0f || sourceV > 1f) {
                    continue;
                }

                int sourceX = clamp((int) (sourceU * frameWidth), 0, frameWidth - 1);
                int sourceY = clamp((int) (sourceV * frameHeight), 0, frameHeight - 1);
                float weight = feather(sourceU) * feather(sourceV) * centerWeight(sourceU, sourceV);
                if (weight <= 0f) {
                    continue;
                }
                int color = pixels[sourceY * frameWidth + sourceX];
                if (renderMode == RenderMode.CONTRIBUTOR_MAP) {
                    color = contributorColor(frameIndex, sourceU, sourceV);
                } else {
                    color = applyExposureGain(color, exposureGain);
                }
                int index = outputRow + x;
                float seamScore = seamFinder.score(frameIndex, sourceU, sourceV, weight, exposureGain);
                if (renderMode.selectsBestSource && weight <= weights[index]) {
                    continue;
                }
                if (renderMode.selectsBestSource) {
                    red[index] = ((color >> 16) & 0xFF) * weight;
                    green[index] = ((color >> 8) & 0xFF) * weight;
                    blue[index] = (color & 0xFF) * weight;
                    weights[index] = weight;
                } else {
                    float seamWeight = seamFinder.blendWeight(weight, seamScore, seamScores[index], seamSource[index] == frameIndex);
                    red[index] += ((color >> 16) & 0xFF) * seamWeight;
                    green[index] += ((color >> 8) & 0xFF) * seamWeight;
                    blue[index] += (color & 0xFF) * seamWeight;
                    weights[index] += seamWeight;
                    if (seamScore > seamScores[index]) {
                        seamScores[index] = seamScore;
                        seamSource[index] = frameIndex;
                    }
                }
            }
        }
    }

    private static int applyExposureGain(int color, float gain) {
        if (Math.abs(gain - 1f) < 0.01f) {
            return color;
        }
        int r = clamp(Math.round(((color >> 16) & 0xFF) * gain), 0, 255);
        int g = clamp(Math.round(((color >> 8) & 0xFF) * gain), 0, 255);
        int b = clamp(Math.round((color & 0xFF) * gain), 0, 255);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float[] compensateRadial(float x, float y, LensModel lensModel) {
        float radiusSquared = x * x + y * y;
        float scale = 1f
                + lensModel.radialK1 * radiusSquared
                + lensModel.radialK2 * radiusSquared * radiusSquared;
        return new float[]{x * scale, y * scale};
    }

    private static Bitmap composeOutput(
            float[] red,
            float[] green,
            float[] blue,
            float[] weights,
            SpherifyLibrary.ProgressReporter progress) {
        int[] pixels = new int[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        report(progress, "write", false, "Composing output pixels from source weights");
        for (int i = 0; i < pixels.length; i++) {
            if (weights[i] <= 0f) {
                pixels[i] = 0xFF101820;
            } else {
                int r = clamp(Math.round(red[i] / weights[i]), 0, 255);
                int g = clamp(Math.round(green[i] / weights[i]), 0, 255);
                int b = clamp(Math.round(blue[i] / weights[i]), 0, 255);
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            if (i > 0 && i % (OUTPUT_WIDTH * 256) == 0) {
                report(progress, "write", false, "Composed row " + (i / OUTPUT_WIDTH) + "/" + OUTPUT_HEIGHT);
            }
        }
        report(progress, "write", false, "Uploading composed pixels into Bitmap");
        Bitmap output = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888);
        output.setPixels(pixels, 0, OUTPUT_WIDTH, 0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT);
        return output;
    }

    private static float feather(float position) {
        float edgeDistance = Math.min(position, 1f - position);
        return clamp(edgeDistance / FEATHER_FRACTION, 0f, 1f);
    }

    private static float centerWeight(float u, float v) {
        float dx = (u - 0.5f) * 2f;
        float dy = (v - 0.5f) * 2f;
        float radiusSquared = dx * dx + dy * dy;
        return clamp(1f - 0.72f * radiusSquared, 0.16f, 1f);
    }

    private static int contributorColor(int frameIndex, float sourceU, float sourceV) {
        int base = CONTRIBUTOR_COLORS[Math.abs(frameIndex) % CONTRIBUTOR_COLORS.length];
        float grid = sourceU < 0.04f || sourceU > 0.96f || sourceV < 0.04f || sourceV > 0.96f ? 0.55f : 1f;
        int r = Math.round(((base >> 16) & 0xFF) * grid);
        int g = Math.round(((base >> 8) & 0xFF) * grid);
        int b = Math.round((base & 0xFF) * grid);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void markCoverage(boolean[][] coverage, RectF target) {
        int startColumn = (int) Math.floor(target.left / OUTPUT_WIDTH * COVERAGE_COLUMNS);
        int endColumn = (int) Math.ceil(target.right / OUTPUT_WIDTH * COVERAGE_COLUMNS);
        int startRow = clamp((int) Math.floor(target.top / OUTPUT_HEIGHT * COVERAGE_ROWS), 0, COVERAGE_ROWS - 1);
        int endRow = clamp((int) Math.ceil(target.bottom / OUTPUT_HEIGHT * COVERAGE_ROWS), 0, COVERAGE_ROWS - 1);
        for (int row = startRow; row <= endRow; row++) {
            for (int column = startColumn; column <= endColumn; column++) {
                int wrappedColumn = ((column % COVERAGE_COLUMNS) + COVERAGE_COLUMNS) % COVERAGE_COLUMNS;
                coverage[row][wrappedColumn] = true;
            }
        }
    }

    private static int coveredCells(boolean[][] coverage) {
        int count = 0;
        for (boolean[] row : coverage) {
            for (boolean covered : row) {
                if (covered) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void writeMetadata(
            File file,
            String sessionId,
            int renderedFrames,
            int coveragePercent,
            int missingExposure,
            Calibration calibration,
            MovementSensitivity movementSensitivity,
            RenderMode renderMode,
            SeamReport seamReport,
            ExportValidationReport exportValidation) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, String.format(
                    Locale.US,
                    "Spherify 0.7.4 graph seam stitch; session=%s; frames=%d; estimatedCoverage=%d%%; missingExposure=%d; projection=equirectangular; hfov=%.1f; vfov=%.1f; k1=%.3f; k2=%.3f; matchedOverlaps=%d; openCvMatchedOverlaps=%d; movementSensitivity=%s; renderMode=%s; captureProfile=%s; phase5bLensPrior=%s; posePriors=%d; intrinsicsPriors=%d; focalPriors=%d; sparseDepthHints=%s; graphEdgesUsed=%d; graphEdgesRejected=%d; meanResidualPx=%.2f; maxResidualPx=%.2f; closureErrorDeg=%.2f; parallaxWarnings=%d; exposureGainImages=%d; seamLowConfidencePixels=%d; seamExposurePenaltyPixels=%d; reviewState=%s; exportValidation=%s",
                    sessionId,
                    renderedFrames,
                    coveragePercent,
                    missingExposure,
                    calibration.lensModel.horizontalFovDegrees,
                    calibration.lensModel.verticalFovDegrees,
                    calibration.lensModel.radialK1,
                    calibration.lensModel.radialK2,
                    calibration.matchedOverlapCount,
                    calibration.openCvMatchedOverlapCount,
                    movementSensitivity.label,
                    renderMode.label,
                    calibration.captureProfile.label,
                    calibration.cameraLensPriors.sourceLabel,
                    calibration.cameraLensPriors.posePriorCount,
                    calibration.cameraLensPriors.intrinsicsPriorCount,
                    calibration.cameraLensPriors.focalLengthPriorCount,
                    calibration.sparseDepthHints.metadataLabel,
                    calibration.solveDiagnostics.edgesUsed,
                    calibration.solveDiagnostics.edgesRejected,
                    calibration.solveDiagnostics.meanResidualPixels,
                    calibration.solveDiagnostics.maxResidualPixels,
                    calibration.solveDiagnostics.closureErrorDegrees,
                    calibration.solveDiagnostics.parallaxWarnings,
                    calibration.exposureGains.size(),
                    seamReport.lowConfidenceSeamPixels,
                    seamReport.exposurePenaltyPixels,
                    exportValidation.reviewState,
                    exportValidation.summary));
            exif.setAttribute(ExifInterface.TAG_MAKE, "Spherify");
            exif.setAttribute(ExifInterface.TAG_MODEL, "0.7.4 seam/blend/export pipeline");
            exif.saveAttributes();
        } catch (IOException ignored) {
            // The master is still usable if optional metadata writing fails.
        }
    }

    private static List<String> warnings(
            int renderedFrames,
            int coveragePercent,
            int missingExposure,
            Calibration calibration,
            MovementSensitivity movementSensitivity,
            ExportValidationReport exportValidation,
            SeamReport seamReport) {
        ArrayList<String> warnings = new ArrayList<>();
        if (renderedFrames < 12) {
            warnings.add("Low frame count for a complete sphere");
        }
        if (coveragePercent < 70) {
            warnings.add("Weak estimated coverage");
        }
        if (missingExposure > 0) {
            warnings.add("Some frames have no exposure references");
        }
        if (calibration.matchedOverlapCount == 0) {
            warnings.add("No reliable overlap correlations found");
        } else if (calibration.openCvMatchedOverlapCount == 0) {
            warnings.add("OpenCV/RANSAC accepted no overlap pairs; fallback matching drove the alignment");
        } else {
            warnings.add(String.format(
                    Locale.US,
                    "Applied %d overlap pose nudges, %d from OpenCV/RANSAC, at %.2f confidence",
                    calibration.matchedOverlapCount,
                    calibration.openCvMatchedOverlapCount,
                    calibration.averageOverlapConfidence));
        }
        warnings.add(String.format(
                Locale.US,
                "Estimated lens %.1f deg HFOV, k1 %.3f, k2 %.3f",
                calibration.lensModel.horizontalFovDegrees,
                calibration.lensModel.radialK1,
                calibration.lensModel.radialK2));
        if (calibration.solveDiagnostics.edgesUsed > 0) {
            warnings.add(String.format(
                    Locale.US,
                    "Graph solver used %d edges; residual mean %.2f px, max %.2f px, closure %.2f deg",
                    calibration.solveDiagnostics.edgesUsed,
                    calibration.solveDiagnostics.meanResidualPixels,
                    calibration.solveDiagnostics.maxResidualPixels,
                    calibration.solveDiagnostics.closureErrorDegrees));
            warnings.add("Exposure compensation: per-image gain on " + calibration.exposureGains.size() + " frames");
        }
        if (calibration.solveDiagnostics.parallaxWarnings > 0) {
            warnings.add("Parallax warning: " + calibration.solveDiagnostics.parallaxWarnings + " graph edges still have high reprojection residuals");
        }
        warnings.add("Public quality state: " + exportValidation.reviewState + " (" + exportValidation.summary + ")");
        warnings.add("Seam selection: low-confidence penalties " + seamReport.lowConfidenceSeamPixels
                + ", exposure penalties " + seamReport.exposurePenaltyPixels);
        warnings.add("Capture profile: " + calibration.captureProfile.label);
        if (calibration.captureProfile == CaptureProfile.HANDHELD) {
            warnings.add("Hand-held parallax expected; use geometry debug output to inspect close-object conflicts");
        }
        if (calibration.sparseDepthHints.hasNearObjectRisk) {
            warnings.add(calibration.sparseDepthHints.warningLabel);
        }
        warnings.add("Movement sensitivity: " + movementSensitivity.label);
        return warnings;
    }

    private static float normalizeDegrees(float value) {
        float result = value % 360f;
        return result < 0f ? result + 360f : result;
    }

    private static float headingDeltaDegrees(float first, float second) {
        float delta = Math.abs(normalizeDegrees(first) - normalizeDegrees(second));
        return delta > 180f ? 360f - delta : delta;
    }

    private static float signedHeadingDelta(float first, float second) {
        float delta = normalizeDegrees(first) - normalizeDegrees(second);
        if (delta > 180f) {
            delta -= 360f;
        } else if (delta < -180f) {
            delta += 360f;
        }
        return delta;
    }

    private static float relativeYaw(DraftFrameRecord from, DraftFrameRecord to, LensModel lensModel) {
        float fromYaw = from.capturedPoseAvailable ? from.headingDegrees : from.targetYawDegrees;
        float toYaw = to.capturedPoseAvailable ? to.headingDegrees : to.targetYawDegrees;
        return signedHeadingDelta(toYaw, fromYaw);
    }

    private static float relativePitch(DraftFrameRecord from, DraftFrameRecord to, LensModel lensModel) {
        float fromPitch = from.capturedPoseAvailable ? from.pitchDegrees : from.targetPitchDegrees;
        float toPitch = to.capturedPoseAvailable ? to.pitchDegrees : to.targetPitchDegrees;
        return toPitch - fromPitch;
    }

    private static void unionComponents(HashMap<String, Integer> components, String first, String second) {
        Integer firstComponent = components.get(first);
        Integer secondComponent = components.get(second);
        if (firstComponent == null || secondComponent == null || firstComponent.equals(secondComponent)) {
            return;
        }
        int replacement = Math.min(firstComponent, secondComponent);
        int removed = Math.max(firstComponent, secondComponent);
        for (Map.Entry<String, Integer> entry : components.entrySet()) {
            if (entry.getValue() == removed) {
                entry.setValue(replacement);
            }
        }
    }

    private static String componentRoot(HashMap<String, Integer> components, String frameId) {
        Integer component = components.get(frameId);
        return component == null ? "" : String.valueOf(component);
    }

    private static Ray pixelToWorld(OptimizedFrame frame, float x, float y, int width, int height, LensModel lensModel) {
        float planeX = (x / Math.max(1f, width) - 0.5f) * 2f;
        float planeY = (0.5f - y / Math.max(1f, height)) * 2f;
        float radiusSquared = planeX * planeX + planeY * planeY;
        float scale = 1f
                - lensModel.radialK1 * radiusSquared
                - lensModel.radialK2 * radiusSquared * radiusSquared;
        planeX *= scale;
        planeY *= scale;
        float tanHalfHorizontal = (float) Math.tan(Math.toRadians(lensModel.horizontalFovDegrees / 2f));
        float tanHalfVertical = (float) Math.tan(Math.toRadians(lensModel.verticalFovDegrees / 2f));
        CameraBasis basis = CameraBasis.from(frame.yawDegrees, frame.pitchDegrees, frame.rollDegrees);
        Ray local = new Ray(planeX * tanHalfHorizontal, planeY * tanHalfVertical, 1f);
        float length = (float) Math.sqrt(local.x * local.x + local.y * local.y + local.z * local.z);
        local = new Ray(local.x / length, local.y / length, local.z / length);
        return normalizeRay(new Ray(
                basis.right.x * local.x + basis.up.x * local.y + basis.forward.x * local.z,
                basis.right.y * local.x + basis.up.y * local.y + basis.forward.y * local.z,
                basis.right.z * local.x + basis.up.z * local.y + basis.forward.z * local.z));
    }

    private static float[] worldToPixel(OptimizedFrame frame, Ray ray, int width, int height, LensModel lensModel) {
        CameraBasis basis = CameraBasis.from(frame.yawDegrees, frame.pitchDegrees, frame.rollDegrees);
        float localX = basis.dotRight(ray);
        float localY = basis.dotUp(ray);
        float localZ = Math.max(0.001f, basis.dotForward(ray));
        float tanHalfHorizontal = (float) Math.tan(Math.toRadians(lensModel.horizontalFovDegrees / 2f));
        float tanHalfVertical = (float) Math.tan(Math.toRadians(lensModel.verticalFovDegrees / 2f));
        float planeX = localX / (localZ * tanHalfHorizontal);
        float planeY = localY / (localZ * tanHalfVertical);
        float[] compensated = compensateRadial(planeX, planeY, lensModel);
        return new float[]{
                (compensated[0] * 0.5f + 0.5f) * width,
                (0.5f - compensated[1] * 0.5f) * height
        };
    }

    private static Ray normalizeRay(Ray ray) {
        float length = (float) Math.sqrt(ray.x * ray.x + ray.y * ray.y + ray.z * ray.z);
        if (length <= 0.0001f) {
            return new Ray(0f, 0f, 1f);
        }
        return new Ray(ray.x / length, ray.y / length, ray.z / length);
    }

    private static float yawOf(Ray ray) {
        return normalizeDegrees((float) Math.toDegrees(Math.atan2(ray.x, ray.z)));
    }

    private static float pitchOf(Ray ray) {
        return (float) Math.toDegrees(Math.asin(clamp(ray.y, -1f, 1f)));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class FrameProjection {
        final float yawDegrees;
        final float pitchDegrees;
        final float rollDegrees;
        final float horizontalFovDegrees;
        final float verticalFovDegrees;
        final RectF bounds;

        FrameProjection(
                float yawDegrees,
                float pitchDegrees,
                float rollDegrees,
                float horizontalFovDegrees,
                float verticalFovDegrees,
                RectF bounds) {
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
            this.horizontalFovDegrees = horizontalFovDegrees;
            this.verticalFovDegrees = verticalFovDegrees;
            this.bounds = bounds;
        }
    }

    private static final class LensModel {
        final float horizontalFovDegrees;
        final float verticalFovDegrees;
        final float radialK1;
        final float radialK2;
        final float focalLengthMm;

        LensModel(
                float horizontalFovDegrees,
                float verticalFovDegrees,
                float radialK1,
                float radialK2,
                float focalLengthMm) {
            this.horizontalFovDegrees = horizontalFovDegrees;
            this.verticalFovDegrees = verticalFovDegrees;
            this.radialK1 = radialK1;
            this.radialK2 = radialK2;
            this.focalLengthMm = focalLengthMm;
        }
    }

    private static final class MovementSensitivity {
        final String label;
        final float minimumConfidence;
        final float maximumRejectedFraction;
        final int gradientOutlierThreshold;
        final int luminanceOutlierThreshold;
        final float correctionScale;
        final float rollMinimumConfidence;
        final float rollCorrectionScale;

        private MovementSensitivity(
                String label,
                float minimumConfidence,
                float maximumRejectedFraction,
                int gradientOutlierThreshold,
                int luminanceOutlierThreshold,
                float correctionScale,
                float rollMinimumConfidence,
                float rollCorrectionScale) {
            this.label = label;
            this.minimumConfidence = minimumConfidence;
            this.maximumRejectedFraction = maximumRejectedFraction;
            this.gradientOutlierThreshold = gradientOutlierThreshold;
            this.luminanceOutlierThreshold = luminanceOutlierThreshold;
            this.correctionScale = correctionScale;
            this.rollMinimumConfidence = rollMinimumConfidence;
            this.rollCorrectionScale = rollCorrectionScale;
        }

        static MovementSensitivity from(String mode) {
            if ("high".equals(mode)) {
                return new MovementSensitivity("high movement", 0.10f, 0.18f, 72, 70, 0.16f, 0.18f, 0.04f);
            }
            if ("low".equals(mode)) {
                return new MovementSensitivity("low movement", 0.07f, 0.34f, 110, 105, 0.24f, 0.16f, 0.06f);
            }
            return new MovementSensitivity("normal", 0.08f, 0.26f, 92, 88, 0.20f, 0.17f, 0.05f);
        }
    }

    private enum RenderMode {
        STRONGEST_SOURCE("diagnostic-sharp-best-source", true, true),
        CONTRIBUTOR_MAP("diagnostic-contributor-map", true, true),
        BLENDED("optimized-blended-master", false, false);

        final String label;
        final boolean selectsBestSource;
        final boolean diagnostic;

        RenderMode(String label, boolean selectsBestSource, boolean diagnostic) {
            this.label = label;
            this.selectsBestSource = selectsBestSource;
            this.diagnostic = diagnostic;
        }

        static RenderMode from(String mode) {
            if ("blended".equals(mode)) {
                return BLENDED;
            }
            if ("contributors".equals(mode)) {
                return CONTRIBUTOR_MAP;
            }
            return BLENDED;
        }
    }

    private enum CaptureProfile {
        HANDHELD("hand-held", 0.20f, 0.05f),
        FIXED_GIMBAL("fixed gimbal", 0f, 0f);

        final String label;
        final float expectedPivotOffsetMeters;
        final float expectedRowHeightOffsetMeters;

        CaptureProfile(String label, float expectedPivotOffsetMeters, float expectedRowHeightOffsetMeters) {
            this.label = label;
            this.expectedPivotOffsetMeters = expectedPivotOffsetMeters;
            this.expectedRowHeightOffsetMeters = expectedRowHeightOffsetMeters;
        }

        static CaptureProfile from(List<DraftFrameRecord> records) {
            int gimbal = 0;
            int handheld = 0;
            for (DraftFrameRecord record : records) {
                if ("fixed_gimbal".equals(record.captureProfile)) {
                    gimbal++;
                } else {
                    handheld++;
                }
            }
            return gimbal > handheld ? FIXED_GIMBAL : HANDHELD;
        }
    }

    private static final class CameraLensPriors {
        final int posePriorCount;
        final int intrinsicsPriorCount;
        final int focalLengthPriorCount;
        final String sourceLabel;

        CameraLensPriors(
                int posePriorCount,
                int intrinsicsPriorCount,
                int focalLengthPriorCount,
                String sourceLabel) {
            this.posePriorCount = posePriorCount;
            this.intrinsicsPriorCount = intrinsicsPriorCount;
            this.focalLengthPriorCount = focalLengthPriorCount;
            this.sourceLabel = sourceLabel;
        }

        static CameraLensPriors from(List<DraftFrameRecord> records) {
            int pose = 0;
            int intrinsics = 0;
            int focal = 0;
            for (DraftFrameRecord record : records) {
                if (record.capturedPoseAvailable) {
                    pose++;
                }
                if (record.imageFocalLengthXPixels > 0f
                        && record.imageFocalLengthYPixels > 0f
                        && record.imageIntrinsicsWidth > 0
                        && record.imageIntrinsicsHeight > 0) {
                    intrinsics++;
                }
                if (record.lensFocalLengthMm > 0.5f && record.lensFocalLengthMm < 12f) {
                    focal++;
                }
            }
            String source = intrinsics > 0
                    ? "arcore-image-intrinsics"
                    : focal > 0
                    ? "physical-focal-sensor-prior"
                    : "bounded-default-lens-prior";
            return new CameraLensPriors(pose, intrinsics, focal, source);
        }

        String summary(int frameCount) {
            return String.format(
                    Locale.US,
                    "source=%s pose=%d/%d intrinsics=%d/%d focal=%d/%d",
                    sourceLabel,
                    posePriorCount,
                    frameCount,
                    intrinsicsPriorCount,
                    frameCount,
                    focalLengthPriorCount,
                    frameCount);
        }
    }

    private static final class GraphBuildResult {
        final List<GraphControlEdge> edges;
        final int rejectedEdges;
        final String rejectedReasons;

        private GraphBuildResult(List<GraphControlEdge> edges, int rejectedEdges, String rejectedReasons) {
            this.edges = edges;
            this.rejectedEdges = rejectedEdges;
            this.rejectedReasons = rejectedReasons;
        }

        static GraphBuildResult from(
                CaptureSessionRecord session,
                Map<String, DraftFrameRecord> recordsById,
                Map<String, CalibrationFrame> calibrationById,
                LensModel lensModel) {
            ArrayList<GraphControlEdge> edges = new ArrayList<>();
            int weak = 0;
            int missing = 0;
            int parallax = 0;
            HashSet<String> seenPairs = new HashSet<>();
            for (CaptureGraphEdgeRecord edge : session.graphEdges) {
                DraftFrameRecord from = recordsById.get(edge.fromFrameId);
                DraftFrameRecord to = recordsById.get(edge.toFrameId);
                CalibrationFrame fromFrame = calibrationById.get(edge.fromFrameId);
                CalibrationFrame toFrame = calibrationById.get(edge.toFrameId);
                if (from == null || to == null || fromFrame == null || toFrame == null) {
                    missing++;
                    continue;
                }
                if (!edgeLooksSolvable(edge)) {
                    weak++;
                    continue;
                }
                String hint = edge.parallaxRiskHint.toLowerCase(Locale.US);
                if (hint.contains("high") || hint.contains("near-object")) {
                    parallax++;
                    continue;
                }
                String pairKey = edge.fromFrameId.compareTo(edge.toFrameId) <= 0
                        ? edge.fromFrameId + "|" + edge.toFrameId
                        : edge.toFrameId + "|" + edge.fromFrameId;
                if (!seenPairs.add(pairKey)) {
                    weak++;
                    continue;
                }
                ArrayList<GraphControlPoint> points = new ArrayList<>();
                for (int i = 0; i < edge.controlPoints.length(); i++) {
                    JSONObject point = edge.controlPoints.optJSONObject(i);
                    if (point == null) {
                        continue;
                    }
                    points.add(new GraphControlPoint(
                            (float) point.optDouble("neighborX", 0.0),
                            (float) point.optDouble("neighborY", 0.0),
                            (float) point.optDouble("candidateX", 0.0),
                            (float) point.optDouble("candidateY", 0.0)));
                }
                if (points.size() < 4) {
                    weak++;
                    continue;
                }
                edges.add(new GraphControlEdge(
                        edge.fromFrameId,
                        edge.toFrameId,
                        from,
                        to,
                        fromFrame.width,
                        fromFrame.height,
                        toFrame.width,
                        toFrame.height,
                        (float) edge.confidence,
                        (float) edge.residualScore,
                        points,
                        relativeYaw(from, to, lensModel),
                        relativePitch(from, to, lensModel)));
            }
            String reasons = "weak=" + weak + ", missing=" + missing + ", parallax=" + parallax;
            return new GraphBuildResult(edges, weak + missing + parallax, reasons);
        }

        boolean ready() {
            if (edges.size() < 2) {
                return false;
            }
            HashMap<String, Integer> components = new HashMap<>();
            for (GraphControlEdge edge : edges) {
                components.putIfAbsent(edge.fromFrameId, components.size());
                components.putIfAbsent(edge.toFrameId, components.size());
                unionComponents(components, edge.fromFrameId, edge.toFrameId);
            }
            String root = "";
            for (String frameId : components.keySet()) {
                if (root.isEmpty()) {
                    root = componentRoot(components, frameId);
                } else if (!root.equals(componentRoot(components, frameId))) {
                    return false;
                }
            }
            return true;
        }

        String failureReason() {
            return "usable graph edges=" + edges.size() + ", rejected=" + rejectedEdges + " (" + rejectedReasons + ")";
        }

        List<FeatureMatchEdge> toFeatureEdges() {
            ArrayList<FeatureMatchEdge> featureEdges = new ArrayList<>();
            for (GraphControlEdge edge : edges) {
                featureEdges.add(new FeatureMatchEdge(
                        edge.from.record.imageFile.getAbsolutePath(),
                        edge.to.record.imageFile.getAbsolutePath(),
                        edge.relativeYawDegrees,
                        edge.relativePitchDegrees,
                        0f,
                        edge.confidence,
                        edge.residualScore,
                        true));
            }
            return featureEdges;
        }

        private static boolean edgeLooksSolvable(CaptureGraphEdgeRecord edge) {
            return edge.confidence >= 0.18
                    && edge.inlierCount >= 8
                    && edge.residualScore <= 80.0
                    && edge.controlPoints.length() >= 4;
        }
    }

    private static final class GraphBundleSolver {
        final HashMap<String, OptimizedFrame> frames = new HashMap<>();
        final List<GraphControlEdge> edges;
        LensModel lensModel;

        private GraphBundleSolver(List<GraphControlEdge> edges, LensModel lensModel) {
            this.edges = edges;
            this.lensModel = lensModel;
        }

        static GraphBundleSolver from(List<DraftFrameRecord> records, List<GraphControlEdge> edges, LensModel lensModel) {
            GraphBundleSolver solver = new GraphBundleSolver(edges, lensModel);
            for (DraftFrameRecord record : records) {
                solver.frames.put(record.imageFile.getAbsolutePath(), OptimizedFrame.from(record));
            }
            return solver;
        }

        OptimizedCameraGraph solve(SpherifyLibrary.ProgressReporter progress) {
            float fovVelocity = 0f;
            float distortionVelocity = 0f;
            for (int iteration = 0; iteration < 64; iteration++) {
                HashMap<String, PoseDelta> deltas = new HashMap<>();
                float signedFovResidual = 0f;
                float signedDistortionResidual = 0f;
                int residualSamples = 0;
                for (GraphControlEdge edge : edges) {
                    OptimizedFrame from = frames.get(edge.from.record.imageFile.getAbsolutePath());
                    OptimizedFrame to = frames.get(edge.to.record.imageFile.getAbsolutePath());
                    if (from == null || to == null) {
                        continue;
                    }
                    float weight = edge.weight();
                    for (GraphControlPoint point : edge.points) {
                        Ray fromRay = pixelToWorld(from, point.fromX, point.fromY, edge.fromWidth, edge.fromHeight, lensModel);
                        Ray toRay = pixelToWorld(to, point.toX, point.toY, edge.toWidth, edge.toHeight, lensModel);
                        float fromYaw = yawOf(fromRay);
                        float toYaw = yawOf(toRay);
                        float fromPitch = pitchOf(fromRay);
                        float toPitch = pitchOf(toRay);
                        float yawResidual = signedHeadingDelta(toYaw, fromYaw);
                        float pitchResidual = toPitch - fromPitch;
                        float edgeDistance = Math.max(Math.abs(point.toX / Math.max(1f, edge.toWidth) - 0.5f),
                                Math.abs(point.toY / Math.max(1f, edge.toHeight) - 0.5f));
                        deltas.computeIfAbsent(edge.from.record.imageFile.getAbsolutePath(), ignored -> new PoseDelta())
                                .add(yawResidual, pitchResidual, -pitchResidual * edgeDistance * 0.10f, weight);
                        deltas.computeIfAbsent(edge.to.record.imageFile.getAbsolutePath(), ignored -> new PoseDelta())
                                .add(-yawResidual, -pitchResidual, pitchResidual * edgeDistance * 0.10f, weight);
                        signedFovResidual += (Math.abs(yawResidual) - Math.abs(pitchResidual)) * weight;
                        signedDistortionResidual += (edgeDistance - 0.22f) * (Math.abs(yawResidual) + Math.abs(pitchResidual)) * weight;
                        residualSamples++;
                    }
                }
                for (Map.Entry<String, PoseDelta> entry : deltas.entrySet()) {
                    OptimizedFrame frame = frames.get(entry.getKey());
                    if (frame == null) {
                        continue;
                    }
                    PoseDelta delta = entry.getValue();
                    float step = 0.28f / Math.max(1f, delta.weightTotal);
                    frame.yawDegrees = normalizeDegrees(frame.yawDegrees + delta.yawDegrees * step);
                    frame.pitchDegrees = clamp(frame.pitchDegrees + delta.pitchDegrees * step, -89f, 89f);
                    frame.rollDegrees = clamp((frame.rollDegrees + delta.rollDegrees * step) * 0.997f, -6f, 6f);
                    applyPosePrior(frame);
                }
                if (residualSamples > 0) {
                    fovVelocity = fovVelocity * 0.82f + signedFovResidual / residualSamples * 0.006f;
                    distortionVelocity = distortionVelocity * 0.88f + signedDistortionResidual / residualSamples * 0.00008f;
                    lensModel = new LensModel(
                            clamp(lensModel.horizontalFovDegrees + fovVelocity, 42f, 88f),
                            clamp(lensModel.verticalFovDegrees - fovVelocity * 0.55f, MIN_VERTICAL_FOV_DEGREES, MAX_VERTICAL_FOV_DEGREES),
                            clamp(lensModel.radialK1 - distortionVelocity, -0.22f, 0.08f),
                            clamp(lensModel.radialK2 + distortionVelocity * 0.45f, -0.06f, 0.10f),
                            lensModel.focalLengthMm);
                }
                if (iteration == 0 || iteration == 31 || iteration == 63) {
                    report(progress, "lens", false, "Graph optimizer iteration " + (iteration + 1) + "/64");
                }
            }
            SolveDiagnostics diagnostics = residualDiagnostics();
            return new OptimizedCameraGraph(lensModel, frames, diagnostics);
        }

        private void applyPosePrior(OptimizedFrame frame) {
            frame.yawDegrees = normalizeDegrees(frame.yawDegrees + signedHeadingDelta(frame.baseYawDegrees, frame.yawDegrees) * 0.018f);
            frame.pitchDegrees = clamp(frame.pitchDegrees + (frame.basePitchDegrees - frame.pitchDegrees) * 0.018f, -89f, 89f);
            frame.rollDegrees = clamp(frame.rollDegrees + (frame.baseRollDegrees - frame.rollDegrees) * 0.010f, -6f, 6f);
        }

        private SolveDiagnostics residualDiagnostics() {
            float total = 0f;
            float max = 0f;
            float confidenceTotal = 0f;
            int samples = 0;
            int parallaxWarnings = 0;
            float closureTotal = 0f;
            for (GraphControlEdge edge : edges) {
                OptimizedFrame from = frames.get(edge.from.record.imageFile.getAbsolutePath());
                OptimizedFrame to = frames.get(edge.to.record.imageFile.getAbsolutePath());
                if (from == null || to == null) {
                    continue;
                }
                float edgeResidual = 0f;
                for (GraphControlPoint point : edge.points) {
                    Ray fromRay = pixelToWorld(from, point.fromX, point.fromY, edge.fromWidth, edge.fromHeight, lensModel);
                    float[] projected = worldToPixel(to, fromRay, edge.toWidth, edge.toHeight, lensModel);
                    float dx = projected[0] - point.toX;
                    float dy = projected[1] - point.toY;
                    float residual = (float) Math.sqrt(dx * dx + dy * dy);
                    total += residual;
                    edgeResidual += residual;
                    max = Math.max(max, residual);
                    samples++;
                }
                if (!edge.points.isEmpty() && edgeResidual / edge.points.size() > 24f) {
                    parallaxWarnings++;
                }
                closureTotal += Math.abs(signedHeadingDelta(
                        normalizeDegrees(from.yawDegrees + edge.relativeYawDegrees),
                        to.yawDegrees));
                confidenceTotal += edge.confidence;
            }
            return new SolveDiagnostics(
                    edges.size(),
                    0,
                    samples == 0 ? 0f : total / samples,
                    max,
                    edges.isEmpty() ? 0f : closureTotal / edges.size(),
                    parallaxWarnings,
                    edges.isEmpty() ? 0f : confidenceTotal / edges.size());
        }
    }

    private static final class OptimizedCameraGraph {
        final LensModel lensModel;
        final Map<String, OptimizedFrame> frames;
        final SolveDiagnostics diagnostics;

        OptimizedCameraGraph(LensModel lensModel, Map<String, OptimizedFrame> frames, SolveDiagnostics diagnostics) {
            this.lensModel = lensModel;
            this.frames = frames;
            this.diagnostics = diagnostics;
        }

        OptimizedCameraGraph withRejectedEdges(int rejectedEdges) {
            return new OptimizedCameraGraph(
                    lensModel,
                    frames,
                    new SolveDiagnostics(
                            diagnostics.edgesUsed,
                            rejectedEdges,
                            diagnostics.meanResidualPixels,
                            diagnostics.maxResidualPixels,
                            diagnostics.closureErrorDegrees,
                            diagnostics.parallaxWarnings,
                            diagnostics.meanConfidence));
        }

        Map<String, PoseCorrection> toPoseCorrections(List<DraftFrameRecord> records) {
            HashMap<String, PoseCorrection> corrections = new HashMap<>();
            for (DraftFrameRecord record : records) {
                OptimizedFrame frame = frames.get(record.imageFile.getAbsolutePath());
                if (frame == null) {
                    continue;
                }
                PoseCorrection correction = new PoseCorrection();
                correction.yawDegrees = signedHeadingDelta(frame.yawDegrees, frame.baseYawDegrees);
                correction.pitchDegrees = frame.pitchDegrees - frame.basePitchDegrees;
                correction.rollDegrees = frame.rollDegrees - frame.baseRollDegrees;
                correction.samples = 1;
                correction.weightTotal = 1f;
                corrections.put(record.imageFile.getAbsolutePath(), correction);
            }
            return corrections;
        }
    }

    private static final class SolveDiagnostics {
        final int edgesUsed;
        final int edgesRejected;
        final float meanResidualPixels;
        final float maxResidualPixels;
        final float closureErrorDegrees;
        final int parallaxWarnings;
        final float meanConfidence;

        SolveDiagnostics(
                int edgesUsed,
                int edgesRejected,
                float meanResidualPixels,
                float maxResidualPixels,
                float closureErrorDegrees,
                int parallaxWarnings,
                float meanConfidence) {
            this.edgesUsed = edgesUsed;
            this.edgesRejected = edgesRejected;
            this.meanResidualPixels = meanResidualPixels;
            this.maxResidualPixels = maxResidualPixels;
            this.closureErrorDegrees = closureErrorDegrees;
            this.parallaxWarnings = parallaxWarnings;
            this.meanConfidence = meanConfidence;
        }

        static SolveDiagnostics legacy() {
            return new SolveDiagnostics(0, 0, 0f, 0f, 0f, 0, 0f);
        }
    }

    private static final class OptimizedFrame {
        final DraftFrameRecord record;
        final float baseYawDegrees;
        final float basePitchDegrees;
        final float baseRollDegrees;
        float yawDegrees;
        float pitchDegrees;
        float rollDegrees;

        private OptimizedFrame(DraftFrameRecord record, float yawDegrees, float pitchDegrees, float rollDegrees) {
            this.record = record;
            this.baseYawDegrees = yawDegrees;
            this.basePitchDegrees = pitchDegrees;
            this.baseRollDegrees = rollDegrees;
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
        }

        static OptimizedFrame from(DraftFrameRecord record) {
            float yaw = record.capturedPoseAvailable ? record.headingDegrees : record.targetYawDegrees;
            float pitch = record.capturedPoseAvailable ? record.pitchDegrees : record.targetPitchDegrees;
            float roll = record.capturedPoseAvailable ? record.rollDegrees : 0f;
            return new OptimizedFrame(record, normalizeDegrees(yaw), clamp(pitch, -89f, 89f), clamp(roll, -8f, 8f));
        }
    }

    private static final class GraphControlEdge {
        final String fromFrameId;
        final String toFrameId;
        final CalibrationFrame from;
        final CalibrationFrame to;
        final int fromWidth;
        final int fromHeight;
        final int toWidth;
        final int toHeight;
        final float confidence;
        final float residualScore;
        final List<GraphControlPoint> points;
        final float relativeYawDegrees;
        final float relativePitchDegrees;

        GraphControlEdge(
                String fromFrameId,
                String toFrameId,
                DraftFrameRecord fromRecord,
                DraftFrameRecord toRecord,
                int fromWidth,
                int fromHeight,
                int toWidth,
                int toHeight,
                float confidence,
                float residualScore,
                List<GraphControlPoint> points,
                float relativeYawDegrees,
                float relativePitchDegrees) {
            this.fromFrameId = fromFrameId;
            this.toFrameId = toFrameId;
            this.from = new CalibrationFrame(fromRecord, fromWidth, fromHeight, new int[0], 0f, fromRecord.targetYawDegrees, fromRecord.targetPitchDegrees, false);
            this.to = new CalibrationFrame(toRecord, toWidth, toHeight, new int[0], 0f, toRecord.targetYawDegrees, toRecord.targetPitchDegrees, false);
            this.fromWidth = fromWidth;
            this.fromHeight = fromHeight;
            this.toWidth = toWidth;
            this.toHeight = toHeight;
            this.confidence = confidence;
            this.residualScore = residualScore;
            this.points = points;
            this.relativeYawDegrees = relativeYawDegrees;
            this.relativePitchDegrees = relativePitchDegrees;
        }

        float weight() {
            return confidence * Math.max(1f, points.size() / 8f) / (1f + Math.max(0f, residualScore) / 36f);
        }
    }

    private static final class GraphControlPoint {
        final float fromX;
        final float fromY;
        final float toX;
        final float toY;

        GraphControlPoint(float fromX, float fromY, float toX, float toY) {
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
        }
    }

    private static final class Calibration {
        final LensModel lensModel;
        final Map<String, PoseCorrection> corrections;
        final int matchedOverlapCount;
        final int openCvMatchedOverlapCount;
        final float averageOverlapConfidence;
        final CaptureProfile captureProfile;
        final SparseDepthHints sparseDepthHints;
        final CameraLensPriors cameraLensPriors;
        final Map<String, Float> exposureGains;
        final SolveDiagnostics solveDiagnostics;

        Calibration(
                LensModel lensModel,
                Map<String, PoseCorrection> corrections,
                int matchedOverlapCount,
                int openCvMatchedOverlapCount,
                float averageOverlapConfidence,
                CaptureProfile captureProfile,
                SparseDepthHints sparseDepthHints,
                CameraLensPriors cameraLensPriors,
                Map<String, Float> exposureGains) {
            this(lensModel, corrections, matchedOverlapCount, openCvMatchedOverlapCount, averageOverlapConfidence,
                    captureProfile, sparseDepthHints, cameraLensPriors, exposureGains, SolveDiagnostics.legacy());
        }

        Calibration(
                LensModel lensModel,
                Map<String, PoseCorrection> corrections,
                int matchedOverlapCount,
                int openCvMatchedOverlapCount,
                float averageOverlapConfidence,
                CaptureProfile captureProfile,
                SparseDepthHints sparseDepthHints,
                CameraLensPriors cameraLensPriors,
                Map<String, Float> exposureGains,
                SolveDiagnostics solveDiagnostics) {
            this.lensModel = lensModel;
            this.corrections = corrections;
            this.matchedOverlapCount = matchedOverlapCount;
            this.openCvMatchedOverlapCount = openCvMatchedOverlapCount;
            this.averageOverlapConfidence = averageOverlapConfidence;
            this.captureProfile = captureProfile;
            this.sparseDepthHints = sparseDepthHints;
            this.cameraLensPriors = cameraLensPriors;
            this.exposureGains = exposureGains;
            this.solveDiagnostics = solveDiagnostics == null ? SolveDiagnostics.legacy() : solveDiagnostics;
        }
    }

    private static final class PoseCorrection {
        float yawDegrees;
        float pitchDegrees;
        float rollDegrees;
        int samples;
        float weightTotal;

        void add(float yawDegrees, float pitchDegrees, float rollDegrees, float weight) {
            float safeWeight = Math.max(0.01f, weight);
            this.yawDegrees += yawDegrees * safeWeight;
            this.pitchDegrees += pitchDegrees * safeWeight;
            this.rollDegrees += rollDegrees * safeWeight;
            this.weightTotal += safeWeight;
            this.samples++;
        }

        void averageAndClamp() {
            if (weightTotal > 0f) {
                yawDegrees /= weightTotal;
                pitchDegrees /= weightTotal;
                rollDegrees /= weightTotal;
            } else if (samples > 1) {
                yawDegrees /= samples;
                pitchDegrees /= samples;
                rollDegrees /= samples;
            }
            yawDegrees = clamp(yawDegrees, -4f, 4f);
            pitchDegrees = clamp(pitchDegrees, -4f, 4f);
            rollDegrees = clamp(rollDegrees, -1.2f, 1.2f);
        }

        PoseCorrection copy() {
            PoseCorrection copy = new PoseCorrection();
            copy.yawDegrees = yawDegrees;
            copy.pitchDegrees = pitchDegrees;
            copy.rollDegrees = rollDegrees;
            copy.samples = samples;
            copy.weightTotal = weightTotal;
            return copy;
        }
    }

    private static final class FeatureMatchGraph {
        final List<FeatureMatchEdge> edges;
        final boolean globallyOptimizable;

        private FeatureMatchGraph(List<FeatureMatchEdge> edges) {
            this(edges, false);
        }

        private FeatureMatchGraph(List<FeatureMatchEdge> edges, boolean globallyOptimizable) {
            this.edges = edges;
            this.globallyOptimizable = globallyOptimizable;
        }

        static FeatureMatchGraph empty() {
            return new FeatureMatchGraph(new ArrayList<>());
        }
    }

    private static final class SparseDepthHints {
        final boolean hasNearObjectRisk;
        final String metadataLabel;
        final String warningLabel;

        private SparseDepthHints(boolean hasNearObjectRisk, String metadataLabel, String warningLabel) {
            this.hasNearObjectRisk = hasNearObjectRisk;
            this.metadataLabel = metadataLabel;
            this.warningLabel = warningLabel;
        }

        static SparseDepthHints from(FeatureMatchGraph graph, CaptureProfile captureProfile) {
            if (captureProfile == CaptureProfile.FIXED_GIMBAL) {
                return new SparseDepthHints(false, "rotation-only-prior", "No near-object parallax risk detected");
            }
            if (graph.edges.isEmpty()) {
                return new SparseDepthHints(
                        true,
                        "pending-feature-graph-handheld-prior",
                        "Sparse depth hints pending; hand-held offset treated as near-object risk");
            }
            if (graph.edges.size() < 4) {
                return new SparseDepthHints(
                        true,
                        "weak-sparse-overlap-graph-handheld-prior",
                        "Sparse depth hints weak; hand-held near-object conflicts may remain");
            }
            float residualTotal = 0f;
            int featureEdges = 0;
            for (FeatureMatchEdge edge : graph.edges) {
                if (edge.featureBased) {
                    residualTotal += edge.residualScore;
                    featureEdges++;
                }
            }
            float meanResidual = featureEdges == 0 ? 0f : residualTotal / featureEdges;
            if (meanResidual > 8f) {
                return new SparseDepthHints(
                        true,
                        "high-residual-feature-graph-handheld-prior",
                        "High feature residuals suggest nearby parallax; prefer sharp source selection and seam routing");
            }
            return new SparseDepthHints(
                    false,
                    "sparse-overlap-graph-no-depth-outliers",
                    "Sparse depth hints found no strong near-object conflicts");
        }
    }

    private static final class FeatureMatchEdge {
        final String fromPath;
        final String toPath;
        final float relativeYawDegrees;
        final float relativePitchDegrees;
        final float relativeRollDegrees;
        final float confidence;
        final float residualScore;
        final boolean featureBased;
        final List<ControlPointPair> inlierControlPoints;

        FeatureMatchEdge(
                String fromPath,
                String toPath,
                float relativeYawDegrees,
                float relativePitchDegrees,
                float relativeRollDegrees,
                float confidence) {
            this(fromPath, toPath, relativeYawDegrees, relativePitchDegrees, relativeRollDegrees, confidence, 0f, false, new ArrayList<>());
        }

        FeatureMatchEdge(
                String fromPath,
                String toPath,
                float relativeYawDegrees,
                float relativePitchDegrees,
                float relativeRollDegrees,
                float confidence,
                float residualScore,
                boolean featureBased) {
            this(fromPath, toPath, relativeYawDegrees, relativePitchDegrees, relativeRollDegrees, confidence, residualScore, featureBased, new ArrayList<>());
        }

        FeatureMatchEdge(
                String fromPath,
                String toPath,
                float relativeYawDegrees,
                float relativePitchDegrees,
                float relativeRollDegrees,
                float confidence,
                float residualScore,
                boolean featureBased,
                List<ControlPointPair> inlierControlPoints) {
            this.fromPath = fromPath;
            this.toPath = toPath;
            this.relativeYawDegrees = relativeYawDegrees;
            this.relativePitchDegrees = relativePitchDegrees;
            this.relativeRollDegrees = relativeRollDegrees;
            this.confidence = confidence;
            this.residualScore = residualScore;
            this.featureBased = featureBased;
            this.inlierControlPoints = inlierControlPoints;
        }
    }

    private static final class GlobalPoseOptimization {
        final FeatureMatchGraph graph;
        final Map<String, PoseCorrection> corrections;

        private GlobalPoseOptimization(FeatureMatchGraph graph, Map<String, PoseCorrection> corrections) {
            this.graph = graph;
            this.corrections = corrections;
        }

        static GlobalPoseOptimization from(FeatureMatchGraph graph, Map<String, PoseCorrection> corrections) {
            return new GlobalPoseOptimization(graph, corrections);
        }

        void apply() {
            if (!graph.globallyOptimizable || graph.edges.isEmpty()) {
                return;
            }
            HashMap<String, PoseCorrection> optimized = new HashMap<>();
            for (FeatureMatchEdge edge : graph.edges) {
                optimized.putIfAbsent(edge.fromPath, corrections.getOrDefault(edge.fromPath, new PoseCorrection()).copy());
                optimized.putIfAbsent(edge.toPath, corrections.getOrDefault(edge.toPath, new PoseCorrection()).copy());
            }
            for (int iteration = 0; iteration < 36; iteration++) {
                HashMap<String, PoseDelta> deltas = new HashMap<>();
                for (FeatureMatchEdge edge : graph.edges) {
                    float graphWeight = optimizerWeight(edge);
                    if (graphWeight <= 0f) {
                        continue;
                    }
                    PoseCorrection source = optimized.get(edge.fromPath);
                    PoseCorrection target = optimized.get(edge.toPath);
                    if (source == null || target == null) {
                        continue;
                    }
                    float yawResidual = edge.relativeYawDegrees - (target.yawDegrees - source.yawDegrees);
                    float pitchResidual = edge.relativePitchDegrees - (target.pitchDegrees - source.pitchDegrees);
                    float rollResidual = edge.relativeRollDegrees - (target.rollDegrees - source.rollDegrees);
                    deltas.computeIfAbsent(edge.fromPath, ignored -> new PoseDelta())
                            .add(-yawResidual, -pitchResidual, -rollResidual, graphWeight);
                    deltas.computeIfAbsent(edge.toPath, ignored -> new PoseDelta())
                            .add(yawResidual, pitchResidual, rollResidual, graphWeight);
                }
                for (Map.Entry<String, PoseDelta> entry : deltas.entrySet()) {
                    PoseCorrection correction = optimized.get(entry.getKey());
                    if (correction == null) {
                        continue;
                    }
                    PoseDelta delta = entry.getValue();
                    float step = 0.34f / Math.max(1f, delta.weightTotal);
                    correction.yawDegrees = clamp((correction.yawDegrees + delta.yawDegrees * step) * 0.992f, -5.5f, 5.5f);
                    correction.pitchDegrees = clamp((correction.pitchDegrees + delta.pitchDegrees * step) * 0.992f, -4.5f, 4.5f);
                    correction.rollDegrees = clamp((correction.rollDegrees + delta.rollDegrees * step) * 0.985f, -1.8f, 1.8f);
                }
            }
            corrections.clear();
            corrections.putAll(optimized);
        }

        private float optimizerWeight(FeatureMatchEdge edge) {
            if (edge.confidence <= 0f) {
                return 0f;
            }
            float inlierWeight = edge.featureBased
                    ? Math.max(1f, edge.inlierControlPoints.size() / 10f)
                    : 0.35f;
            float residualPenalty = 1f / (1f + Math.max(0f, edge.residualScore) / 18f);
            return edge.confidence * inlierWeight * residualPenalty;
        }
    }

    private static final class PoseDelta {
        float yawDegrees;
        float pitchDegrees;
        float rollDegrees;
        float weightTotal;

        void add(float yawDegrees, float pitchDegrees, float rollDegrees, float weight) {
            this.yawDegrees += yawDegrees * weight;
            this.pitchDegrees += pitchDegrees * weight;
            this.rollDegrees += rollDegrees * weight;
            this.weightTotal += weight;
        }
    }

    private static final class ImageFeature {
        final int x;
        final int y;
        final float matchX;
        final float matchY;
        final int score;
        final long[] descriptor;

        ImageFeature(int x, int y, float matchX, float matchY, int score, long[] descriptor) {
            this.x = x;
            this.y = y;
            this.matchX = matchX;
            this.matchY = matchY;
            this.score = score;
            this.descriptor = descriptor;
        }
    }

    private static final class FeaturePair {
        final ImageFeature left;
        final ImageFeature right;
        final int distance;

        FeaturePair(ImageFeature left, ImageFeature right, int distance) {
            this.left = left;
            this.right = right;
            this.distance = distance;
        }
    }

    private static final class OpenCvFeaturePair {
        final Point left;
        final Point right;
        final float distance;

        OpenCvFeaturePair(Point left, Point right, float distance) {
            this.left = left;
            this.right = right;
            this.distance = distance;
        }
    }

    private static final class ControlPointPair {
        final float leftX;
        final float leftY;
        final float rightX;
        final float rightY;
        final float descriptorDistance;

        ControlPointPair(float leftX, float leftY, float rightX, float rightY, float descriptorDistance) {
            this.leftX = leftX;
            this.leftY = leftY;
            this.rightX = rightX;
            this.rightY = rightY;
            this.descriptorDistance = descriptorDistance;
        }
    }

    private static final class FramePair {
        final CalibrationFrame left;
        final CalibrationFrame right;

        FramePair(CalibrationFrame left, CalibrationFrame right) {
            this.left = left;
            this.right = right;
        }
    }

    private static final class PairAdjustment {
        final int horizontalOffsetPixels;
        final int verticalOffsetPixels;
        final int rollPixels;
        final float score;
        final float confidence;
        final boolean featureBased;
        final List<ControlPointPair> inlierControlPoints;

        PairAdjustment(
                int horizontalOffsetPixels,
                int verticalOffsetPixels,
                int rollPixels,
                float score,
                float confidence) {
            this(horizontalOffsetPixels, verticalOffsetPixels, rollPixels, score, confidence, false, new ArrayList<>());
        }

        PairAdjustment(
                int horizontalOffsetPixels,
                int verticalOffsetPixels,
                int rollPixels,
                float score,
                float confidence,
                boolean featureBased) {
            this(horizontalOffsetPixels, verticalOffsetPixels, rollPixels, score, confidence, featureBased, new ArrayList<>());
        }

        PairAdjustment(
                int horizontalOffsetPixels,
                int verticalOffsetPixels,
                int rollPixels,
                float score,
                float confidence,
                boolean featureBased,
                List<ControlPointPair> inlierControlPoints) {
            this.horizontalOffsetPixels = horizontalOffsetPixels;
            this.verticalOffsetPixels = verticalOffsetPixels;
            this.rollPixels = rollPixels;
            this.score = score;
            this.confidence = confidence;
            this.featureBased = featureBased;
            this.inlierControlPoints = inlierControlPoints;
        }
    }

    private static final class CalibrationFrame {
        final DraftFrameRecord record;
        final int width;
        final int height;
        final int[] grayscale;
        final float meanLuminance;
        final float baseYawDegrees;
        final int basePitchDegrees;
        final boolean polar;

        private CalibrationFrame(
                DraftFrameRecord record,
                int width,
                int height,
                int[] grayscale,
                float meanLuminance,
                float baseYawDegrees,
                int basePitchDegrees,
                boolean polar) {
            this.record = record;
            this.width = width;
            this.height = height;
            this.grayscale = grayscale;
            this.meanLuminance = meanLuminance;
            this.baseYawDegrees = baseYawDegrees;
            this.basePitchDegrees = basePitchDegrees;
            this.polar = polar;
        }

        static CalibrationFrame decode(DraftFrameRecord record, LensModel lensModel) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(record.imageFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while ((bounds.outWidth / sample) > CALIBRATION_MAX_DIMENSION
                    || (bounds.outHeight / sample) > CALIBRATION_MAX_DIMENSION) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(record.imageFile.getAbsolutePath(), options);
            if (bitmap == null) {
                return null;
            }
            bitmap = SpherifyLibrary.applyExifRotation(bitmap, record.imageFile.getAbsolutePath());
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();
            int[] grayscale = new int[pixels.length];
            long luminanceTotal = 0L;
            for (int i = 0; i < pixels.length; i++) {
                int color = pixels[i];
                grayscale[i] = Math.round(
                        ((color >> 16) & 0xFF) * 0.299f
                                + ((color >> 8) & 0xFF) * 0.587f
                                + (color & 0xFF) * 0.114f);
                luminanceTotal += grayscale[i];
            }
            boolean polar = Math.abs(record.targetPitchDegrees) >= 70;
            return new CalibrationFrame(
                    record,
                    width,
                    height,
                    grayscale,
                    luminanceTotal / (float) Math.max(1, grayscale.length),
                    normalizeDegrees(record.targetYawDegrees),
                    record.targetPitchDegrees,
                    polar);
        }

        int grayAt(int x, int y) {
            return grayscale[clamp(y, 0, height - 1) * width + clamp(x, 0, width - 1)];
        }

        Mat toOpenCvMat() {
            Mat mat = new Mat(height, width, CvType.CV_8UC1);
            byte[] bytes = new byte[grayscale.length];
            for (int i = 0; i < grayscale.length; i++) {
                bytes[i] = (byte) clamp(grayscale[i], 0, 255);
            }
            mat.put(0, 0, bytes);
            return mat;
        }
    }

    private static final class Ray {
        final float x;
        final float y;
        final float z;

        private Ray(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static Ray from(float yawDegrees, float pitchDegrees) {
            double yaw = Math.toRadians(yawDegrees);
            double pitch = Math.toRadians(pitchDegrees);
            float cosPitch = (float) Math.cos(pitch);
            return new Ray(
                    cosPitch * (float) Math.sin(yaw),
                    (float) Math.sin(pitch),
                    cosPitch * (float) Math.cos(yaw));
        }
    }

    private static final class CameraBasis {
        final Ray forward;
        final Ray right;
        final Ray up;

        private CameraBasis(Ray forward, Ray right, Ray up) {
            this.forward = forward;
            this.right = right;
            this.up = up;
        }

        static CameraBasis from(float yawDegrees, float pitchDegrees, float rollDegrees) {
            Ray forward = Ray.from(yawDegrees, pitchDegrees);
            double yaw = Math.toRadians(yawDegrees);
            Ray right = new Ray((float) Math.cos(yaw), 0f, (float) -Math.sin(yaw));
            Ray up = cross(forward, right);
            if (Math.abs(rollDegrees) > 0.01f) {
                double roll = Math.toRadians(rollDegrees);
                float cos = (float) Math.cos(roll);
                float sin = (float) Math.sin(roll);
                Ray rolledRight = new Ray(
                        right.x * cos + up.x * sin,
                        right.y * cos + up.y * sin,
                        right.z * cos + up.z * sin);
                Ray rolledUp = new Ray(
                        up.x * cos - right.x * sin,
                        up.y * cos - right.y * sin,
                        up.z * cos - right.z * sin);
                return new CameraBasis(forward, rolledRight, rolledUp);
            }
            return new CameraBasis(forward, right, up);
        }

        float dotForward(Ray ray) {
            return dot(ray, forward);
        }

        float dotRight(Ray ray) {
            return dot(ray, right);
        }

        float dotUp(Ray ray) {
            return dot(ray, up);
        }

        private static float dot(Ray a, Ray b) {
            return a.x * b.x + a.y * b.y + a.z * b.z;
        }

        private static Ray cross(Ray a, Ray b) {
            return new Ray(
                    a.y * b.z - a.z * b.y,
                    a.z * b.x - a.x * b.z,
                    a.x * b.y - a.y * b.x);
        }
    }

    private static final class SeamFinder {
        final Calibration calibration;
        int lowConfidenceSeamPixels;
        int exposurePenaltyPixels;

        SeamFinder(Calibration calibration) {
            this.calibration = calibration;
        }

        float score(int frameIndex, float sourceU, float sourceV, float baseWeight, float exposureGain) {
            float centerPreference = centerWeight(sourceU, sourceV);
            float edgePenalty = 1f - feather(sourceU) * feather(sourceV);
            float confidence = calibration.solveDiagnostics.meanConfidence <= 0f
                    ? 0.55f
                    : calibration.solveDiagnostics.meanConfidence;
            float exposurePenalty = Math.min(0.45f, Math.abs(exposureGain - 1f) * 0.65f);
            float polePenalty = (sourceV < 0.10f || sourceV > 0.90f) && calibration.solveDiagnostics.parallaxWarnings > 0
                    ? 0.22f
                    : 0f;
            float score = baseWeight * (0.55f + centerPreference * 0.45f)
                    - edgePenalty * 0.20f
                    - (1f - confidence) * 0.16f
                    - exposurePenalty
                    - polePenalty;
            if (confidence < 0.35f || polePenalty > 0f) {
                lowConfidenceSeamPixels++;
            }
            if (exposurePenalty > 0.08f) {
                exposurePenaltyPixels++;
            }
            return score;
        }

        float blendWeight(float baseWeight, float score, float existingScore, boolean sameSource) {
            if (existingScore <= 0f || score >= existingScore || sameSource) {
                return baseWeight;
            }
            float ratio = clamp(score / Math.max(0.001f, existingScore), 0.18f, 1f);
            return baseWeight * ratio * ratio;
        }

        SeamReport report(float[] seamScores) {
            int weak = 0;
            for (float score : seamScores) {
                if (score > 0f && score < 0.12f) {
                    weak++;
                }
            }
            return new SeamReport(lowConfidenceSeamPixels + weak, exposurePenaltyPixels);
        }
    }

    private static final class SeamReport {
        final int lowConfidenceSeamPixels;
        final int exposurePenaltyPixels;

        SeamReport(int lowConfidenceSeamPixels, int exposurePenaltyPixels) {
            this.lowConfidenceSeamPixels = lowConfidenceSeamPixels;
            this.exposurePenaltyPixels = exposurePenaltyPixels;
        }
    }

    private static final class MultibandBlender {
        static Bitmap cosmeticBlend(Bitmap source, float[] weights, SpherifyLibrary.ProgressReporter progress) {
            report(progress, "write", false, "Applying final multiband-style cosmetic blend");
            int width = source.getWidth();
            int height = source.getHeight();
            int[] pixels = new int[width * height];
            int[] blended = new int[pixels.length];
            source.getPixels(pixels, 0, width, 0, 0, width, height);
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int index = row + x;
                    if (weights[index] <= 0f) {
                        blended[index] = pixels[index];
                        continue;
                    }
                    int r = 0;
                    int g = 0;
                    int b = 0;
                    int samples = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = clamp(y + dy, 0, height - 1);
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = (x + dx + width) % width;
                            int color = pixels[yy * width + xx];
                            r += (color >> 16) & 0xFF;
                            g += (color >> 8) & 0xFF;
                            b += color & 0xFF;
                            samples++;
                        }
                    }
                    int original = pixels[index];
                    float localBlend = weights[index] < 0.35f ? 0.30f : 0.12f;
                    int rr = clamp(Math.round(((original >> 16) & 0xFF) * (1f - localBlend) + (r / (float) samples) * localBlend), 0, 255);
                    int gg = clamp(Math.round(((original >> 8) & 0xFF) * (1f - localBlend) + (g / (float) samples) * localBlend), 0, 255);
                    int bb = clamp(Math.round((original & 0xFF) * (1f - localBlend) + (b / (float) samples) * localBlend), 0, 255);
                    blended[index] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                }
            }
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            result.setPixels(blended, 0, width, 0, 0, width, height);
            source.recycle();
            return result;
        }
    }

    private static final class ExportValidator {
        static ExportValidationReport validate(
                Bitmap output,
                float[] weights,
                boolean[][] coverage,
                Calibration calibration,
                SeamReport seamReport,
                List<DraftFrameRecord> records) {
            int holes = 0;
            int horizonHoles = 0;
            for (int y = 0; y < OUTPUT_HEIGHT; y++) {
                int row = y * OUTPUT_WIDTH;
                for (int x = 0; x < OUTPUT_WIDTH; x++) {
                    if (weights[row + x] <= 0f) {
                        holes++;
                        if (Math.abs(y - OUTPUT_HEIGHT / 2) < OUTPUT_HEIGHT / 12) {
                            horizonHoles++;
                        }
                    }
                }
            }
            int wrapDifference = wrapDifference(output);
            int topCoverage = rowCoverage(coverage, 0);
            int bottomCoverage = rowCoverage(coverage, COVERAGE_ROWS - 1);
            boolean hasLocation = false;
            boolean hasHeading = false;
            for (DraftFrameRecord record : records) {
                hasLocation |= record.location != null && !record.location.isEmpty();
                hasHeading |= record.capturedPoseAvailable;
            }
            ArrayList<String> blockers = new ArrayList<>();
            ArrayList<String> notes = new ArrayList<>();
            if (output.getWidth() != OUTPUT_WIDTH || output.getHeight() != OUTPUT_HEIGHT || output.getWidth() != output.getHeight() * 2) {
                blockers.add("master is not exact 2:1 equirectangular");
            }
            if (holes > OUTPUT_WIDTH * OUTPUT_HEIGHT * 0.015f) {
                blockers.add("major gaps detected");
            }
            if (wrapDifference > 42) {
                blockers.add("broken wrap seam");
            }
            if (topCoverage < COVERAGE_COLUMNS / 3 || bottomCoverage < COVERAGE_COLUMNS / 3) {
                blockers.add("weak pole coverage");
            }
            if (calibration.solveDiagnostics.maxResidualPixels > 42f || calibration.solveDiagnostics.meanResidualPixels > 14f) {
                blockers.add("excessive graph residuals");
            }
            if (horizonHoles > OUTPUT_WIDTH * 8 || calibration.solveDiagnostics.closureErrorDegrees > 6f) {
                blockers.add("horizon or closure needs review");
            }
            if (!hasLocation) {
                notes.add("missing location metadata");
            }
            if (!hasHeading) {
                notes.add("missing heading metadata");
            }
            if (seamReport.lowConfidenceSeamPixels > OUTPUT_WIDTH * OUTPUT_HEIGHT / 5) {
                notes.add("many seam candidates crossed weak overlaps");
            }
            String state;
            if (!blockers.isEmpty()) {
                state = "Needs review";
            } else if (!notes.isEmpty()) {
                state = "Local master";
            } else if (calibration.solveDiagnostics.parallaxWarnings > 0) {
                state = "Creative export";
            } else {
                state = "Map-ready";
            }
            StringBuilder summary = new StringBuilder();
            summary.append("holes=").append(holes)
                    .append(", wrapDelta=").append(wrapDifference)
                    .append(", poles=").append(topCoverage).append('/').append(bottomCoverage);
            for (String blocker : blockers) {
                summary.append("; ").append(blocker);
            }
            for (String note : notes) {
                summary.append("; ").append(note);
            }
            return new ExportValidationReport(state, summary.toString(), blockers, notes);
        }

        private static int wrapDifference(Bitmap output) {
            int[] left = new int[OUTPUT_HEIGHT];
            int[] right = new int[OUTPUT_HEIGHT];
            output.getPixels(left, 0, 1, 0, 0, 1, OUTPUT_HEIGHT);
            output.getPixels(right, 0, 1, OUTPUT_WIDTH - 1, 0, 1, OUTPUT_HEIGHT);
            long total = 0L;
            for (int i = 0; i < OUTPUT_HEIGHT; i++) {
                total += Math.abs(((left[i] >> 16) & 0xFF) - ((right[i] >> 16) & 0xFF));
                total += Math.abs(((left[i] >> 8) & 0xFF) - ((right[i] >> 8) & 0xFF));
                total += Math.abs((left[i] & 0xFF) - (right[i] & 0xFF));
            }
            return Math.round(total / (OUTPUT_HEIGHT * 3f));
        }

        private static int rowCoverage(boolean[][] coverage, int row) {
            int count = 0;
            for (boolean covered : coverage[clamp(row, 0, coverage.length - 1)]) {
                if (covered) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class ExportValidationReport {
        final String reviewState;
        final String summary;
        final List<String> blockers;
        final List<String> notes;

        ExportValidationReport(String reviewState, String summary, List<String> blockers, List<String> notes) {
            this.reviewState = reviewState;
            this.summary = summary;
            this.blockers = blockers;
            this.notes = notes;
        }
    }

    private static final class PhotoSphereXmp {
        static void write(File file, List<DraftFrameRecord> records, int coveragePercent) {
            try {
                byte[] original = readAll(file);
                if (original.length < 2 || (original[0] & 0xFF) != 0xFF || (original[1] & 0xFF) != 0xD8) {
                    return;
                }
                String xmp = xmpPacket(records, coveragePercent);
                byte[] identifier = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.UTF_8);
                byte[] payload = xmp.getBytes(StandardCharsets.UTF_8);
                int length = identifier.length + payload.length + 2;
                if (length > 0xFFFF) {
                    return;
                }
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(original, 0, 2);
                    output.write(0xFF);
                    output.write(0xE1);
                    output.write((length >> 8) & 0xFF);
                    output.write(length & 0xFF);
                    output.write(identifier);
                    output.write(payload);
                    output.write(original, 2, original.length - 2);
                }
            } catch (IOException ignored) {
                // XMP is required for map-ready export, but a local master still opens without it.
            }
        }

        private static byte[] readAll(File file) throws IOException {
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }

        private static String xmpPacket(List<DraftFrameRecord> records, int coveragePercent) {
            DraftFrameRecord first = records.isEmpty() ? null : records.get(0);
            String timestamp = first == null
                    ? ""
                    : new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .format(new java.util.Date(first.createdAt));
            String heading = first != null && first.capturedPoseAvailable
                    ? String.format(Locale.US, "%.1f", first.headingDegrees)
                    : "";
            String headingTag = heading.isEmpty() ? "" : "<GPano:PoseHeadingDegrees>" + heading + "</GPano:PoseHeadingDegrees>";
            return "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                    + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                    + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                    + "<rdf:Description xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\" "
                    + "xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">"
                    + "<GPano:ProjectionType>equirectangular</GPano:ProjectionType>"
                    + "<GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>"
                    + "<GPano:FullPanoWidthPixels>" + OUTPUT_WIDTH + "</GPano:FullPanoWidthPixels>"
                    + "<GPano:FullPanoHeightPixels>" + OUTPUT_HEIGHT + "</GPano:FullPanoHeightPixels>"
                    + "<GPano:CroppedAreaImageWidthPixels>" + OUTPUT_WIDTH + "</GPano:CroppedAreaImageWidthPixels>"
                    + "<GPano:CroppedAreaImageHeightPixels>" + OUTPUT_HEIGHT + "</GPano:CroppedAreaImageHeightPixels>"
                    + "<GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>"
                    + "<GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>"
                    + headingTag
                    + "<GPano:LargestValidInteriorRectLeft>0</GPano:LargestValidInteriorRectLeft>"
                    + "<GPano:LargestValidInteriorRectTop>0</GPano:LargestValidInteriorRectTop>"
                    + "<GPano:LargestValidInteriorRectWidth>" + OUTPUT_WIDTH + "</GPano:LargestValidInteriorRectWidth>"
                    + "<GPano:LargestValidInteriorRectHeight>" + Math.round(OUTPUT_HEIGHT * coveragePercent / 100f) + "</GPano:LargestValidInteriorRectHeight>"
                    + "<xmp:CreatorTool>Spherify 0.7.4</xmp:CreatorTool>"
                    + "<xmp:CreateDate>" + timestamp + "</xmp:CreateDate>"
                    + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
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
