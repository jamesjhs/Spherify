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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
        MovementSensitivity movementSensitivity = MovementSensitivity.from(movementSensitivityMode);
        RenderMode renderMode = RenderMode.from(renderModeName);
        CaptureProfile captureProfile = CaptureProfile.from(usable);
        Calibration calibration = calibrate(usable, movementSensitivity, captureProfile);

        float[] red = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        float[] green = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        float[] blue = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        float[] weights = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        boolean[][] coverage = new boolean[COVERAGE_ROWS][COVERAGE_COLUMNS];
        int rendered = 0;
        int missingExposure = 0;

        for (DraftFrameRecord record : usable) {
            Bitmap frame = decodeFrame(record.imageFile);
            if (frame == null) {
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
                    weights);
            frame.recycle();
            rendered++;
        }

        if (rendered == 0) {
            throw new IOException("could not decode any draft frames");
        }

        Bitmap output = composeOutput(red, green, blue, weights);
        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            if (!output.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new IOException("could not write stitched master");
            }
        } finally {
            output.recycle();
        }

        int coveragePercent = Math.round(100f * coveredCells(coverage) / (COVERAGE_COLUMNS * COVERAGE_ROWS));
        writeMetadata(outputFile, usable.get(0).sessionId, rendered, coveragePercent, missingExposure, calibration, movementSensitivity, renderMode);
        return new Result(rendered, coveragePercent, missingExposure, warnings(rendered, coveragePercent, missingExposure, calibration, movementSensitivity));
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
            CaptureProfile captureProfile) {
        LensModel lensModel = estimateLensModel(records);
        ArrayList<CalibrationFrame> frames = new ArrayList<>();
        for (DraftFrameRecord record : records) {
            CalibrationFrame frame = CalibrationFrame.decode(record, lensModel);
            if (frame != null && !frame.polar) {
                frames.add(frame);
            }
        }
        frames.sort((left, right) -> {
            int pitchCompare = Integer.compare(left.basePitchDegrees, right.basePitchDegrees);
            return pitchCompare != 0 ? pitchCompare : Float.compare(left.baseYawDegrees, right.baseYawDegrees);
        });

        HashMap<String, PoseCorrection> corrections = new HashMap<>();
        ArrayList<FeatureMatchEdge> matchEdges = new ArrayList<>();
        int matchedPairs = 0;
        int openCvMatchedPairs = 0;
        float confidenceTotal = 0f;
        for (FramePair framePair : predictedOverlapPairs(frames, lensModel)) {
            CalibrationFrame left = framePair.left;
            CalibrationFrame right = framePair.right;
            PairAdjustment adjustment = matchFeatureOverlap(left, right, movementSensitivity);
            if (adjustment == null) {
                adjustment = correlateOverlap(left, right, movementSensitivity);
            }
            if (adjustment == null || adjustment.confidence < movementSensitivity.minimumConfidence) {
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
        }
        for (PoseCorrection correction : corrections.values()) {
            correction.averageAndClamp();
        }
        FeatureMatchGraph featureMatchGraph = new FeatureMatchGraph(matchEdges, true);
        GlobalPoseOptimization poseOptimization = GlobalPoseOptimization.from(featureMatchGraph, corrections);
        poseOptimization.apply();
        SparseDepthHints sparseDepthHints = SparseDepthHints.from(featureMatchGraph, captureProfile);
        return new Calibration(
                lensModel,
                corrections,
                matchedPairs,
                openCvMatchedPairs,
                matchedPairs == 0 ? 0f : confidenceTotal / matchedPairs,
                captureProfile,
                sparseDepthHints,
                estimateExposureGains(frames));
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
            float[] weights) {
        blendOne(frame, projection, lensModel, renderMode, frameIndex, exposureGain, projection.bounds, red, green, blue, weights);
        if (projection.bounds.left < 0f) {
            RectF shifted = new RectF(projection.bounds);
            shifted.offset(OUTPUT_WIDTH, 0f);
            blendOne(frame, projection, lensModel, renderMode, frameIndex, exposureGain, shifted, red, green, blue, weights);
        }
        if (projection.bounds.right > OUTPUT_WIDTH) {
            RectF shifted = new RectF(projection.bounds);
            shifted.offset(-OUTPUT_WIDTH, 0f);
            blendOne(frame, projection, lensModel, renderMode, frameIndex, exposureGain, shifted, red, green, blue, weights);
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
            float[] weights) {
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
                if (renderMode.selectsBestSource && weight <= weights[index]) {
                    continue;
                }
                if (renderMode.selectsBestSource) {
                    red[index] = ((color >> 16) & 0xFF) * weight;
                    green[index] = ((color >> 8) & 0xFF) * weight;
                    blue[index] = (color & 0xFF) * weight;
                    weights[index] = weight;
                } else {
                    red[index] += ((color >> 16) & 0xFF) * weight;
                    green[index] += ((color >> 8) & 0xFF) * weight;
                    blue[index] += (color & 0xFF) * weight;
                    weights[index] += weight;
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

    private static Bitmap composeOutput(float[] red, float[] green, float[] blue, float[] weights) {
        int[] pixels = new int[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        for (int i = 0; i < pixels.length; i++) {
            if (weights[i] <= 0f) {
                pixels[i] = 0xFF101820;
                continue;
            }
            int r = clamp(Math.round(red[i] / weights[i]), 0, 255);
            int g = clamp(Math.round(green[i] / weights[i]), 0, 255);
            int b = clamp(Math.round(blue[i] / weights[i]), 0, 255);
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
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
            RenderMode renderMode) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, String.format(
                    Locale.US,
                    "Spherify Phase 5 draft stitch; session=%s; frames=%d; estimatedCoverage=%d%%; missingExposure=%d; projection=equirectangular; hfov=%.1f; vfov=%.1f; k1=%.3f; k2=%.3f; matchedOverlaps=%d; openCvMatchedOverlaps=%d; movementSensitivity=%s; renderMode=%s; captureProfile=%s; sparseDepthHints=%s",
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
                    calibration.sparseDepthHints.metadataLabel));
            exif.setAttribute(ExifInterface.TAG_MAKE, "Spherify");
            exif.setAttribute(ExifInterface.TAG_MODEL, "Phase 5 draft stitcher");
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
            MovementSensitivity movementSensitivity) {
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
        STRONGEST_SOURCE("sharp-best-source", true),
        CONTRIBUTOR_MAP("contributor-map", true),
        BLENDED("blended", false);

        final String label;
        final boolean selectsBestSource;

        RenderMode(String label, boolean selectsBestSource) {
            this.label = label;
            this.selectsBestSource = selectsBestSource;
        }

        static RenderMode from(String mode) {
            if ("blended".equals(mode)) {
                return BLENDED;
            }
            if ("contributors".equals(mode)) {
                return CONTRIBUTOR_MAP;
            }
            return STRONGEST_SOURCE;
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

    private static final class Calibration {
        final LensModel lensModel;
        final Map<String, PoseCorrection> corrections;
        final int matchedOverlapCount;
        final int openCvMatchedOverlapCount;
        final float averageOverlapConfidence;
        final CaptureProfile captureProfile;
        final SparseDepthHints sparseDepthHints;
        final Map<String, Float> exposureGains;

        Calibration(
                LensModel lensModel,
                Map<String, PoseCorrection> corrections,
                int matchedOverlapCount,
                int openCvMatchedOverlapCount,
                float averageOverlapConfidence,
                CaptureProfile captureProfile,
                SparseDepthHints sparseDepthHints,
                Map<String, Float> exposureGains) {
            this.lensModel = lensModel;
            this.corrections = corrections;
            this.matchedOverlapCount = matchedOverlapCount;
            this.openCvMatchedOverlapCount = openCvMatchedOverlapCount;
            this.averageOverlapConfidence = averageOverlapConfidence;
            this.captureProfile = captureProfile;
            this.sparseDepthHints = sparseDepthHints;
            this.exposureGains = exposureGains;
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

    static final class Result {
        final int renderedFrames;
        final int coveragePercent;
        final int missingExposureFrames;
        final List<String> warnings;

        Result(int renderedFrames, int coveragePercent, int missingExposureFrames, List<String> warnings) {
            this.renderedFrames = renderedFrames;
            this.coveragePercent = coveragePercent;
            this.missingExposureFrames = missingExposureFrames;
            this.warnings = warnings;
        }
    }
}
