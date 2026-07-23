/*
 * SpherifyLibrary.java
 *
 * Educational overview:
 * SpherifyLibrary is the app's local persistence layer. Activities ask it to
 * import images, create bundled demo content, save exported variants, list
 * gallery items, rename/delete entries, create/delete draft capture files, and
 * record draft metadata. The class deliberately keeps storage details out of
 * MainActivity and the capture screens so those UI classes can focus on flow.
 *
 * Data flow:
 * External/bundled image input -> copied into app-private library folders ->
 * thumbnail generated -> LibraryItem added to the in-memory list -> metadata
 * written to metadata.json. For exports, GLProjectionView creates temporary
 * files and MainActivity passes a ProjectionExport here; this class copies the
 * files into the library and optionally publishes the image to Android
 * MediaStore. For capture drafts, the active capture backend asks for a destination JPEG
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
 * Date labels create friendly ids for image records.
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
import android.graphics.Matrix;
import android.media.ExifInterface;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private final File captureSessionsFile;
    private final ArrayList<LibraryItem> items = new ArrayList<>();

    interface ProgressReporter {
        void onProgress(String stepKey, boolean complete, String message);
    }

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
        captureSessionsFile = new File(root, "capture-sessions.json");
        ensureDirs();
        load();
        if (reconcileCaptureSessionItems() | reconcileDraftSessionItems()) {
            save();
        }
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
        String id = newId(now);
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
        String id = newId(now);
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
        String label = friendlyDateLabel(now);
        String title = "flat".equals(projection)
                ? "Imported Flat " + label
                : "tinyplanet".equals(projection)
                ? "Imported Tiny Planet " + label
                : "Imported PhotoSphere " + label;
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
        String id = newId(now);
        File imageFile = new File(variantsDir, id + ".png");
        File thumbnailFile = new File(thumbnailsDir, id + ".jpg");
        copy(export.imageFile, imageFile);
        copy(export.thumbnailFile, thumbnailFile);

        LibraryItem item = new LibraryItem(
                id,
                (projection.equals("tinyplanet") ? "Tiny Planet Variant " : "PhotoSphere Variant ") + friendlyDateLabel(now),
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

    LibraryItem saveFullResolutionPhotoSphereVariant(LibraryItem parent) throws IOException {
        if (parent == null || parent.imagePath == null || parent.imagePath.isEmpty()) {
            throw new IOException("select a PhotoSphere first");
        }
        File sourceFile = parent.imageFile();
        if (!sourceFile.exists()) {
            throw new IOException("source image is missing");
        }
        long now = System.currentTimeMillis();
        String id = newId(now);
        String extension = fileExtension(sourceFile.getName());
        File imageFile = new File(variantsDir, id + extension);
        copy(sourceFile, imageFile);
        File thumbnailFile = makeThumbnail(imageFile, id);

        LibraryItem item = new LibraryItem(
                id,
                "Full PhotoSphere Export " + friendlyDateLabel(now),
                LibraryItem.TYPE_VARIANT,
                "local",
                "sphere",
                parent.id,
                imageFile.getAbsolutePath(),
                thumbnailFile.getAbsolutePath(),
                now,
                now);
        items.add(item);
        saveToMediaStore(imageFile, item.title);
        save();
        return item;
    }

    File createFullResolutionPhotoSphereShareFile(LibraryItem item) throws IOException {
        if (item == null || item.imagePath == null || item.imagePath.isEmpty()) {
            throw new IOException("select a PhotoSphere first");
        }
        File sourceFile = item.imageFile();
        if (!sourceFile.exists()) {
            throw new IOException("source image is missing");
        }
        File directory = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Spherify");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("could not create export directory");
        }
        File destination = new File(
                directory,
                "photosphere-full-" + friendlyDateLabel(System.currentTimeMillis()) + fileExtension(sourceFile.getName()));
        copy(sourceFile, destination);
        return destination;
    }

    void updateTinyPlanetCenter(LibraryItem item, float centerX, float centerY) throws IOException {
        if (item == null) {
            throw new IOException("select a Tiny Planet first");
        }
        item.tinyPlanetCenterX = clamp01(centerX);
        item.tinyPlanetCenterY = clamp01(centerY);
        item.updatedAt = System.currentTimeMillis();
        save();
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
            if ((item.imageFile().exists() || isRecoverableCaptureSessionItem(item)) && item.matchesFilter(filter)) {
                result.add(item);
            }
        }
        Collections.sort(result, Comparator.comparingLong((LibraryItem item) -> item.updatedAt).reversed());
        return result;
    }

    /*
     * Function: refresh
     * Arguments: none.
     * Calls: load(), reconcileDraftSessionItems(), and save().
     * Flow: reload persisted library metadata after another Activity has written
     * draft frames, then rebuild any missing first-class pending session records
     * from drafts.json so Browse stays in sync with the raw frame index.
     */
    void refresh() throws IOException {
        load();
        if (reconcileCaptureSessionItems() | reconcileDraftSessionItems()) {
            save();
        }
    }

    CaptureSessionRecord createCaptureSession(CaptureMode captureMode, JSONObject readiness) throws IOException {
        long now = System.currentTimeMillis();
        String id = newId(now);
        CaptureSessionRecord session = new CaptureSessionRecord(
                id,
                "Capture Session " + compactSessionLabel(id),
                now,
                now,
                captureMode == null ? CaptureMode.HAND_HELD : captureMode,
                readinessPasses(readiness) ? SessionStatus.READY : SessionStatus.NEW,
                false,
                readiness,
                new ArrayList<>(),
                new ArrayList<>());
        ArrayList<CaptureSessionRecord> sessions = readCaptureSessions();
        sessions.add(session);
        writeCaptureSessions(sessions);
        upsertCaptureSessionItem(session, null);
        return session;
    }

    CaptureSessionRecord ensureCaptureSession(String sessionId, CaptureMode captureMode, JSONObject readiness) throws IOException {
        if (sessionId == null || sessionId.isEmpty()) {
            return createCaptureSession(captureMode, readiness);
        }
        ArrayList<CaptureSessionRecord> sessions = readCaptureSessions();
        for (CaptureSessionRecord session : sessions) {
            if (sessionId.equals(session.id)) {
                session.captureMode = captureMode == null ? session.captureMode : captureMode;
                session.readiness = readiness == null ? session.readiness : readiness;
                if (session.status == SessionStatus.NEW && readinessPasses(session.readiness)) {
                    session.status = SessionStatus.READY;
                }
                session.updatedAt = System.currentTimeMillis();
                writeCaptureSessions(sessions);
                upsertCaptureSessionItem(session, firstExistingFrame(session));
                return session;
            }
        }
        long now = System.currentTimeMillis();
        CaptureSessionRecord session = new CaptureSessionRecord(
                sessionId,
                "Capture Session " + compactSessionLabel(sessionId),
                now,
                now,
                captureMode == null ? CaptureMode.HAND_HELD : captureMode,
                readinessPasses(readiness) ? SessionStatus.READY : SessionStatus.NEW,
                false,
                readiness,
                new ArrayList<>(),
                new ArrayList<>());
        sessions.add(session);
        writeCaptureSessions(sessions);
        upsertCaptureSessionItem(session, null);
        return session;
    }

    void updateCaptureSessionReadiness(String sessionId, JSONObject readiness, boolean capturing) throws IOException {
        ArrayList<CaptureSessionRecord> sessions = readCaptureSessions();
        boolean changed = false;
        for (CaptureSessionRecord session : sessions) {
            if (session.id.equals(sessionId)) {
                session.readiness = readiness == null ? new JSONObject() : readiness;
                if (capturing) {
                    session.status = SessionStatus.CAPTURING;
                } else if (session.status == SessionStatus.NEW
                        || session.status == SessionStatus.READY
                        || session.status == SessionStatus.CAPTURING) {
                    session.status = readinessPasses(readiness) ? SessionStatus.READY : SessionStatus.NEW;
                }
                session.updatedAt = System.currentTimeMillis();
                changed = true;
                break;
            }
        }
        if (changed) {
            writeCaptureSessions(sessions);
        }
    }

    CaptureSessionRecord findCaptureSession(String sessionId) {
        for (CaptureSessionRecord session : readCaptureSessions()) {
            if (session.id.equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    CaptureSessionRecord latestCaptureSession() {
        CaptureSessionRecord latest = null;
        for (CaptureSessionRecord session : readCaptureSessions()) {
            if (latest == null || session.updatedAt > latest.updatedAt) {
                latest = session;
            }
        }
        return latest;
    }

    String describeCaptureSessionDiagnostics(String sessionId) {
        CaptureSessionRecord session = findCaptureSession(sessionId);
        if (session == null) {
            return "No 0.4.2 guided capture-session record exists yet.\n\nThis is probably an older drafts.json compatibility session.";
        }
        StringBuilder message = new StringBuilder();
        message.append("Format: 0.4.2 guided capture session")
                .append("\nSession: ").append(session.id)
                .append("\nMode: ").append(session.captureMode.label)
                .append("\nStatus: ").append(session.status.storageValue)
                .append("\nDiagnostic export: ").append(session.diagnosticSpherifyAllowed ? "allowed" : "off")
                .append("\nFrames: source ").append(session.countFrames(CaptureFrameRole.SOURCE))
                .append(", candidates ").append(session.countFrames(CaptureFrameRole.CANDIDATE))
                .append(", accepted ").append(session.countFrames(CaptureFrameRole.ACCEPTED))
                .append(", rejected ").append(session.countFrames(CaptureFrameRole.REJECTED))
                .append("\nGraph edges: ").append(session.graphEdges.size())
                .append("\n\nReadiness\n")
                .append(formatJsonObject(session.readiness));
        if (session.frames.isEmpty()) {
            message.append("\n\nFrames\nNo frames recorded yet. Missing targets are the unpainted guide positions.");
            return message.toString();
        }
        message.append("\n\nFrames");
        for (CaptureFrameRecord frame : session.frames) {
            message.append("\n\n").append(frame.id)
                    .append(" [").append(frame.role.storageValue).append("]")
                    .append("\nRaw path: ").append(frame.rawFacts.filePath)
                    .append("\nRaw target: yaw ").append(frame.rawFacts.targetYawDegrees)
                    .append(", pitch ").append(frame.rawFacts.targetPitchDegrees)
                    .append("\nRaw pose: yaw ").append(formatOne(frame.rawFacts.capturedYawDegrees))
                    .append(", pitch ").append(formatOne(frame.rawFacts.capturedPitchDegrees))
                    .append(", roll ").append(formatOne(frame.rawFacts.capturedRollDegrees))
                    .append("\nRaw location: ").append(emptyAsMissing(frame.rawFacts.locationSummary))
                    .append("\nRaw intrinsics: ").append(frame.rawFacts.intrinsics.optBoolean("available", false) ? "available" : "missing")
                    .append("\nRaw exposure/focus/WB: ").append(frame.rawFacts.exposure.optBoolean("available", false) ? "available" : "missing")
                    .append("\nAnalysis blur/exposure/texture: ")
                    .append(formatScore(frame.analysisFacts.blurScore)).append(" / ")
                    .append(formatScore(frame.analysisFacts.exposureScore)).append(" / ")
                    .append(formatScore(frame.analysisFacts.textureScore))
                    .append("\nAnalysis overlap: ").append(frame.analysisFacts.predictedOverlapSet.length())
                    .append(" predicted, RANSAC ").append(frame.analysisFacts.opencvRansacResult)
                    .append(", inliers ").append(frame.analysisFacts.inlierCount)
                    .append(", residual ").append(formatScore(frame.analysisFacts.residualScore))
                    .append(", confidence ").append(formatScore(frame.analysisFacts.confidence))
                    .append("\nWeak/recapture hint: ").append(emptyAsMissing(frame.analysisFacts.parallaxRiskHint))
                    .append("\nRejection: ").append(emptyAsMissing(frame.analysisFacts.rejectionReason));
        }
        return message.toString();
    }

    /*
     * Function: listDraftFrames
     * Arguments: none.
     * Calls: File.listFiles() and Collections.sort().
     * Flow: scan the drafts directory for JPEG files written by capture and
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
     * Function: listDraftFrameRecords
     * Arguments: sessionId identifies one draft capture session.
     * Calls: readDraftMetadata(), JSONObject accessors, and File.exists().
     * Flow: translate the JSON draft index into typed Phase 5 frame records so
     * stitching can use orientation, target, capture, and exposure fields without
     * re-parsing metadata at every call site.
     */
    List<DraftFrameRecord> listDraftFrameRecords(String sessionId) {
        ArrayList<DraftFrameRecord> result = new ArrayList<>();
        JSONArray metadata = readDraftMetadata();
        for (int i = 0; i < metadata.length(); i++) {
            JSONObject json = metadata.optJSONObject(i);
            if (json == null || !sessionId.equals(json.optString("sessionId", ""))) {
                continue;
            }
            File file = new File(json.optString("path", ""));
            if (!file.exists()) {
                continue;
            }
            JSONObject exposure = json.optJSONObject("exposure");
            float lensFocalLengthMm = exposure == null || exposure.isNull("lensFocalLengthMm")
                    ? 0f
                    : (float) exposure.optDouble("lensFocalLengthMm", 0.0);
            float sensorPhysicalWidthMm = exposure == null || exposure.isNull("sensorPhysicalWidthMm")
                    ? 0f
                    : (float) exposure.optDouble("sensorPhysicalWidthMm", 0.0);
            float sensorPhysicalHeightMm = exposure == null || exposure.isNull("sensorPhysicalHeightMm")
                    ? 0f
                    : (float) exposure.optDouble("sensorPhysicalHeightMm", 0.0);
            float imageFocalLengthXPixels = exposure == null || exposure.isNull("imageFocalLengthXPixels")
                    ? 0f
                    : (float) exposure.optDouble("imageFocalLengthXPixels", 0.0);
            float imageFocalLengthYPixels = exposure == null || exposure.isNull("imageFocalLengthYPixels")
                    ? 0f
                    : (float) exposure.optDouble("imageFocalLengthYPixels", 0.0);
            float imagePrincipalPointXPixels = exposure == null || exposure.isNull("imagePrincipalPointXPixels")
                    ? 0f
                    : (float) exposure.optDouble("imagePrincipalPointXPixels", 0.0);
            float imagePrincipalPointYPixels = exposure == null || exposure.isNull("imagePrincipalPointYPixels")
                    ? 0f
                    : (float) exposure.optDouble("imagePrincipalPointYPixels", 0.0);
            int imageIntrinsicsWidth = exposure == null || exposure.isNull("imageIntrinsicsWidth")
                    ? 0
                    : exposure.optInt("imageIntrinsicsWidth", 0);
            int imageIntrinsicsHeight = exposure == null || exposure.isNull("imageIntrinsicsHeight")
                    ? 0
                    : exposure.optInt("imageIntrinsicsHeight", 0);
            result.add(new DraftFrameRecord(
                    file,
                    json.optString("sessionId", ""),
                    json.optLong("createdAt", file.lastModified()),
                    json.optString("location", ""),
                    (float) json.optDouble("headingDegrees", 0.0),
                    (float) json.optDouble("pitchDegrees", 0.0),
                    (float) json.optDouble("rollDegrees", 0.0),
                    json.has("headingDegrees") && json.has("pitchDegrees") && json.has("rollDegrees"),
                    json.optString("captureProfile", "handheld"),
                    json.optInt("targetYawDegrees", 0),
                    json.optInt("targetPitchDegrees", 0),
                    json.optString("captureMode", "manual"),
                    exposure != null && exposure.optBoolean("available", false),
                    lensFocalLengthMm,
                    sensorPhysicalWidthMm,
                    sensorPhysicalHeightMm,
                    imageFocalLengthXPixels,
                    imageFocalLengthYPixels,
                    imagePrincipalPointXPixels,
                    imagePrincipalPointYPixels,
                    imageIntrinsicsWidth,
                    imageIntrinsicsHeight));
        }
        Collections.sort(result, Comparator.comparingLong(record -> record.createdAt));
        return result;
    }

    /*
     * Function: createMasterFromCaptureSession
     * Arguments: sessionId identifies the active guided capture graph.
     * Calls: listDraftFrameRecords(), Phase5Stitcher.stitch(), makeThumbnail(),
     * LibraryItem constructor, and save().
     * Flow: finish the single capture-and-spherification workflow by consuming
     * the validated capture graph, rendering an equirectangular PhotoSphere,
     * adding the master image to the local library, and returning the result to
     * the capture activity.
     */
    StitchMasterResult createMasterFromCaptureSession(
            String sessionId,
            String movementSensitivity,
            String renderMode,
            ProgressReporter progress) throws IOException {
        LibraryItem captureSessionItem = findDraftSessionItem(sessionId);
        if (captureSessionItem == null) {
            CaptureSessionRecord session = findCaptureSession(sessionId);
            if (session == null) {
                throw new IOException("Spherify 0.4.2 requires a validated guided capture graph; no capture-session record was found.");
            }
            upsertCaptureSessionItem(session, firstExistingFrame(session));
            captureSessionItem = findDraftSessionItem(sessionId);
        }
        if (captureSessionItem == null) {
            throw new IOException("could not resolve capture session for PhotoSphere export");
        }
        return createMasterFromDraftSession(captureSessionItem, movementSensitivity, renderMode, progress);
    }

    /*
     * Function: createMasterFromDraftSession
     * Arguments: draftSession is the compatibility LibraryItem that backs one
     * guided capture session.
     * Calls: listDraftFrameRecords(), Phase5Stitcher.stitch(), makeThumbnail(),
     * LibraryItem constructor, and save().
     * Flow: shared implementation for the integrated capture workflow; the
     * persisted type name remains for older metadata, but this is no longer a
     * separate user-facing "draft then spherify" product path.
     */
    StitchMasterResult createMasterFromDraftSession(
            LibraryItem draftSession,
            String movementSensitivity,
            String renderMode) throws IOException {
        return createMasterFromDraftSession(draftSession, movementSensitivity, renderMode, null);
    }

    StitchMasterResult createMasterFromDraftSession(
            LibraryItem draftSession,
            String movementSensitivity,
            String renderMode,
            ProgressReporter progress) throws IOException {
        if (draftSession == null || !LibraryItem.TYPE_DRAFT_SESSION.equals(draftSession.type)) {
            throw new IOException("select a capture session first");
        }
        CaptureSessionRecord captureSession = findCaptureSession(draftSession.id);
        if (captureSession == null) {
            throw new IOException("Spherify 0.4.2 requires a validated guided capture graph; this older draft session has no graph record.");
        }
        GraphReadinessReport graphReadiness = validateCaptureGraphReadiness(captureSession);
        if (!graphReadiness.pass) {
            throw new IOException(graphReadiness.failureMessage());
        }
        report(progress, "quality", false, "Running Stage 5A input session integrity checks");
        List<DraftFrameRecord> records = listDraftFrameRecords(draftSession.id);
        if (records.isEmpty()) {
            throw new IOException("capture session has no readable source frames");
        }
        DraftQualityReport qualityReport = validateDraftStitchQuality(records);
        if (!qualityReport.pass) {
            throw new IOException(qualityReport.failureMessage());
        }
        report(progress, "quality", true, String.format(
                "Quality and graph gates passed: %d frames, pose %d/%d, intrinsics %d/%d, graph edges %d/%d",
                qualityReport.readableFrames,
                qualityReport.poseFrames,
                qualityReport.readableFrames,
                qualityReport.intrinsicsFrames,
                qualityReport.readableFrames,
                graphReadiness.acceptedEdges,
                graphReadiness.totalEdges));

        long now = System.currentTimeMillis();
        String id = newId(now);
        File imageFile = new File(mastersDir, id + ".jpg");
        Phase5Stitcher.Result stitch = Phase5Stitcher.stitch(captureSession, imageFile, movementSensitivity, renderMode, progress);
        report(progress, "library", false, "Creating thumbnail and library master record");
        File thumbnailFile = makeThumbnail(imageFile, id);
        LibraryItem item = new LibraryItem(
                id,
                "Stitched PhotoSphere " + friendlyDateLabel(now),
                LibraryItem.TYPE_MASTER,
                "phase5_stitch",
                "sphere",
                draftSession.id,
                imageFile.getAbsolutePath(),
                thumbnailFile.getAbsolutePath(),
                now,
                now);
        items.add(item);
        save();
        report(progress, "library", true, "Saved master to the local Spherify library");
        return new StitchMasterResult(item, stitch);
    }

    private static void report(ProgressReporter progress, String stepKey, boolean complete, String message) {
        if (progress != null) {
            progress.onProgress(stepKey, complete, message);
        }
    }

    DraftQualityReport assessDraftStitchQuality(LibraryItem draftSession) throws IOException {
        if (draftSession == null || !LibraryItem.TYPE_DRAFT_SESSION.equals(draftSession.type)) {
            throw new IOException("select a capture session first");
        }
        DraftQualityReport draftQuality = validateDraftStitchQuality(listDraftFrameRecords(draftSession.id));
        CaptureSessionRecord captureSession = findCaptureSession(draftSession.id);
        GraphReadinessReport graphQuality = captureSession == null
                ? GraphReadinessReport.missingSession()
                : validateCaptureGraphReadiness(captureSession);
        return draftQuality.withGraphReadiness(graphQuality);
    }

    private static GraphReadinessReport validateCaptureGraphReadiness(CaptureSessionRecord session) {
        if (session == null) {
            return GraphReadinessReport.missingSession();
        }
        ArrayList<String> acceptedFrameIds = new ArrayList<>();
        for (CaptureFrameRecord frame : session.frames) {
            if (frame.role == CaptureFrameRole.ACCEPTED && new File(frame.rawFacts.filePath).exists()) {
                acceptedFrameIds.add(frame.id);
            }
        }
        ArrayList<String> blockers = new ArrayList<>();
        if (acceptedFrameIds.size() < 30) {
            blockers.add("graph needs at least 30 readable accepted guided frames; found " + acceptedFrameIds.size());
        }
        HashMap<String, Integer> componentIndexes = new HashMap<>();
        for (String frameId : acceptedFrameIds) {
            componentIndexes.put(frameId, componentIndexes.size());
        }
        int acceptedEdges = 0;
        int rejectedEdges = 0;
        for (CaptureGraphEdgeRecord edge : session.graphEdges) {
            if (!componentIndexes.containsKey(edge.fromFrameId) || !componentIndexes.containsKey(edge.toFrameId)) {
                rejectedEdges++;
                continue;
            }
            if (!graphEdgeLooksSolvable(edge)) {
                rejectedEdges++;
                continue;
            }
            acceptedEdges++;
            union(componentIndexes, edge.fromFrameId, edge.toFrameId);
        }
        if (acceptedEdges < Math.max(acceptedFrameIds.size() - 1, 29)) {
            blockers.add("graph is too sparse after filtering; usable edges " + acceptedEdges + "/" + session.graphEdges.size());
        }
        String root = acceptedFrameIds.isEmpty() ? "" : find(componentIndexes, acceptedFrameIds.get(0));
        for (String frameId : acceptedFrameIds) {
            if (!root.equals(find(componentIndexes, frameId))) {
                blockers.add("accepted frame graph is disconnected after weak/parallax edges are removed");
                break;
            }
        }
        return new GraphReadinessReport(blockers.isEmpty(), acceptedFrameIds.size(), session.graphEdges.size(), acceptedEdges, rejectedEdges, blockers);
    }

    private static boolean graphEdgeLooksSolvable(CaptureGraphEdgeRecord edge) {
        if (edge.confidence < 0.28 || edge.inlierCount < 12 || edge.controlPoints.length() < 6) {
            return false;
        }
        if (edge.residualScore > 55.0) {
            return false;
        }
        String parallax = edge.parallaxRiskHint.toLowerCase(Locale.US);
        return !parallax.contains("high") && !parallax.contains("near-object");
    }

    private static void union(HashMap<String, Integer> componentIndexes, String first, String second) {
        Integer firstComponent = componentIndexes.get(first);
        Integer secondComponent = componentIndexes.get(second);
        if (firstComponent == null || secondComponent == null || firstComponent.equals(secondComponent)) {
            return;
        }
        int replacement = Math.min(firstComponent, secondComponent);
        int removed = Math.max(firstComponent, secondComponent);
        for (Map.Entry<String, Integer> entry : componentIndexes.entrySet()) {
            if (entry.getValue() == removed) {
                entry.setValue(replacement);
            }
        }
    }

    private static String find(HashMap<String, Integer> componentIndexes, String frameId) {
        Integer component = componentIndexes.get(frameId);
        return component == null ? "" : String.valueOf(component);
    }

    private static DraftQualityReport validateDraftStitchQuality(List<DraftFrameRecord> records) {
        int readableFrames = 0;
        int poseFrames = 0;
        int intrinsicsFrames = 0;
        int nonGuidedFrames = 0;
        int horizon = 0;
        int upper30 = 0;
        int lower30 = 0;
        int upperHigh = 0;
        int lowerHigh = 0;
        int anchorPitch = 0;
        boolean anchorPitchSet = false;
        boolean handheld = false;
        for (DraftFrameRecord record : records) {
            if (!record.imageFile.exists()) {
                continue;
            }
            if (!anchorPitchSet) {
                anchorPitch = record.targetPitchDegrees;
                anchorPitchSet = true;
            }
            readableFrames++;
            if (record.capturedPoseAvailable) {
                poseFrames++;
            }
            if (record.imageFocalLengthXPixels > 0f
                    && record.imageFocalLengthYPixels > 0f
                    && record.imageIntrinsicsWidth > 0
                    && record.imageIntrinsicsHeight > 0) {
                intrinsicsFrames++;
            }
            if (!record.captureMode.contains("dot")) {
                nonGuidedFrames++;
            }
            if ("handheld".equals(record.captureProfile)) {
                handheld = true;
            }
            int pitch = record.targetPitchDegrees;
            int relativePitch = pitch - anchorPitch;
            if (Math.abs(relativePitch) <= 10) {
                horizon++;
            } else if (relativePitch >= 20 && relativePitch <= 45) {
                upper30++;
            } else if (relativePitch <= -20 && relativePitch >= -45) {
                lower30++;
            } else if (relativePitch >= 55 || pitch >= 80) {
                upperHigh++;
            } else if (relativePitch <= -55 || pitch <= -80) {
                lowerHigh++;
            }
        }
        ArrayList<String> blockers = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        if (readableFrames < 30) {
            blockers.add("need at least 30 accepted guided stills for an OpenCV sphere solve; found " + readableFrames);
        }
        if (horizon < 8) {
            blockers.add("horizon row needs 8 accepted guided dots; found " + horizon);
        }
        if (upper30 < 8) {
            blockers.add("+35 row needs 8 accepted guided dots; found " + upper30);
        }
        if (lower30 < 8) {
            blockers.add("-35 row needs 8 accepted guided dots; found " + lower30);
        }
        if (upperHigh < 5) {
            blockers.add("upper high/pole coverage needs 5 accepted guided dots; found " + upperHigh);
        }
        if (lowerHigh < 5) {
            blockers.add("lower high/pole coverage needs 5 accepted guided dots; found " + lowerHigh);
        }
        if (poseFrames < readableFrames) {
            blockers.add("rotation-vector pose is missing on " + (readableFrames - poseFrames) + " accepted frames");
        }
        if (intrinsicsFrames < readableFrames) {
            blockers.add("Camera2/CameraCharacteristics intrinsics are missing on " + (readableFrames - intrinsicsFrames) + " accepted frames");
        }
        if (nonGuidedFrames > 0) {
            blockers.add("all production source frames must come from guided dot capture; non-guided frames " + nonGuidedFrames);
        }
        if (handheld && readableFrames > 0) {
            warnings.add("hand-held indoor captures need extra care around furniture and other close objects");
        }
        if (readableFrames >= 30 && readableFrames < 34) {
            warnings.add("capture may be solvable, but completing all 34 targets improves OpenCV matching and GPano confidence");
        }
        return new DraftQualityReport(
                blockers.isEmpty(),
                readableFrames,
                poseFrames,
                intrinsicsFrames,
                nonGuidedFrames,
                horizon,
                upper30,
                lower30,
                upperHigh,
                lowerHigh,
                handheld,
                blockers,
                warnings);
    }

    static final class DraftQualityReport {
        final boolean pass;
        final int readableFrames;
        final int poseFrames;
        final int intrinsicsFrames;
        final int movingOrManualFrames;
        final int horizonFrames;
        final int upper30Frames;
        final int lower30Frames;
        final int upperHighFrames;
        final int lowerHighFrames;
        final boolean handheld;
        final List<String> blockers;
        final List<String> warnings;
        final GraphReadinessReport graphReadiness;

        DraftQualityReport(
                boolean pass,
                int readableFrames,
                int poseFrames,
                int intrinsicsFrames,
                int movingOrManualFrames,
                int horizonFrames,
                int upper30Frames,
                int lower30Frames,
                int upperHighFrames,
                int lowerHighFrames,
                boolean handheld,
                List<String> blockers,
                List<String> warnings) {
            this.pass = pass;
            this.readableFrames = readableFrames;
            this.poseFrames = poseFrames;
            this.intrinsicsFrames = intrinsicsFrames;
            this.movingOrManualFrames = movingOrManualFrames;
            this.horizonFrames = horizonFrames;
            this.upper30Frames = upper30Frames;
            this.lower30Frames = lower30Frames;
            this.upperHighFrames = upperHighFrames;
            this.lowerHighFrames = lowerHighFrames;
            this.handheld = handheld;
            this.blockers = blockers;
            this.warnings = warnings;
            this.graphReadiness = null;
        }

        private DraftQualityReport(
                boolean pass,
                int readableFrames,
                int poseFrames,
                int intrinsicsFrames,
                int movingOrManualFrames,
                int horizonFrames,
                int upper30Frames,
                int lower30Frames,
                int upperHighFrames,
                int lowerHighFrames,
                boolean handheld,
                List<String> blockers,
                List<String> warnings,
                GraphReadinessReport graphReadiness) {
            this.pass = pass && (graphReadiness == null || graphReadiness.pass);
            this.readableFrames = readableFrames;
            this.poseFrames = poseFrames;
            this.intrinsicsFrames = intrinsicsFrames;
            this.movingOrManualFrames = movingOrManualFrames;
            this.horizonFrames = horizonFrames;
            this.upper30Frames = upper30Frames;
            this.lower30Frames = lower30Frames;
            this.upperHighFrames = upperHighFrames;
            this.lowerHighFrames = lowerHighFrames;
            this.handheld = handheld;
            this.blockers = blockers;
            this.warnings = warnings;
            this.graphReadiness = graphReadiness;
        }

        DraftQualityReport withGraphReadiness(GraphReadinessReport graphReadiness) {
            return new DraftQualityReport(
                    pass,
                    readableFrames,
                    poseFrames,
                    intrinsicsFrames,
                    movingOrManualFrames,
                    horizonFrames,
                    upper30Frames,
                    lower30Frames,
                    upperHighFrames,
                    lowerHighFrames,
                    handheld,
                    blockers,
                    warnings,
                    graphReadiness);
        }

        String failureMessage() {
            StringBuilder message = new StringBuilder("capture quality gate failed");
            if (handheld) {
                message.append(" (hand-held indoor captures need extra care)");
            }
            for (String blocker : blockers) {
                message.append("; ").append(blocker);
            }
            if (graphReadiness != null && !graphReadiness.pass) {
                message.append("; ").append(graphReadiness.failureMessage());
            }
            return message.toString();
        }

        String summary() {
            StringBuilder message = new StringBuilder();
            message.append("Frames: ").append(readableFrames)
                    .append("\nPose metadata: ").append(poseFrames).append("/").append(readableFrames)
                    .append("\nCamera intrinsics: ").append(intrinsicsFrames).append("/").append(readableFrames)
                    .append("\nRows: horizon ").append(horizonFrames)
                    .append(", +30 ").append(upper30Frames)
                    .append(", -30 ").append(lower30Frames)
                    .append(", +65 ").append(upperHighFrames)
                    .append(", -65 ").append(lowerHighFrames);
            if (graphReadiness != null) {
                message.append("\nGraph: frames ").append(graphReadiness.acceptedFrames)
                        .append(", usable edges ").append(graphReadiness.acceptedEdges)
                        .append("/")
                        .append(graphReadiness.totalEdges)
                        .append(", rejected ").append(graphReadiness.rejectedEdges);
            }
            if (!blockers.isEmpty()) {
                message.append("\n\nBlockers:");
                for (String blocker : blockers) {
                    message.append("\n- ").append(blocker);
                }
            }
            if (graphReadiness != null && !graphReadiness.blockers.isEmpty()) {
                message.append(blockers.isEmpty() ? "\n\nBlockers:" : "");
                for (String blocker : graphReadiness.blockers) {
                    message.append("\n- ").append(blocker);
                }
            }
            if (!warnings.isEmpty()) {
                message.append("\n\nWarnings:");
                for (String warning : warnings) {
                    message.append("\n- ").append(warning);
                }
            }
            return message.toString();
        }
    }

    static final class GraphReadinessReport {
        final boolean pass;
        final int acceptedFrames;
        final int totalEdges;
        final int acceptedEdges;
        final int rejectedEdges;
        final List<String> blockers;

        GraphReadinessReport(
                boolean pass,
                int acceptedFrames,
                int totalEdges,
                int acceptedEdges,
                int rejectedEdges,
                List<String> blockers) {
            this.pass = pass;
            this.acceptedFrames = acceptedFrames;
            this.totalEdges = totalEdges;
            this.acceptedEdges = acceptedEdges;
            this.rejectedEdges = rejectedEdges;
            this.blockers = blockers;
        }

        static GraphReadinessReport missingSession() {
            ArrayList<String> blockers = new ArrayList<>();
            blockers.add("Spherify 0.4.2 requires a validated guided capture graph; no capture-session record was found");
            return new GraphReadinessReport(false, 0, 0, 0, 0, blockers);
        }

        String failureMessage() {
            StringBuilder message = new StringBuilder("graph-readiness gate failed");
            for (String blocker : blockers) {
                message.append("; ").append(blocker);
            }
            return message.toString();
        }
    }

    /*
     * Function: deleteDraftFrame
     * Arguments: imageFile is a captured draft JPEG selected from the Draft Frames
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
        writeCaptureSessions(new ArrayList<>());
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
                + ("tinyplanet".equals(item.projection)
                ? String.format(
                java.util.Locale.US,
                "\nTiny Planet center: %.1f%% x, %.1f%% y",
                item.tinyPlanetCenterX * 100f,
                item.tinyPlanetCenterY * 100f)
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
     * where the active capture backend can write the next frame.
     */
    File createDraftFrameFile() throws IOException {
        mkdir(draftsDir);
        return new File(draftsDir, friendlyDateLabel(System.currentTimeMillis()) + ".jpg");
    }

    /*
     * Function: recordDraftFrame
     * Arguments: imageFile is the captured draft JPEG; sessionId groups frames
     * into one guided capture attempt; locationSummary is an optional lat/long
     * string; heading/pitch/roll record the measured capture orientation;
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
            String captureProfile,
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
            json.put("captureProfile", normalizeCaptureProfile(captureProfile));
            JSONObject exposure = parseExposureJson(exposureJson);
            String metadataBlocker = requiredCaptureMetadataBlocker(exposure);
            if (metadataBlocker != null) {
                throw new IOException(metadataBlocker);
            }
            json.put("exposure", exposure);
            array.put(json);
            writeDraftMetadata(array);
            recordCaptureSessionFrame(
                    imageFile,
                    sessionId,
                    locationSummary,
                    headingDegrees,
                    pitchDegrees,
                    rollDegrees,
                    targetYawDegrees,
                    targetPitchDegrees,
                    captureMode,
                    captureProfile,
                    exposure,
                    now);
            upsertDraftSessionItem(imageFile, sessionId, now);
        } catch (JSONException e) {
            throw new IOException("could not write draft metadata", e);
        }
    }

    int frameGuidedHorizontalTargetYawDegrees(
            String sessionId,
            int nominalTargetYawDegrees,
            int targetPitchDegrees,
            float capturedYawDegrees) {
        CaptureSessionRecord session = findCaptureSession(sessionId);
        if (session == null) {
            return Math.round(normalizeHeading(capturedYawDegrees));
        }
        CaptureFrameRecord nearest = null;
        float nearestDelta = Float.MAX_VALUE;
        for (CaptureFrameRecord frame : session.frames) {
            if (frame.role != CaptureFrameRole.ACCEPTED || !new File(frame.rawFacts.filePath).exists()) {
                continue;
            }
            float pitchDelta = Math.abs(targetPitchDegrees - frame.rawFacts.targetPitchDegrees);
            if (pitchDelta > 18f) {
                continue;
            }
            float yawDelta = Math.abs(signedHeadingDelta(capturedYawDegrees, frameCenterYawDegrees(frame)));
            if (yawDelta < nearestDelta) {
                nearestDelta = yawDelta;
                nearest = frame;
            }
        }
        if (nearest == null) {
            return Math.round(normalizeHeading(capturedYawDegrees));
        }
        float nearestYaw = frameCenterYawDegrees(nearest);
        float capturedDelta = signedHeadingDelta(capturedYawDegrees, nearestYaw);
        float nominalDelta = signedHeadingDelta(nominalTargetYawDegrees, nearestYaw);
        float direction = Math.abs(capturedDelta) >= 2f ? Math.signum(capturedDelta) : Math.signum(nominalDelta);
        if (direction == 0f) {
            return Math.round(normalizeHeading(capturedYawDegrees));
        }
        float step = Math.min(Math.abs(capturedDelta), idealHorizontalStepDegrees(nearest));
        return Math.round(normalizeHeading(nearestYaw + direction * Math.max(8f, step)));
    }

    List<CaptureFrameRecord> predictedAcceptedNeighbors(String sessionId, int targetYawDegrees, int targetPitchDegrees) {
        ArrayList<CaptureFrameRecord> neighbors = new ArrayList<>();
        CaptureSessionRecord session = findCaptureSession(sessionId);
        if (session == null) {
            return neighbors;
        }
        for (CaptureFrameRecord frame : session.frames) {
            if (frame.role != CaptureFrameRole.ACCEPTED) {
                continue;
            }
            File file = new File(frame.rawFacts.filePath);
            if (!file.exists()) {
                continue;
            }
            float yawDelta = Math.abs(signedHeadingDelta(targetYawDegrees, frameCenterYawDegrees(frame)));
            float pitchDelta = Math.abs(targetPitchDegrees - frame.rawFacts.targetPitchDegrees);
            if (pitchDelta <= 42f && yawDelta <= 70f) {
                neighbors.add(frame);
            }
        }
        Collections.sort(neighbors, (left, right) -> {
            float leftScore = Math.abs(signedHeadingDelta(targetYawDegrees, frameCenterYawDegrees(left)))
                    + Math.abs(targetPitchDegrees - left.rawFacts.targetPitchDegrees) * 1.5f;
            float rightScore = Math.abs(signedHeadingDelta(targetYawDegrees, frameCenterYawDegrees(right)))
                    + Math.abs(targetPitchDegrees - right.rawFacts.targetPitchDegrees) * 1.5f;
            return Float.compare(leftScore, rightScore);
        });
        if (neighbors.size() > 6) {
            return new ArrayList<>(neighbors.subList(0, 6));
        }
        return neighbors;
    }

    int acceptedFrameCount(String sessionId) {
        CaptureSessionRecord session = findCaptureSession(sessionId);
        return session == null ? 0 : session.countFrames(CaptureFrameRole.ACCEPTED);
    }

    void recordAnalyzedCandidateFrame(
            File imageFile,
            String sessionId,
            String locationSummary,
            float headingDegrees,
            float pitchDegrees,
            float rollDegrees,
            int targetYawDegrees,
            int targetPitchDegrees,
            String captureMode,
            String captureProfile,
            String exposureJson,
            CandidateAnalysisResult result) throws IOException {
        long now = System.currentTimeMillis();
        try {
            JSONObject exposure = parseExposureJson(exposureJson);
            String metadataBlocker = requiredCaptureMetadataBlocker(exposure);
            CandidateAnalysisResult finalResult = result;
            if (metadataBlocker != null) {
                CandidateQualityReport quality = new CandidateQualityReport(false, 0.0, 0.0, 0.0, "Metadata incomplete");
                finalResult = new CandidateAnalysisResult(
                        false,
                        quality,
                        new JSONArray(),
                        "metadata_incomplete",
                        0,
                        -1.0,
                        0.0,
                        metadataBlocker,
                        "Metadata incomplete",
                        "",
                        new JSONArray());
            }
            CaptureRawFacts rawFacts = buildRawFacts(
                    imageFile,
                    locationSummary,
                    headingDegrees,
                    pitchDegrees,
                    rollDegrees,
                    targetYawDegrees,
                    targetPitchDegrees,
                    captureProfile,
                    exposure,
                    now);
            ArrayList<CaptureSessionRecord> sessions = readCaptureSessions();
            CaptureSessionRecord session = findOrCreateSession(
                    sessions,
                    sessionId,
                    CaptureMode.fromStorageValue(captureProfile),
                    now);
            String frameId = "frame-" + friendlyDateLabel(now) + "-" + session.frames.size();
            session.frames.add(new CaptureFrameRecord(
                    frameId + "-candidate",
                    sessionId,
                    CaptureFrameRole.CANDIDATE,
                    rawFacts,
                    finalResult.toAnalysisFacts()));
            if (finalResult.accepted) {
                session.frames.add(new CaptureFrameRecord(
                        frameId + "-source",
                        sessionId,
                        CaptureFrameRole.SOURCE,
                        rawFacts,
                        finalResult.toAnalysisFacts()));
                session.frames.add(new CaptureFrameRecord(
                        frameId + "-accepted",
                        sessionId,
                        CaptureFrameRole.ACCEPTED,
                        rawFacts,
                        finalResult.toAnalysisFacts()));
                if (!finalResult.acceptedNeighborFrameId.isEmpty()) {
                    session.graphEdges.add(new CaptureGraphEdgeRecord(
                            "edge-" + frameId,
                            sessionId,
                            finalResult.acceptedNeighborFrameId,
                            frameId + "-accepted",
                            finalResult.inlierCount,
                            finalResult.residualScore,
                            finalResult.confidence,
                            finalResult.parallaxRiskHint,
                            finalResult.controlPoints));
                }
                appendDraftMetadata(
                        imageFile,
                        sessionId,
                        locationSummary,
                        headingDegrees,
                        pitchDegrees,
                        rollDegrees,
                        targetYawDegrees,
                        targetPitchDegrees,
                        captureMode,
                        captureProfile,
                        exposure,
                        now);
                session.status = session.countFrames(CaptureFrameRole.ACCEPTED) >= 34
                        ? SessionStatus.CAPTURE_COMPLETE
                        : SessionStatus.CAPTURING;
            } else {
                session.frames.add(new CaptureFrameRecord(
                        frameId + "-rejected",
                        sessionId,
                        CaptureFrameRole.REJECTED,
                        rawFacts,
                        finalResult.toAnalysisFacts()));
                session.status = SessionStatus.NEEDS_RECAPTURE;
            }
            session.updatedAt = now;
            writeCaptureSessions(sessions);
            upsertCaptureSessionItem(session, imageFile);
        } catch (JSONException e) {
            throw new IOException("could not write candidate metadata", e);
        }
    }

    private void recordCaptureSessionFrame(
            File imageFile,
            String sessionId,
            String locationSummary,
            float headingDegrees,
            float pitchDegrees,
            float rollDegrees,
            int targetYawDegrees,
            int targetPitchDegrees,
            String captureMode,
            String captureProfile,
            JSONObject exposure,
            long now) throws IOException, JSONException {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        ArrayList<CaptureSessionRecord> sessions = readCaptureSessions();
        CaptureSessionRecord target = null;
        for (CaptureSessionRecord session : sessions) {
            if (sessionId.equals(session.id)) {
                target = session;
                break;
            }
        }
        if (target == null) {
            target = new CaptureSessionRecord(
                    sessionId,
                    "Capture Session " + compactSessionLabel(sessionId),
                    now,
                    now,
                    CaptureMode.fromStorageValue(captureProfile),
                    SessionStatus.CAPTURING,
                    false,
                    new JSONObject(),
                    new ArrayList<>(),
                    new ArrayList<>());
            sessions.add(target);
        }
        JSONObject intrinsics = new JSONObject();
        intrinsics.put("available", exposure != null
                && exposure.optDouble("imageFocalLengthXPixels", 0.0) > 0.0
                && exposure.optDouble("imageFocalLengthYPixels", 0.0) > 0.0
                && exposure.optInt("imageIntrinsicsWidth", 0) > 0
                && exposure.optInt("imageIntrinsicsHeight", 0) > 0);
        intrinsics.put("focalLengthXPixels", exposure == null ? 0.0 : exposure.optDouble("imageFocalLengthXPixels", 0.0));
        intrinsics.put("focalLengthYPixels", exposure == null ? 0.0 : exposure.optDouble("imageFocalLengthYPixels", 0.0));
        intrinsics.put("principalPointXPixels", exposure == null ? 0.0 : exposure.optDouble("imagePrincipalPointXPixels", 0.0));
        intrinsics.put("principalPointYPixels", exposure == null ? 0.0 : exposure.optDouble("imagePrincipalPointYPixels", 0.0));
        intrinsics.put("width", exposure == null ? 0 : exposure.optInt("imageIntrinsicsWidth", 0));
        intrinsics.put("height", exposure == null ? 0 : exposure.optInt("imageIntrinsicsHeight", 0));

        String frameId = "frame-" + friendlyDateLabel(now) + "-" + target.frames.size();
        CaptureRawFacts rawFacts = new CaptureRawFacts(
                imageFile.getAbsolutePath(),
                now,
                targetYawDegrees,
                targetPitchDegrees,
                headingDegrees,
                pitchDegrees,
                rollDegrees,
                true,
                intrinsics,
                exposure,
                locationSummary,
                normalizeCaptureProfile(captureProfile),
                Build.MANUFACTURER + " " + Build.MODEL,
                "");
        target.frames.add(new CaptureFrameRecord(
                frameId + "-source",
                sessionId,
                CaptureFrameRole.SOURCE,
                rawFacts,
                CaptureAnalysisFacts.empty()));
        target.frames.add(new CaptureFrameRecord(
                frameId + "-candidate",
                sessionId,
                CaptureFrameRole.CANDIDATE,
                rawFacts,
                CaptureAnalysisFacts.empty()));
        target.status = SessionStatus.CANDIDATE_PENDING_ANALYSIS;
        target.updatedAt = now;
        writeCaptureSessions(sessions);
    }

    private CaptureSessionRecord findOrCreateSession(
            ArrayList<CaptureSessionRecord> sessions,
            String sessionId,
            CaptureMode captureMode,
            long now) {
        for (CaptureSessionRecord session : sessions) {
            if (sessionId.equals(session.id)) {
                return session;
            }
        }
        CaptureSessionRecord session = new CaptureSessionRecord(
                sessionId,
                "Capture Session " + compactSessionLabel(sessionId),
                now,
                now,
                captureMode == null ? CaptureMode.HAND_HELD : captureMode,
                SessionStatus.CAPTURING,
                false,
                new JSONObject(),
                new ArrayList<>(),
                new ArrayList<>());
        sessions.add(session);
        return session;
    }

    private CaptureRawFacts buildRawFacts(
            File imageFile,
            String locationSummary,
            float headingDegrees,
            float pitchDegrees,
            float rollDegrees,
            int targetYawDegrees,
            int targetPitchDegrees,
            String captureProfile,
            JSONObject exposure,
            long now) throws JSONException {
        JSONObject intrinsics = new JSONObject();
        intrinsics.put("available", exposure != null
                && exposure.optDouble("imageFocalLengthXPixels", 0.0) > 0.0
                && exposure.optDouble("imageFocalLengthYPixels", 0.0) > 0.0
                && exposure.optInt("imageIntrinsicsWidth", 0) > 0
                && exposure.optInt("imageIntrinsicsHeight", 0) > 0);
        intrinsics.put("focalLengthXPixels", exposure == null ? 0.0 : exposure.optDouble("imageFocalLengthXPixels", 0.0));
        intrinsics.put("focalLengthYPixels", exposure == null ? 0.0 : exposure.optDouble("imageFocalLengthYPixels", 0.0));
        intrinsics.put("principalPointXPixels", exposure == null ? 0.0 : exposure.optDouble("imagePrincipalPointXPixels", 0.0));
        intrinsics.put("principalPointYPixels", exposure == null ? 0.0 : exposure.optDouble("imagePrincipalPointYPixels", 0.0));
        intrinsics.put("width", exposure == null ? 0 : exposure.optInt("imageIntrinsicsWidth", 0));
        intrinsics.put("height", exposure == null ? 0 : exposure.optInt("imageIntrinsicsHeight", 0));
        return new CaptureRawFacts(
                imageFile.getAbsolutePath(),
                now,
                targetYawDegrees,
                targetPitchDegrees,
                headingDegrees,
                pitchDegrees,
                rollDegrees,
                true,
                intrinsics,
                exposure,
                locationSummary,
                normalizeCaptureProfile(captureProfile),
                Build.MANUFACTURER + " " + Build.MODEL,
                "");
    }

    private void appendDraftMetadata(
            File imageFile,
            String sessionId,
            String locationSummary,
            float headingDegrees,
            float pitchDegrees,
            float rollDegrees,
            int targetYawDegrees,
            int targetPitchDegrees,
            String captureMode,
            String captureProfile,
            JSONObject exposure,
            long now) throws IOException, JSONException {
        JSONArray array = readDraftMetadataOrThrow();
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
        json.put("captureProfile", normalizeCaptureProfile(captureProfile));
        json.put("exposure", exposure);
        array.put(json);
        writeDraftMetadata(array);
    }

    private ArrayList<CaptureSessionRecord> readCaptureSessions() {
        ArrayList<CaptureSessionRecord> sessions = new ArrayList<>();
        if (!captureSessionsFile.exists()) {
            return sessions;
        }
        try (FileInputStream input = new FileInputStream(captureSessionsFile)) {
            byte[] data = new byte[(int) captureSessionsFile.length()];
            int read = input.read(data);
            if (read <= 0) {
                return sessions;
            }
            JSONArray array = new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) {
                    CaptureSessionRecord session = CaptureSessionRecord.fromJson(json);
                    if (!session.id.isEmpty()) {
                        sessions.add(session);
                    }
                }
            }
        } catch (IOException | JSONException ignored) {
            return new ArrayList<>();
        }
        return sessions;
    }

    private void writeCaptureSessions(List<CaptureSessionRecord> sessions) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (CaptureSessionRecord session : sessions) {
                array.put(session.toJson());
            }
            try (FileOutputStream output = new FileOutputStream(captureSessionsFile)) {
                output.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException e) {
            throw new IOException("could not write capture-session metadata", e);
        }
    }

    private boolean reconcileCaptureSessionItems() {
        boolean changed = false;
        for (CaptureSessionRecord session : readCaptureSessions()) {
            LibraryItem existing = findDraftSessionItem(session.id);
            File firstFrame = firstExistingFrame(session);
            if (existing == null) {
                items.add(new LibraryItem(
                        session.id,
                        session.title,
                        LibraryItem.TYPE_DRAFT_SESSION,
                        "capture_0_7_1",
                        "capture_session",
                        "",
                        firstFrame == null ? "" : firstFrame.getAbsolutePath(),
                        firstFrame == null ? "" : firstFrame.getAbsolutePath(),
                        session.createdAt,
                        session.updatedAt));
                changed = true;
            } else {
                if (!"capture_0_7_1".equals(existing.source)) {
                    existing.title = session.title;
                    changed = true;
                }
                if (firstFrame != null && !existing.imageFile().exists()) {
                    existing.imagePath = firstFrame.getAbsolutePath();
                    existing.thumbnailPath = firstFrame.getAbsolutePath();
                    changed = true;
                }
                if (session.updatedAt > existing.updatedAt) {
                    existing.updatedAt = session.updatedAt;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void upsertCaptureSessionItem(CaptureSessionRecord session, File representativeFrame) throws IOException {
        LibraryItem existing = findDraftSessionItem(session.id);
        String framePath = representativeFrame == null ? "" : representativeFrame.getAbsolutePath();
        if (existing == null) {
            items.add(new LibraryItem(
                    session.id,
                    session.title,
                    LibraryItem.TYPE_DRAFT_SESSION,
                    "capture_0_7_1",
                    "capture_session",
                    "",
                    framePath,
                    framePath,
                    session.createdAt,
                    session.updatedAt));
        } else {
            existing.title = session.title;
            if (!framePath.isEmpty()) {
                existing.imagePath = framePath;
                existing.thumbnailPath = framePath;
            }
            existing.updatedAt = session.updatedAt;
        }
        save();
    }

    private File firstExistingFrame(CaptureSessionRecord session) {
        for (CaptureFrameRecord frame : session.frames) {
            File file = new File(frame.rawFacts.filePath);
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }

    private boolean isRecoverableCaptureSessionItem(LibraryItem item) {
        return LibraryItem.TYPE_DRAFT_SESSION.equals(item.type) && findCaptureSession(item.id) != null;
    }

    private void removeCaptureSession(String sessionId) throws IOException {
        ArrayList<CaptureSessionRecord> sessions = readCaptureSessions();
        boolean removed = false;
        for (int i = sessions.size() - 1; i >= 0; i--) {
            if (sessionId.equals(sessions.get(i).id)) {
                sessions.remove(i);
                removed = true;
            }
        }
        if (removed) {
            writeCaptureSessions(sessions);
        }
    }

    private static boolean readinessPasses(JSONObject readiness) {
        return readiness != null
                && readiness.optBoolean("cameraPermission", false)
                && readiness.optBoolean("arCoreTracking", false)
                && readiness.optBoolean("arCoreSharedCameraBackend", false)
                && readiness.optBoolean("arCoreDepthOrFeatureConfidence", false)
                && readiness.optBoolean("arCorePoseFeedsCaptureGraph", false)
                && readiness.optBoolean("parallaxWarningBeforeCapture", false)
                && readiness.optBoolean("cameraIntrinsicsAvailable", false)
                && readiness.optBoolean("storageAvailable", false);
    }

    private static String formatJsonObject(JSONObject json) {
        if (json == null || json.length() == 0) {
            return "not captured";
        }
        StringBuilder result = new StringBuilder();
        JSONArray names = json.names();
        if (names == null) {
            return "not captured";
        }
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            result.append(name).append(": ").append(json.opt(name)).append('\n');
        }
        return result.toString().trim();
    }

    private static String formatOne(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String formatScore(double value) {
        return value < 0.0 ? "pending" : String.format(Locale.US, "%.2f", value);
    }

    private static String emptyAsMissing(String value) {
        return value == null || value.isEmpty() ? "missing" : value;
    }

    private static float signedHeadingDelta(float target, float current) {
        float delta = (target - current + 540f) % 360f - 180f;
        return delta < -180f ? delta + 360f : delta;
    }

    private static float normalizeHeading(float degrees) {
        float normalized = degrees % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    private static float frameCenterYawDegrees(CaptureFrameRecord frame) {
        return frame.rawFacts.capturedPoseAvailable
                ? normalizeHeading(frame.rawFacts.capturedYawDegrees)
                : normalizeHeading(frame.rawFacts.targetYawDegrees);
    }

    private static float idealHorizontalStepDegrees(CaptureFrameRecord frame) {
        JSONObject intrinsics = frame.rawFacts.intrinsics;
        double fx = firstPositive(
                intrinsics.optDouble("imageFocalLengthXPixels", 0.0),
                intrinsics.optDouble("focalLengthXPixels", 0.0),
                intrinsics.optDouble("fx", 0.0));
        double width = firstPositive(
                intrinsics.optDouble("imageIntrinsicsWidth", 0.0),
                intrinsics.optDouble("width", 0.0));
        if (fx > 0.0 && width > 0.0) {
            double horizontalFov = Math.toDegrees(2.0 * Math.atan(width / (2.0 * fx)));
            return Math.max(10f, Math.min(18f, (float) horizontalFov * 0.28f));
        }
        return 14f;
    }

    private static double firstPositive(double first, double second) {
        return first > 0.0 ? first : second;
    }

    private static double firstPositive(double first, double second, double third) {
        if (first > 0.0) {
            return first;
        }
        return second > 0.0 ? second : third;
    }

    private static String normalizeCaptureProfile(String captureProfile) {
        return "fixed_gimbal".equals(captureProfile) || "tripod_phone_mount".equals(captureProfile)
                ? "fixed_gimbal"
                : "handheld";
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
            removeCaptureSession(sessionId);
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
                "Pending Capture " + compactSessionLabel(sessionId),
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
     * Function: reconcileDraftSessionItems
     * Arguments: none.
     * Calls: readDraftMetadata(), File.exists(), findDraftSessionItem(), and
     * LibraryItem constructor.
     * Flow: ensure every non-empty draft session in drafts.json has a matching
     * first-class Pending library record, even if MainActivity's in-memory list
     * was loaded before the capture backend saved the frames.
     */
    private boolean reconcileDraftSessionItems() {
        boolean changed = false;
        JSONArray metadata = readDraftMetadata();
        for (int i = 0; i < metadata.length(); i++) {
            JSONObject json = metadata.optJSONObject(i);
            if (json == null) {
                continue;
            }
            String sessionId = json.optString("sessionId", "");
            if (sessionId.isEmpty()) {
                continue;
            }
            File file = new File(json.optString("path", ""));
            if (!file.exists()) {
                continue;
            }
            long createdAt = json.optLong("createdAt", file.lastModified());
            LibraryItem existing = findDraftSessionItem(sessionId);
            if (existing == null) {
                items.add(new LibraryItem(
                        sessionId,
                        "Pending Capture " + compactSessionLabel(sessionId),
                        LibraryItem.TYPE_DRAFT_SESSION,
                        "capture",
                        "draft_session",
                        "",
                        file.getAbsolutePath(),
                        file.getAbsolutePath(),
                        createdAt,
                        createdAt));
                changed = true;
            } else {
                if (existing.title.startsWith("Draft Capture ")) {
                    existing.title = "Pending Capture " + compactSessionLabel(sessionId);
                    changed = true;
                }
                if (!existing.imageFile().exists()) {
                    existing.imagePath = file.getAbsolutePath();
                    existing.thumbnailPath = file.getAbsolutePath();
                    changed = true;
                }
                if (createdAt > existing.updatedAt) {
                    existing.updatedAt = createdAt;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private LibraryItem findDraftSessionItem(String sessionId) {
        for (LibraryItem item : items) {
            if (sessionId.equals(item.id) && LibraryItem.TYPE_DRAFT_SESSION.equals(item.type)) {
                return item;
            }
        }
        return null;
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

    private static String requiredCaptureMetadataBlocker(JSONObject json) {
        if (!json.optBoolean("available", false)) {
            return "source frame rejected: exposure metadata unavailable";
        }
        String[] positiveFields = {
                "sensorExposureTimeNs",
                "sensorSensitivityIso",
                "sensorFrameDurationNs",
                "sensorTimestampNs",
                "lensAperture",
                "lensFocalLengthMm",
                "sensorPhysicalWidthMm",
                "sensorPhysicalHeightMm",
                "imageFocalLengthXPixels",
                "imageFocalLengthYPixels",
                "imagePrincipalPointXPixels",
                "imagePrincipalPointYPixels",
                "imageIntrinsicsWidth",
                "imageIntrinsicsHeight"
        };
        for (String field : positiveFields) {
            if (json.isNull(field) || json.optDouble(field, 0.0) <= 0.0) {
                return "source frame rejected: missing " + field;
            }
        }
        String[] requiredStateFields = {
                "aeState",
                "awbState",
                "aeExposureCompensation",
                "aeMode",
                "awbMode"
        };
        for (String field : requiredStateFields) {
            if (json.isNull(field)) {
                return "source frame rejected: missing " + field;
            }
        }
        return null;
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
        bitmap = applyExifRotation(bitmap, imageFile.getAbsolutePath());
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

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String fileExtension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) {
            return ".jpg";
        }
        String extension = name.substring(dot).toLowerCase(Locale.US);
        return extension.length() > 6 ? ".jpg" : extension;
    }

    static Bitmap applyExifRotation(Bitmap bitmap, String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            int degrees;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  degrees = 90;  break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                default: return bitmap;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return rotated;
        } catch (IOException ignored) {
            return bitmap;
        }
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
        String extension = fileExtension(imageFile.getName());
        values.put(MediaStore.Images.Media.DISPLAY_NAME, title.replaceAll("[^a-zA-Z0-9_-]+", "-") + extension);
        values.put(MediaStore.Images.Media.MIME_TYPE, ".png".equals(extension) ? "image/png" : "image/jpeg");
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
     * Arguments: createdAt is the creation time in milliseconds.
     * Calls: friendlyDateLabel().
     * Flow: create a readable date-based id for file names and metadata keys.
     */
    private static String newId(long createdAt) {
        return friendlyDateLabel(createdAt);
    }

    private static String friendlyDateLabel(long createdAt) {
        return new SimpleDateFormat("yyMMddss-SSS", Locale.US).format(new Date(createdAt));
    }
}
