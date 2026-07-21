package com.spherify.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

final class LibraryItem {
    static final String TYPE_MASTER = "master";
    static final String TYPE_VARIANT = "variant";
    static final String FILTER_ALL = "all";
    static final String FILTER_MASTERS = "masters";
    static final String FILTER_TINY_PLANETS = "tiny_planets";
    static final String FILTER_IMPORTS = "imports";
    static final String FILTER_SAVED = "saved";

    final String id;
    String title;
    final String type;
    final String source;
    final String projection;
    final String parentId;
    final String imagePath;
    final String thumbnailPath;
    final long createdAt;
    long updatedAt;

    LibraryItem(
            String id,
            String title,
            String type,
            String source,
            String projection,
            String parentId,
            String imagePath,
            String thumbnailPath,
            long createdAt,
            long updatedAt) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.source = source;
        this.projection = projection;
        this.parentId = parentId;
        this.imagePath = imagePath;
        this.thumbnailPath = thumbnailPath;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    File imageFile() {
        return new File(imagePath);
    }

    File thumbnailFile() {
        return new File(thumbnailPath);
    }

    boolean matchesFilter(String filter) {
        if (FILTER_ALL.equals(filter)) {
            return true;
        }
        if (FILTER_MASTERS.equals(filter)) {
            return TYPE_MASTER.equals(type);
        }
        if (FILTER_TINY_PLANETS.equals(filter)) {
            return "tinyplanet".equals(projection);
        }
        if (FILTER_IMPORTS.equals(filter)) {
            return "import".equals(source);
        }
        if (FILTER_SAVED.equals(filter)) {
            return TYPE_VARIANT.equals(type);
        }
        return true;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("title", title);
        json.put("type", type);
        json.put("source", source);
        json.put("projection", projection);
        json.put("parentId", parentId);
        json.put("imagePath", imagePath);
        json.put("thumbnailPath", thumbnailPath);
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        return json;
    }

    static LibraryItem fromJson(JSONObject json) throws JSONException {
        return new LibraryItem(
                json.getString("id"),
                json.getString("title"),
                json.getString("type"),
                json.optString("source", "local"),
                json.optString("projection", "sphere"),
                json.optString("parentId", ""),
                json.getString("imagePath"),
                json.getString("thumbnailPath"),
                json.getLong("createdAt"),
                json.optLong("updatedAt", json.getLong("createdAt")));
    }
}
