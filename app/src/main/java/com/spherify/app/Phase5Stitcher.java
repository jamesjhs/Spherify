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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

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
            throw new IOException("Spherify 0.5.3 requires a validated guided capture graph");
        }
        ArrayList<DraftFrameRecord> records = acceptedDraftRecordsFromGraphSession(graphSession);
        records.sort(Comparator.comparingLong(record -> record.createdAt));
        if (records.size() < 30) {
            throw new IOException("OpenCV sphere solve needs at least 30 accepted guided frames; found " + records.size());
        }
        report(progress, "input", true, "Loaded " + records.size() + " accepted graph frames");
        if (!NativeOpenCvStitcher.isAvailable()) {
            report(progress, "opencv", false, "Native OpenCV stitch/detail backend is not configured");
            throw new IOException("Spherify 0.5.3 has removed the old custom stitch renderer. Configure a full native OpenCV Android SDK with stitching/detail support in local.properties before production master export can run.");
        }

        ArrayList<CaptureFrameRecord> acceptedFrames = acceptedFrameRecords(graphSession);
        acceptedFrames.sort(Comparator.comparingLong(frame -> frame.rawFacts.timestampMillis));
        File parentDir = outputFile.getParentFile() == null ? new File(".") : outputFile.getParentFile();
        File workDir = new File(parentDir, "opencv-calibrated-" + graphSession.id);
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("could not create calibrated OpenCV work directory");
        }
        String[] paths = new String[acceptedFrames.size()];
        int calibratedFrames = 0;
        for (int i = 0; i < acceptedFrames.size(); i++) {
            CaptureFrameRecord frame = acceptedFrames.get(i);
            File calibrated = OpenCvCalibrationPreprocessor.calibratedWorkingImage(
                    new File(frame.rawFacts.filePath),
                    frame.rawFacts.exposure,
                    frame.rawFacts.intrinsics,
                    new File(workDir, String.format(Locale.US, "frame-%03d.png", i)));
            paths[i] = calibrated.getAbsolutePath();
            if (!calibrated.equals(new File(frame.rawFacts.filePath))) {
                calibratedFrames++;
            }
        }
        report(progress, "calibration", true, "Prepared OpenCV inputs; manual undistortion applied to " + calibratedFrames + " frames");
        int[] matchingMask = matchingMaskFor(graphSession, acceptedFrames);
        CameraPriorSet cameraPriors = cameraPriorsFor(acceptedFrames);
        if (!cameraPriors.complete()) {
            throw new IOException("all accepted frames need ARCore pose and camera intrinsics for sensor-initialized OpenCV bundle adjustment");
        }
        int missingExposure = 0;
        for (int i = 0; i < records.size(); i++) {
            DraftFrameRecord record = records.get(i);
            if (!record.exposureAvailable) {
                missingExposure++;
            }
        }
        if (missingExposure > 0) {
            throw new IOException("all accepted frames need real Camera2 exposure metadata; missing " + missingExposure);
        }

        report(progress, "opencv", false, "Running native OpenCV Stitcher pipeline");
        int status = NativeOpenCvStitcher.stitchPanorama(
                paths,
                matchingMask,
                cameraPriors.intrinsics,
                cameraPriors.rotations,
                cameraPriors.available,
                outputFile.getAbsolutePath());
        if (status != NativeOpenCvStitcher.STATUS_OK) {
            throw new IOException("native OpenCV stitch failed with status " + status + ": " + statusLabel(status));
        }
        report(progress, "opencv", true, "Native OpenCV Stitcher pipeline wrote candidate panorama");

        ExportCheck beforeXmp = validateGooglePhotoSphereCandidate(outputFile, records, false);
        if (!beforeXmp.mapReadyWithoutXmp()) {
            throw new IOException(beforeXmp.summary);
        }
        writeExifDiagnostics(outputFile, graphSession.id, records.size(), beforeXmp);
        PhotoSphereXmp.write(outputFile, records, beforeXmp.width, beforeXmp.height);
        ExportCheck afterXmp = validateGooglePhotoSphereCandidate(outputFile, records, true);
        if (!afterXmp.mapReady) {
            throw new IOException(afterXmp.summary);
        }
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

    private static ArrayList<CaptureFrameRecord> acceptedFrameRecords(CaptureSessionRecord session) {
        ArrayList<CaptureFrameRecord> records = new ArrayList<>();
        for (CaptureFrameRecord frame : session.frames) {
            if (frame.role == CaptureFrameRole.ACCEPTED && new File(frame.rawFacts.filePath).exists()) {
                records.add(frame);
            }
        }
        return records;
    }

    private static int[] matchingMaskFor(CaptureSessionRecord session, ArrayList<CaptureFrameRecord> frames) {
        int count = frames.size();
        int[] mask = new int[count * count];
        HashMap<String, Integer> indexById = new HashMap<>();
        for (int i = 0; i < count; i++) {
            indexById.put(frames.get(i).id, i);
            mask[i * count + i] = 1;
        }
        for (int left = 0; left < count; left++) {
            for (int right = left + 1; right < count; right++) {
                CaptureFrameRecord a = frames.get(left);
                CaptureFrameRecord b = frames.get(right);
                float yawDelta = Math.abs(signedHeadingDelta(a.rawFacts.targetYawDegrees, b.rawFacts.targetYawDegrees));
                int pitchDelta = Math.abs(a.rawFacts.targetPitchDegrees - b.rawFacts.targetPitchDegrees);
                if (yawDelta <= 70f && pitchDelta <= 48) {
                    mask[left * count + right] = 1;
                    mask[right * count + left] = 1;
                }
            }
        }
        for (CaptureGraphEdgeRecord edge : session.graphEdges) {
            Integer from = indexById.get(edge.fromFrameId);
            Integer to = indexById.get(edge.toFrameId);
            if (from != null && to != null) {
                mask[from * count + to] = 1;
                mask[to * count + from] = 1;
            }
        }
        return mask;
    }

    private static float signedHeadingDelta(float target, float current) {
        float delta = (target - current + 540f) % 360f - 180f;
        return delta < -180f ? delta + 360f : delta;
    }

    private static CameraPriorSet cameraPriorsFor(ArrayList<CaptureFrameRecord> frames) {
        int count = frames.size();
        double[] intrinsics = new double[count * 4];
        double[] rotations = new double[count * 9];
        int[] available = new int[count];
        double[][] anchor = null;
        for (int i = 0; i < count; i++) {
            CaptureFrameRecord frame = frames.get(i);
            double fx = firstPositive(
                    frame.rawFacts.exposure.optDouble("imageFocalLengthXPixels", 0.0),
                    frame.rawFacts.intrinsics.optDouble("focalLengthXPixels", 0.0));
            double fy = firstPositive(
                    frame.rawFacts.exposure.optDouble("imageFocalLengthYPixels", 0.0),
                    frame.rawFacts.intrinsics.optDouble("focalLengthYPixels", 0.0));
            double cx = firstPositive(
                    frame.rawFacts.exposure.optDouble("imagePrincipalPointXPixels", 0.0),
                    frame.rawFacts.intrinsics.optDouble("principalPointXPixels", 0.0));
            double cy = firstPositive(
                    frame.rawFacts.exposure.optDouble("imagePrincipalPointYPixels", 0.0),
                    frame.rawFacts.intrinsics.optDouble("principalPointYPixels", 0.0));
            double[][] cameraToWorld = rotationFromExposure(frame);
            if (fx <= 0.0 || fy <= 0.0 || cx <= 0.0 || cy <= 0.0 || cameraToWorld == null) {
                available[i] = 0;
                continue;
            }
            if (anchor == null) {
                anchor = cameraToWorld;
            }
            double[][] relative = multiply(transpose(anchor), cameraToWorld);
            int intrinsicOffset = i * 4;
            intrinsics[intrinsicOffset] = (fx + fy) * 0.5;
            intrinsics[intrinsicOffset + 1] = fy / fx;
            intrinsics[intrinsicOffset + 2] = cx;
            intrinsics[intrinsicOffset + 3] = cy;
            int rotationOffset = i * 9;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    rotations[rotationOffset + row * 3 + col] = relative[row][col];
                }
            }
            available[i] = 1;
        }
        return new CameraPriorSet(intrinsics, rotations, available);
    }

    private static double[][] rotationFromExposure(CaptureFrameRecord frame) {
        if (frame == null || !frame.rawFacts.capturedPoseAvailable || frame.rawFacts.exposure == null) {
            return null;
        }
        double qx = frame.rawFacts.exposure.optDouble("arCorePoseQx", Double.NaN);
        double qy = frame.rawFacts.exposure.optDouble("arCorePoseQy", Double.NaN);
        double qz = frame.rawFacts.exposure.optDouble("arCorePoseQz", Double.NaN);
        double qw = frame.rawFacts.exposure.optDouble("arCorePoseQw", Double.NaN);
        if (Double.isNaN(qx) || Double.isNaN(qy) || Double.isNaN(qz) || Double.isNaN(qw)) {
            return null;
        }
        return quaternionToRotation(qx, qy, qz, qw);
    }

    private static double[][] quaternionToRotation(double qx, double qy, double qz, double qw) {
        double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (norm <= 0.0) {
            return null;
        }
        qx /= norm;
        qy /= norm;
        qz /= norm;
        qw /= norm;
        double xx = qx * qx;
        double yy = qy * qy;
        double zz = qz * qz;
        double xy = qx * qy;
        double xz = qx * qz;
        double yz = qy * qz;
        double wx = qw * qx;
        double wy = qw * qy;
        double wz = qw * qz;
        return new double[][]{
                {1.0 - 2.0 * (yy + zz), 2.0 * (xy - wz), 2.0 * (xz + wy)},
                {2.0 * (xy + wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz - wx)},
                {2.0 * (xz - wy), 2.0 * (yz + wx), 1.0 - 2.0 * (xx + yy)}
        };
    }

    private static double[][] multiply(double[][] left, double[][] right) {
        double[][] result = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                result[row][col] = left[row][0] * right[0][col]
                        + left[row][1] * right[1][col]
                        + left[row][2] * right[2][col];
            }
        }
        return result;
    }

    private static double[][] transpose(double[][] matrix) {
        return new double[][]{
                {matrix[0][0], matrix[1][0], matrix[2][0]},
                {matrix[0][1], matrix[1][1], matrix[2][1]},
                {matrix[0][2], matrix[1][2], matrix[2][2]}
        };
    }

    private static double firstPositive(double preferred, double fallback) {
        return preferred > 0.0 ? preferred : fallback;
    }

    private static final class CameraPriorSet {
        final double[] intrinsics;
        final double[] rotations;
        final int[] available;

        CameraPriorSet(double[] intrinsics, double[] rotations, int[] available) {
            this.intrinsics = intrinsics;
            this.rotations = rotations;
            this.available = available;
        }

        boolean complete() {
            for (int value : available) {
                if (value == 0) {
                    return false;
                }
            }
            return available.length > 0;
        }
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
        boolean xmp = requireXmp && hasGpanoXmp(outputFile);
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
        return CaptureTargetPlanner.coverageForDraftRecords(records).complete();
    }

    private static int coveragePercent(List<DraftFrameRecord> records) {
        return CaptureTargetPlanner.coverageForDraftRecords(records).percent();
    }

    private static boolean hasGpanoXmp(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] header = new byte[256 * 1024];
            int read = input.read(header);
            if (read <= 0) {
                return false;
            }
            String text = new String(header, 0, read, StandardCharsets.ISO_8859_1);
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
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Spherify 0.5.3 native OpenCV stitcher");
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
            byte[] identifier = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.UTF_8);
            byte[] payload = xmpPacket(records, width, height).getBytes(StandardCharsets.UTF_8);
            int length = identifier.length + payload.length + 2;
            if (length > 0xFFFF) {
                throw new IOException("GPano XMP packet is too large for one JPEG APP1 segment");
            }
            File temp = File.createTempFile("gpano-", ".jpg", file.getParentFile());
            try (FileInputStream input = new FileInputStream(file);
                 FileOutputStream output = new FileOutputStream(temp)) {
                int soi0 = input.read();
                int soi1 = input.read();
                if (soi0 != 0xFF || soi1 != 0xD8) {
                    throw new IOException("cannot write GPano XMP to a non-JPEG file");
                }
                output.write(soi0);
                output.write(soi1);
                output.write(0xFF);
                output.write(0xE1);
                output.write((length >> 8) & 0xFF);
                output.write(length & 0xFF);
                output.write(identifier);
                output.write(payload);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            } catch (IOException e) {
                temp.delete();
                throw e;
            }
            if (!temp.renameTo(file)) {
                try (FileInputStream input = new FileInputStream(temp);
                     FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                } finally {
                    temp.delete();
                }
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
                    + "<xmp:CreatorTool>Spherify 0.5.3 native OpenCV stitcher</xmp:CreatorTool>"
                    + "<xmp:CreateDate>" + lastDate + "</xmp:CreateDate>"
                    + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        }

        private static String xmpDate(DraftFrameRecord record) {
            long when = record == null ? System.currentTimeMillis() : record.createdAt;
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new Date(when));
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
