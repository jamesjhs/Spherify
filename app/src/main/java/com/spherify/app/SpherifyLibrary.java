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

    List<File> listDraftFrames() {
        ArrayList<File> result = new ArrayList<>();
        File[] files = draftsDir.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files != null) {
            Collections.addAll(result, files);
        }
        Collections.sort(result, Comparator.comparingLong(File::lastModified).reversed());
        return result;
    }

    void rename(LibraryItem item, String title) throws IOException {
        item.title = title.trim();
        item.updatedAt = System.currentTimeMillis();
        save();
    }

    void delete(LibraryItem item) throws IOException {
        items.remove(item);
        deleteFile(item.imageFile());
        deleteFile(item.thumbnailFile());
        save();
    }

    String describe(LibraryItem item) {
        return "Title: " + item.title
                + "\nType: " + item.type
                + "\nSource: " + item.source
                + "\nProjection: " + item.projection
                + "\nImage: " + item.imagePath
                + "\nThumbnail: " + item.thumbnailPath
                + "\nCreated: " + item.createdAt;
    }

    File createDraftFrameFile() throws IOException {
        mkdir(draftsDir);
        return new File(draftsDir, "draft-frame-" + System.currentTimeMillis() + ".jpg");
    }

    void recordDraftFrame(File imageFile, String locationSummary) throws IOException {
        JSONArray array = new JSONArray();
        if (draftMetadataFile.exists()) {
            try (FileInputStream input = new FileInputStream(draftMetadataFile)) {
                byte[] data = new byte[(int) draftMetadataFile.length()];
                int read = input.read(data);
                if (read > 0) {
                    array = new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
                }
            } catch (JSONException e) {
                throw new IOException("draft metadata is corrupt", e);
            }
        }
        try {
            JSONObject json = new JSONObject();
            json.put("path", imageFile.getAbsolutePath());
            json.put("createdAt", System.currentTimeMillis());
            json.put("location", locationSummary == null ? "" : locationSummary);
            array.put(json);
            try (FileOutputStream output = new FileOutputStream(draftMetadataFile)) {
                output.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException e) {
            throw new IOException("could not write draft metadata", e);
        }
    }

    private void ensureDirs() throws IOException {
        mkdir(root);
        mkdir(mastersDir);
        mkdir(variantsDir);
        mkdir(draftsDir);
        mkdir(thumbnailsDir);
    }

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

    private static void mkdir(File file) throws IOException {
        if (!file.exists() && !file.mkdirs()) {
            throw new IOException("could not create " + file.getAbsolutePath());
        }
    }

    private static void copy(InputStream input, File destination) throws IOException {
        try (OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
        }
    }

    private static void copy(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static void deleteFile(File file) {
        if (file.exists()) {
            file.delete();
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
