package com.spherify.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features.BFMatcher;
import org.opencv.features.ORB;
import org.opencv.geometry.Geometry;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class OpenCvOverlapValidator {
    private static final int MAX_SAMPLE_SIZE = 720;
    private static final int ORB_FEATURES = 650;
    private static final int MIN_MATCHES = 16;
    private static final int MIN_INLIERS = 12;
    private static final double RATIO_TEST = 0.78;
    private static final double RANSAC_REPROJECTION_THRESHOLD = 8.0;
    private static final double ADJACENT_ROW_MIN_PITCH_DELTA = 16.0;
    private static final double ADJACENT_ROW_MAX_PITCH_DELTA = 48.0;
    private static final double VERTICAL_OVERLAP_BAND_FRACTION = 0.62;

    CandidateAnalysisResult analyze(
            File candidateFile,
            CandidateQualityReport quality,
            List<CaptureFrameRecord> predictedNeighbors,
            JSONObject candidateExposure,
            boolean candidatePoseAvailable,
            int candidateTargetPitchDegrees,
            boolean allowNoOverlapAnchorFrame,
            boolean poseMetadataAvailable) {
        JSONArray predicted = new JSONArray();
        for (CaptureFrameRecord neighbor : predictedNeighbors) {
            predicted.put(neighbor.id);
        }
        if (!quality.pass) {
            if (isLowTextureOnlyFailure(quality)
                    && poseMetadataAvailable
                    && (allowNoOverlapAnchorFrame || !predictedNeighbors.isEmpty())) {
                return acceptedLowTexturePoseOnly(quality, predicted, allowNoOverlapAnchorFrame);
            }
            return rejected(quality, predicted, "not_run", 0, -1.0, "quality gate", quality.rejectionReason);
        }
        if (predictedNeighbors.isEmpty() && allowNoOverlapAnchorFrame) {
            return new CandidateAnalysisResult(
                    true,
                    quality,
                    predicted,
                    "not_required_first_frame",
                    0,
                    0.0,
                    1.0,
                    "",
                    "",
                    "",
                    new JSONArray(),
                    "pose_anchor");
        }
        if (predictedNeighbors.isEmpty()) {
            return rejected(quality, predicted, "not_run", 0, -1.0, "no predicted overlap", "Weak overlap");
        }
        if (!initOpenCv()) {
            return rejected(quality, predicted, "opencv_unavailable", 0, -1.0, "OpenCV unavailable", "Could not align");
        }

        MatchResult best = null;
        for (CaptureFrameRecord neighbor : predictedNeighbors) {
            MatchResult result = match(
                    candidateFile,
                    new File(neighbor.rawFacts.filePath),
                    neighbor,
                    candidateExposure,
                    candidatePoseAvailable,
                    candidateTargetPitchDegrees);
            if (result != null && (best == null || result.confidence > best.confidence)) {
                best = result;
            }
        }
        if (best == null) {
            if (isLowTextureScene(quality) && poseMetadataAvailable) {
                return acceptedLowTexturePoseOnly(quality, predicted, false);
            }
            return rejected(quality, predicted, "ransac_failed", 0, -1.0, "no valid overlap", "Weak overlap");
        }
        if (best.inlierCount < MIN_INLIERS || best.confidence < 0.25) {
            if (isLowTextureScene(quality) && poseMetadataAvailable && best.residualScore <= 55.0) {
                return acceptedLowTexturePoseOnly(quality, predicted, false);
            }
            return rejected(
                    quality,
                    predicted,
                    "ransac_weak",
                    best.inlierCount,
                    best.residualScore,
                    "weak inlier support",
                    "Weak overlap");
        }
        return new CandidateAnalysisResult(
                true,
                quality,
                predicted,
                "ransac_valid",
                best.inlierCount,
                best.residualScore,
                best.confidence,
                best.residualScore > 6.0 ? "possible parallax or rolling shutter" : "",
                "",
                best.neighborFrameId,
                best.controlPoints,
                "visual_overlap");
    }

    private CandidateAnalysisResult acceptedLowTexturePoseOnly(
            CandidateQualityReport quality,
            JSONArray predicted,
            boolean anchorFrame) {
        return new CandidateAnalysisResult(
                true,
                quality,
                predicted,
                anchorFrame ? "low_texture_pose_anchor" : "low_texture_pose_only",
                0,
                -1.0,
                0.0,
                "insufficient visual features",
                "",
                "",
                new JSONArray(),
                "low_texture_pose_only");
    }

    private CandidateAnalysisResult rejected(
            CandidateQualityReport quality,
            JSONArray predicted,
            String ransacResult,
            int inliers,
            double residual,
            String parallaxHint,
            String reason) {
        return new CandidateAnalysisResult(
                false,
                quality,
                predicted,
                ransacResult,
                inliers,
                residual,
                0.0,
                parallaxHint,
                reason,
                "",
                new JSONArray(),
                "rejected");
    }

    private static boolean isLowTextureOnlyFailure(CandidateQualityReport quality) {
        return quality != null && "Need more visual detail".equals(quality.rejectionReason);
    }

    private static boolean isLowTextureScene(CandidateQualityReport quality) {
        return quality != null && quality.textureScore >= 0.0 && quality.textureScore < 7.0;
    }

    private static boolean initOpenCv() {
        try {
            return OpenCVLoader.initLocal();
        } catch (Throwable ignored) {
            try {
                return OpenCVLoader.initDebug();
            } catch (Throwable ignoredAgain) {
                return false;
            }
        }
    }

    private MatchResult match(
            File candidateFile,
            File neighborFile,
            CaptureFrameRecord neighbor,
            JSONObject candidateExposure,
            boolean candidatePoseAvailable,
            int candidateTargetPitchDegrees) {
        Bitmap candidateBitmap = decodeSample(candidateFile);
        Bitmap neighborBitmap = decodeSample(neighborFile);
        if (candidateBitmap == null || neighborBitmap == null) {
            recycle(candidateBitmap);
            recycle(neighborBitmap);
            return null;
        }
        Mat candidateRgba = new Mat();
        Mat neighborRgba = new Mat();
        Mat candidateGray = new Mat();
        Mat neighborGray = new Mat();
        try {
            Utils.bitmapToMat(candidateBitmap, candidateRgba);
            Utils.bitmapToMat(neighborBitmap, neighborRgba);
            Imgproc.cvtColor(candidateRgba, candidateGray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(neighborRgba, neighborGray, Imgproc.COLOR_RGBA2GRAY);

            MatchResult poseNormalized = poseNormalizedMatch(
                    candidateGray,
                    neighborGray,
                    neighbor,
                    candidateExposure,
                    candidatePoseAvailable);
            if (isAdjacentRowPitch(candidateTargetPitchDegrees, neighbor.rawFacts.targetPitchDegrees)) {
                MatchResult band = adjacentRowBandMatch(
                        candidateGray,
                        neighborGray,
                        neighbor,
                        candidateTargetPitchDegrees);
                return better(poseNormalized, band);
            }
            MatchResult full = matchGray(candidateGray, neighborGray, neighbor.id, 0, 0, 0, 0);
            MatchResult band = adjacentRowBandMatch(
                    candidateGray,
                    neighborGray,
                    neighbor,
                    candidateTargetPitchDegrees);
            if (band != null && (full == null || band.confidence > full.confidence)) {
                full = band;
            }
            return better(poseNormalized, full);
        } catch (JSONException ignored) {
            return null;
        } finally {
            recycle(candidateBitmap);
            recycle(neighborBitmap);
            candidateRgba.release();
            neighborRgba.release();
            candidateGray.release();
            neighborGray.release();
        }
    }

    private MatchResult poseNormalizedMatch(
            Mat candidateGray,
            Mat neighborGray,
            CaptureFrameRecord neighbor,
            JSONObject candidateExposure,
            boolean candidatePoseAvailable) throws JSONException {
        FrameGeometry candidateGeometry = FrameGeometry.from(
                candidateExposure,
                null,
                candidatePoseAvailable,
                candidateGray.width(),
                candidateGray.height());
        FrameGeometry neighborGeometry = FrameGeometry.from(
                neighbor.rawFacts.exposure,
                neighbor.rawFacts.intrinsics,
                neighbor.rawFacts.capturedPoseAvailable,
                neighborGray.width(),
                neighborGray.height());
        if (candidateGeometry == null || neighborGeometry == null) {
            return null;
        }

        Mat candidateToNeighbor = rotationHomography(candidateGeometry, neighborGeometry);
        Mat neighborToCandidate = rotationHomography(neighborGeometry, candidateGeometry);
        Mat warpedCandidate = new Mat();
        Mat warpedNeighbor = new Mat();
        try {
            Imgproc.warpPerspective(
                    candidateGray,
                    warpedCandidate,
                    candidateToNeighbor,
                    new Size(neighborGray.width(), neighborGray.height()),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT,
                    Scalar.all(0));
            Imgproc.warpPerspective(
                    neighborGray,
                    warpedNeighbor,
                    neighborToCandidate,
                    new Size(candidateGray.width(), candidateGray.height()),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT,
                    Scalar.all(0));
            MatchResult candidateInNeighbor = matchGray(warpedCandidate, neighborGray, neighbor.id, 0, 0, 0, 0);
            MatchResult neighborInCandidate = matchGray(candidateGray, warpedNeighbor, neighbor.id, 0, 0, 0, 0);
            return better(candidateInNeighbor, neighborInCandidate);
        } finally {
            candidateToNeighbor.release();
            neighborToCandidate.release();
            warpedCandidate.release();
            warpedNeighbor.release();
        }
    }

    private static MatchResult better(MatchResult left, MatchResult right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.confidence >= right.confidence ? left : right;
    }

    private boolean isAdjacentRowPitch(int candidateTargetPitchDegrees, int neighborTargetPitchDegrees) {
        double absPitchDelta = Math.abs(candidateTargetPitchDegrees - neighborTargetPitchDegrees);
        return absPitchDelta >= ADJACENT_ROW_MIN_PITCH_DELTA && absPitchDelta <= ADJACENT_ROW_MAX_PITCH_DELTA;
    }

    private MatchResult adjacentRowBandMatch(
            Mat candidateGray,
            Mat neighborGray,
            CaptureFrameRecord neighbor,
            int candidateTargetPitchDegrees) throws JSONException {
        double pitchDelta = candidateTargetPitchDegrees - neighbor.rawFacts.targetPitchDegrees;
        double absPitchDelta = Math.abs(pitchDelta);
        if (absPitchDelta < ADJACENT_ROW_MIN_PITCH_DELTA || absPitchDelta > ADJACENT_ROW_MAX_PITCH_DELTA) {
            return null;
        }
        int candidateBandHeight = Math.max(1, (int) Math.round(candidateGray.height() * VERTICAL_OVERLAP_BAND_FRACTION));
        int neighborBandHeight = Math.max(1, (int) Math.round(neighborGray.height() * VERTICAL_OVERLAP_BAND_FRACTION));
        int candidateTop = pitchDelta > 0.0 ? candidateGray.height() - candidateBandHeight : 0;
        int neighborTop = pitchDelta > 0.0 ? 0 : neighborGray.height() - neighborBandHeight;
        Mat candidateBand = candidateGray.submat(new Rect(0, candidateTop, candidateGray.width(), candidateBandHeight));
        Mat neighborBand = neighborGray.submat(new Rect(0, neighborTop, neighborGray.width(), neighborBandHeight));
        try {
            return matchGray(candidateBand, neighborBand, neighbor.id, 0, candidateTop, 0, neighborTop);
        } finally {
            candidateBand.release();
            neighborBand.release();
        }
    }

    private MatchResult matchGray(
            Mat candidateGray,
            Mat neighborGray,
            String neighborFrameId,
            double candidateOffsetX,
            double candidateOffsetY,
            double neighborOffsetX,
            double neighborOffsetY) throws JSONException {
        MatOfKeyPoint candidateKeypoints = new MatOfKeyPoint();
        MatOfKeyPoint neighborKeypoints = new MatOfKeyPoint();
        Mat candidateDescriptors = new Mat();
        Mat neighborDescriptors = new Mat();
        Mat inlierMask = new Mat();
        MatOfPoint2f candidateMat = new MatOfPoint2f();
        MatOfPoint2f neighborMat = new MatOfPoint2f();
        MatOfPoint2f projectedCandidateMat = new MatOfPoint2f();
        try {
            ORB orb = ORB.create(ORB_FEATURES);
            orb.detectAndCompute(candidateGray, new Mat(), candidateKeypoints, candidateDescriptors);
            orb.detectAndCompute(neighborGray, new Mat(), neighborKeypoints, neighborDescriptors);
            if (candidateDescriptors.empty() || neighborDescriptors.empty()) {
                return null;
            }

            List<MatOfDMatch> knn = new ArrayList<>();
            BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);
            matcher.knnMatch(candidateDescriptors, neighborDescriptors, knn, 2);
            KeyPoint[] candidatePoints = candidateKeypoints.toArray();
            KeyPoint[] neighborPoints = neighborKeypoints.toArray();
            ArrayList<Point> candidateControl = new ArrayList<>();
            ArrayList<Point> neighborControl = new ArrayList<>();
            for (MatOfDMatch pair : knn) {
                DMatch[] matches = pair.toArray();
                if (matches.length < 2 || matches[0].distance >= matches[1].distance * RATIO_TEST) {
                    continue;
                }
                DMatch best = matches[0];
                if (best.queryIdx >= 0 && best.queryIdx < candidatePoints.length
                        && best.trainIdx >= 0 && best.trainIdx < neighborPoints.length) {
                    candidateControl.add(candidatePoints[best.queryIdx].pt);
                    neighborControl.add(neighborPoints[best.trainIdx].pt);
                }
            }
            if (candidateControl.size() < MIN_MATCHES) {
                return null;
            }
            candidateMat.fromList(candidateControl);
            neighborMat.fromList(neighborControl);
            Mat homography = Geometry.findHomography(
                    candidateMat,
                    neighborMat,
                    Geometry.RANSAC,
                    RANSAC_REPROJECTION_THRESHOLD,
                    inlierMask);
            if (homography.empty() || inlierMask.empty()) {
                homography.release();
                return null;
            }
            Core.perspectiveTransform(candidateMat, projectedCandidateMat, homography);
            homography.release();
            byte[] mask = new byte[(int) inlierMask.total()];
            inlierMask.get(0, 0, mask);
            Point[] projectedPoints = projectedCandidateMat.toArray();
            int inliers = 0;
            double residualTotal = 0.0;
            JSONArray controlPoints = new JSONArray();
            for (int i = 0; i < mask.length && i < candidateControl.size() && i < projectedPoints.length; i++) {
                if (mask[i] == 0) {
                    continue;
                }
                Point left = candidateControl.get(i);
                Point right = neighborControl.get(i);
                Point projected = projectedPoints[i];
                double dx = right.x - projected.x;
                double dy = right.y - projected.y;
                residualTotal += Math.sqrt(dx * dx + dy * dy);
                inliers++;
                if (controlPoints.length() < 24) {
                    JSONObject point = new JSONObject();
                    point.put("candidateX", left.x + candidateOffsetX);
                    point.put("candidateY", left.y + candidateOffsetY);
                    point.put("neighborX", right.x + neighborOffsetX);
                    point.put("neighborY", right.y + neighborOffsetY);
                    controlPoints.put(point);
                }
            }
            if (inliers <= 0) {
                return null;
            }
            double residual = residualTotal / inliers;
            double confidence = Math.min(1.0, inliers / 48.0) * Math.max(0.1, 1.0 - Math.min(0.8, residual / 80.0));
            return new MatchResult(neighborFrameId, inliers, residual, confidence, controlPoints);
        } finally {
            candidateKeypoints.release();
            neighborKeypoints.release();
            candidateDescriptors.release();
            neighborDescriptors.release();
            inlierMask.release();
            candidateMat.release();
            neighborMat.release();
            projectedCandidateMat.release();
        }
    }

    private static Bitmap decodeSample(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / sample > MAX_SAMPLE_SIZE || bounds.outHeight / sample > MAX_SAMPLE_SIZE) {
            sample *= 2;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static Mat rotationHomography(FrameGeometry source, FrameGeometry destination) {
        double[][] relative = multiply(transpose(destination.rotationCameraToWorld), source.rotationCameraToWorld);
        double[][] homography = multiply(destination.intrinsics, multiply(relative, inverseIntrinsics(source.intrinsics)));
        Mat mat = new Mat(3, 3, org.opencv.core.CvType.CV_64F);
        mat.put(0, 0,
                homography[0][0], homography[0][1], homography[0][2],
                homography[1][0], homography[1][1], homography[1][2],
                homography[2][0], homography[2][1], homography[2][2]);
        return mat;
    }

    private static double[][] inverseIntrinsics(double[][] k) {
        double fx = k[0][0];
        double fy = k[1][1];
        double cx = k[0][2];
        double cy = k[1][2];
        return new double[][]{
                {1.0 / fx, 0.0, -cx / fx},
                {0.0, 1.0 / fy, -cy / fy},
                {0.0, 0.0, 1.0}
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

    private static final class FrameGeometry {
        final double[][] intrinsics;
        final double[][] rotationCameraToWorld;

        private FrameGeometry(double[][] intrinsics, double[][] rotationCameraToWorld) {
            this.intrinsics = intrinsics;
            this.rotationCameraToWorld = rotationCameraToWorld;
        }

        static FrameGeometry from(
                JSONObject exposure,
                JSONObject intrinsicsJson,
                boolean poseAvailable,
                int decodedWidth,
                int decodedHeight) {
            if (!poseAvailable || exposure == null || decodedWidth <= 0 || decodedHeight <= 0) {
                return null;
            }
            double fx = firstPositive(
                    exposure.optDouble("imageFocalLengthXPixels", 0.0),
                    intrinsicsJson == null ? 0.0 : intrinsicsJson.optDouble("focalLengthXPixels", 0.0));
            double fy = firstPositive(
                    exposure.optDouble("imageFocalLengthYPixels", 0.0),
                    intrinsicsJson == null ? 0.0 : intrinsicsJson.optDouble("focalLengthYPixels", 0.0));
            double cx = firstPositive(
                    exposure.optDouble("imagePrincipalPointXPixels", 0.0),
                    intrinsicsJson == null ? 0.0 : intrinsicsJson.optDouble("principalPointXPixels", 0.0));
            double cy = firstPositive(
                    exposure.optDouble("imagePrincipalPointYPixels", 0.0),
                    intrinsicsJson == null ? 0.0 : intrinsicsJson.optDouble("principalPointYPixels", 0.0));
            int sourceWidth = firstPositiveInt(
                    exposure.optInt("imageIntrinsicsWidth", 0),
                    intrinsicsJson == null ? 0 : intrinsicsJson.optInt("width", 0));
            int sourceHeight = firstPositiveInt(
                    exposure.optInt("imageIntrinsicsHeight", 0),
                    intrinsicsJson == null ? 0 : intrinsicsJson.optInt("height", 0));
            if (fx <= 0.0 || fy <= 0.0 || sourceWidth <= 0 || sourceHeight <= 0) {
                return null;
            }
            double scaleX = decodedWidth / (double) sourceWidth;
            double scaleY = decodedHeight / (double) sourceHeight;
            double[][] scaledIntrinsics = new double[][]{
                    {fx * scaleX, 0.0, cx * scaleX},
                    {0.0, fy * scaleY, cy * scaleY},
                    {0.0, 0.0, 1.0}
            };
            double qx = exposure.optDouble("arCorePoseQx", Double.NaN);
            double qy = exposure.optDouble("arCorePoseQy", Double.NaN);
            double qz = exposure.optDouble("arCorePoseQz", Double.NaN);
            double qw = exposure.optDouble("arCorePoseQw", Double.NaN);
            if (Double.isNaN(qx) || Double.isNaN(qy) || Double.isNaN(qz) || Double.isNaN(qw)) {
                return null;
            }
            return new FrameGeometry(scaledIntrinsics, quaternionToRotation(qx, qy, qz, qw));
        }

        private static double firstPositive(double preferred, double fallback) {
            return preferred > 0.0 ? preferred : fallback;
        }

        private static int firstPositiveInt(int preferred, int fallback) {
            return preferred > 0 ? preferred : fallback;
        }

        private static double[][] quaternionToRotation(double qx, double qy, double qz, double qw) {
            double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (norm <= 0.0) {
                return new double[][]{{1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {0.0, 0.0, 1.0}};
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
    }

    private static final class MatchResult {
        final String neighborFrameId;
        final int inlierCount;
        final double residualScore;
        final double confidence;
        final JSONArray controlPoints;

        MatchResult(String neighborFrameId, int inlierCount, double residualScore, double confidence, JSONArray controlPoints) {
            this.neighborFrameId = neighborFrameId;
            this.inlierCount = inlierCount;
            this.residualScore = residualScore;
            this.confidence = confidence;
            this.controlPoints = controlPoints;
        }
    }
}
