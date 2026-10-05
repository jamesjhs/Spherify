package com.spherify.app;

final class CaptureAcceptancePolicy {
    static final String DEFAULT_VERSION = "fixed36_strict_pose_only_v1";
    static final boolean POSE_ONLY_ACCEPTANCE_ENABLED = true;
    static final boolean POSE_ONLY_REQUIRES_USER_CONFIRMATION = true;
    static final boolean PARTIAL_FINISH_PUBLIC = false;
    static final boolean RECOVERY_FILL_TARGETS_PUBLIC_DEFAULT = false;

    private CaptureAcceptancePolicy() {
    }
}
