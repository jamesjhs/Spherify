package com.spherify.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class CaptureGraphEdgeRecord {
    final String id;
    final String sessionId;
    final String fromFrameId;
    final String toFrameId;
    final int inlierCount;
    final double residualScore;
    final double confidence;
    final String parallaxRiskHint;
    final JSONArray controlPoints;

    CaptureGraphEdgeRecord(
            String id,
            String sessionId,
            String fromFrameId,
            String toFrameId,
            int inlierCount,
            double residualScore,
            double confidence,
            String parallaxRiskHint,
            JSONArray controlPoints) {
        this.id = id;
        this.sessionId = sessionId;
        this.fromFrameId = fromFrameId;
        this.toFrameId = toFrameId;
        this.inlierCount = inlierCount;
        this.residualScore = residualScore;
        this.confidence = confidence;
        this.parallaxRiskHint = parallaxRiskHint == null ? "" : parallaxRiskHint;
        this.controlPoints = controlPoints == null ? new JSONArray() : controlPoints;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("sessionId", sessionId);
        json.put("fromFrameId", fromFrameId);
        json.put("toFrameId", toFrameId);
        json.put("inlierCount", inlierCount);
        json.put("residualScore", residualScore);
        json.put("confidence", confidence);
        json.put("parallaxRiskHint", parallaxRiskHint);
        json.put("controlPoints", controlPoints);
        return json;
    }

    static CaptureGraphEdgeRecord fromJson(JSONObject json) {
        return new CaptureGraphEdgeRecord(
                json.optString("id", ""),
                json.optString("sessionId", ""),
                json.optString("fromFrameId", ""),
                json.optString("toFrameId", ""),
                json.optInt("inlierCount", 0),
                json.optDouble("residualScore", -1.0),
                json.optDouble("confidence", 0.0),
                json.optString("parallaxRiskHint", ""),
                json.optJSONArray("controlPoints"));
    }
}
