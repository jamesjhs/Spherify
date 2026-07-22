/*
 * SpherifyLibrary.java
 *
 * Educational overview:
 * SpherifyLibrary is the app's local persistence layer. Activities ask it to
 * import images, create bundled demo content, save exported variants, list
 * gallery items, rename/delete entries, create/delete draft capture files, and
 * record draft metadata. The class deliberately keeps storage details out of
 * MainActivity and CaptureActivity so those screens can focus on UI flow.
 *
 * Data flow:
 * External/bundled image input -> copied into app-private library folders ->
 * thumbnail generated -> LibraryItem added to the in-memory list -> metadata
 * written to metadata.json. For exports, GLProjectionView creates temporary
 * files and MainActivity passes a ProjectionExport here; this class copies the
 * files into the library and optionally publishes the image to Android
 * MediaStore. For capture drafts, CaptureActivity asks for a destination JPEG
 * File, writes the latest ARCore CPU camera frame, and recordDraftFrame()
 * appends path/location/
 * orientation/exposure/session data to drafts.json while upserting a
 * first-class draft-session library item.
 *
 * External files/functions:
 * Reads the bundled asset stream supplied by MainActivity.
 * Reads images from user-selected Uri values through ContentResolver.
 * Writes files below context.getFilesDir()/library.
 * Writes metadata.json and drafts.json as JSON arrays.
 * Writes saved variants to MediaStore on Android Q+ so exports appear in Photos.
 *
 * Imports/dependencies:
 * Android ContentResolver/ContentValues/MediaStore publish saved variants.
 * Bitmap/BitmapFactory decode images and create thumbnails.
 * File/InputStream/OutputStream classes copy bytes and create directories.
 * org.json classes serialize library and draft metadata.
 * UUID creates collision-resistant ids for image records.
 *
 * Key variables:
 * THUMBNAIL_SIZE: square thumbnail dimension in pixels.
 * context: application context used for files, ContentResolver, and MediaStore.
 * root: app-private library root directory.
 * mastersDir/variantsDir/draftsDir/thumbnailsDir: content subfolders.
 * metadataFile: JSON index of LibraryItem records.
 * draftMetadataFile: JSON index of ARCore draft frames.
 * items: in-memory LibraryItem list loaded from metadataFile.
 */
