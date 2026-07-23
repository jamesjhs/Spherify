package com.spherify.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class CaptureAnalysisFacts {
    final double blurScore;
    final double exposureScore;
    final double textureScore;
    final JSONArray predictedOverlapSet;
    final String opencvRansacResult;
    final int inlierCount;
    final double residualScore;
    final double confidence;
    final String parallaxRiskHint;
    final String rejectionReason;

    CaptureAnalysisFacts(
            double blurScore,
            double exposureScore,
            double textureScore,
            JSONArray predictedOverlapSet,
            String opencvRansacResult,
            int inlierCount,
            double residualScore,
            double confidence,
            String parallaxRiskHint,
            String rejectionReason) {
        this.blurScore = blurScore;
        this.exposureScore = exposureScore;
        this.textureScore = textureScore;
        this.predictedOverlapSet = predictedOverlapSet == null ? new JSONArray() : predictedOverlapSet;
        this.opencvRansacResult = opencvRansacResult == null ? "not_run" : opencvRansacResult;
        this.inlierCount = inlierCount;
        this.residualScore = residualScore;
        this.confidence = confidence;
        this.parallaxRiskHint = parallaxRiskHint == null ? "" : parallaxRiskHint;
        this.rejectionReason = rejectionReason == null ? "" : rejectionReason;
    }

    static CaptureAnalysisFacts empty() {
        return new CaptureAnalysisFacts(
                -1.0,
                -1.0,
                -1.0,
                new JSONArray(),
                "not_run",
                0,
                -1.0,
                0.0,
                "",
                "");
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("blurScore", blurScore);
        json.put("exposureScore", exposureScore);
        json.put("textureScore", textureScore);
        json.put("predictedOverlapSet", predictedOverlapSet);
        json.put("opencvRansacResult", opencvRansacResult);
        json.put("inlierCount", inlierCount);
        json.put("residualScore", residualScore);
        json.put("confidence", confidence);
        json.put("parallaxRiskHint", parallaxRiskHint);
        json.put("rejectionReason", rejectionReason);
        return json;
    }

    static CaptureAnalysisFacts fromJson(JSONObject json) {
        if (json == null) {
            return empty();
        }
        return new CaptureAnalysisFacts(
                json.optDouble("blurScore", -1.0),
                json.optDouble("exposureScore", -1.0),
                json.optDouble("textureScore", -1.0),
                json.optJSONArray("predictedOverlapSet"),
                json.optString("opencvRansacResult", "not_run"),
                json.optInt("inlierCount", 0),
                json.optDouble("residualScore", -1.0),
                json.optDouble("confidence", 0.0),
                json.optString("parallaxRiskHint", ""),
                json.optString("rejectionReason", ""));
    }
}
