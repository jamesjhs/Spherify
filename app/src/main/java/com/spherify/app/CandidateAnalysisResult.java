package com.spherify.app;

import org.json.JSONArray;

final class CandidateAnalysisResult {
    final boolean accepted;
    final CandidateQualityReport quality;
    final JSONArray predictedOverlapSet;
    final String opencvRansacResult;
    final int inlierCount;
    final double residualScore;
    final double confidence;
    final String parallaxRiskHint;
    final String rejectionReason;
    final String acceptedNeighborFrameId;
    final JSONArray controlPoints;

    CandidateAnalysisResult(
            boolean accepted,
            CandidateQualityReport quality,
            JSONArray predictedOverlapSet,
            String opencvRansacResult,
            int inlierCount,
            double residualScore,
            double confidence,
            String parallaxRiskHint,
            String rejectionReason,
            String acceptedNeighborFrameId,
            JSONArray controlPoints) {
        this.accepted = accepted;
        this.quality = quality;
        this.predictedOverlapSet = predictedOverlapSet == null ? new JSONArray() : predictedOverlapSet;
        this.opencvRansacResult = opencvRansacResult == null ? "not_run" : opencvRansacResult;
        this.inlierCount = inlierCount;
        this.residualScore = residualScore;
        this.confidence = confidence;
        this.parallaxRiskHint = parallaxRiskHint == null ? "" : parallaxRiskHint;
        this.rejectionReason = rejectionReason == null ? "" : rejectionReason;
        this.acceptedNeighborFrameId = acceptedNeighborFrameId == null ? "" : acceptedNeighborFrameId;
        this.controlPoints = controlPoints == null ? new JSONArray() : controlPoints;
    }

    CaptureAnalysisFacts toAnalysisFacts() {
        return new CaptureAnalysisFacts(
                quality == null ? -1.0 : quality.blurScore,
                quality == null ? -1.0 : quality.exposureScore,
                quality == null ? -1.0 : quality.textureScore,
                predictedOverlapSet,
                opencvRansacResult,
                inlierCount,
                residualScore,
                confidence,
                parallaxRiskHint,
                rejectionReason);
    }
}
