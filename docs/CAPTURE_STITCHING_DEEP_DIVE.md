# Capture and Stitching Deep Dive

## Direction

The target architecture is not "capture lots of moving frames and average them."
The target is the established panorama pipeline used by mature tools: constrain
capture, collect still overlapping keyframes, detect control points, estimate
camera parameters globally, choose seams, then blend only after geometry is
credible.

## What Mature Stitchers Do

Brown and Lowe's automatic panorama work frames stitching as a multi-image
matching problem, then uses invariant feature matches, bundle adjustment,
gain compensation, automatic straightening, and multi-band blending. OpenCV's
detailed stitcher exposes the same broad stages as separate parts: feature
finding, pairwise matching, camera estimation, bundle adjustment, warping,
exposure compensation, seam finding, and blending. Hugin uses control points
between overlapping photos, then optimizes image orientation and lens settings
until those points line up.

That pattern matters because blending is the last cosmetic step. If the camera
poses or lens model are wrong, blending turns a geometric error into blur and
ghosting.

## Capture Policy

The default capture must behave like a guided Photo Sphere workflow:

- Move to one dot.
- Hold still.
- Capture one sharp keyframe.
- Move to the next dot only after the keyframe is accepted.

Continuous AR preview is useful for guidance, but continuous moving frames are
bad stitching inputs. The app should keep rejecting transitional frames unless
they are explicitly marked as diagnostic data.

For handheld indoor captures, the app should assume parallax is present. The
user can still get good results, but only if capture is strongly constrained:
feet fixed, arms steady, phone rotated as close as possible around the lens, and
close furniture avoided when choosing seams. Fixed gimbal captures can be treated
as closer to a pure spherical rotation.

## Current Implementation Direction

The normal Spherify output should remain sharp source-selected. Blended output is
useful only after geometry is strong enough. The current code now:

- uses ARCore/captured heading, pitch, and roll as the first pose prior;
- stores per-frame camera intrinsics for FOV estimation;
- uses OpenCV ORB features, Hamming matching, ratio/cross-check filtering, and
  RANSAC homography validation;
- stores inlier control points on accepted overlap edges;
- runs an iterative pose graph relaxation instead of per-frame overwrite nudges;
- normalizes source exposure before rendering to reduce harsh source-selected
  brightness jumps;
- blocks obviously weak capture sessions before rendering.

## Next Required Work

1. Replace the lightweight pose relaxation with a real bundle adjustment layer.
   The optimized variables should include frame yaw, pitch, roll, focal/FOV, and
   simple radial distortion. Sensor/ARCore pose should be a prior, not the truth.

2. Promote inlier control points into reprojection residuals. Average dx/dy is
   not enough; each point pair should contribute an angular reprojection error.

3. Add seam selection before any normal blended export. Prefer source centers,
   penalize high residual regions, and route seams around suspected near-object
   parallax.

4. Add a capture score before Spherify. Warn or refuse if row closure is weak,
   OpenCV inliers are sparse, captured angular velocity was high, pose metadata
   is missing, or expected row counts are incomplete.

5. Add a public capture UX that teaches the physical method without prose-heavy
   screens: dot, hold countdown, stillness feedback, and row completion.

## References

- Brown and Lowe, "Automatic Panoramic Image Stitching using Invariant Features":
  https://www.cs.ubc.ca/~lowe/papers/07brown.pdf
- OpenCV stitching pipeline and detailed stitcher concepts:
  https://docs.opencv.org/4.x/d1/d46/group__stitching.html
- OpenCV detailed stitcher sample:
  https://github.com/opencv/opencv/blob/4.x/samples/cpp/stitching_detailed.cpp
- Hugin Photos tab, control-point optimisation:
  https://hugin.sourceforge.io/docs/manual/Hugin_Photos_tab.html
- Hugin Control Points tab:
  https://hugin.sourceforge.io/docs/manual/Hugin_Control_Points_tab.html
- Google Maps Photo Sphere guidance:
  https://support.google.com/maps/answer/7012050?hl=en
