package com.spherify.app;

enum CaptureFrameRole {
    SOURCE("source"),
    CANDIDATE("candidate"),
    ACCEPTED("accepted"),
    REJECTED("rejected");

    final String storageValue;

    CaptureFrameRole(String storageValue) {
        this.storageValue = storageValue;
    }

    static CaptureFrameRole fromStorageValue(String value) {
        for (CaptureFrameRole role : values()) {
            if (role.storageValue.equals(value)) {
                return role;
            }
        }
        return CANDIDATE;
    }
}
