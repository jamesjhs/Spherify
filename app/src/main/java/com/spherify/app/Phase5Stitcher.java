/*
 * Phase5Stitcher.java
 *
 * Educational overview:
 * Phase5Stitcher is the first experimental stitching pipeline. It does not yet
 * perform feature matching or drift correction; instead it uses the orientation
 * metadata captured in Phase 4 as an initial pose estimate and projects each
 * draft frame onto a 2:1 equirectangular canvas. This gives the app a real
 * locally-generated master file for Phase 5 iteration.
 */
package com.spherify.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class Phase5Stitcher {
    private static final int OUTPUT_WIDTH = 4096;
    private static final int OUTPUT_HEIGHT = OUTPUT_WIDTH / 2;
    private static final float DEFAULT_HORIZONTAL_FOV_DEGREES = 68f;
    private static final float MIN_VERTICAL_FOV_DEGREES = 42f;
    private static final float MAX_VERTICAL_FOV_DEGREES = 82f;
    private static final int COVERAGE_COLUMNS = 48;
    private static final int COVERAGE_ROWS = 24;

    private Phase5Stitcher() {
    }

    static Result stitch(List<DraftFrameRecord> records, File outputFile) throws IOException {
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

        Bitmap output = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(0xFF101820);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
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
            RectF target = targetRectFor(record, frame);
            markCoverage(coverage, target);
            drawWrapped(canvas, frame, target, paint);
            frame.recycle();
            rendered++;
        }

        if (rendered == 0) {
            output.recycle();
            throw new IOException("could not decode any draft frames");
        }

        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            if (!output.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new IOException("could not write stitched master");
            }
        } finally {
            output.recycle();
        }

        int coveragePercent = Math.round(100f * coveredCells(coverage) / (COVERAGE_COLUMNS * COVERAGE_ROWS));
        writeMetadata(outputFile, usable.get(0).sessionId, rendered, coveragePercent, missingExposure);
        return new Result(rendered, coveragePercent, missingExposure, warnings(rendered, coveragePercent, missingExposure));
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

    private static RectF targetRectFor(DraftFrameRecord record, Bitmap frame) {
        float pitch = Math.abs(record.targetPitchDegrees) >= 70
                ? clamp(record.targetPitchDegrees, -89f, 89f)
                : clamp(record.pitchDegrees, -89f, 89f);
        float frameAspect = Math.max(0.1f, frame.getHeight() / (float) Math.max(1, frame.getWidth()));
        float horizontalFov = DEFAULT_HORIZONTAL_FOV_DEGREES;
        float verticalFov = clamp(horizontalFov * frameAspect, MIN_VERTICAL_FOV_DEGREES, MAX_VERTICAL_FOV_DEGREES);
        if (Math.abs(pitch) >= 70f) {
            float centerY = (90f - pitch) / 180f * OUTPUT_HEIGHT;
            float height = verticalFov / 180f * OUTPUT_HEIGHT;
            return new RectF(0f, centerY - height / 2f, OUTPUT_WIDTH, centerY + height / 2f);
        }
        float yaw = normalizeDegrees(record.headingDegrees);
        float centerX = yaw / 360f * OUTPUT_WIDTH;
        float centerY = (90f - pitch) / 180f * OUTPUT_HEIGHT;
        float width = horizontalFov / 360f * OUTPUT_WIDTH;
        float height = verticalFov / 180f * OUTPUT_HEIGHT;
        return new RectF(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f);
    }

    private static void drawWrapped(Canvas canvas, Bitmap frame, RectF target, Paint paint) {
        Rect source = new Rect(0, 0, frame.getWidth(), frame.getHeight());
        drawOne(canvas, frame, source, target, paint);
        if (target.left < 0f) {
            RectF shifted = new RectF(target);
            shifted.offset(OUTPUT_WIDTH, 0f);
            drawOne(canvas, frame, source, shifted, paint);
        }
        if (target.right > OUTPUT_WIDTH) {
            RectF shifted = new RectF(target);
            shifted.offset(-OUTPUT_WIDTH, 0f);
            drawOne(canvas, frame, source, shifted, paint);
        }
    }

    private static void drawOne(Canvas canvas, Bitmap frame, Rect source, RectF target, Paint paint) {
        float roll = 0f;
        canvas.save();
        canvas.rotate(roll, target.centerX(), target.centerY());
        canvas.drawBitmap(frame, source, target, paint);
        canvas.restore();
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
            int missingExposure) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, String.format(
                    Locale.US,
                    "Spherify Phase 5 draft stitch; session=%s; frames=%d; estimatedCoverage=%d%%; missingExposure=%d; projection=equirectangular",
                    sessionId,
                    renderedFrames,
                    coveragePercent,
                    missingExposure));
            exif.setAttribute(ExifInterface.TAG_MAKE, "Spherify");
            exif.setAttribute(ExifInterface.TAG_MODEL, "Phase 5 draft stitcher");
            exif.saveAttributes();
        } catch (IOException ignored) {
            // The master is still usable if optional metadata writing fails.
        }
    }

    private static List<String> warnings(int renderedFrames, int coveragePercent, int missingExposure) {
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
        warnings.add("Feature matching and drift correction are not active yet");
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
