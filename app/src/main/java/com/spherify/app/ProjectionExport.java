package com.spherify.app;

import java.io.File;

final class ProjectionExport {
    final File imageFile;
    final File thumbnailFile;

    ProjectionExport(File imageFile, File thumbnailFile) {
        this.imageFile = imageFile;
        this.thumbnailFile = thumbnailFile;
    }
}
