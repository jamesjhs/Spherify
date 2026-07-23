package com.spherify.app;

final class CandidateQualityReport {
    final boolean pass;
    final double blurScore;
    final double exposureScore;
    final double textureScore;
    final String rejectionReason;

    CandidateQualityReport(
            boolean pass,
            double blurScore,
            double exposureScore,
            double textureScore,
            String rejectionReason) {
        this.pass = pass;
        this.blurScore = blurScore;
        this.exposureScore = exposureScore;
        this.textureScore = textureScore;
        this.rejectionReason = rejectionReason == null ? "" : rejectionReason;
    }
}
