package com.spherify.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;

final class OpenCvCalibrationPreprocessor {
    private OpenCvCalibrationPreprocessor() {
    }

    static File calibratedWorkingImage(File source, JSONObject exposure, JSONObject intrinsics, File output)
            throws IOException {
        if (!initOpenCv()) {
            throw new IOException("OpenCV calibration preprocessor is unavailable");
        }
        DistortionCalibration calibration = DistortionCalibration.from(exposure, intrinsics);
        if (calibration == null) {
            return source;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (bitmap == null) {
            throw new IOException("could not decode source frame for calibration: " + source.getName());
        }
        Mat rgba = new Mat();
        Mat correctedRgba = new Mat();
        Mat bgr = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            remap(rgba, correctedRgba, calibration.scaledTo(rgba.width(), rgba.height()));
            Imgproc.cvtColor(correctedRgba, bgr, Imgproc.COLOR_RGBA2BGR);
            if (!Imgcodecs.imwrite(output.getAbsolutePath(), bgr)) {
                throw new IOException("could not write calibrated working frame: " + output.getName());
            }
            return output;
        } finally {
            bitmap.recycle();
            rgba.release();
            correctedRgba.release();
            bgr.release();
        }
    }

    static Mat undistortGrayIfRequired(Mat gray, JSONObject exposure, JSONObject intrinsics) {
        DistortionCalibration calibration = DistortionCalibration.from(exposure, intrinsics);
        if (calibration == null) {
            return null;
        }
        Mat corrected = new Mat();
        remap(gray, corrected, calibration.scaledTo(gray.width(), gray.height()));
        return corrected;
    }

    private static void remap(Mat source, Mat destination, DistortionCalibration calibration) {
        Mat mapX = new Mat(source.height(), source.width(), CvType.CV_32FC1);
        Mat mapY = new Mat(source.height(), source.width(), CvType.CV_32FC1);
        try {
            float[] xMap = new float[source.width() * source.height()];
            float[] yMap = new float[xMap.length];
            int index = 0;
            for (int y = 0; y < source.height(); y++) {
                double yi = (y - calibration.cy) / calibration.fy;
                for (int x = 0; x < source.width(); x++) {
                    double xi = (x - calibration.cx) / calibration.fx;
                    double r2 = xi * xi + yi * yi;
                    double r4 = r2 * r2;
                    double r6 = r4 * r2;
                    double radial = 1.0
                            + calibration.k1 * r2
                            + calibration.k2 * r4
                            + calibration.k3 * r6;
                    double xc = xi * radial
                            + calibration.p1 * (2.0 * xi * yi)
                            + calibration.p2 * (r2 + 2.0 * xi * xi);
                    double yc = yi * radial
                            + calibration.p2 * (2.0 * xi * yi)
                            + calibration.p1 * (r2 + 2.0 * yi * yi);
                    xMap[index] = (float) (xc * calibration.fx + calibration.cx);
                    yMap[index] = (float) (yc * calibration.fy + calibration.cy);
                    index++;
                }
            }
            mapX.put(0, 0, xMap);
            mapY.put(0, 0, yMap);
            Imgproc.remap(source, destination, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(0));
        } finally {
            mapX.release();
            mapY.release();
        }
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

    private static final class DistortionCalibration {
        final double sourceWidth;
        final double sourceHeight;
        final double fx;
        final double fy;
        final double cx;
        final double cy;
        final double k1;
        final double k2;
        final double k3;
        final double p1;
        final double p2;

        private DistortionCalibration(
                double sourceWidth,
                double sourceHeight,
                double fx,
                double fy,
                double cx,
                double cy,
                double k1,
                double k2,
                double k3,
                double p1,
                double p2) {
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.fx = fx;
            this.fy = fy;
            this.cx = cx;
            this.cy = cy;
            this.k1 = k1;
            this.k2 = k2;
            this.k3 = k3;
            this.p1 = p1;
            this.p2 = p2;
        }

        static DistortionCalibration from(JSONObject exposure, JSONObject intrinsics) {
            if (!manualUndistortionRequired(exposure, intrinsics)) {
                return null;
            }
            JSONArray distortion = firstArray(exposure, intrinsics, "lensDistortion");
            JSONArray calibration = firstArray(exposure, intrinsics, "lensIntrinsicCalibration");
            if (distortion == null || distortion.length() < 5 || calibration == null || calibration.length() < 4) {
                return null;
            }
            int width = firstPositiveInt(
                    exposure == null ? 0 : exposure.optInt("preCorrectionActiveArrayWidth", 0),
                    intrinsics == null ? 0 : intrinsics.optInt("preCorrectionActiveArrayWidth", 0),
                    exposure == null ? 0 : exposure.optInt("imageIntrinsicsWidth", 0),
                    intrinsics == null ? 0 : intrinsics.optInt("width", 0));
            int height = firstPositiveInt(
                    exposure == null ? 0 : exposure.optInt("preCorrectionActiveArrayHeight", 0),
                    intrinsics == null ? 0 : intrinsics.optInt("preCorrectionActiveArrayHeight", 0),
                    exposure == null ? 0 : exposure.optInt("imageIntrinsicsHeight", 0),
                    intrinsics == null ? 0 : intrinsics.optInt("height", 0));
            if (width <= 0 || height <= 0 || calibration.optDouble(0, 0.0) <= 0.0
                    || calibration.optDouble(1, 0.0) <= 0.0) {
                return null;
            }
            return new DistortionCalibration(
                    width,
                    height,
                    calibration.optDouble(0, 0.0),
                    calibration.optDouble(1, 0.0),
                    calibration.optDouble(2, width * 0.5),
                    calibration.optDouble(3, height * 0.5),
                    distortion.optDouble(0, 0.0),
                    distortion.optDouble(1, 0.0),
                    distortion.optDouble(2, 0.0),
                    distortion.optDouble(3, 0.0),
                    distortion.optDouble(4, 0.0));
        }

        DistortionCalibration scaledTo(int width, int height) {
            double scaleX = width / sourceWidth;
            double scaleY = height / sourceHeight;
            return new DistortionCalibration(
                    width,
                    height,
                    fx * scaleX,
                    fy * scaleY,
                    cx * scaleX,
                    cy * scaleY,
                    k1,
                    k2,
                    k3,
                    p1,
                    p2);
        }

        private static boolean manualUndistortionRequired(JSONObject exposure, JSONObject intrinsics) {
            if (exposure != null && exposure.has("manualLensUndistortionRequired")) {
                return exposure.optBoolean("manualLensUndistortionRequired", false);
            }
            if (intrinsics != null && intrinsics.has("manualLensUndistortionRequired")) {
                return intrinsics.optBoolean("manualLensUndistortionRequired", false);
            }
            return false;
        }

        private static JSONArray firstArray(JSONObject preferred, JSONObject fallback, String key) {
            JSONArray preferredArray = preferred == null ? null : preferred.optJSONArray(key);
            return preferredArray != null ? preferredArray : fallback == null ? null : fallback.optJSONArray(key);
        }

        private static int firstPositiveInt(int first, int second, int third, int fourth) {
            if (first > 0) {
                return first;
            }
            if (second > 0) {
                return second;
            }
            return third > 0 ? third : fourth;
        }
    }
}
