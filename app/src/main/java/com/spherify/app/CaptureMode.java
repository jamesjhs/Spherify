package com.spherify.app;

enum CaptureMode {
    HAND_HELD("handheld", "Hand-held"),
    TRIPOD_PHONE_MOUNT("tripod_phone_mount", "Tripod / phone mount"),
    EXTERNAL_360_CAMERA_IMPORT("external_360_camera_import", "External 360 camera import");

    final String storageValue;
    final String label;

    CaptureMode(String storageValue, String label) {
        this.storageValue = storageValue;
        this.label = label;
    }

    static CaptureMode fromStorageValue(String value) {
        if ("fixed_gimbal".equals(value) || "tripod_phone_mount".equals(value)) {
            return TRIPOD_PHONE_MOUNT;
        }
        if ("external_360_camera_import".equals(value)) {
            return EXTERNAL_360_CAMERA_IMPORT;
        }
        return HAND_HELD;
    }
}
