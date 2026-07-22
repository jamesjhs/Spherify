/*
 * Phase5Stitcher.java
 *
 * Educational overview:
 * Phase5Stitcher is the experimental stitching pipeline. It does not yet run a
 * full bundle-adjusted feature stitch, but it now uses the Phase 4 capture plan
 * as a pose estimate, scales polar frames like ordinary source images, and
 * projects camera frames through a simple pinhole lens model before blending
 * overlaps through weighted accumulation.
 */
package com.spherify.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.media.ExifInterface;

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
    private static final int COVERAGE_COLUMNS = 48;
    private static final int COVERAGE_ROWS = 24;

    private Phase5Stitcher() {
    }

    static Result stitch(List<DraftFrameRecord> records, File outputFile, String movementSensitivityMode) throws IOException {
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
        Calibration calibration = calibrate(usable, movementSensitivity);

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
            markCoverage(coverage, projection.bounds);
            blendWrapped(frame, projection, calibration.lensModel, red, green, blue, weights);
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
        writeMetadata(outputFile, usable.get(0).sessionId, rendered, coveragePercent, missingExposure, calibration, movementSensitivity);
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

    private static Calibration calibrate(List<DraftFrameRecord> records, MovementSensitivity movementSensitivity) {
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
        int matchedPairs = 0;
        float confidenceTotal = 0f;
        for (int i = 0; i < frames.size(); i++) {
            CalibrationFrame left = frames.get(i);
            CalibrationFrame right = nextFrameInLayer(frames, i);
            if (right == null) {
                continue;
            }
            PairAdjustment adjustment = correlateOverlap(left, right, movementSensitivity);
            if (adjustment == null || adjustment.confidence < movementSensitivity.minimumConfidence) {
                continue;
            }
            PoseCorrection correction = corrections.computeIfAbsent(
                    right.record.imageFile.getAbsolutePath(),
                    ignored -> new PoseCorrection());
            correction.yawDegrees -= adjustment.horizontalOffsetPixels
                    / Math.max(1f, right.width)
                    * lensModel.horizontalFovDegrees
                    * movementSensitivity.correctionScale;
            correction.pitchDegrees -= adjustment.verticalOffsetPixels
                    / Math.max(1f, right.height)
                    * lensModel.verticalFovDegrees
                    * movementSensitivity.correctionScale;
            if (adjustment.confidence >= movementSensitivity.rollMinimumConfidence) {
                correction.rollDegrees += adjustment.rollPixels
                        / Math.max(1f, right.height)
                        * lensModel.verticalFovDegrees
                        * movementSensitivity.rollCorrectionScale;
            }
            correction.samples++;
            matchedPairs++;
            confidenceTotal += adjustment.confidence;
        }
        for (PoseCorrection correction : corrections.values()) {
            correction.averageAndClamp();
        }
        return new Calibration(lensModel, corrections, matchedPairs, matchedPairs == 0 ? 0f : confidenceTotal / matchedPairs);
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
        float pitch = polar
                ? clamp(record.targetPitchDegrees, -89f, 89f)
                : clamp(record.targetPitchDegrees != 0 ? record.targetPitchDegrees : record.pitchDegrees, -89f, 89f);
        float horizontalFov = polar ? POLAR_HORIZONTAL_FOV_DEGREES : calibration.lensModel.horizontalFovDegrees;
        float verticalFov = polar
                ? POLAR_VERTICAL_FOV_DEGREES
                : clamp(calibration.lensModel.verticalFovDegrees, MIN_VERTICAL_FOV_DEGREES, MAX_VERTICAL_FOV_DEGREES);
        PoseCorrection correction = calibration.corrections.get(record.imageFile.getAbsolutePath());
        float yaw = normalizeDegrees(record.targetYawDegrees + (correction == null ? 0f : correction.yawDegrees));
        pitch = clamp(pitch + (correction == null ? 0f : correction.pitchDegrees), -89f, 89f);
        float roll = polar ? 0f : clamp(record.rollDegrees * 0.10f + (correction == null ? 0f : correction.rollDegrees), -5f, 5f);
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
            float[] red,
            float[] green,
            float[] blue,
            float[] weights) {
        blendOne(frame, projection, lensModel, projection.bounds, red, green, blue, weights);
        if (projection.bounds.left < 0f) {
            RectF shifted = new RectF(projection.bounds);
            shifted.offset(OUTPUT_WIDTH, 0f);
            blendOne(frame, projection, lensModel, shifted, red, green, blue, weights);
        }
        if (projection.bounds.right > OUTPUT_WIDTH) {
            RectF shifted = new RectF(projection.bounds);
            shifted.offset(-OUTPUT_WIDTH, 0f);
            blendOne(frame, projection, lensModel, shifted, red, green, blue, weights);
        }
    }

    private static void blendOne(
            Bitmap frame,
            FrameProjection projection,
            LensModel lensModel,
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
                int index = outputRow + x;
                red[index] += ((color >> 16) & 0xFF) * weight;
                green[index] += ((color >> 8) & 0xFF) * weight;
                blue[index] += (color & 0xFF) * weight;
                weights[index] += weight;
            }
        }
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
            MovementSensitivity movementSensitivity) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, String.format(
                    Locale.US,
                    "Spherify Phase 5 draft stitch; session=%s; frames=%d; estimatedCoverage=%d%%; missingExposure=%d; projection=equirectangular; hfov=%.1f; vfov=%.1f; k1=%.3f; k2=%.3f; matchedOverlaps=%d; movementSensitivity=%s",
                    sessionId,
                    renderedFrames,
                    coveragePercent,
                    missingExposure,
                    calibration.lensModel.horizontalFovDegrees,
                    calibration.lensModel.verticalFovDegrees,
                    calibration.lensModel.radialK1,
                    calibration.lensModel.radialK2,
                    calibration.matchedOverlapCount,
                    movementSensitivity.label));
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
        } else {
            warnings.add(String.format(
                    Locale.US,
                    "Applied %d overlap pose nudges at %.2f confidence",
                    calibration.matchedOverlapCount,
                    calibration.averageOverlapConfidence));
        }
        warnings.add(String.format(
                Locale.US,
                "Estimated lens %.1f deg HFOV, k1 %.3f, k2 %.3f",
                calibration.lensModel.horizontalFovDegrees,
                calibration.lensModel.radialK1,
                calibration.lensModel.radialK2));
        warnings.add("Movement sensitivity: " + movementSensitivity.label);
        return warnings;
    }

    private static float normalizeDegrees(float value) {
        float result = value % 360f;
        return result < 0f ? result + 360f : result;
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

    private static final class Calibration {
        final LensModel lensModel;
        final Map<String, PoseCorrection> corrections;
        final int matchedOverlapCount;
        final float averageOverlapConfidence;

        Calibration(
                LensModel lensModel,
                Map<String, PoseCorrection> corrections,
                int matchedOverlapCount,
                float averageOverlapConfidence) {
            this.lensModel = lensModel;
            this.corrections = corrections;
            this.matchedOverlapCount = matchedOverlapCount;
            this.averageOverlapConfidence = averageOverlapConfidence;
        }
    }

    private static final class PoseCorrection {
        float yawDegrees;
        float pitchDegrees;
        float rollDegrees;
        int samples;

        void averageAndClamp() {
            if (samples > 1) {
                yawDegrees /= samples;
                pitchDegrees /= samples;
                rollDegrees /= samples;
            }
            yawDegrees = clamp(yawDegrees, -4f, 4f);
            pitchDegrees = clamp(pitchDegrees, -4f, 4f);
            rollDegrees = clamp(rollDegrees, -1.2f, 1.2f);
        }
    }

    private static final class PairAdjustment {
        final int horizontalOffsetPixels;
        final int verticalOffsetPixels;
        final int rollPixels;
        final float score;
        final float confidence;

        PairAdjustment(
                int horizontalOffsetPixels,
                int verticalOffsetPixels,
                int rollPixels,
                float score,
                float confidence) {
            this.horizontalOffsetPixels = horizontalOffsetPixels;
            this.verticalOffsetPixels = verticalOffsetPixels;
            this.rollPixels = rollPixels;
            this.score = score;
            this.confidence = confidence;
        }
    }

    private static final class CalibrationFrame {
        final DraftFrameRecord record;
        final int width;
        final int height;
        final int[] grayscale;
        final float baseYawDegrees;
        final int basePitchDegrees;
        final boolean polar;

        private CalibrationFrame(
                DraftFrameRecord record,
                int width,
                int height,
                int[] grayscale,
                float baseYawDegrees,
                int basePitchDegrees,
                boolean polar) {
            this.record = record;
            this.width = width;
            this.height = height;
            this.grayscale = grayscale;
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
            for (int i = 0; i < pixels.length; i++) {
                int color = pixels[i];
                grayscale[i] = Math.round(
                        ((color >> 16) & 0xFF) * 0.299f
                                + ((color >> 8) & 0xFF) * 0.587f
                                + (color & 0xFF) * 0.114f);
            }
            boolean polar = Math.abs(record.targetPitchDegrees) >= 70;
            return new CalibrationFrame(
                    record,
                    width,
                    height,
                    grayscale,
                    normalizeDegrees(record.targetYawDegrees),
                    record.targetPitchDegrees,
                    polar);
        }

        int grayAt(int x, int y) {
            return grayscale[clamp(y, 0, height - 1) * width + clamp(x, 0, width - 1)];
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
