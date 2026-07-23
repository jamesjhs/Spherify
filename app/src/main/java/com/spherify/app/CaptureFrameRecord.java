package com.spherify.app;

import org.json.JSONException;
import org.json.JSONObject;

final class CaptureFrameRecord {
    final String id;
    final String sessionId;
    final CaptureFrameRole role;
    final CaptureRawFacts rawFacts;
    final CaptureAnalysisFacts analysisFacts;

    CaptureFrameRecord(
            String id,
            String sessionId,
            CaptureFrameRole role,
            CaptureRawFacts rawFacts,
            CaptureAnalysisFacts analysisFacts) {
        this.id = id;
        this.sessionId = sessionId;
        this.role = role;
        this.rawFacts = rawFacts;
        this.analysisFacts = analysisFacts == null ? CaptureAnalysisFacts.empty() : analysisFacts;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("sessionId", sessionId);
        json.put("role", role.storageValue);
        json.put("rawFacts", rawFacts.toJson());
        json.put("analysisFacts", analysisFacts.toJson());
        return json;
    }

    static CaptureFrameRecord fromJson(JSONObject json) {
        return new CaptureFrameRecord(
                json.optString("id", ""),
                json.optString("sessionId", ""),
                CaptureFrameRole.fromStorageValue(json.optString("role", "candidate")),
                CaptureRawFacts.fromJson(json.optJSONObject("rawFacts") == null
                        ? new JSONObject()
                        : json.optJSONObject("rawFacts")),
                CaptureAnalysisFacts.fromJson(json.optJSONObject("analysisFacts")));
    }
}
