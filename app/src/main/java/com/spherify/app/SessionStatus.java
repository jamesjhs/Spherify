package com.spherify.app;

enum SessionStatus {
    NEW("new"),
    READY("ready"),
    CAPTURING("capturing"),
    CANDIDATE_PENDING_ANALYSIS("candidate_pending_analysis"),
    NEEDS_RECAPTURE("needs_recapture"),
    CAPTURE_COMPLETE("capture_complete"),
    VALID_FOR_SPHERIFY("valid_for_spherify"),
    SPHERIFYING("spherifying"),
    MASTER_CREATED("master_created"),
    NEEDS_REVIEW("needs_review"),
    FAILED("failed");

    final String storageValue;

    SessionStatus(String storageValue) {
        this.storageValue = storageValue;
    }

    static SessionStatus fromStorageValue(String value) {
        for (SessionStatus status : values()) {
            if (status.storageValue.equals(value)) {
                return status;
            }
        }
        return NEW;
    }
}
