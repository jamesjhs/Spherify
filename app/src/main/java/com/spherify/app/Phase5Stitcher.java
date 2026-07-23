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

import android.graphics.BitmapFactory;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class Phase5Stitcher {
    private static final int MIN_GPANO_WIDTH = 3840;
    private static final int MAX_GOOGLE_FILE_BYTES = 75 * 1024 * 1024;

    private Phase5Stitcher() {
    }

    static Result stitch(
            CaptureSessionRecord graphSession,
            File outputFile,
            String movementSensitivityMode,
            String renderModeName,
            SpherifyLibrary.ProgressReporter progress) throws IOException {
        if (graphSession == null) {
            throw new IOException("Spherify 0.4.2 requires a validated guided capture graph");
        }
        ArrayList<DraftFrameRecord> records = acceptedDraftRecordsFromGraphSession(graphSession);
        records.sort(Comparator.comparingLong(record -> record.createdAt));
        if (records.size() < 30) {
            throw new IOException("OpenCV sphere solve needs at least 30 accepted guided frames; found " + records.size());
        }
        report(progress, "input", true, "Loaded " + records.size() + " accepted graph frames");
        if (!NativeOpenCvStitcher.isAvailable()) {
            report(progress, "opencv", false, "Native OpenCV stitch/detail backend is not configured");
            throw new IOException("Spherify 0.4.2 has removed the old custom stitch renderer. Configure a full native OpenCV Android SDK with stitching/detail support in local.properties before production master export can run.");
        }

        String[] paths = new String[records.size()];
        int missingExposure = 0;
        for (int i = 0; i < records.size(); i++) {
            DraftFrameRecord record = records.get(i);
            paths[i] = record.imageFile.getAbsolutePath();
            if (!record.exposureAvailable) {
                missingExposure++;
            }
        }
        if (missingExposure > 0) {
            throw new IOException("all accepted frames need real Camera2 exposure metadata; missing " + missingExposure);
        }

        report(progress, "opencv", false, "Running native OpenCV detail pipeline");
        int status = NativeOpenCvStitcher.stitchPanorama(paths, outputFile.getAbsolutePath());
        if (status != NativeOpenCvStitcher.STATUS_OK) {
            throw new IOException("native OpenCV stitch failed with status " + status + ": " + statusLabel(status));
        }
        report(progress, "opencv", true, "Native OpenCV detail pipeline wrote candidate panorama");

        ExportCheck beforeXmp = validateGooglePhotoSphereCandidate(outputFile, records, false);
        if (!beforeXmp.mapReadyWithoutXmp()) {
            throw new IOException(beforeXmp.summary);
        }
        PhotoSphereXmp.write(outputFile, records, beforeXmp.width, beforeXmp.height);
        ExportCheck afterXmp = validateGooglePhotoSphereCandidate(outputFile, records, true);
        if (!afterXmp.mapReady) {
            throw new IOException(afterXmp.summary);
        }
        writeExifDiagnostics(outputFile, graphSession.id, records.size(), afterXmp);
        report(progress, "metadata", true, "GPano XMP readback passed");
        return new Result(
                records.size(),
                afterXmp.coveragePercent,
                0,
                "Map-ready",
                afterXmp.summary,
                afterXmp.warnings);
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
            records.add(new DraftFrameRecord(
                    file,
                    frame.sessionId,
                    frame.rawFacts.timestampMillis,
                    frame.rawFacts.locationSummary,
                    frame.rawFacts.capturedYawDegrees,
                    frame.rawFacts.capturedPitchDegrees,
                    frame.rawFacts.capturedRollDegrees,
                    frame.rawFacts.capturedPoseAvailable,
                    frame.rawFacts.captureProfile,
                    frame.rawFacts.targetYawDegrees,
                    frame.rawFacts.targetPitchDegrees,
                    "dot-accepted",
                    frame.rawFacts.exposure.optBoolean("available", false),
                    (float) frame.rawFacts.exposure.optDouble("lensFocalLengthMm", 0.0),
                    (float) frame.rawFacts.exposure.optDouble("sensorPhysicalWidthMm", 0.0),
                    (float) frame.rawFacts.exposure.optDouble("sensorPhysicalHeightMm", 0.0),
                    (float) frame.rawFacts.exposure.optDouble("imageFocalLengthXPixels", 0.0),
                    (float) frame.rawFacts.exposure.optDouble("imageFocalLengthYPixels", 0.0),
                    (float) frame.rawFacts.exposure.optDouble("imagePrincipalPointXPixels", 0.0),
                    (float) frame.rawFacts.exposure.optDouble("imagePrincipalPointYPixels", 0.0),
                    frame.rawFacts.exposure.optInt("imageIntrinsicsWidth", 0),
                    frame.rawFacts.exposure.optInt("imageIntrinsicsHeight", 0)));
        }
        return records;
    }

    private static ExportCheck validateGooglePhotoSphereCandidate(File outputFile, List<DraftFrameRecord> records, boolean requireXmp) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(outputFile.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("native output is not a readable JPEG");
        }
        ArrayList<String> warnings = new ArrayList<>();
        boolean aspect = bounds.outWidth == bounds.outHeight * 2;
        boolean resolution = bounds.outWidth >= MIN_GPANO_WIDTH && bounds.outHeight >= MIN_GPANO_WIDTH / 2;
        boolean size = outputFile.length() <= MAX_GOOGLE_FILE_BYTES;
        boolean coverage = hasFullGuidedCoverage(records);
        boolean xmp = hasGpanoXmp(outputFile);
        if (!aspect) {
            warnings.add("output is not exact 2:1 equirectangular (" + bounds.outWidth + "x" + bounds.outHeight + ")");
        }
        if (!resolution) {
            warnings.add("output is below 3840 x 1920");
        }
        if (!size) {
            warnings.add("output exceeds 75 MB");
        }
        if (!coverage) {
            warnings.add("guided capture coverage is incomplete");
        }
        if (requireXmp && !xmp) {
            warnings.add("GPano XMP readback failed");
        }
        boolean mapReady = aspect && resolution && size && coverage && (!requireXmp || xmp);
        String summary = String.format(
                Locale.US,
                "PhotoSphere validation: dimensions=%dx%d; guidedCoverage=%d%%; gpanoXmp=%s%s",
                bounds.outWidth,
                bounds.outHeight,
                coveragePercent(records),
                xmp ? "present" : "absent",
                warnings.isEmpty() ? "" : "; " + String.join("; ", warnings));
        return new ExportCheck(mapReady, aspect && resolution && size && coverage, bounds.outWidth, bounds.outHeight, coveragePercent(records), summary, warnings);
    }

    private static boolean hasFullGuidedCoverage(List<DraftFrameRecord> records) {
        int anchorPitch = records.isEmpty() ? 0 : records.get(0).targetPitchDegrees;
        return countRelativePitch(records, anchorPitch, -8, 8, false) >= 8
                && countRelativePitch(records, anchorPitch, 27, 43, false) >= 8
                && countRelativePitch(records, anchorPitch, -43, -27, false) >= 8
                && countRelativePitch(records, anchorPitch, 58, 90, true) >= 5
                && countRelativePitch(records, anchorPitch, -90, -58, true) >= 5;
    }

    private static int coveragePercent(List<DraftFrameRecord> records) {
        int anchorPitch = records.isEmpty() ? 0 : records.get(0).targetPitchDegrees;
        int score = 0;
        score += Math.min(8, countRelativePitch(records, anchorPitch, -8, 8, false));
        score += Math.min(8, countRelativePitch(records, anchorPitch, 27, 43, false));
        score += Math.min(8, countRelativePitch(records, anchorPitch, -43, -27, false));
        score += Math.min(5, countRelativePitch(records, anchorPitch, 58, 90, true));
        score += Math.min(5, countRelativePitch(records, anchorPitch, -90, -58, true));
        return Math.round(score * 100f / 34f);
    }

    private static int countRelativePitch(List<DraftFrameRecord> records, int anchorPitch, int minPitch, int maxPitch, boolean includePoles) {
        int count = 0;
        for (DraftFrameRecord record : records) {
            int relativePitch = record.targetPitchDegrees - anchorPitch;
            boolean pole = includePoles
                    && ((maxPitch > 0 && record.targetPitchDegrees >= 80)
                    || (minPitch < 0 && record.targetPitchDegrees <= -80));
            if ((relativePitch >= minPitch && relativePitch <= maxPitch) || pole) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasGpanoXmp(File file) {
        try {
            String text = new String(PhotoSphereXmp.readAll(file), StandardCharsets.ISO_8859_1);
            return text.contains("GPano:ProjectionType")
                    && text.contains("GPano:FullPanoWidthPixels")
                    && text.contains("GPano:CroppedAreaImageWidthPixels")
                    && text.contains("GPano:SourcePhotosCount");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void writeExifDiagnostics(File outputFile, String sessionId, int frames, ExportCheck check) {
        try {
            ExifInterface exif = new ExifInterface(outputFile.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Spherify 0.4.2 native OpenCV stitcher");
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,
                    "session=" + sessionId + "; frames=" + frames + "; review=" + (check.mapReady ? "Map-ready" : "Needs review"));
            exif.saveAttributes();
        } catch (IOException ignored) {
            // GPano XMP is the authoritative export certification record.
        }
    }

    private static String statusLabel(int status) {
        switch (status) {
            case -1000:
                return "native backend unavailable";
            case -10:
                return "image read/write failed";
            case -11:
                return "feature extraction failed";
            case -12:
                return "spherical warping failed";
            case -13:
                return "multiband blending failed";
            case 1:
                return "need more images";
            case 2:
                return "homography estimation failed";
            case 3:
                return "camera parameter adjustment failed";
            default:
                return "unknown";
        }
    }

    private static final class ExportCheck {
        final boolean mapReady;
        final boolean geometryReady;
        final int width;
        final int height;
        final int coveragePercent;
        final String summary;
        final List<String> warnings;

        ExportCheck(boolean mapReady, boolean geometryReady, int width, int height, int coveragePercent, String summary, List<String> warnings) {
            this.mapReady = mapReady;
            this.geometryReady = geometryReady;
            this.width = width;
            this.height = height;
            this.coveragePercent = coveragePercent;
            this.summary = summary;
            this.warnings = warnings;
        }

        boolean mapReadyWithoutXmp() {
            return geometryReady;
        }
    }

    private static final class PhotoSphereXmp {
        static void write(File file, List<DraftFrameRecord> records, int width, int height) throws IOException {
            byte[] original = readAll(file);
            if (original.length < 2 || (original[0] & 0xFF) != 0xFF || (original[1] & 0xFF) != 0xD8) {
                throw new IOException("cannot write GPano XMP to a non-JPEG file");
            }
            byte[] identifier = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.UTF_8);
            byte[] payload = xmpPacket(records, width, height).getBytes(StandardCharsets.UTF_8);
            int length = identifier.length + payload.length + 2;
            if (length > 0xFFFF) {
                throw new IOException("GPano XMP packet is too large for one JPEG APP1 segment");
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
        }

        static byte[] readAll(File file) throws IOException {
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

        private static String xmpPacket(List<DraftFrameRecord> records, int width, int height) {
            DraftFrameRecord first = records.isEmpty() ? null : records.get(0);
            DraftFrameRecord last = records.isEmpty() ? null : records.get(records.size() - 1);
            String firstDate = xmpDate(first);
            String lastDate = xmpDate(last);
            String heading = first != null && first.capturedPoseAvailable
                    ? String.format(Locale.US, "<GPano:PoseHeadingDegrees>%.1f</GPano:PoseHeadingDegrees>", first.headingDegrees)
                    : "";
            return "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                    + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                    + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                    + "<rdf:Description xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\" "
                    + "xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">"
                    + "<GPano:ProjectionType>equirectangular</GPano:ProjectionType>"
                    + "<GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>"
                    + "<GPano:FullPanoWidthPixels>" + width + "</GPano:FullPanoWidthPixels>"
                    + "<GPano:FullPanoHeightPixels>" + height + "</GPano:FullPanoHeightPixels>"
                    + "<GPano:CroppedAreaImageWidthPixels>" + width + "</GPano:CroppedAreaImageWidthPixels>"
                    + "<GPano:CroppedAreaImageHeightPixels>" + height + "</GPano:CroppedAreaImageHeightPixels>"
                    + "<GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>"
                    + "<GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>"
                    + "<GPano:FirstPhotoDate>" + firstDate + "</GPano:FirstPhotoDate>"
                    + "<GPano:LastPhotoDate>" + lastDate + "</GPano:LastPhotoDate>"
                    + "<GPano:SourcePhotosCount>" + records.size() + "</GPano:SourcePhotosCount>"
                    + heading
                    + "<xmp:CreatorTool>Spherify 0.4.2 native OpenCV stitcher</xmp:CreatorTool>"
                    + "<xmp:CreateDate>" + lastDate + "</xmp:CreateDate>"
                    + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        }

        private static String xmpDate(DraftFrameRecord record) {
            long when = record == null ? System.currentTimeMillis() : record.createdAt;
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date(when));
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
