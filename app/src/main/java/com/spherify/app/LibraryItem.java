/*
 * LibraryItem.java
 *
 * Educational overview:
 * LibraryItem is the in-memory record for one image known to Spherify. It is a
 * model object rather than a screen or storage service. It describes where the
 * image and thumbnail live on disk, what kind of image it is, how it entered the
 * app, which projection mode it represents, and when it was created/updated.
 * SpherifyLibrary owns the collection of LibraryItem objects and serializes
 * them to metadata.json. MainActivity reads these objects to populate the
 * gallery, decide which viewer to open, and show metadata to the user.
 *
 * Data flow:
 * SpherifyLibrary creates or loads LibraryItem objects -> MainActivity presents
 * them -> user actions can rename/delete/open them -> SpherifyLibrary writes the
 * updated object list back to metadata.json via LibraryItem.toJson().
 *
 * Imports/dependencies:
 * org.json.JSONObject/JSONException encode and decode the metadata file.
 * java.io.File turns stored absolute path strings into File handles for callers
 * that need filesystem operations.
 *
 * Key variables:
 * id: stable unique identifier used for metadata and parent/child links.
 * title: user-visible label; mutable because rename edits it.
 * type: master or variant, used for filtering and delete/open behavior.
 * source: bundled/import/local, describing where the image came from.
 * projection: sphere, tinyplanet, or flat, controlling which viewer is used.
 * parentId: id of a source image when this item is an exported variant.
 * imagePath/thumbnailPath: absolute paths to local image files.
 * createdAt/updatedAt: timestamps used for metadata display and sorting.
 */
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

    /*
     * Function: LibraryItem constructor
     * Arguments: every metadata field required to describe one image record.
     * Calls: no helper functions; this is a direct value assignment object.
     * Flow: SpherifyLibrary passes metadata gathered from imports, bundled
     * assets, or exports; the constructor stores it for later filtering,
     * display, JSON encoding, and file lookup.
     */
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

    /*
     * Function: imageFile
     * Arguments: none.
     * Calls: java.io.File constructor.
     * Flow: converts the stored imagePath string into a File handle so library
     * and sharing code can test existence, delete, copy, or expose the image.
     */
    File imageFile() {
        return new File(imagePath);
    }

    /*
     * Function: thumbnailFile
     * Arguments: none.
     * Calls: java.io.File constructor.
     * Flow: converts thumbnailPath into a File handle used by galleries,
     * deletion, and variant persistence.
     */
    File thumbnailFile() {
        return new File(thumbnailPath);
    }

    /*
     * Function: matchesFilter
     * Arguments: filter is one of the FILTER_* constants selected by the Browse
     * dialog.
     * Calls: String.equals comparisons against this item's type/source/projection.
     * Flow: returns true when the item belongs in the requested gallery view.
     * Unknown filters fall through to true so the UI remains forgiving.
     */
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

    /*
     * Function: toJson
     * Arguments: none.
     * Calls: JSONObject.put for each field.
     * Flow: translates the in-memory record into the persisted metadata shape
     * that SpherifyLibrary writes to metadata.json.
     */
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

    /*
     * Function: fromJson
     * Arguments: json is one object read from metadata.json.
     * Calls: JSONObject getters and opt* methods, then the LibraryItem
     * constructor.
     * Flow: reconstructs a LibraryItem from disk. Optional fields provide
     * defaults so older metadata can still load after the schema grows.
     */
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
