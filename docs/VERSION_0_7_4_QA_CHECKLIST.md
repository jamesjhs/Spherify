# Spherify 0.7.4 QA Checklist

Version 0.7.4 requires real capture-session regression review before calling a master map-ready.

## Required Capture Corpus

- Indoor hand-held: furniture at mixed depth, normal lighting.
- Outdoor hand-held: distant scene with sky and horizon.
- Tripod or phone mount: rotation-only baseline.
- Low-light: visible exposure gain differences.
- Low-texture: plain walls, sky, or water.
- Moving object: people, cars, screens, or foliage crossing overlaps.
- Close-object parallax: foreground object within roughly 1 meter.

## Review Steps

1. Capture a complete graph-ready session.
2. Run Spherify and confirm it creates an exact 2:1 JPEG master.
3. Open the master in a common 360 viewer and confirm Photo Sphere XMP is recognized.
4. Record public quality state: `Local master`, `Creative export`, `Needs review`, or `Map-ready`.
5. Check that missing location or heading appears separately from stitch quality.
6. Inspect wrap seam at yaw 0/360 for discontinuity.
7. Inspect top and bottom poles for holes, smeared detail, or weak coverage.
8. Inspect horizon for tilt, broken closure, and visible geometry errors.
9. Inspect seams near moving objects, close-object parallax, low-confidence overlaps, and exposure boundaries.
10. Compare diagnostic contributor-map or sharp-source renders only for engineering investigation; do not treat them as public output.

## Pass Conditions

- Map-ready is allowed only when there are no major gaps, no broken wrap seam, acceptable graph residuals, and adequate pole coverage.
- Creative export is acceptable for visually useful local output that has parallax warnings or non-map metadata gaps.
- Needs review is required for major gaps, broken wrap seam, excessive residuals, weak pole coverage, or horizon/closure failures.
- Direct Google Maps or Google Photos publishing remains out of scope until privacy, store policy, and end-to-end publishing tests are added.
