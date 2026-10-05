package com.spherify.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CaptureProfile {
    static final String STANFORD_FIXED_36_ID = "stanford_fixed_36";
    static final String ADAPTIVE_FOV_ID = "spherify_adaptive_fov";

    final String id;
    final String displayName;
    final int expectedTargetCount;
    final boolean requiresPortrait;
    final boolean publicDefault;
    final List<CaptureTargetSpec> targetSpecs;
    final String acceptancePolicyVersion;

    private CaptureProfile(
            String id,
            String displayName,
            int expectedTargetCount,
            boolean requiresPortrait,
            boolean publicDefault,
            List<CaptureTargetSpec> targetSpecs,
            String acceptancePolicyVersion) {
        this.id = id;
        this.displayName = displayName;
        this.expectedTargetCount = expectedTargetCount;
        this.requiresPortrait = requiresPortrait;
        this.publicDefault = publicDefault;
        this.targetSpecs = Collections.unmodifiableList(new ArrayList<>(targetSpecs));
        this.acceptancePolicyVersion = acceptancePolicyVersion == null || acceptancePolicyVersion.isEmpty()
                ? CaptureAcceptancePolicy.DEFAULT_VERSION
                : acceptancePolicyVersion;
    }

    static CaptureProfile stanfordFixed36() {
        ArrayList<CaptureTargetSpec> specs = new ArrayList<>();
        specs.add(new CaptureTargetSpec(0, 0, 0, CaptureTargetPhase.START));
        int index = 1;
        for (int column = 1; column < 12; column++) {
            specs.add(new CaptureTargetSpec(index++, column * 30, 0, CaptureTargetPhase.HORIZON));
        }
        for (int column = 0; column < 12; column++) {
            specs.add(new CaptureTargetSpec(index++, column * 30, 45, CaptureTargetPhase.MID));
        }
        for (int column = 0; column < 12; column++) {
            specs.add(new CaptureTargetSpec(index++, column * 30, -45, CaptureTargetPhase.MID));
        }
        return new CaptureProfile(
                STANFORD_FIXED_36_ID,
                "Fixed 36",
                CaptureTargetPlanner.FIXED_36_TARGET_COUNT,
                true,
                true,
                specs,
                CaptureAcceptancePolicy.DEFAULT_VERSION);
    }

    static final class CaptureTargetSpec {
        final int index;
        final int localYawDegrees;
        final int localPitchDegrees;
        final CaptureTargetPhase phase;

        CaptureTargetSpec(int index, int localYawDegrees, int localPitchDegrees, CaptureTargetPhase phase) {
            this.index = index;
            this.localYawDegrees = localYawDegrees;
            this.localPitchDegrees = localPitchDegrees;
            this.phase = phase;
        }
    }
}
