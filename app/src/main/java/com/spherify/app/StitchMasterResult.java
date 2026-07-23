/*
 * StitchMasterResult.java
 *
 * Educational overview:
 * StitchMasterResult bundles the newly-created library item with the public
 * quality summary that should be shown after OpenCV validation.
 */
package com.spherify.app;

final class StitchMasterResult {
    final LibraryItem item;
    final Phase5Stitcher.Result stitch;

    StitchMasterResult(LibraryItem item, Phase5Stitcher.Result stitch) {
        this.item = item;
        this.stitch = stitch;
    }
}
