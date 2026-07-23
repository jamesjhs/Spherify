package com.spherify.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class CaptureSessionRecord {
    static final int FORMAT_VERSION = 71;

    final String id;
    String title;
    final long createdAt;
    long updatedAt;
    CaptureMode captureMode;
    SessionStatus status;
    boolean diagnosticSpherifyAllowed;
    JSONObject readiness;
    final ArrayList<CaptureFrameRecord> frames;
    final ArrayList<CaptureGraphEdgeRecord> graphEdges;

    CaptureSessionRecord(
            String id,
            String title,
            long createdAt,
            long updatedAt,
            CaptureMode captureMode,
            SessionStatus status,
            boolean diagnosticSpherifyAllowed,
            JSONObject readiness,
            List<CaptureFrameRecord> frames,
            List<CaptureGraphEdgeRecord> graphEdges) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.captureMode = captureMode;
        this.status = status;
        this.diagnosticSpherifyAllowed = diagnosticSpherifyAllowed;
        this.readiness = readiness == null ? new JSONObject() : readiness;
        this.frames = new ArrayList<>(frames == null ? new ArrayList<>() : frames);
        this.graphEdges = new ArrayList<>(graphEdges == null ? new ArrayList<>() : graphEdges);
    }

    int countFrames(CaptureFrameRole role) {
        int count = 0;
        for (CaptureFrameRecord frame : frames) {
            if (frame.role == role) {
                count++;
            }
        }
        return count;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("formatVersion", FORMAT_VERSION);
        json.put("id", id);
        json.put("title", title);
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        json.put("captureMode", captureMode.storageValue);
        json.put("status", status.storageValue);
        json.put("diagnosticSpherifyAllowed", diagnosticSpherifyAllowed);
        json.put("readiness", readiness);
        JSONArray frameArray = new JSONArray();
        for (CaptureFrameRecord frame : frames) {
            frameArray.put(frame.toJson());
        }
        json.put("frames", frameArray);
        JSONArray edgeArray = new JSONArray();
        for (CaptureGraphEdgeRecord edge : graphEdges) {
            edgeArray.put(edge.toJson());
        }
        json.put("graphEdges", edgeArray);
        return json;
    }

    static CaptureSessionRecord fromJson(JSONObject json) {
        ArrayList<CaptureFrameRecord> frames = new ArrayList<>();
        JSONArray frameArray = json.optJSONArray("frames");
        if (frameArray != null) {
            for (int i = 0; i < frameArray.length(); i++) {
                JSONObject frame = frameArray.optJSONObject(i);
                if (frame != null) {
                    frames.add(CaptureFrameRecord.fromJson(frame));
                }
            }
        }
        ArrayList<CaptureGraphEdgeRecord> edges = new ArrayList<>();
        JSONArray edgeArray = json.optJSONArray("graphEdges");
        if (edgeArray != null) {
            for (int i = 0; i < edgeArray.length(); i++) {
                JSONObject edge = edgeArray.optJSONObject(i);
                if (edge != null) {
                    edges.add(CaptureGraphEdgeRecord.fromJson(edge));
                }
            }
        }
        return new CaptureSessionRecord(
                json.optString("id", ""),
                json.optString("title", "Pending Capture"),
                json.optLong("createdAt", 0L),
                json.optLong("updatedAt", json.optLong("createdAt", 0L)),
                CaptureMode.fromStorageValue(json.optString("captureMode", "handheld")),
                SessionStatus.fromStorageValue(json.optString("status", "new")),
                json.optBoolean("diagnosticSpherifyAllowed", false),
                json.optJSONObject("readiness"),
                frames,
                edges);
    }
}
