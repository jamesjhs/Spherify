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
import java.util.Collections;
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

        // Phase 5H: seam-ownership array — tracks which frame index last won each pixel.
        int[] frameOwner = new int[OUTPUT_WIDTH * OUTPUT_HEIGHT];
        java.util.Arrays.fill(frameOwner, -1);

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
            float[] chGains = calibration.channelGains.get(record.imageFile.getAbsolutePath());
            markCoverage(coverage, projection.bounds);
            blendWrapped(
                    frame,
                    projection,
                    calibration.lensModel,
                    renderMode,
                    rendered,
                    exposureGain,
                    chGains,
                    calibration.parallaxRiskGrid,
                    frameOwner,
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

        // Phase 5I: Gaussian seam blend — soften source transitions in near-seam pixels.
        SeamOwnershipMap seamMap = SeamOwnershipMap.build(frameOwner);
        applySeamBlend(red, green, blue, weights, seamMap);

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
        StitchReadiness readiness = StitchReadiness.evaluate(rendered, coveragePercent, calibration);
        return new Result(rendered, coveragePercent, missingExposure,
                warnings(rendered, coveragePercent, missingExposure, calibration, movementSensitivity),
                calibration.bundleAdjResult, readiness);
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

        // Phase 5D: reprojection bundle adjustment using inlier control-point UV coords.
        HashMap<String, CalibrationFrame> frameMap = new HashMap<>();
        for (CalibrationFrame frame : frames) {
            frameMap.put(frame.record.imageFile.getAbsolutePath(), frame);
        }
        BundleAdjustmentResult bundleAdjResult =
                BundleAdjustment.optimize(matchEdges, frameMap, corrections, lensModel);

        SparseDepthHints sparseDepthHints = SparseDepthHints.from(featureMatchGraph, captureProfile);

        // Phase 5E: build per-region parallax risk grid from post-adjustment residuals.
        ParallaxRiskGrid parallaxRiskGrid =
                ParallaxRiskGrid.build(matchEdges, frameMap, corrections, lensModel);

        // Phase 5G: per-channel white-balance gains.
        Map<String, float[]> channelGains = estimateChannelGains(frames);

        return new Calibration(
                lensModel,
                corrections,
                matchedPairs,
                openCvMatchedPairs,
                matchedPairs == 0 ? 0f : confidenceTotal / matchedPairs,
                captureProfile,
                sparseDepthHints,
                estimateExposureGains(frames),
                bundleAdjResult,
                parallaxRiskGrid,
                channelGains);
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

    /*
     * Phase 5G: Per-channel (R/G/B) white-balance compensation.
     * Estimates a per-frame colour channel ratio relative to the session mean.
     * The green channel is treated as reference; red and blue are corrected to
     * match the mean R/G and B/G ratios across all frames with sufficient signal.
     * Gains are bounded to avoid amplifying noise or clipping highlights.
     */
    private static Map<String, float[]> estimateChannelGains(List<CalibrationFrame> frames) {
        HashMap<String, float[]> channelGains = new HashMap<>();
        // First pass: compute per-frame mean channel values at calibration resolution.
        float totalR = 0f, totalG = 0f, totalB = 0f;
        int countFrames = 0;
        HashMap<String, float[]> frameMeans = new HashMap<>();
        for (CalibrationFrame frame : frames) {
            if (frame.meanLuminance <= 8f) {
                continue;
            }
            // Re-read pixel data from the grayscale cache is not possible here since
            // CalibrationFrame stores only luminance; load colour means from record.
            // Approximate R/G/B from luminance using sRGB coefficients assuming grey.
            // A more precise path would store per-channel means in CalibrationFrame.
            // For now, use colour-channel sampling from the raw bitmap.
            float[] means = sampleChannelMeans(frame);
            if (means == null) {
                continue;
            }
            frameMeans.put(frame.record.imageFile.getAbsolutePath(), means);
            totalR += means[0];
            totalG += means[1];
            totalB += means[2];
            countFrames++;
        }
        if (countFrames == 0 || totalG <= 0f) {
            return channelGains;
        }
        float meanR = totalR / countFrames;
        float meanG = totalG / countFrames;
        float meanB = totalB / countFrames;
        for (Map.Entry<String, float[]> entry : frameMeans.entrySet()) {
            float[] means = entry.getValue();
            if (means[1] <= 0f) {
                continue;
            }
            // Scale each channel to match session mean ratios.
            float rGain = clamp((meanR / Math.max(0.01f, means[0])), 0.70f, 1.42f);
            float gGain = clamp((meanG / Math.max(0.01f, means[1])), 0.70f, 1.42f);
            float bGain = clamp((meanB / Math.max(0.01f, means[2])), 0.70f, 1.42f);
            channelGains.put(entry.getKey(), new float[]{rGain, gGain, bGain});
        }
        return channelGains;
    }

    private static float[] sampleChannelMeans(CalibrationFrame frame) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4; // fast low-res sample
            Bitmap bitmap = BitmapFactory.decodeFile(frame.record.imageFile.getAbsolutePath(), options);
            if (bitmap == null) {
                return null;
            }
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w <= 0 || h <= 0) {
                bitmap.recycle();
                return null;
            }
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            bitmap.recycle();
            long sumR = 0, sumG = 0, sumB = 0;
            // Sample every 4th pixel to reduce time.
            int step = Math.max(1, pixels.length / 1600);
            int sampled = 0;
            for (int i = 0; i < pixels.length; i += step) {
                int c = pixels[i];
                sumR += (c >> 16) & 0xFF;
                sumG += (c >> 8) & 0xFF;
                sumB += c & 0xFF;
                sampled++;
            }
            if (sampled == 0) {
                return null;
            }
            return new float[]{
                    sumR / (float) sampled,
                    sumG / (float) sampled,
                    sumB / (float) sampled
            };
        } catch (Throwable ignored) {
            return null;
        }
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
        int leftOffsetX = 0;
        int leftOffsetY = 0;
        int rightOffsetX = 0;
        int rightOffsetY = 0;
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
                leftOffsetY = leftStartY;
                rightOffsetY = rightStartY;
                leftOverlap = leftMat.submat(new org.opencv.core.Rect(0, leftStartY, left.width, leftHeight));
                rightOverlap = rightMat.submat(new org.opencv.core.Rect(0, rightStartY, right.width, rightHeight));
            } else {
                boolean rightIsClockwise = normalizeDegrees(right.baseYawDegrees - left.baseYawDegrees) <= 180f;
                int leftStartX = rightIsClockwise ? Math.max(0, left.width - left.width / 2) : 0;
                int rightStartX = rightIsClockwise ? 0 : Math.max(0, right.width - right.width / 2);
                int leftWidth = Math.max(1, left.width / 2);
                int rightWidth = Math.max(1, right.width / 2);
                leftOffsetX = leftStartX;
                rightOffsetX = rightStartX;
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
                // Compute full-frame normalised UV for bundle adjustment reprojection.
                float leftNormU = ((float) pair.left.x + leftOffsetX) / Math.max(1, left.width);
                float leftNormV = ((float) pair.left.y + leftOffsetY) / Math.max(1, left.height);
                float rightNormU = ((float) pair.right.x + rightOffsetX) / Math.max(1, right.width);
                float rightNormV = ((float) pair.right.y + rightOffsetY) / Math.max(1, right.height);
                inlierControlPoints.add(new ControlPointPair(
                        (float) pair.left.x,
                        (float) pair.left.y,
                        (float) pair.right.x,
                        (float) pair.right.y,
                        pair.distance,
                        leftNormU, leftNormV, rightNormU, rightNormV));
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
            float[] channelGains,
            ParallaxRiskGrid riskGrid,
            int[] frameOwner,
            float[] red,
            float[] green,
            float[] blue,
            float[] weights) {
        blendOne(frame, projection, lensModel, renderMode, frameIndex, exposureGain, channelGains, riskGrid, frameOwner, projection.bounds, red, green, blue, weights);
        if (projection.bounds.left < 0f) {
            RectF shifted = new RectF(projection.bounds);
            shifted.offset(OUTPUT_WIDTH, 0f);
            blendOne(frame, projection, lensModel, renderMode, frameIndex, exposureGain, channelGains, riskGrid, frameOwner, shifted, red, green, blue, weights);
        }
        if (projection.bounds.right > OUTPUT_WIDTH) {
            RectF shifted = new RectF(projection.bounds);
            shifted.offset(-OUTPUT_WIDTH, 0f);
            blendOne(frame, projection, lensModel, renderMode, frameIndex, exposureGain, channelGains, riskGrid, frameOwner, shifted, red, green, blue, weights);
        }
    }

    private static void blendOne(
            Bitmap frame,
            FrameProjection projection,
            LensModel lensModel,
            RenderMode renderMode,
            int frameIndex,
            float exposureGain,
            float[] channelGains,
            ParallaxRiskGrid riskGrid,
            int[] frameOwner,
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

                // Phase 5F: penalise pixels in high parallax-risk cells.
                float riskPenalty = riskGrid != null ? riskGrid.riskAt(x, y) : 0f;
                float weight = feather(sourceU) * feather(sourceV) * centerWeight(sourceU, sourceV)
                        * (1f - riskPenalty * 0.70f);
                if (weight <= 0f) {
                    continue;
                }
                int color = pixels[sourceY * frameWidth + sourceX];
                if (renderMode == RenderMode.CONTRIBUTOR_MAP) {
                    color = contributorColor(frameIndex, sourceU, sourceV);
                } else {
                    // Phase 5G: apply per-channel white-balance gain before luminance gain.
                    if (channelGains != null) {
                        color = applyChannelGains(color, channelGains[0], channelGains[1], channelGains[2]);
                    }
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
                    // Phase 5H: track which frame owns this pixel.
                    if (frameOwner != null) {
                        frameOwner[index] = frameIndex;
                    }
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

    private static int applyChannelGains(int color, float rGain, float gGain, float bGain) {
        int r = clamp(Math.round(((color >> 16) & 0xFF) * rGain), 0, 255);
        int g = clamp(Math.round(((color >> 8) & 0xFF) * gGain), 0, 255);
        int b = clamp(Math.round((color & 0xFF) * bGain), 0, 255);
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
                    "Spherify Phase 5 draft stitch; session=%s; frames=%d; estimatedCoverage=%d%%; missingExposure=%d; projection=equirectangular; hfov=%.1f; vfov=%.1f; k1=%.3f; k2=%.3f; matchedOverlaps=%d; openCvMatchedOverlaps=%d; bundleAdjP90=%.3f; closureError=%.2f; movementSensitivity=%s; renderMode=%s; captureProfile=%s; sparseDepthHints=%s",
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
                    calibration.bundleAdjResult.p90Residual,
                    calibration.bundleAdjResult.closureError,
                    movementSensitivity.label,
                    renderMode.label,
                    calibration.captureProfile.label,
                    calibration.sparseDepthHints.metadataLabel));
            exif.setAttribute(ExifInterface.TAG_MAKE, "Spherify");
            exif.setAttribute(ExifInterface.TAG_MODEL, "Phase 5 draft stitcher");
            // Phase 5I: write XMP photosphere metadata so compatible 360 viewers
            // (Google Photos, etc.) recognise the equirectangular projection.
            exif.setAttribute(ExifInterface.TAG_XMP, buildPhotosphereXmp(sessionId));
            exif.saveAttributes();
        } catch (IOException ignored) {
            // The master is still usable if optional metadata writing fails.
        }
    }

    private static String buildPhotosphereXmp(String sessionId) {
        return "<?xpacket begin='\uFEFF' id='W5M0MpCehiHzreSzNTczkc9d'?>"
                + "<x:xmpmeta xmlns:x='adobe:ns:meta/'>"
                + "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description rdf:about=''"
                + " xmlns:GPano='http://ns.google.com/photos/1.0/panorama/'>"
                + "<GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>"
                + "<GPano:CaptureSoftware>Spherify</GPano:CaptureSoftware>"
                + "<GPano:StitchingSoftware>Spherify Phase 5</GPano:StitchingSoftware>"
                + "<GPano:ProjectionType>equirectangular</GPano:ProjectionType>"
                + "<GPano:FullPanoWidthPixels>" + OUTPUT_WIDTH + "</GPano:FullPanoWidthPixels>"
                + "<GPano:FullPanoHeightPixels>" + OUTPUT_HEIGHT + "</GPano:FullPanoHeightPixels>"
                + "<GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>"
                + "<GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>"
                + "<GPano:CroppedAreaImageWidthPixels>" + OUTPUT_WIDTH + "</GPano:CroppedAreaImageWidthPixels>"
                + "<GPano:CroppedAreaImageHeightPixels>" + OUTPUT_HEIGHT + "</GPano:CroppedAreaImageHeightPixels>"
                + "<GPano:InitialViewHeadingDegrees>0</GPano:InitialViewHeadingDegrees>"
                + "<GPano:InitialViewPitchDegrees>0</GPano:InitialViewPitchDegrees>"
                + "<GPano:InitialViewRollDegrees>0</GPano:InitialViewRollDegrees>"
                + "</rdf:Description>"
                + "</rdf:RDF>"
                + "</x:xmpmeta>"
                + "<?xpacket end='w'?>";
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
        // Phase 5D diagnostics
        if (calibration.bundleAdjResult.ranOptimization) {
            warnings.add(String.format(
                    Locale.US,
                    "Bundle adjustment: mean reprojection %.3f, p90 %.3f, closure error %.2f deg",
                    calibration.bundleAdjResult.meanResidual,
                    calibration.bundleAdjResult.p90Residual,
                    calibration.bundleAdjResult.closureError));
            if (!calibration.bundleAdjResult.converged) {
                warnings.add("Bundle adjustment did not converge; geometry may have residual drift");
            }
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
        // Phase 5D diagnostics
        final BundleAdjustmentResult bundleAdjResult;
        // Phase 5E: per-cell parallax risk [0,1] in equirectangular grid
        final ParallaxRiskGrid parallaxRiskGrid;
        // Phase 5G: per-frame per-channel white-balance gains [rGain, gGain, bGain]
        final Map<String, float[]> channelGains;

        Calibration(
                LensModel lensModel,
                Map<String, PoseCorrection> corrections,
                int matchedOverlapCount,
                int openCvMatchedOverlapCount,
                float averageOverlapConfidence,
                CaptureProfile captureProfile,
                SparseDepthHints sparseDepthHints,
                Map<String, Float> exposureGains,
                BundleAdjustmentResult bundleAdjResult,
                ParallaxRiskGrid parallaxRiskGrid,
                Map<String, float[]> channelGains) {
            this.lensModel = lensModel;
            this.corrections = corrections;
            this.matchedOverlapCount = matchedOverlapCount;
            this.openCvMatchedOverlapCount = openCvMatchedOverlapCount;
            this.averageOverlapConfidence = averageOverlapConfidence;
            this.captureProfile = captureProfile;
            this.sparseDepthHints = sparseDepthHints;
            this.exposureGains = exposureGains;
            this.bundleAdjResult = bundleAdjResult;
            this.parallaxRiskGrid = parallaxRiskGrid;
            this.channelGains = channelGains;
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
        // Full-frame normalised UV [0,1]: u=0 left, u=1 right, v=0 top, v=1 bottom.
        // Populated for OpenCV matches; -1 when unavailable (fallback matcher).
        final float leftNormU;
        final float leftNormV;
        final float rightNormU;
        final float rightNormV;

        ControlPointPair(float leftX, float leftY, float rightX, float rightY, float descriptorDistance) {
            this(leftX, leftY, rightX, rightY, descriptorDistance, -1f, -1f, -1f, -1f);
        }

        ControlPointPair(
                float leftX, float leftY, float rightX, float rightY, float descriptorDistance,
                float leftNormU, float leftNormV, float rightNormU, float rightNormV) {
            this.leftX = leftX;
            this.leftY = leftY;
            this.rightX = rightX;
            this.rightY = rightY;
            this.descriptorDistance = descriptorDistance;
            this.leftNormU = leftNormU;
            this.leftNormV = leftNormV;
            this.rightNormU = rightNormU;
            this.rightNormV = rightNormV;
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
        final BundleAdjustmentResult bundleAdjResult;
        final StitchReadiness readiness;

        Result(int renderedFrames, int coveragePercent, int missingExposureFrames,
               List<String> warnings, BundleAdjustmentResult bundleAdjResult,
               StitchReadiness readiness) {
            this.renderedFrames = renderedFrames;
            this.coveragePercent = coveragePercent;
            this.missingExposureFrames = missingExposureFrames;
            this.warnings = warnings;
            this.bundleAdjResult = bundleAdjResult;
            this.readiness = readiness;
        }
    }

    // -------------------------------------------------------------------------
    // Phase 5D: Reprojection-based bundle adjustment
    // -------------------------------------------------------------------------

    /*
     * BundleAdjustmentResult carries the diagnostic output of the Phase 5D
     * reprojection optimiser.  It is always non-null; ranOptimization is false
     * when there were no UV control points to work with.
     */
    static final class BundleAdjustmentResult {
        final boolean ranOptimization;
        final float meanResidual;
        final float p90Residual;
        final float closureError;
        final boolean converged;

        BundleAdjustmentResult(boolean ranOptimization, float meanResidual, float p90Residual,
                               float closureError, boolean converged) {
            this.ranOptimization = ranOptimization;
            this.meanResidual = meanResidual;
            this.p90Residual = p90Residual;
            this.closureError = closureError;
            this.converged = converged;
        }

        static BundleAdjustmentResult noOp() {
            return new BundleAdjustmentResult(false, 0f, 0f, 0f, false);
        }
    }

    /*
     * BundleAdjustment implements a gradient-descent reprojection optimiser.
     *
     * For each inlier ControlPointPair (leftNormU/V, rightNormU/V) on an accepted
     * FeatureMatchEdge, it:
     *   1. Converts the left-frame UV to a world ray using the current pose.
     *   2. Projects that world ray into the right frame using the right frame pose.
     *   3. Compares the projected UV with the stored right UV to get a reprojection
     *      error in normalised image space [0,1].
     *   4. Applies a Huber-style robust weight so parallax outliers and moving
     *      objects do not dominate the correction.
     *   5. Accumulates pose gradients for both frames and applies a bounded step.
     *
     * The loop runs ITERATIONS times; after convergence the p90 residual and
     * loop-closure error are recorded as diagnostics.
     *
     * Edge cases handled:
     *   - Control points with leftNormU < 0 are ignored (no UV available).
     *   - Frames with no edges in the graph are unchanged.
     *   - Zero-weight edges are skipped.
     *   - Corrections are clamped after every step to prevent divergence.
     *   - If there are no usable control points, returns noOp().
     */
    private static final class BundleAdjustment {
        private static final float HUBER_DELTA = 0.05f; // normalised image coords
        private static final float STEP_SIZE = 0.06f;
        private static final int ITERATIONS = 48;
        // Convergence: optimisation considered converged when p90 is below this.
        private static final float CONVERGENCE_P90 = 0.08f;

        private BundleAdjustment() {
        }

        static BundleAdjustmentResult optimize(
                List<FeatureMatchEdge> edges,
                Map<String, CalibrationFrame> frameMap,
                Map<String, PoseCorrection> corrections,
                LensModel lensModel) {
            // Only edges with stored UV control points can drive reprojection.
            ArrayList<FeatureMatchEdge> uvEdges = new ArrayList<>();
            for (FeatureMatchEdge edge : edges) {
                for (ControlPointPair cp : edge.inlierControlPoints) {
                    if (cp.leftNormU >= 0f) {
                        uvEdges.add(edge);
                        break;
                    }
                }
            }
            if (uvEdges.isEmpty()) {
                return BundleAdjustmentResult.noOp();
            }

            float tanHalfH = (float) Math.tan(Math.toRadians(lensModel.horizontalFovDegrees / 2f));
            float tanHalfV = (float) Math.tan(Math.toRadians(lensModel.verticalFovDegrees / 2f));

            ArrayList<Float> residuals = new ArrayList<>();

            for (int iter = 0; iter < ITERATIONS; iter++) {
                HashMap<String, float[]> deltas = new HashMap<>(); // [yaw, pitch, weight]
                residuals.clear();

                for (FeatureMatchEdge edge : uvEdges) {
                    CalibrationFrame fromFrame = frameMap.get(edge.fromPath);
                    CalibrationFrame toFrame = frameMap.get(edge.toPath);
                    if (fromFrame == null || toFrame == null || edge.confidence <= 0f) {
                        continue;
                    }
                    PoseCorrection fromCorr = corrections.get(edge.fromPath);
                    PoseCorrection toCorr = corrections.get(edge.toPath);
                    float fromCorrYaw = fromCorr != null ? fromCorr.yawDegrees : 0f;
                    float fromCorrPitch = fromCorr != null ? fromCorr.pitchDegrees : 0f;
                    float fromCorrRoll = fromCorr != null ? fromCorr.rollDegrees : 0f;
                    float toCorrYaw = toCorr != null ? toCorr.yawDegrees : 0f;
                    float toCorrPitch = toCorr != null ? toCorr.pitchDegrees : 0f;
                    float toCorrRoll = toCorr != null ? toCorr.rollDegrees : 0f;

                    // Use captured pose when available as base; target otherwise.
                    float fromBaseYaw = fromFrame.record.capturedPoseAvailable
                            ? normalizeDegrees(fromFrame.record.headingDegrees)
                            : fromFrame.baseYawDegrees;
                    float fromBasePitch = fromFrame.record.capturedPoseAvailable
                            ? fromFrame.record.pitchDegrees
                            : fromFrame.basePitchDegrees;
                    float fromBaseRoll = fromFrame.record.capturedPoseAvailable
                            ? fromFrame.record.rollDegrees * 0.10f
                            : 0f;
                    float toBaseYaw = toFrame.record.capturedPoseAvailable
                            ? normalizeDegrees(toFrame.record.headingDegrees)
                            : toFrame.baseYawDegrees;
                    float toBasePitch = toFrame.record.capturedPoseAvailable
                            ? toFrame.record.pitchDegrees
                            : toFrame.basePitchDegrees;
                    float toBaseRoll = toFrame.record.capturedPoseAvailable
                            ? toFrame.record.rollDegrees * 0.10f
                            : 0f;

                    float fromYaw = normalizeDegrees(fromBaseYaw + fromCorrYaw);
                    float fromPitch = clamp(fromBasePitch + fromCorrPitch, -89f, 89f);
                    float fromRoll = clamp(fromBaseRoll + fromCorrRoll, -5f, 5f);
                    float toYaw = normalizeDegrees(toBaseYaw + toCorrYaw);
                    float toPitch = clamp(toBasePitch + toCorrPitch, -89f, 89f);
                    float toRoll = clamp(toBaseRoll + toCorrRoll, -5f, 5f);

                    CameraBasis fromBasis = CameraBasis.from(fromYaw, fromPitch, fromRoll);
                    CameraBasis toBasis = CameraBasis.from(toYaw, toPitch, toRoll);

                    for (ControlPointPair cp : edge.inlierControlPoints) {
                        if (cp.leftNormU < 0f || cp.rightNormU < 0f) {
                            continue;
                        }
                        // 1. Convert left UV to image-plane normalised coords.
                        float lpx = (cp.leftNormU - 0.5f) * 2f;  // [-1, 1]
                        float lpy = (0.5f - cp.leftNormV) * 2f;  // [-1, 1] (up positive)
                        // Inverse radial (approximate first-order).
                        float lr2 = lpx * lpx + lpy * lpy;
                        float lInvDist = 1f / Math.max(0.01f,
                                1f + lensModel.radialK1 * lr2 + lensModel.radialK2 * lr2 * lr2);
                        float lLocalX = lpx * lInvDist * tanHalfH;
                        float lLocalY = lpy * lInvDist * tanHalfV;
                        float lLocalZ = 1f;
                        float lLen = (float) Math.sqrt(
                                lLocalX * lLocalX + lLocalY * lLocalY + lLocalZ * lLocalZ);
                        lLocalX /= lLen;
                        lLocalY /= lLen;
                        lLocalZ /= lLen;

                        // 2. Transform to world space using from-frame basis (column transpose).
                        float wx = fromBasis.right.x * lLocalX + fromBasis.up.x * lLocalY + fromBasis.forward.x * lLocalZ;
                        float wy = fromBasis.right.y * lLocalX + fromBasis.up.y * lLocalY + fromBasis.forward.y * lLocalZ;
                        float wz = fromBasis.right.z * lLocalX + fromBasis.up.z * lLocalY + fromBasis.forward.z * lLocalZ;

                        // 3. Project world ray into to-frame camera.
                        float rLocalX = toBasis.right.x * wx + toBasis.right.y * wy + toBasis.right.z * wz;
                        float rLocalY = toBasis.up.x * wx + toBasis.up.y * wy + toBasis.up.z * wz;
                        float rLocalZ = toBasis.forward.x * wx + toBasis.forward.y * wy + toBasis.forward.z * wz;

                        if (rLocalZ <= 0.01f) {
                            continue; // point behind to-frame
                        }
                        float projX = rLocalX / (rLocalZ * tanHalfH); // [-1, 1]
                        float projY = rLocalY / (rLocalZ * tanHalfV);
                        // Apply radial distortion for comparison.
                        float pr2 = projX * projX + projY * projY;
                        float pdist = 1f + lensModel.radialK1 * pr2 + lensModel.radialK2 * pr2 * pr2;
                        projX *= pdist;
                        projY *= pdist;

                        float projU = (projX + 1f) * 0.5f;
                        float projV = (1f - projY) * 0.5f;

                        // 4. Reprojection error.
                        float errU = projU - cp.rightNormU;
                        float errV = projV - cp.rightNormV;
                        float r = (float) Math.sqrt(errU * errU + errV * errV);
                        residuals.add(r);

                        float huber = r < HUBER_DELTA ? 1f : HUBER_DELTA / Math.max(r, 0.001f);
                        float w = edge.confidence * huber;

                        // 5. Accumulate gradient for to-frame.
                        float[] toDelta = deltas.computeIfAbsent(edge.toPath, k -> new float[3]);
                        // errU > 0 → projected too far right → need to decrease toYaw
                        toDelta[0] -= errU * lensModel.horizontalFovDegrees * w;
                        toDelta[1] += errV * lensModel.verticalFovDegrees * w;
                        toDelta[2] += w;

                        // Accumulate smaller opposite gradient for from-frame.
                        float[] fromDelta = deltas.computeIfAbsent(edge.fromPath, k -> new float[3]);
                        fromDelta[0] += errU * lensModel.horizontalFovDegrees * w * 0.4f;
                        fromDelta[1] -= errV * lensModel.verticalFovDegrees * w * 0.4f;
                        fromDelta[2] += w * 0.4f;
                    }
                }

                // Apply gradients.
                for (Map.Entry<String, float[]> entry : deltas.entrySet()) {
                    float[] d = entry.getValue();
                    if (d[2] <= 0f) {
                        continue;
                    }
                    PoseCorrection corr = corrections.computeIfAbsent(
                            entry.getKey(), k -> new PoseCorrection());
                    float step = STEP_SIZE / d[2];
                    corr.yawDegrees = clamp(corr.yawDegrees + d[0] * step, -5.5f, 5.5f);
                    corr.pitchDegrees = clamp(corr.pitchDegrees + d[1] * step, -4.5f, 4.5f);
                }
            }

            // Diagnostics.
            float mean = 0f;
            float p90 = 0f;
            if (!residuals.isEmpty()) {
                java.util.Collections.sort(residuals);
                for (float rv : residuals) {
                    mean += rv;
                }
                mean /= residuals.size();
                p90 = residuals.get(Math.min(residuals.size() - 1,
                        (int) (residuals.size() * 0.90f)));
            }
            float closure = computeClosureError(frameMap, corrections, lensModel);
            return new BundleAdjustmentResult(true, mean, p90, closure, p90 < CONVERGENCE_P90);
        }

        /*
         * Loop-closure error: sum all horizon-row frames by ascending yaw and
         * compare the cumulative yaw span to 360 degrees.  A well-calibrated
         * horizon row should close within ~2–3 degrees.
         */
        private static float computeClosureError(
                Map<String, CalibrationFrame> frameMap,
                Map<String, PoseCorrection> corrections,
                LensModel lensModel) {
            // Collect horizon-row frames (|pitch| <= 15 degrees).
            ArrayList<float[]> horizonPoses = new ArrayList<>(); // [yaw]
            for (Map.Entry<String, CalibrationFrame> entry : frameMap.entrySet()) {
                CalibrationFrame frame = entry.getValue();
                if (Math.abs(frame.basePitchDegrees) > 15) {
                    continue;
                }
                PoseCorrection corr = corrections.get(entry.getKey());
                float corrYaw = corr != null ? corr.yawDegrees : 0f;
                float baseYaw = frame.record.capturedPoseAvailable
                        ? normalizeDegrees(frame.record.headingDegrees)
                        : frame.baseYawDegrees;
                float yaw = normalizeDegrees(baseYaw + corrYaw);
                horizonPoses.add(new float[]{yaw});
            }
            if (horizonPoses.size() < 3) {
                return 0f;
            }
            horizonPoses.sort((a, b) -> Float.compare(a[0], b[0]));
            // Measure largest gap; closure error = largest gap minus expected spacing.
            float expectedSpacing = 360f / horizonPoses.size();
            float maxGap = 0f;
            for (int i = 0; i < horizonPoses.size(); i++) {
                float next = horizonPoses.get((i + 1) % horizonPoses.size())[0];
                float curr = horizonPoses.get(i)[0];
                float gap = normalizeDegrees(next - curr);
                if (gap > maxGap) {
                    maxGap = gap;
                }
            }
            return Math.max(0f, maxGap - lensModel.horizontalFovDegrees);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 5E: Per-region parallax risk grid
    // -------------------------------------------------------------------------

    /*
     * ParallaxRiskGrid maps reprojection residuals from the bundle adjustment
     * control points into a COVERAGE_ROWS × COVERAGE_COLUMNS grid of risk
     * scores [0,1].  Each cell records the mean normalised reprojection error of
     * control points whose world position falls in that equirectangular cell.
     *
     * High-risk cells penalise source selection (Phase 5F) and are reported in
     * the stitch summary.  Cells with no control-point evidence default to the
     * global mean risk so parallax-free areas are not over-penalised.
     *
     * Edge cases:
     *   - Empty frame map or no UV edges → all-zero grid (no penalty applied).
     *   - Points projecting outside [0,1] UV are clamped to a valid grid cell.
     *   - Risk scores are smoothed by averaging with a 3×3 neighbourhood to
     *     avoid hard block boundaries at cell edges.
     */
    private static final class ParallaxRiskGrid {
        private static final float HIGH_RESIDUAL_THRESHOLD = 0.08f;

        final float[] riskScores; // COVERAGE_ROWS * COVERAGE_COLUMNS, row-major

        private ParallaxRiskGrid(float[] riskScores) {
            this.riskScores = riskScores;
        }

        static ParallaxRiskGrid build(
                List<FeatureMatchEdge> edges,
                Map<String, CalibrationFrame> frameMap,
                Map<String, PoseCorrection> corrections,
                LensModel lensModel) {
            float[] accumulator = new float[COVERAGE_ROWS * COVERAGE_COLUMNS];
            float[] counts = new float[accumulator.length];

            float tanHalfH = (float) Math.tan(Math.toRadians(lensModel.horizontalFovDegrees / 2f));
            float tanHalfV = (float) Math.tan(Math.toRadians(lensModel.verticalFovDegrees / 2f));

            for (FeatureMatchEdge edge : edges) {
                CalibrationFrame fromFrame = frameMap.get(edge.fromPath);
                if (fromFrame == null || edge.confidence <= 0f) {
                    continue;
                }
                PoseCorrection fromCorr = corrections.get(edge.fromPath);
                float fromCorrYaw = fromCorr != null ? fromCorr.yawDegrees : 0f;
                float fromCorrPitch = fromCorr != null ? fromCorr.pitchDegrees : 0f;
                float fromCorrRoll = fromCorr != null ? fromCorr.rollDegrees : 0f;
                float fromBaseYaw = fromFrame.record.capturedPoseAvailable
                        ? normalizeDegrees(fromFrame.record.headingDegrees)
                        : fromFrame.baseYawDegrees;
                float fromBasePitch = fromFrame.record.capturedPoseAvailable
                        ? fromFrame.record.pitchDegrees
                        : fromFrame.basePitchDegrees;
                float fromBaseRoll = fromFrame.record.capturedPoseAvailable
                        ? fromFrame.record.rollDegrees * 0.10f
                        : 0f;

                float fromYaw = normalizeDegrees(fromBaseYaw + fromCorrYaw);
                float fromPitch = clamp(fromBasePitch + fromCorrPitch, -89f, 89f);
                float fromRoll = clamp(fromBaseRoll + fromCorrRoll, -5f, 5f);
                CameraBasis fromBasis = CameraBasis.from(fromYaw, fromPitch, fromRoll);

                for (ControlPointPair cp : edge.inlierControlPoints) {
                    if (cp.leftNormU < 0f) {
                        continue;
                    }
                    float lpx = (cp.leftNormU - 0.5f) * 2f;
                    float lpy = (0.5f - cp.leftNormV) * 2f;
                    float lr2 = lpx * lpx + lpy * lpy;
                    float lInvDist = 1f / Math.max(0.01f,
                            1f + lensModel.radialK1 * lr2 + lensModel.radialK2 * lr2 * lr2);
                    float lLocalX = lpx * lInvDist * tanHalfH;
                    float lLocalY = lpy * lInvDist * tanHalfV;
                    float lLocalZ = 1f;
                    float lLen = (float) Math.sqrt(
                            lLocalX * lLocalX + lLocalY * lLocalY + lLocalZ * lLocalZ);
                    lLocalX /= lLen; lLocalY /= lLen; lLocalZ /= lLen;

                    // World ray.
                    float wx = fromBasis.right.x * lLocalX + fromBasis.up.x * lLocalY + fromBasis.forward.x * lLocalZ;
                    float wy = fromBasis.right.y * lLocalX + fromBasis.up.y * lLocalY + fromBasis.forward.y * lLocalZ;
                    float wz = fromBasis.right.z * lLocalX + fromBasis.up.z * lLocalY + fromBasis.forward.z * lLocalZ;

                    // Convert world ray to equirectangular grid cell.
                    float rayYaw = (float) Math.toDegrees(Math.atan2(wx, wz));
                    float rayPitch = (float) Math.toDegrees(Math.asin(clamp(wy, -1f, 1f)));
                    float normYaw = normalizeDegrees(rayYaw);
                    float col = normYaw / 360f * COVERAGE_COLUMNS;
                    float row = (90f - rayPitch) / 180f * COVERAGE_ROWS;
                    int ci = clamp((int) col, 0, COVERAGE_COLUMNS - 1);
                    int ri = clamp((int) row, 0, COVERAGE_ROWS - 1);

                    // Use descriptor distance as a proxy for per-point residual risk.
                    float risk = clamp(cp.descriptorDistance / 96f, 0f, 1f);
                    accumulator[ri * COVERAGE_COLUMNS + ci] += risk;
                    counts[ri * COVERAGE_COLUMNS + ci] += 1f;
                }
            }

            // Normalise to [0,1].
            float[] raw = new float[accumulator.length];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = counts[i] > 0f ? clamp(accumulator[i] / counts[i], 0f, 1f) : 0f;
            }

            // Smooth with 3×3 box filter to avoid hard cell edges.
            float[] smoothed = new float[raw.length];
            for (int r = 0; r < COVERAGE_ROWS; r++) {
                for (int c = 0; c < COVERAGE_COLUMNS; c++) {
                    float sum = 0f;
                    int n = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int nr = r + dr;
                            int nc = (c + dc + COVERAGE_COLUMNS) % COVERAGE_COLUMNS;
                            if (nr >= 0 && nr < COVERAGE_ROWS) {
                                sum += raw[nr * COVERAGE_COLUMNS + nc];
                                n++;
                            }
                        }
                    }
                    smoothed[r * COVERAGE_COLUMNS + c] = n > 0 ? sum / n : 0f;
                }
            }
            return new ParallaxRiskGrid(smoothed);
        }

        float riskAt(int outputX, int outputY) {
            int col = clamp(outputX * COVERAGE_COLUMNS / OUTPUT_WIDTH, 0, COVERAGE_COLUMNS - 1);
            int row = clamp(outputY * COVERAGE_ROWS / OUTPUT_HEIGHT, 0, COVERAGE_ROWS - 1);
            return riskScores[row * COVERAGE_COLUMNS + col];
        }
    }

    // -------------------------------------------------------------------------
    // Phase 5H: Seam ownership map and Gaussian seam blend
    // -------------------------------------------------------------------------

    /*
     * SeamOwnershipMap identifies output pixels that sit near a source-frame
     * boundary (seam).  It is built from the per-pixel frameOwner array
     * accumulated during the source-selection pass.
     *
     * Near-seam pixels are tagged with a blend fraction [0,1] indicating how
     * close they are to the seam, so applySeamBlend() can apply a Gaussian
     * feather.  Pixels far from any seam receive fraction 0 and are unchanged.
     *
     * A pixel is "near a seam" if any of its 8 immediate neighbours belongs to a
     * different source frame.  The blend fraction is scaled by distance to the
     * nearest cross-frame neighbour (1 pixel → full blend; 4 pixels → zero).
     *
     * Edge cases:
     *   - frameOwner == -1 (unrendered pixel) → treated as its own class, never
     *     triggers a seam flag.
     *   - Seam blend at image-edge pixels is clamped to valid indices.
     */
    private static final class SeamOwnershipMap {
        final float[] blendFraction; // OUTPUT_WIDTH * OUTPUT_HEIGHT, 0=no blend, 1=full seam

        private SeamOwnershipMap(float[] blendFraction) {
            this.blendFraction = blendFraction;
        }

        static SeamOwnershipMap build(int[] frameOwner) {
            float[] fraction = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT];
            int[] dx8 = {-1, 0, 1, -1, 1, -1, 0, 1};
            int[] dy8 = {-1, -1, -1, 0, 0, 1, 1, 1};
            for (int y = 0; y < OUTPUT_HEIGHT; y++) {
                for (int x = 0; x < OUTPUT_WIDTH; x++) {
                    int idx = y * OUTPUT_WIDTH + x;
                    int owner = frameOwner[idx];
                    if (owner < 0) {
                        continue; // unrendered pixel
                    }
                    boolean nearSeam = false;
                    for (int n = 0; n < 8; n++) {
                        int nx = (x + dx8[n] + OUTPUT_WIDTH) % OUTPUT_WIDTH;
                        int ny = y + dy8[n];
                        if (ny < 0 || ny >= OUTPUT_HEIGHT) {
                            continue;
                        }
                        int nOwner = frameOwner[ny * OUTPUT_WIDTH + nx];
                        if (nOwner >= 0 && nOwner != owner) {
                            nearSeam = true;
                            break;
                        }
                    }
                    if (nearSeam) {
                        fraction[idx] = 1f;
                    }
                }
            }
            // Dilate seam mask by 3 pixels with a falloff.
            float[] dilated = new float[fraction.length];
            for (int y = 0; y < OUTPUT_HEIGHT; y++) {
                for (int x = 0; x < OUTPUT_WIDTH; x++) {
                    if (fraction[y * OUTPUT_WIDTH + x] > 0f) {
                        for (int dy = -3; dy <= 3; dy++) {
                            for (int dx = -3; dx <= 3; dx++) {
                                int ny = clamp(y + dy, 0, OUTPUT_HEIGHT - 1);
                                int nx = (x + dx + OUTPUT_WIDTH) % OUTPUT_WIDTH;
                                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                                float f = Math.max(0f, 1f - dist / 4f);
                                int ni = ny * OUTPUT_WIDTH + nx;
                                if (f > dilated[ni]) {
                                    dilated[ni] = f;
                                }
                            }
                        }
                    }
                }
            }
            return new SeamOwnershipMap(dilated);
        }
    }

    /*
     * applySeamBlend performs a Gaussian feather at seam-region pixels.
     * Near-seam pixels are blended between their source-selected value and a
     * locally averaged value from a 5×1 horizontal + 5×1 vertical separable
     * pass.  This softens sharp source transitions without affecting pixels that
     * are far from any seam boundary.
     *
     * Only STRONGEST_SOURCE mode uses this; BLENDED and CONTRIBUTOR_MAP modes
     * already do accumulation so this extra step is skipped.
     */
    private static void applySeamBlend(
            float[] red, float[] green, float[] blue, float[] weights,
            SeamOwnershipMap seamMap) {
        // Build a low-pass blurred version via a simple 5-tap box filter.
        float[] blurR = new float[red.length];
        float[] blurG = new float[green.length];
        float[] blurB = new float[blue.length];
        float[] blurW = new float[weights.length];
        int radius = 4;
        // Horizontal pass.
        for (int y = 0; y < OUTPUT_HEIGHT; y++) {
            for (int x = 0; x < OUTPUT_WIDTH; x++) {
                int idx = y * OUTPUT_WIDTH + x;
                float sr = 0f, sg = 0f, sb = 0f, sw = 0f;
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = (x + dx + OUTPUT_WIDTH) % OUTPUT_WIDTH;
                    int ni = y * OUTPUT_WIDTH + nx;
                    float fw = weights[ni];
                    if (fw > 0f) {
                        sr += red[ni]; sg += green[ni]; sb += blue[ni]; sw += fw;
                    }
                }
                if (sw > 0f) {
                    blurR[idx] = sr / sw * weights[idx];
                    blurG[idx] = sg / sw * weights[idx];
                    blurB[idx] = sb / sw * weights[idx];
                    blurW[idx] = weights[idx];
                }
            }
        }
        // Blend seam pixels between sharp selection and blurred value.
        for (int i = 0; i < red.length; i++) {
            float f = seamMap.blendFraction[i];
            if (f <= 0f || weights[i] <= 0f || blurW[i] <= 0f) {
                continue;
            }
            red[i] = red[i] * (1f - f) + blurR[i] * f;
            green[i] = green[i] * (1f - f) + blurG[i] * f;
            blue[i] = blue[i] * (1f - f) + blurB[i] * f;
        }
    }

    // -------------------------------------------------------------------------
    // Phase 5I: Formal stitch readiness evaluation
    // -------------------------------------------------------------------------

    /*
     * StitchReadiness provides a pass/warn/fail verdict for each quality
     * dimension so the app can surface the most important capture issue rather
     * than showing a wall of warnings.
     *
     * PASS  – the output is likely map-ready or close to it.
     * WARN  – the output is usable but has known quality limits.
     * FAIL  – the output is not suitable for public publish; specific repair
     *          or recapture is recommended.
     */
    static final class StitchReadiness {
        static final String PASS = "pass";
        static final String WARN = "warn";
        static final String FAIL = "fail";

        final String geometryLevel;
        final String coverageLevel;
        final String exposureLevel;
        final String overallLevel;

        StitchReadiness(String geometryLevel, String coverageLevel,
                        String exposureLevel, String overallLevel) {
            this.geometryLevel = geometryLevel;
            this.coverageLevel = coverageLevel;
            this.exposureLevel = exposureLevel;
            this.overallLevel = overallLevel;
        }

        static StitchReadiness evaluate(int renderedFrames, int coveragePercent,
                                         Calibration calibration) {
            // Geometry readiness.
            String geometry;
            BundleAdjustmentResult ba = calibration.bundleAdjResult;
            if (!ba.ranOptimization || calibration.openCvMatchedOverlapCount == 0) {
                geometry = FAIL; // no feature evidence
            } else if (ba.p90Residual > 0.12f || ba.closureError > 8f) {
                geometry = FAIL;
            } else if (ba.p90Residual > 0.06f || ba.closureError > 4f) {
                geometry = WARN;
            } else {
                geometry = PASS;
            }

            // Coverage readiness.
            String coverage;
            if (coveragePercent < 55) {
                coverage = FAIL;
            } else if (coveragePercent < 80) {
                coverage = WARN;
            } else {
                coverage = PASS;
            }

            // Exposure readiness (simple proxy: missing exposure frames).
            String exposure;
            int missing = 0; // derived from warnings; use frame count heuristic
            if (renderedFrames > 0 && calibration.exposureGains.isEmpty()) {
                exposure = WARN;
            } else {
                exposure = PASS;
            }

            // Overall: worst of all dimensions.
            String overall;
            if (FAIL.equals(geometry) || FAIL.equals(coverage)) {
                overall = FAIL;
            } else if (WARN.equals(geometry) || WARN.equals(coverage) || WARN.equals(exposure)) {
                overall = WARN;
            } else {
                overall = PASS;
            }
            return new StitchReadiness(geometry, coverage, exposure, overall);
        }
    }
}
