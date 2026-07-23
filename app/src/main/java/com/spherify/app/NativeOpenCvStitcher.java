/*
 * NativeOpenCvStitcher.java
 *
 * Educational overview:
 * This is the optional bridge to a full native OpenCV Android build. The Maven
 * OpenCV AAR used by Java capture validation does not link the stitching/detail
 * module, so production stitching is available only when Gradle is configured
 * with a locally built OpenCV SDK that exports those native symbols.
 */
package com.spherify.app;

final class NativeOpenCvStitcher {
    static final int STATUS_OK = 0;
    private static final boolean AVAILABLE = loadNativeLibrary();

    private NativeOpenCvStitcher() {
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    static int stitchPanorama(String[] inputPaths, String outputPath) {
        if (!AVAILABLE) {
            return -1000;
        }
        return stitchPanoramaNative(inputPaths, outputPath);
    }

    private static boolean loadNativeLibrary() {
        try {
            System.loadLibrary("spherify_stitcher");
            return true;
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    private static native int stitchPanoramaNative(String[] inputPaths, String outputPath);
}
