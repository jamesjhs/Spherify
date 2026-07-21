/*
 * ProjectionExport.java
 *
 * Educational overview:
 * This file defines the tiny data object returned when GLProjectionView renders
 * the current Photo Sphere or Tiny Planet view to disk. It does not render,
 * compress, or save anything itself; it simply names the two files produced by
 * GLProjectionView.exportProjection(): the full-size exported image and its
 * thumbnail. MainActivity passes this object to SpherifyLibrary.saveVariant(),
 * which copies both files into the app's managed library folders.
 *
 * Data flow:
 * GLProjectionView writes temporary export files -> constructs ProjectionExport
 * with those File references -> MainActivity receives it -> SpherifyLibrary
 * copies the files into persistent storage and records metadata.
 *
 * Imports/dependencies:
 * java.io.File is the only dependency. A File is a path handle; it may refer to
 * an existing file, but this class does not verify existence.
 *
 * Key variables:
 * imageFile: File pointing to the full-size PNG export.
 * thumbnailFile: File pointing to the smaller JPEG thumbnail for galleries.
 */
package com.spherify.app;

import java.io.File;

final class ProjectionExport {
    final File imageFile;
    final File thumbnailFile;

    /*
     * Function: ProjectionExport constructor
     * Arguments: imageFile is the full-size projection image; thumbnailFile is
     * the gallery thumbnail generated alongside it.
     * Calls: no external functions beyond assigning fields.
     * Flow: store both File references so callers can move, share, or index the
     * generated assets without needing separate return values.
     */
    ProjectionExport(File imageFile, File thumbnailFile) {
        this.imageFile = imageFile;
        this.thumbnailFile = thumbnailFile;
    }
}