package com.spherify.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class SpherifyLibrary {
    private static final int THUMBNAIL_SIZE = 320;

    private final Context context;
    private final File root;
    private final File mastersDir;
    private final File variantsDir;
    private final File draftsDir;
    private final File thumbnailsDir;
    private final File metadataFile;
    private final File draftMetadataFile;
    private final ArrayList<LibraryItem> items = new ArrayList<>();

    /*
     * Function: SpherifyLibrary constructor
     * Arguments: context supplies Android filesystem and resolver access.
     * Calls: getApplicationContext(), getFilesDir(), ensureDirs(), and load().
     * Flow: resolve all library paths, create missing directories, then hydrate
     * the in-memory items list from metadata.json.
     */
    SpherifyLibrary(Context context) throws IOException {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "library");
        mastersDir = new File(root, "masters");
        variantsDir = new File(root, "variants");
        draftsDir = new File(root, "drafts");
        thumbnailsDir = new File(root, "thumbnails");
        metadataFile = new File(root, "metadata.json");
        draftMetadataFile = new File(root, "drafts.json");
        ensureDirs();
        load();
    }

    /*
     * Function: ensureBundledMaster
     * Arguments: assetStream is an InputStream for the bundled demo image.
     * Calls: copy(), makeThumbnail(), save(), and LibraryItem constructor.
     * Flow: if a bundled master already exists, refresh its file/thumbnail and
     * metadata; otherwise copy the asset into masters, create a thumbnail, add a
     * new master record, and persist metadata.json.
     */
    LibraryItem ensureBundledMaster(InputStream assetStream) throws IOException {
        for (LibraryItem item : items) {
            if (LibraryItem.TYPE_MASTER.equals(item.type) && "bundled".equals(item.source)) {
                copy(assetStream, item.imageFile());
                makeThumbnail(item.imageFile(), item.id);
                item.title = "Sci-Fi PhotoSphere Demo";
                item.updatedAt = System.currentTimeMillis();
                save();
                return item;
            }
        }

        long now = System.currentTimeMillis();
        String id = newId();
        File imageFile = new File(mastersDir, id + ".jpg");
        copy(assetStream, imageFile);
        File thumbnailFile = makeThumbnail(imageFile, id);
        LibraryItem item = new LibraryItem(
                id,
                "Sci-Fi PhotoSphere Demo",
                LibraryItem.TYPE_MASTER,
                "bundled",
                "sphere",
                "",
                imageFile.getAbsolutePath(),
                thumbnailFile.getAbsolutePath(),
                now,
                now);
        items.add(item);
        save();
        return item;
    }

    /*
     * Function: importImage
     * Arguments: uri points to a user-selected image; projection records whether
     * the user treats it as sphere, tinyplanet, or flat.
     * Calls: ContentResolver.openInputStream(), copy(), BitmapFactory.decodeFile(),
     * makeThumbnail(), LibraryItem constructor, and save().
     * Flow: copy the selected image into masters, verify it decodes, generate a
     * thumbnail, create a master LibraryItem, append it to items, and persist.
     */
    LibraryItem importImage(Uri uri, String projection) throws IOException {
        long now = System.currentTimeMillis();
        String id = newId();
        File imageFile = new File(mastersDir, id + ".jpg");
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("could not open selected image");
            }
            copy(input, imageFile);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("selected file is not a readable image");
        }

        File thumbnailFile = makeThumbnail(imageFile, id);
        String title = "flat".equals(projection)
                ? "Imported Flat " + items.size()
                : "tinyplanet".equals(projection)
                ? "Imported Tiny Planet " + items.size()
                : "Imported PhotoSphere " + items.size();
        LibraryItem item = new LibraryItem(
                id,
                title,
                LibraryItem.TYPE_MASTER,
                "import",
                projection,
                "",
                imageFile.getAbsolutePath(),
                thumbnailFile.getAbsolutePath(),
                now,
                now);
        items.add(item);
        save();
        return item;
    }

    /*
     * Function: saveVariant
     * Arguments: parent is the source LibraryItem, export holds generated files,
     * and projection names the exported view type.
     * Calls: copy(), LibraryItem constructor, saveToMediaStore(), and save().
     * Flow: copy export image/thumbnail into managed folders, record a variant
     * item linked to its parent, publish the image to Pictures/Spherify on
     * supported Android versions, then persist metadata.json.
     */
    LibraryItem saveVariant(LibraryItem parent, ProjectionExport export, String projection) throws IOException {
        long now = System.currentTimeMillis();
        String id = newId();
        File imageFile = new File(variantsDir, id + ".png");
        File thumbnailFile = new File(thumbnailsDir, id + ".jpg");
        copy(export.imageFile, imageFile);
        copy(export.thumbnailFile, thumbnailFile);

        LibraryItem item = new LibraryItem(
                id,
                projection.equals("tinyplanet") ? "Tiny Planet Variant" : "PhotoSphere Variant",
                LibraryItem.TYPE_VARIANT,
                "local",
                projection,
                parent == null ? "" : parent.id,
                imageFile.getAbsolutePath(),
                thumbnailFile.getAbsolutePath(),
                now,
                now);
        items.add(item);
        saveToMediaStore(imageFile, item.title);
        save();
        return item;
    }

    /*
     * Function: list
     * Arguments: filter is a LibraryItem.FILTER_* value requested by MainActivity.
     * Calls: LibraryItem.imageFile(), LibraryItem.matchesFilter(), and Collections.sort().
     * Flow: return existing image records matching the filter, newest updated
     * first, so the gallery never shows missing deleted files.
     */
    List<LibraryItem> list(String filter) {
        ArrayList<LibraryItem> result = new ArrayList<>();
        for (LibraryItem item : items) {
            if (item.imageFile().exists() && item.matchesFilter(filter)) {
                result.add(item);
            }
        }
        Collections.sort(result, Comparator.comparingLong((LibraryItem item) -> item.updatedAt).reversed());
        return result;
    }

    /*
     * Function: listDraftFrames
     * Arguments: none.
     * Calls: File.listFiles() and Collections.sort().
     * Flow: scan the drafts directory for JPEG files written by CameraX and
     * return them newest first for the Draft Frames browser.
     */
    List<File> listDraftFrames() {
        ArrayList<File> result = new ArrayList<>();
        JSONArray metadata = readDraftMetadata();
        for (int i = 0; i < metadata.length(); i++) {
            JSONObject json = metadata.optJSONObject(i);
            if (json == null) {
                continue;
            }
            File file = new File(json.optString("path", ""));
            if (file.exists()) {
                result.add(file);
            }
        }
        if (result.isEmpty()) {
            File[] files = draftsDir.listFiles((dir, name) -> name.endsWith(".jpg"));
            if (files != null) {
                Collections.addAll(result, files);
            }
        }
        Collections.sort(result, Comparator.comparingLong(File::lastModified).reversed());
        return result;
    }

    /*
     * Function: listDraftFrames
     * Arguments: sessionId identifies one draft capture LibraryItem.
     * Calls: readDraftMetadata(), File.exists(), and Collections.sort().
     * Flow: return only JPEG frames recorded for the selected draft session so
     * users and Phase 5 jobs can work with a coherent capture set.
     */
    List<File> listDraftFrames(String sessionId) {
        ArrayList<File> result = new ArrayList<>();
        JSONArray metadata = readDraftMetadata();
        for (int i = 0; i < metadata.length(); i++) {
            JSONObject json = metadata.optJSONObject(i);
            if (json == null || !sessionId.equals(json.optString("sessionId", ""))) {
                continue;
            }
            File file = new File(json.optString("path", ""));
            if (file.exists()) {
                result.add(file);
            }
        }
        Collections.sort(result, Comparator.comparingLong(File::lastModified).reversed());
        return result;
    }

    /*
     * Function: deleteDraftFrame
     * Arguments: imageFile is a CameraX draft JPEG selected from the Draft Frames
     * browser.
     * Calls: deleteFile(), JSONArray/JSONObject parsing, and FileOutputStream.
     * Flow: remove the JPEG from local storage, remove any matching metadata
     * rows from drafts.json, and rewrite the draft index so recovery/stitching
     * experiments no longer see a deleted frame.
     */
    void deleteDraftFrame(File imageFile) throws IOException {
        String deletedPath = imageFile.getAbsolutePath();
        String deletedSessionId = "";
        deleteFile(imageFile);
        JSONArray source = readDraftMetadataOrThrow();

        JSONArray kept = new JSONArray();
        try {
            for (int i = 0; i < source.length(); i++) {
                JSONObject json = source.getJSONObject(i);
                if (deletedPath.equals(json.optString("path", ""))) {
                    deletedSessionId = json.optString("sessionId", "");
                } else {
                    kept.put(json);
                }
            }
            writeDraftMetadata(kept);
            refreshDraftSessionItem(deletedSessionId, kept);
        } catch (JSONException e) {
            throw new IOException("could not update draft metadata", e);
        }
    }

    /*
     * Function: deleteAllDraftFrames
     * Arguments: none.
     * Calls: File.listFiles(), deleteFile(), JSONArray.toString(), and
     * FileOutputStream.
     * Flow: remove every CameraX draft JPEG from the drafts folder and reset
     * drafts.json to an empty array so large draft sessions can be cleared in
     * one confirmed action.
     */
    void deleteAllDraftFrames() throws IOException {
        File[] files = draftsDir.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files != null) {
            for (File file : files) {
                deleteFile(file);
            }
        }
        try (FileOutputStream output = new FileOutputStream(draftMetadataFile)) {
            output.write(new JSONArray().toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("could not reset draft metadata", e);
        }
        items.removeIf(item -> LibraryItem.TYPE_DRAFT_SESSION.equals(item.type));
        save();
    }

    /*
     * Function: rename
     * Arguments: item is the LibraryItem to mutate; title is the replacement
     * user-visible label.
     * Calls: save().
     * Flow: trim and store the title, update the timestamp, then rewrite the
     * metadata index.
     */
    void rename(LibraryItem item, String title) throws IOException {
        item.title = title.trim();
        item.updatedAt = System.currentTimeMillis();
        save();
    }

    /*
     * Function: delete
     * Arguments: item is the library record selected for deletion.
     * Calls: deleteFile() for image and thumbnail, then save().
     * Flow: remove the record from memory, delete local files if present, and
     * persist the changed item list.
     */
    void delete(LibraryItem item) throws IOException {
        if (LibraryItem.TYPE_DRAFT_SESSION.equals(item.type)) {
            deleteDraftSession(item.id);
            return;
        }
        items.remove(item);
        deleteFile(item.imageFile());
        deleteFile(item.thumbnailFile());
        save();
    }

    /*
     * Function: describe
     * Arguments: item is the library record to display.
     * Calls: String concatenation only.
     * Flow: produce the multiline metadata text shown by MainActivity's Info
     * dialog.
     */
    String describe(LibraryItem item) {
        return "Title: " + item.title
                + "\nType: " + item.type
                + "\nSource: " + item.source
                + "\nProjection: " + item.projection
                + (LibraryItem.TYPE_DRAFT_SESSION.equals(item.type)
                ? "\nDraft frames: " + listDraftFrames(item.id).size()
                : "")
                + "\nImage: " + item.imagePath
                + "\nThumbnail: " + item.thumbnailPath
                + "\nCreated: " + item.createdAt;
    }

    /*
     * Function: createDraftFrameFile
     * Arguments: none.
     * Calls: mkdir() and File constructor.
     * Flow: ensure the drafts folder exists and return a timestamped JPEG path
     * where CaptureActivity/CameraX can write the next frame.
     */
    File createDraftFrameFile() throws IOException {
        mkdir(draftsDir);
        return new File(draftsDir, "draft-frame-" + System.currentTimeMillis() + ".jpg");
    }

    /*
     * Function: recordDraftFrame
     * Arguments: imageFile is the CameraX-written draft JPEG; sessionId groups
     * frames into one guided capture attempt; locationSummary is an optional
     * lat/long string; heading/pitch/roll approximate the phone orientation;
     * targetYaw/targetPitch identify the guide target being covered; captureMode
     * records whether the frame came from manual or auto capture.
     * Calls: JSONArray/JSONObject parsing and FileOutputStream.
     * Flow: load existing drafts.json if present, append a new path/timestamp/
     * location/orientation/target object, and rewrite the formatted JSON file.
     */
    void recordDraftFrame(
            File imageFile,
            String sessionId,
            String locationSummary,
            float headingDegrees,
            float pitchDegrees,
            float rollDegrees,
            int targetYawDegrees,
            int targetPitchDegrees,
            String captureMode,
            String exposureJson) throws IOException {
        JSONArray array = readDraftMetadataOrThrow();
        long now = System.currentTimeMillis();
        try {
            JSONObject json = new JSONObject();
            json.put("path", imageFile.getAbsolutePath());
            json.put("sessionId", sessionId == null ? "" : sessionId);
            json.put("createdAt", now);
            json.put("location", locationSummary == null ? "" : locationSummary);
            json.put("headingDegrees", headingDegrees);
            json.put("pitchDegrees", pitchDegrees);
            json.put("rollDegrees", rollDegrees);
            json.put("targetYawDegrees", targetYawDegrees);
            json.put("targetPitchDegrees", targetPitchDegrees);
            json.put("captureMode", captureMode == null ? "manual" : captureMode);
            json.put("exposure", parseExposureJson(exposureJson));
            array.put(json);
            writeDraftMetadata(array);
            upsertDraftSessionItem(imageFile, sessionId, now);
        } catch (JSONException e) {
            throw new IOException("could not write draft metadata", e);
        }
    }

    /*
     * Function: deleteDraftSession
     * Arguments: sessionId identifies the draft capture LibraryItem to remove.
     * Calls: readDraftMetadataOrThrow(), deleteFile(), writeDraftMetadata(), and
     * save().
     * Flow: delete all JPEGs and metadata rows for one draft session, then remove
     * its first-class library record.
     */
    private void deleteDraftSession(String sessionId) throws IOException {
        JSONArray source = readDraftMetadataOrThrow();
        JSONArray kept = new JSONArray();
        try {
            for (int i = 0; i < source.length(); i++) {
                JSONObject json = source.getJSONObject(i);
                if (sessionId.equals(json.optString("sessionId", ""))) {
                    deleteFile(new File(json.optString("path", "")));
                } else {
                    kept.put(json);
                }
            }
            writeDraftMetadata(kept);
            items.removeIf(item -> sessionId.equals(item.id)
                    && LibraryItem.TYPE_DRAFT_SESSION.equals(item.type));
            save();
        } catch (JSONException e) {
            throw new IOException("could not delete draft session", e);
        }
    }

    /*
     * Function: upsertDraftSessionItem
     * Arguments: imageFile is the newest/representative draft frame, sessionId
     * groups frames, and now is the record timestamp.
     * Calls: LibraryItem constructor and save().
     * Flow: create a first-class draft-session gallery item when a session first
     * records a frame, or update the existing item timestamp and representative
     * frame path as new frames arrive.
     */
    private void upsertDraftSessionItem(File imageFile, String sessionId, long now) throws IOException {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        for (LibraryItem item : items) {
            if (sessionId.equals(item.id) && LibraryItem.TYPE_DRAFT_SESSION.equals(item.type)) {
                if (!item.imageFile().exists()) {
                    item.imagePath = imageFile.getAbsolutePath();
                    item.thumbnailPath = imageFile.getAbsolutePath();
                }
                item.updatedAt = now;
                save();
                return;
            }
        }
        items.add(new LibraryItem(
                sessionId,
                "Draft Capture " + compactSessionLabel(sessionId),
                LibraryItem.TYPE_DRAFT_SESSION,
                "capture",
                "draft_session",
                "",
                imageFile.getAbsolutePath(),
                imageFile.getAbsolutePath(),
                now,
                now));
        save();
    }

    /*
     * Function: refreshDraftSessionItem
     * Arguments: sessionId is the affected session; metadata is the retained
     * draft-frame index after a deletion.
     * Calls: save().
     * Flow: keep a draft-session LibraryItem pointed at an existing frame, or
     * remove it when the session no longer has any frames.
     */
    private void refreshDraftSessionItem(String sessionId, JSONArray metadata) throws IOException, JSONException {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        File replacement = null;
        for (int i = 0; i < metadata.length(); i++) {
            JSONObject json = metadata.getJSONObject(i);
            if (sessionId.equals(json.optString("sessionId", ""))) {
                File file = new File(json.optString("path", ""));
                if (file.exists()) {
                    replacement = file;
                    break;
                }
            }
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            LibraryItem item = items.get(i);
            if (sessionId.equals(item.id) && LibraryItem.TYPE_DRAFT_SESSION.equals(item.type)) {
                if (replacement == null) {
                    items.remove(i);
                } else {
                    item.imagePath = replacement.getAbsolutePath();
                    item.thumbnailPath = replacement.getAbsolutePath();
                    item.updatedAt = System.currentTimeMillis();
                }
            }
        }
        save();
    }

    private static String compactSessionLabel(String sessionId) {
        return sessionId.length() <= 12 ? sessionId : sessionId.substring(sessionId.length() - 12);
    }

    private static JSONObject parseExposureJson(String exposureJson) throws JSONException {
        if (exposureJson == null || exposureJson.trim().isEmpty()) {
            JSONObject json = new JSONObject();
            json.put("available", false);
            json.put("reason", "metadata unavailable");
            return json;
        }
        return new JSONObject(exposureJson);
    }

    /*
     * Function: readDraftMetadata
     * Arguments: none.
     * Calls: readDraftMetadataOrThrow().
     * Flow: best-effort draft metadata read for non-mutating UI lists; corrupt
     * metadata behaves like an empty set so the app can still show filesystem
     * fallback frames.
     */
    private JSONArray readDraftMetadata() {
        try {
            return readDraftMetadataOrThrow();
        } catch (IOException ignored) {
            return new JSONArray();
        }
    }

    /*
     * Function: readDraftMetadataOrThrow
     * Arguments: none.
     * Calls: FileInputStream and JSONArray parser.
     * Flow: read the draft-frame index or return an empty array when it has not
     * been created yet.
     */
    private JSONArray readDraftMetadataOrThrow() throws IOException {
        if (!draftMetadataFile.exists()) {
            return new JSONArray();
        }
        try (FileInputStream input = new FileInputStream(draftMetadataFile)) {
            byte[] data = new byte[(int) draftMetadataFile.length()];
            int read = input.read(data);
            return read > 0
                    ? new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8))
                    : new JSONArray();
        } catch (JSONException e) {
            throw new IOException("draft metadata is corrupt", e);
        }
    }

    /*
     * Function: writeDraftMetadata
     * Arguments: metadata is the full draft-frame index to persist.
     * Calls: FileOutputStream and JSONArray.toString().
     * Flow: rewrite drafts.json in one pass after an append or deletion.
     */
    private void writeDraftMetadata(JSONArray metadata) throws IOException, JSONException {
        try (FileOutputStream output = new FileOutputStream(draftMetadataFile)) {
            output.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    /*
     * Function: ensureDirs
     * Arguments: none.
     * Calls: mkdir() for each managed folder.
     * Flow: create the library root and all content subdirectories before any
     * import, export, draft, thumbnail, or metadata operation runs.
     */
    private void ensureDirs() throws IOException {
        mkdir(root);
        mkdir(mastersDir);
        mkdir(variantsDir);
        mkdir(draftsDir);
        mkdir(thumbnailsDir);
    }

    /*
     * Function: load
     * Arguments: none.
     * Calls: FileInputStream, JSONArray parser, and LibraryItem.fromJson().
     * Flow: clear the in-memory list, read metadata.json if it exists, decode
     * each JSON object into a LibraryItem, and surface corrupt JSON as IOException.
     */
    private void load() throws IOException {
        items.clear();
        if (!metadataFile.exists()) {
            return;
        }
        String text;
        try (FileInputStream input = new FileInputStream(metadataFile)) {
            byte[] data = new byte[(int) metadataFile.length()];
            int read = input.read(data);
            text = new String(data, 0, Math.max(0, read), StandardCharsets.UTF_8);
        }
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                items.add(LibraryItem.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException e) {
            throw new IOException("library metadata is corrupt", e);
        }
    }

    /*
     * Function: save
     * Arguments: none.
     * Calls: LibraryItem.toJson(), JSONArray.toString(), and FileOutputStream.
     * Flow: encode every in-memory item into a JSON array and rewrite
     * metadata.json atomically from the app's point of view.
     */
    private void save() throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (LibraryItem item : items) {
                array.put(item.toJson());
            }
        } catch (JSONException e) {
            throw new IOException("could not encode library metadata", e);
        }
        try (FileOutputStream output = new FileOutputStream(metadataFile)) {
            output.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("could not format library metadata", e);
        }
    }

    /*
     * Function: makeThumbnail
     * Arguments: imageFile is the source image; id names the thumbnail file.
     * Calls: BitmapFactory.decodeFile(), Bitmap.createScaledBitmap(), and
     * Bitmap.compress().
     * Flow: decode the source image, scale it to THUMBNAIL_SIZE square, write a
     * JPEG thumbnail, recycle temporary bitmaps, and return the thumbnail File.
     */
    private File makeThumbnail(File imageFile, String id) throws IOException {
        Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
        if (bitmap == null) {
            throw new IOException("could not decode image for thumbnail");
        }
        Bitmap thumbnail = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true);
        File thumbnailFile = new File(thumbnailsDir, id + ".jpg");
        try (FileOutputStream output = new FileOutputStream(thumbnailFile)) {
            if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 86, output)) {
                throw new IOException("could not write thumbnail");
            }
        } finally {
            thumbnail.recycle();
            bitmap.recycle();
        }
        return thumbnailFile;
    }

    /*
     * Function: saveToMediaStore
     * Arguments: imageFile is a local PNG; title becomes a sanitized display name.
     * Calls: ContentResolver.insert/openOutputStream/update/delete and copy().
     * Flow: on Android Q+, create a pending image row in Pictures/Spherify, copy
     * bytes into it, mark it complete, or delete the row if copying fails.
     */
    private void saveToMediaStore(File imageFile, String title) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, title.replaceAll("[^a-zA-Z0-9_-]+", "-") + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Spherify");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return;
        }
        try (InputStream input = new FileInputStream(imageFile);
             OutputStream output = resolver.openOutputStream(uri)) {
            if (output != null) {
                copy(input, output);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } catch (IOException ignored) {
            resolver.delete(uri, null, null);
        }
    }

    /*
     * Function: mkdir
     * Arguments: file is the directory to ensure.
     * Calls: File.exists() and File.mkdirs().
     * Flow: create a directory tree if absent, otherwise throw IOException so the
     * caller can stop before writing into a missing path.
     */
    private static void mkdir(File file) throws IOException {
        if (!file.exists() && !file.mkdirs()) {
            throw new IOException("could not create " + file.getAbsolutePath());
        }
    }

    /*
     * Function: copy(InputStream, File)
     * Arguments: input supplies bytes; destination is the target file.
     * Calls: FileOutputStream constructor and copy(InputStream, OutputStream).
     * Flow: open an output stream for the destination and delegate byte copying
     * to the shared stream-to-stream helper.
     */
    private static void copy(InputStream input, File destination) throws IOException {
        try (OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
        }
    }

    /*
     * Function: copy(File, File)
     * Arguments: source and destination are filesystem paths.
     * Calls: FileInputStream, FileOutputStream, and copy(InputStream, OutputStream).
     * Flow: open streams for both files and delegate the buffered copy loop.
     */
    private static void copy(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
        }
    }

    /*
     * Function: copy(InputStream, OutputStream)
     * Arguments: input is any readable stream; output is any writable stream.
     * Calls: InputStream.read() and OutputStream.write().
     * Flow: transfer bytes in 8 KB chunks until read() returns end-of-stream.
     */
    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    /*
     * Function: deleteFile
     * Arguments: file is the path to remove if it exists.
     * Calls: File.exists() and File.delete().
     * Flow: best-effort deletion used for local image and thumbnail cleanup.
     */
    private static void deleteFile(File file) {
        if (file.exists()) {
            file.delete();
        }
    }

    /*
     * Function: newId
     * Arguments: none.
     * Calls: UUID.randomUUID() and String.replace().
     * Flow: create a compact id without dashes for file names and metadata keys.
     */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
