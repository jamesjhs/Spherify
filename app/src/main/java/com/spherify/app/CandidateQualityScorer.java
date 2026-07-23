package com.spherify.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

final class CandidateQualityScorer {
    private static final int MAX_SAMPLE_SIZE = 640;
    private static final double MIN_BLUR_SCORE = 55.0;
    private static final double MIN_EXPOSURE_SCORE = 0.32;
    private static final double MIN_TEXTURE_SCORE = 5.5;

    CandidateQualityReport score(File imageFile, double yawRate, double pitchRate, double rollRate) {
        Bitmap bitmap = decodeSample(imageFile);
        if (bitmap == null) {
            return new CandidateQualityReport(false, 0.0, 0.0, 0.0, "Metadata incomplete");
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();

        double dark = 0.0;
        double clipped = 0.0;
        double gradientTotal = 0.0;
        double laplacianTotal = 0.0;
        double laplacianSquaredTotal = 0.0;
        int samples = 0;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int index = row + x;
                int center = luminance(pixels[index]);
                int left = luminance(pixels[index - 1]);
                int right = luminance(pixels[index + 1]);
                int up = luminance(pixels[index - width]);
                int down = luminance(pixels[index + width]);
                int gx = right - left;
                int gy = down - up;
                int laplacian = 4 * center - left - right - up - down;
                gradientTotal += Math.sqrt(gx * gx + gy * gy);
                laplacianTotal += laplacian;
                laplacianSquaredTotal += laplacian * laplacian;
                if (center < 12) {
                    dark += 1.0;
                } else if (center > 243) {
                    clipped += 1.0;
                }
                samples++;
            }
        }
        if (samples <= 0) {
            return new CandidateQualityReport(false, 0.0, 0.0, 0.0, "Metadata incomplete");
        }
        double laplacianMean = laplacianTotal / samples;
        double blurScore = Math.max(0.0, laplacianSquaredTotal / samples - laplacianMean * laplacianMean);
        double badExposureRatio = (dark + clipped) / samples;
        double exposureScore = Math.max(0.0, 1.0 - badExposureRatio * 4.0);
        double textureScore = gradientTotal / samples;
        String rejection = "";
        if (Math.max(Math.abs(yawRate), Math.max(Math.abs(pitchRate), Math.abs(rollRate))) > 8.0) {
            rejection = "Moved too fast";
        } else if (blurScore < MIN_BLUR_SCORE) {
            rejection = "Blurred";
        } else if (exposureScore < MIN_EXPOSURE_SCORE) {
            rejection = "Too dark";
        } else if (textureScore < MIN_TEXTURE_SCORE) {
            rejection = "Need more visual detail";
        }
        return new CandidateQualityReport(
                rejection.isEmpty(),
                blurScore,
                exposureScore,
                textureScore,
                rejection);
    }

    private static Bitmap decodeSample(File imageFile) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
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
        return BitmapFactory.decodeFile(imageFile.getAbsolutePath(), decode);
    }

    private static int luminance(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return (r * 77 + g * 150 + b * 29) >> 8;
    }
}
