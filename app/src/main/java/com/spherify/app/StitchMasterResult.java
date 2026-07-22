/*
 * StitchMasterResult.java
 *
 * Educational overview:
 * StitchMasterResult bundles the newly-created library item with the Phase 5
 * quality summary that should be shown to the user after an experimental stitch.
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
