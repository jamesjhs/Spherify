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

    CandidateAnalysisResult analyze(
            File candidateFile,
            CandidateQualityReport quality,
            List<CaptureFrameRecord> predictedNeighbors) {
        JSONArray predicted = new JSONArray();
        for (CaptureFrameRecord neighbor : predictedNeighbors) {
            predicted.put(neighbor.id);
        }
        if (!quality.pass) {
            return rejected(quality, predicted, "not_run", 0, -1.0, "quality gate", quality.rejectionReason);
        }
        if (predictedNeighbors.isEmpty()) {
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
                    new JSONArray());
        }
        if (!initOpenCv()) {
            return rejected(quality, predicted, "opencv_unavailable", 0, -1.0, "OpenCV unavailable", "Could not align");
        }

        MatchResult best = null;
        for (CaptureFrameRecord neighbor : predictedNeighbors) {
            MatchResult result = match(candidateFile, new File(neighbor.rawFacts.filePath), neighbor.id);
            if (result != null && (best == null || result.confidence > best.confidence)) {
                best = result;
            }
        }
        if (best == null) {
            return rejected(quality, predicted, "ransac_failed", 0, -1.0, "no valid overlap", "Weak overlap");
        }
        if (best.inlierCount < MIN_INLIERS || best.confidence < 0.25) {
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
                best.controlPoints);
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
                new JSONArray());
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

    private MatchResult match(File candidateFile, File neighborFile, String neighborFrameId) {
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
        MatOfKeyPoint candidateKeypoints = new MatOfKeyPoint();
        MatOfKeyPoint neighborKeypoints = new MatOfKeyPoint();
        Mat candidateDescriptors = new Mat();
        Mat neighborDescriptors = new Mat();
        Mat inlierMask = new Mat();
        try {
            Utils.bitmapToMat(candidateBitmap, candidateRgba);
            Utils.bitmapToMat(neighborBitmap, neighborRgba);
            Imgproc.cvtColor(candidateRgba, candidateGray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(neighborRgba, neighborGray, Imgproc.COLOR_RGBA2GRAY);

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
            MatOfPoint2f candidateMat = new MatOfPoint2f();
            MatOfPoint2f neighborMat = new MatOfPoint2f();
            candidateMat.fromList(candidateControl);
            neighborMat.fromList(neighborControl);
            Mat homography = Geometry.findHomography(
                    candidateMat,
                    neighborMat,
                    Geometry.RANSAC,
                    RANSAC_REPROJECTION_THRESHOLD,
                    inlierMask);
            if (homography.empty() || inlierMask.empty()) {
                return null;
            }
            byte[] mask = new byte[(int) inlierMask.total()];
            inlierMask.get(0, 0, mask);
            int inliers = 0;
            double residualTotal = 0.0;
            JSONArray controlPoints = new JSONArray();
            for (int i = 0; i < mask.length && i < candidateControl.size(); i++) {
                if (mask[i] == 0) {
                    continue;
                }
                Point left = candidateControl.get(i);
                Point right = neighborControl.get(i);
                double dx = right.x - left.x;
                double dy = right.y - left.y;
                residualTotal += Math.sqrt(dx * dx + dy * dy);
                inliers++;
                if (controlPoints.length() < 24) {
                    JSONObject point = new JSONObject();
                    point.put("candidateX", left.x);
                    point.put("candidateY", left.y);
                    point.put("neighborX", right.x);
                    point.put("neighborY", right.y);
                    controlPoints.put(point);
                }
            }
            if (inliers <= 0) {
                return null;
            }
            double residual = residualTotal / inliers;
            double confidence = Math.min(1.0, inliers / 48.0) * Math.max(0.1, 1.0 - Math.min(0.8, residual / 80.0));
            return new MatchResult(neighborFrameId, inliers, residual, confidence, controlPoints);
        } catch (JSONException ignored) {
            return null;
        } finally {
            recycle(candidateBitmap);
            recycle(neighborBitmap);
            candidateRgba.release();
            neighborRgba.release();
            candidateGray.release();
            neighborGray.release();
            candidateKeypoints.release();
            neighborKeypoints.release();
            candidateDescriptors.release();
            neighborDescriptors.release();
            inlierMask.release();
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
