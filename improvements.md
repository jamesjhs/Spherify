# Spherify Improvements From Stanford 360Cam Methodology

## Purpose

This document converts the observed methodology of the Stanford 360Cam PWA
at https://360cam.stanford.edu/ into explicit architecture and implementation
instructions for Spherify.

The goal is not to replace Spherify's stronger native Android, ARCore,
Camera2, metadata, validation, and OpenCV-based direction. The goal is to
adopt the Stanford project's best product and acquisition ideas:

- a simple fixed capture ritual;
- correctly proportioned portrait capture;
- a visible target lattice;
- auto-capture after stable alignment;
- immediate feedback for captured frames;
- an obvious fixed completion count;
- a fast pre-stitched preview before final export.

Spherify should keep its current technical advantages as the default safety
contract for production-quality output, but those advantages should be
expressed as configurable architecture options so field testing can decide
which constraints are too strict, too loose, or too confusing.

## High-Level Decision

Spherify should adopt a two-layer architecture:

1. Public capture workflow:
   - Stanford-style fixed 36 target capture.
   - Portrait-first, proportion-correct viewfinder.
   - Auto-capture after target alignment and stability.
   - Immediate captured-frame visual feedback.
   - Clear 36/36 completion state.
   - Fast preview generation immediately after capture.

2. Production solver and export:
   - Keep Spherify's ARCore SharedCamera plus Camera2 metadata contract.
   - Keep per-frame pose, intrinsics, exposure, feature-confidence, and
     parallax facts.
   - Keep overlap validation and graph-based acceptance.
   - Keep native OpenCV-style master stitching for trustworthy output.
   - Keep metadata honesty: do not mark incomplete or weak output as a
     complete Photo Sphere.

The Stanford project should be treated as a reference for acquisition UX and
fast preview rendering. It should not be treated as a complete replacement for
Spherify's final stitching architecture.

## Stanford Methodology To Adopt

### Fixed Capture Lattice

The Stanford capture model is intentionally simple:

- 36 total capture points.
- 3 pitch rows.
- 12 yaw columns per row.
- Pitch rows at +45 degrees, 0 degrees, and -45 degrees.
- Yaw columns every 30 degrees around the full 360 degrees.

This gives the user a stable mental model:

- there is a known target count;
- progress is easy to understand;
- the user is never asked to reason about FOV, overlap, graph closure, or
  target planning;
- the capture loop ends cleanly.

Spherify should implement this as the default public capture profile:

```text
profile_id: stanford_fixed_36
display_name: Fixed 36
rows:
  upper: pitch +45, yaw 0..330 step 30
  middle: pitch 0, yaw 0..330 step 30
  lower: pitch -45, yaw 0..330 step 30
target_count: 36
default_public_profile: true
```

The first accepted frame should still anchor the lattice to the user's chosen
starting direction. Internally, target yaw should be stored as both local yaw
and world/AR yaw:

```text
target_local_yaw_degrees
target_local_pitch_degrees
target_world_yaw_degrees
target_world_pitch_degrees
anchor_world_yaw_degrees
anchor_world_pitch_degrees
```

This keeps the Stanford simplicity while preserving Spherify's current anchor
model.

### Portrait-First Geometry

The Stanford PWA treats portrait capture as part of the acquisition model, not
as cosmetic UI. Spherify should do the same.

Default capture should assume:

```text
orientation: portrait
nominal_aspect_ratio: 9:16
nominal_capture_size_android: 1080 x 1920 or closest supported equivalent
nominal_capture_size_low_memory: 720 x 1280
expected_horizontal_fov_degrees: calibrated per device when possible
expected_vertical_fov_degrees: calibrated per device when possible
```

Implementation instructions:

- The live preview must show the same crop and aspect ratio that will be used
  for accepted still frames.
- The target projection must use the actual preview transform, display
  rotation, sensor orientation, crop rect, and intrinsics.
- If the preview crop differs from the still image crop, show a blocker until
  the mapping is known and stable.
- Do not use a full-screen stretched preview for target alignment unless the
  projection code explicitly compensates for the stretch.
- Store the effective capture rect and preview rect for every accepted frame.

Required persisted fields:

```text
preview_width_pixels
preview_height_pixels
preview_crop_left
preview_crop_top
preview_crop_right
preview_crop_bottom
capture_width_pixels
capture_height_pixels
capture_crop_left
capture_crop_top
capture_crop_right
capture_crop_bottom
display_rotation_degrees
sensor_orientation_degrees
image_mirror_applied
```

### Simple Capture Loop

The public workflow should be:

1. Show start instructions.
2. Ask user to hold the phone vertically and close to face.
3. Start camera and tracking.
4. Show orange target dots.
5. User rotates body to bring the reticle onto the active target.
6. Dot becomes green when aligned.
7. App waits for a short stability interval.
8. App captures automatically.
9. Captured target becomes a filled/hidden/green state.
10. Immediate reference patch or thumbnail appears.
11. Progress updates as `n / 36`.
12. Repeat until 36 accepted frames.
13. Main action becomes finish/check.
14. Finish opens processor preview.

Manual capture should remain available only as an explicit fallback for testing
or accessibility. Public default should prefer auto-capture because it produces
more consistent accepted frames and reduces user timing variance.

Recommended constants:

```text
target_approach_tolerance_degrees: 14
target_capture_yaw_tolerance_degrees: 5 to 7
target_capture_pitch_tolerance_degrees: 5 to 7
required_aligned_duration_ms: 850 to 1100
minimum_capture_interval_ms: 1000 to 1300
roll_level_tolerance_degrees: 2 to 3
soft_roll_warning_degrees: 5
max_translation_from_anchor_meters: 0.08 initial default
```

Spherify already has similar tolerance ideas in `SharedCameraCaptureActivity`.
The improvement is to express them through a fixed 36 target product workflow.

### Immediate Captured-Frame Feedback

The Stanford UI makes accepted images visible immediately as patches on the
capture sphere. Spherify should implement a native version of that idea.

After each accepted frame:

- mark the target captured;
- update the progress ring/bar;
- add a small reference patch at the target position;
- optionally show no more than the nearest 3 reference patches in the live
  camera view;
- fade reference patches by angular distance from current view;
- never let reference patches fully obscure the live camera feed;
- store the accepted thumbnail independently from the full source image.

Reference overlay policy:

```text
max_visible_reference_overlays: 3
max_retained_reference_overlays_in_memory: 40
reference_fade_start_degrees: 14
reference_fade_end_degrees: 52
reference_min_alpha: 0.15 to 0.25
reference_max_alpha: 0.45 to 0.60
```

This should be a guidance aid, not a compositor. It must not change the final
source graph.

### Completion Is A Fixed Product State

The user-facing completion state should be based on 36 accepted targets.

Default public behavior:

- before 36 accepted frames: show `n / 36 captured`;
- at 36 accepted frames: show `Capture complete`;
- the primary button becomes a check/finish button;
- tapping finish starts preview generation and then final master processing.

Partial stitch should not be the normal public path. It should be exposed as:

- debug option;
- recovery option;
- field-test option;
- accessibility escape hatch;
- explicit "Create partial panorama" action with strong labeling.

Partial output must not be marked as a complete Photo Sphere unless coverage
and metadata prove that it is complete.

## Spherify Advantages To Preserve

These are current Spherify strengths that should remain in the architecture.
They may become adjustable after field testing, but they should not be removed
as part of adopting the Stanford workflow.

### ARCore SharedCamera And Camera2 Metadata

Spherify's production capture path uses ARCore SharedCamera with Camera2. This
is a major advantage over a browser PWA because accepted frames can be tied to:

- AR tracking state;
- camera pose;
- projection and view matrices;
- CPU image data;
- Camera2 `TotalCaptureResult`;
- sensor timestamps;
- exposure metadata;
- lens and sensor metadata;
- per-frame intrinsics where available.

Instruction:

- Keep this as the production capture backend.
- Do not regress to camera-only capture for production masters.
- If a non-AR fallback exists, label it as preview/experimental/partial.

Field-test changeable option:

```text
option_id: allow_non_ar_preview_capture
default: false
purpose: compare Stanford-like low-friction capture against ARCore capture
allowed_output: preview only, no map-ready master
```

### Capture Graph And Immutable Frame Facts

Spherify stores capture sessions as records with raw facts and analysis facts.
This should remain the source of truth.

Each accepted frame should include:

```text
frame_id
session_id
target_index
target_profile_id
target_local_yaw_degrees
target_local_pitch_degrees
target_world_yaw_degrees
target_world_pitch_degrees
captured_yaw_degrees
captured_pitch_degrees
captured_roll_degrees
captured_pose_available
ar_tracking_state
feature_point_count
anchor_translation_delta_meters
camera_intrinsics_json
camera2_total_capture_result_summary_json
exposure_time_ns
iso
focal_length
focus_distance
white_balance_state
timestamp_sensor_ns
timestamp_wall_ms
source_image_path
thumbnail_path
accepted_by_policy_version
```

Analysis facts should remain separate:

```text
blur_score
exposure_score
texture_score
overlap_inlier_count
overlap_inlier_ratio
overlap_residual_px
pose_prior_weight
parallax_risk_score
acceptance_decision
rejection_reason
```

Instruction:

- Do not overwrite raw facts during later optimization.
- Store optimized pose separately from captured pose.
- Store final solver outputs as derived facts.

### Strict Acceptance Gates

The Stanford workflow accepts frames based on alignment and levelness. Spherify
should do that, but also keep stricter native acceptance gates:

- AR tracking must be valid.
- Feature confidence must be sufficient or explicitly classified as low texture.
- Capture must be close to the target.
- Phone translation from anchor must remain small.
- The frame must be sharp enough.
- Exposure must not be unusable.
- Required metadata must be timestamp-paired.
- Overlap with expected neighbors must be validated when enough neighboring
  frames exist.

Recommended acceptance pipeline:

```text
1. UI alignment gate
2. UI stability gate
3. AR tracking gate
4. translation/parallax gate
5. image sharpness gate
6. exposure gate
7. texture gate
8. neighbor overlap gate
9. graph coverage update
10. persist accepted frame
```

Field-test changeable option:

```text
option_id: acceptance_strictness
values: strict, balanced, lenient
default: balanced for consumer capture, strict for map-ready export
```

### Pose-Only Acceptance For Featureless Frames

Spherify should support a constrained fallback for frames that are spatially
correct but visually featureless. This is the controlled version of a "force"
button. It must not be a generic user override.

The problem case:

```text
target alignment: good
device stability: good
AR tracking: good
translation/parallax risk: low
image sharpness: acceptable
exposure: acceptable
texture score: very low
feature matching result: insufficient features
geometric contradiction: none
```

Examples:

- blank painted wall;
- blue sky;
- smooth ceiling;
- smooth floor;
- low-detail pole region;
- evenly lit featureless surface.

In those cases, visual matching fails because there are no reliable local
features to match. That is different from a bad frame whose features conflict
with expected geometry. Spherify should let the user accept the former as a
pose-only source while still rejecting the latter.

User-facing control:

```text
label: Accept Pose-Only
short explanation: Spatial tracking is strong, but this view has too few visual
features to match. Accept it using pose data only?
default visibility: hidden
shown only after low-texture visual-match failure
```

Do not label this `Force`, because that implies the user can override any
failure. The UI should communicate that Spherify has classified a specific
technical condition and is offering a bounded fallback.

Show `Accept Pose-Only` only when all required gates pass:

```text
target_alignment_passed == true
stability_passed == true
ar_tracking_state == tracking
pose_available == true
camera_intrinsics_available == true
timestamp_pairing_available == true
translation_from_anchor_meters <= max_translation_from_anchor_meters
blur_score >= minimum_blur_score
exposure_score >= minimum_exposure_score
texture_score < low_texture_threshold
feature_match_status == insufficient_features
homography_or_overlap_status != high_residual_conflict
parallax_risk_score <= parallax_pose_only_threshold
```

Never show `Accept Pose-Only` when:

```text
target alignment failed
device was moving too fast
roll/levelness failed
AR tracking was paused, stopped, or relocalizing
pose timestamp could not be paired with the source image
intrinsics are missing
translation from anchor is excessive
blur is high
exposure is clipped or unusable
feature matching found many matches but high residuals
neighbor geometry contradicts this frame
```

Persist pose-only frames distinctly:

```text
frame_role: accepted_pose_only
acceptance_decision: pose_only_low_texture
visual_match_required: false
visual_match_status: insufficient_features
pose_prior_weight: high
solver_weight: lower_than_visual_matched_frame
eligible_for_preview: true
eligible_for_master: conditional
eligible_for_certified_photosphere: conditional
user_confirmed_pose_only: true
pose_only_policy_version: <version>
```

Solver rules:

- Pose-only frames may contribute pixels to preview rendering.
- Pose-only frames may contribute pixels to the master only after neighboring
  visually matched frames keep the graph connected.
- Pose-only frames must not create graph connectivity by themselves.
- Pose-only frames should be lower weight during optimization than visually
  matched frames.
- Pose-only frames should not be used to resolve conflicting geometry.
- If a required region is covered only by pose-only frames, final export may be
  allowed only if neighboring graph edges validate the surrounding geometry and
  certification records that the region is pose-prior-dominated.

Recommended graph model:

```text
node.type:
  visual_accepted
  pose_only_accepted
  rejected

edge.type:
  visual_match
  pose_prior_adjacency

graph connectivity:
  use visual_match edges for certification
  use pose_prior_adjacency only for rendering support and coverage hints
```

Preview behavior:

- The fast preview may render pose-only frames immediately.
- The preview should optionally display a subtle needs-review flag if a large
  portion of the output comes from pose-only sources.
- The preview mask should track which ERP pixels came from pose-only frames.

Master/certification behavior:

- A master can include pose-only pixels when the surrounding graph is valid.
- A certified Photo Sphere should require a maximum pose-only coverage ratio or
  explicit field-tested thresholds.
- Map-ready language must be blocked if pose-only frames dominate critical
  seam, pole, or wrap regions without validated neighbors.

Field-test changeable options:

```text
option_id: pose_only_acceptance_enabled
default: true

option_id: pose_only_requires_user_confirmation
default: true

option_id: pose_only_max_certified_pixel_ratio
default: 0.20
test_values: 0.10, 0.20, 0.35

option_id: pose_only_allowed_regions
default: sky_wall_floor_poles
test_values:
  - poles_only
  - low_texture_anywhere
  - disabled
```

### Native OpenCV Master Pipeline

The Stanford project uses a browser rendering approach for speed and simplicity.
Spherify should keep native OpenCV-style master processing as the trustworthy
path.

Production master pipeline should remain:

```text
accepted frames
-> feature extraction
-> pairwise matching
-> graph connectivity check
-> pose/camera estimation
-> bundle adjustment or equivalent global refinement
-> spherical warping
-> exposure compensation
-> seam selection
-> blending or source-selected render
-> pole and wrap verification
-> metadata certification
-> library save
```

The Stanford-style best-pixel renderer can be added as a preview stage, but it
must not silently replace final master generation.

Field-test changeable option:

```text
option_id: preview_renderer_can_be_promoted
default: false
promotion_condition: repeated field tests show preview renderer equals or beats native master for target scene classes
```

### Metadata Honesty

Spherify's metadata approach should stay stricter than Stanford's. GPano/XMP
metadata should be considered a truth claim.

Instruction:

- Write complete Photo Sphere metadata only when the output is complete.
- Mark partial panoramas as partial in internal records.
- Do not label weak previews as map-ready.
- Store capture profile and solver profile in metadata/debug records.

Required certification checks before complete Photo Sphere export:

```text
aspect_ratio_is_2_to_1
coverage_horizontal_degrees >= 360
coverage_vertical_degrees >= 180 or pole_fill_certified
all_required_36_targets_accepted
wrap_seam_checked
pole_regions_checked
graph_connected
minimum_overlap_edges_present
residual_threshold_passed
metadata_fields_present
```

## Proposed Spherify Target Profiles

Spherify should explicitly support capture profiles instead of burying target
planning inside heuristics.

### Profile 1: Stanford Fixed 36

Default public profile.

```text
profile_id: stanford_fixed_36
target_count: 36
rows:
  - pitch: +45
    yaw_count: 12
    yaw_step: 30
  - pitch: 0
    yaw_count: 12
    yaw_step: 30
  - pitch: -45
    yaw_count: 12
    yaw_step: 30
requires_portrait: true
requires_auto_capture: true
allows_partial_finish: hidden_by_default
expected_use: normal consumer capture
```

Implementation note:

- Anchor the lattice at the first accepted yaw.
- Keep pitch rows fixed relative to local horizon unless field tests show
  anchor pitch should shift the whole lattice.
- Store both fixed target pitch and actual captured pitch.

### Profile 2: Spherify Adaptive FOV

Backup option based on current Spherify behavior.

```text
profile_id: spherify_adaptive_fov
target_count: derived
inputs:
  - measured_horizontal_fov
  - measured_vertical_fov
  - desired_horizontal_overlap
  - desired_vertical_overlap
expected_use: difficult devices, unusual lenses, research capture
public_default: false
```

Keep this as a field-testing option because it may outperform fixed 36 on
devices with unusual FOV or aggressive crop.

### Profile 3: Recovery Fill

Used after the 36-shot profile identifies weak graph regions.

```text
profile_id: recovery_fill
target_count: variable
trigger:
  - weak overlap edge
  - missing target
  - high residual region
  - suspected parallax region
target_generation:
  - midpoint between weak neighbors
  - nearest uncovered direction
  - same-row closure repair
expected_use: quality repair after normal capture
```

Recovery fill should be opt-in after the first 36 frames. The user should see
plain language like `Capture 3 extra repair views`.

## Detailed Implementation Instructions

### 1. Add CaptureProfile Abstraction

Create an explicit capture profile model.

Suggested types:

```java
final class CaptureProfile {
    final String id;
    final String displayName;
    final int expectedTargetCount;
    final boolean requiresPortrait;
    final boolean publicDefault;
    final ArrayList<CaptureTargetSpec> targetSpecs;
    final CaptureAcceptancePolicy acceptancePolicy;
}

final class CaptureTargetSpec {
    final int index;
    final int localYawDegrees;
    final int localPitchDegrees;
    final CaptureTargetPhase phase;
}
```

Do not remove the current planner immediately. Instead:

- add fixed 36 as the default profile;
- keep adaptive FOV as a selectable/internal profile;
- record the profile ID in every session;
- record policy version in every accepted frame.

### 2. Change CaptureTargetPlanner To Support Fixed 36

Add:

```java
static ArrayList<CaptureTarget> fixed36Targets(
        int anchorYawDegrees,
        int anchorPitchDegrees)
```

Initial implementation:

```text
rows = [+45, 0, -45]
columns = 12
yaw_step = 30
for each row:
  for column 0..11:
    local_yaw = column * 30
    world_yaw = normalize(anchorYawDegrees + local_yaw)
    world_pitch = row_pitch
```

Important:

- The first target should be the current view or nearest fixed target.
- If the first accepted frame is not exactly pitch 0, store the actual pitch as
  captured pose but keep target pitch fixed for lattice coverage.
- Do not let anchor pitch drift the upper/lower rows without a field-test flag.

Field-test flag:

```text
fixed36_anchor_pitch_mode:
  default: fixed_horizon_rows
  alternatives:
    shift_rows_by_anchor_pitch
    clamp_shifted_rows
```

### 3. Make 36 Count The User-Facing Completion Gate

In capture UI:

- progress max should be 36 for the default profile;
- text should be `n / 36`;
- completion should trigger at exactly 36 accepted targets;
- partial finish should move behind a menu or debug affordance.

Internal quality scoring may still fail a 36/36 capture if graph quality is
weak. In that case, the UI should say:

```text
Capture complete. Quality repair needed: 2 views.
```

not:

```text
Capture incomplete.
```

This distinction matters because the user completed the visible ritual, but the
solver found technical weakness.

### 4. Implement Stanford-Style Auto-Capture State Machine

Current Spherify already has capture pending and alignment ideas. Make the
state machine explicit:

```text
IDLE
TRACKING_NOT_READY
SEARCHING_TARGET
APPROACHING_TARGET
ALIGNED_NOT_LEVEL
ALIGNED_STABILIZING
CAPTURING
VALIDATING
ACCEPTED
REJECTED_RETRY_SAME_TARGET
COMPLETE
QUALITY_REPAIR
PROCESSING_PREVIEW
PROCESSING_MASTER
```

Transitions:

```text
SEARCHING_TARGET -> APPROACHING_TARGET
  nearest uncaptured target is within approach tolerance

APPROACHING_TARGET -> ALIGNED_NOT_LEVEL
  yaw/pitch within capture tolerance but roll outside level tolerance

APPROACHING_TARGET -> ALIGNED_STABILIZING
  yaw/pitch within capture tolerance and roll within level tolerance

ALIGNED_STABILIZING -> CAPTURING
  target remains same, alignment remains valid, level remains valid,
  required_aligned_duration_ms elapsed

CAPTURING -> VALIDATING
  image and metadata captured

VALIDATING -> ACCEPTED
  acceptance policy passes

VALIDATING -> REJECTED_RETRY_SAME_TARGET
  acceptance policy fails

ACCEPTED -> COMPLETE
  accepted count equals 36

ACCEPTED -> SEARCHING_TARGET
  accepted count less than 36
```

Every rejection must show a short actionable reason:

```text
Hold steadier
Too much movement
Need more texture
Camera tracking weak
Image blurred
Move back to the dot
```

### 5. Add Correctly Proportioned Viewfinder

The capture UI should draw a visible portrait frame that represents the actual
accepted image crop.

Implementation requirements:

- Compute the preview-to-view transform once the camera/AR surface is ready.
- Compute target projection in the displayed coordinate system.
- Draw the reticle at the center of the capture crop, not simply the screen.
- Draw target dots only when their projected coordinates are inside or near the
  viewfinder.
- Use letterbox/pillarbox explicitly if preview and capture aspect differ.
- Do not stretch camera preview to fill if that breaks target geometry.

QA tests:

```text
portrait phone, no rotation: target centered when device points at target
portrait phone, 90-degree sensor: target still centered
landscape rotation: capture blocked or warning shown
front camera accidentally selected: capture blocked
preview crop differs from still crop: UI shows calibrated crop frame
```

### 6. Add Immediate Pre-Stitched Preview

After 36 accepted frames, generate a fast preview before final master stitching.

Preview renderer requirements:

- Use accepted source frames only.
- Use captured pose when available.
- Fall back to target pose only when captured pose is missing and output is
  clearly labeled preview.
- Generate equirectangular preview at 2048 x 1024 or 4096 x 2048 depending on
  device memory.
- Use source-selected or best-pixel compositing so preview stays sharp.
- Apply simple gain normalization.
- Fill missing poles only as preview if pole capture is not present.
- Never use preview alone to certify final output.

Suggested preview pipeline:

```text
accepted frames
-> decode thumbnails or reduced images
-> spherical projection using pose/intrinsics
-> source center weighting
-> best-source selection per ERP pixel
-> optional feather fallback in low-confidence overlap
-> simple exposure gain normalization
-> draw preview canvas
-> show processor card
```

The Stanford WebGL2 method can inspire the preview renderer:

```text
For each ERP output pixel:
  convert ERP u/v to world direction
  for each source frame:
    transform world direction into camera coordinates
    reject if behind camera
    reject if outside frame FOV
    compute image u/v
    compute quality from distance to source center
    compute source-center or Voronoi bias
    apply exposure gain
  choose highest-score source
  if no source, output black/transparent/missing mask
```

Spherify improvement over Stanford:

- use real intrinsics instead of fixed HFOV/VFOV when available;
- use optimized pose when available;
- output a coverage mask and uncertainty mask;
- feed weak regions back into recovery fill targets.

### 7. Separate Preview, Draft, Master, And Certified Master

Add explicit output states:

```text
preview_pano:
  fast, may be approximate, used immediately after capture

draft_master:
  native stitch attempted, may need review

master:
  native stitch passed basic quality checks

certified_photosphere:
  master passed complete Photo Sphere checks and metadata certification
```

Do not collapse these states into one `saved panorama` concept.

### 8. Add Processor Card Workflow

Mirror the useful Stanford processor flow:

```text
Processing images...
Rendering preview...
Checking quality...
Creating master...
Saving to library...
```

The processor card should show:

- progress bar;
- current stage text;
- preview canvas;
- view button;
- share/export button;
- camera roll/library button;
- start new capture button;
- quality repair action if needed.

The preview should appear as soon as possible, even while the final master is
still processing.

### 9. Add Session Recovery

Stanford's PWA handles interrupted stitch jobs. Spherify should do the native
equivalent.

Persist:

```text
stitch_job_id
session_id
input_frame_ids
job_stage
started_at_ms
last_progress_at_ms
preview_path
draft_output_path
error_message
retry_count
```

On app launch:

- detect incomplete jobs;
- offer resume, keep capture only, or discard derived job;
- never discard source frames automatically;
- if a preview exists, show it while retrying master generation.

### 10. Use Stanford's Simplicity Without Adopting Its Weaknesses

Do adopt:

- fixed 36 count;
- simple visible progress;
- auto-capture;
- immediate preview;
- local-first storage;
- recoverable processing jobs;
- share/view/gallery flow.

Do not adopt as final production behavior:

- browser orientation as geometric truth;
- fixed FOV constants as final calibration;
- preview renderer as certified master;
- partial captures as normal complete output;
- metadata that overstates coverage;
- dead parallel stitch paths that confuse maintenance.

## Quality Gates For Field Testing

Every field test capture should produce a machine-readable summary.

Suggested metrics:

```text
device_model
android_version
camera_id
capture_profile_id
capture_resolution
preview_resolution
accepted_count
rejected_count
total_capture_duration_seconds
average_alignment_error_degrees
max_alignment_error_degrees
average_roll_degrees
max_roll_degrees
average_translation_from_anchor_meters
max_translation_from_anchor_meters
tracking_loss_count
low_feature_frame_count
blur_rejection_count
overlap_rejection_count
weak_edge_count
graph_connected
mean_overlap_inliers
minimum_overlap_inliers
mean_reprojection_residual
maximum_reprojection_residual
preview_generation_ms
master_generation_ms
export_certification_result
```

Field-test scene categories:

```text
outdoor_open_area
outdoor_with_trees
indoor_room
indoor_low_light
low_texture_walls
near_furniture
people_moving
high_dynamic_range
night_scene
small_room_high_parallax
```

For each scene, compare:

```text
fixed36_strict
fixed36_balanced
adaptive_fov_strict
adaptive_fov_balanced
fixed36_plus_recovery_fill
```

Field-test pass criteria:

```text
user_can_complete_capture_without_instructional_help
36_targets_are_understandable
preview_is_available_under_10_seconds_on_target_devices
master_generation_does_not_crash
wrap_seam_has_no_major discontinuity
poles_have_no obvious black holes unless labeled partial
metadata opens correctly in common 360 viewers
quality gates reject genuinely bad sessions
quality gates do not reject most good sessions
```

## Configurable Options To Keep For Future Change

These options should be centralized, logged, and included in debug export.

```text
capture_profile:
  default: stanford_fixed_36
  options:
    - stanford_fixed_36
    - spherify_adaptive_fov
    - recovery_fill

auto_capture_enabled:
  default: true

required_aligned_duration_ms:
  default: 900
  test_values: 650, 850, 1100, 1400

target_capture_tolerance_degrees:
  default: 6
  test_values: 4.5, 6, 7.5

roll_level_tolerance_degrees:
  default: 2.5
  test_values: 2, 3, 5

max_translation_from_anchor_meters:
  default: 0.08
  test_values: 0.05, 0.08, 0.12, 0.20

pose_only_acceptance_enabled:
  default: true

pose_only_requires_user_confirmation:
  default: true

pose_only_max_certified_pixel_ratio:
  default: 0.20
  test_values: 0.10, 0.20, 0.35

pose_only_allowed_regions:
  default: sky_wall_floor_poles
  options:
    - poles_only
    - sky_wall_floor_poles
    - low_texture_anywhere
    - disabled

preview_renderer:
  default: source_selected_webgl_or_native_equivalent
  options:
    - source_selected
    - feathered
    - best_pixel
    - native_fast_preview

final_renderer:
  default: native_opencv_detail
  options:
    - native_opencv_detail
    - source_selected_after_bundle_adjustment
    - blended_after_bundle_adjustment

metadata_certification_required:
  default: true

partial_finish_public:
  default: false
```

## Implementation Order

### Phase 1: Product Capture Contract

1. Add capture profile abstraction.
2. Add fixed 36 target planner.
3. Make fixed 36 the public default.
4. Change UI progress to `n / 36`.
5. Hide partial finish from normal flow.
6. Persist profile ID and target index.

### Phase 2: Capture UX

1. Add portrait crop/viewfinder verification.
2. Make auto-capture state machine explicit.
3. Add level/stability countdown.
4. Add captured target patch feedback.
5. Add completion state and finish button behavior.
6. Add `Accept Pose-Only` fallback for classified low-texture failures.

### Phase 3: Fast Preview

1. Add preview output state.
2. Implement reduced-resolution ERP preview from accepted frames.
3. Use pose/intrinsics where available.
4. Show preview in processor card before final master.
5. Persist preview path.
6. Add preview mask for pixels sourced from pose-only frames.

### Phase 4: Master And Certification

1. Keep native OpenCV master path.
2. Add final output state distinctions.
3. Add certification gate before Photo Sphere metadata.
4. Add repair target generation for weak regions.
5. Add QA report export.
6. Add certification thresholds for pose-only frame contribution.

### Phase 5: Field Testing Switchboard

1. Centralize tunable constants.
2. Add debug UI for profile/tolerance selection.
3. Include all options in debug CSV/JSON.
4. Run scene-category test matrix.
5. Promote only tested defaults.

## Exact Acceptance Criteria

The improvement is complete when:

- a normal user sees a fixed 36-shot capture workflow;
- the preview/capture frame is visibly portrait and proportion-correct;
- the app auto-captures after alignment and stability;
- the user sees immediate captured-frame feedback;
- the app shows `36 / 36` complete before normal finish;
- a fast pre-stitched preview appears after finishing capture;
- featureless but spatially valid frames can be accepted only through the
  constrained `Accept Pose-Only` path;
- generic force-accept of bad frames is not available in the public workflow;
- Spherify still stores ARCore/Camera2 metadata for accepted frames;
- Spherify still validates source frames before final master generation;
- final Photo Sphere export is blocked when coverage or quality is insufficient;
- adaptive target planning remains available as a backup option;
- current Spherify strictness remains adjustable for field testing.

## Summary

Stanford's project demonstrates a strong capture ritual: 36 known targets,
portrait-first acquisition, automatic capture, visible progress, immediate
coverage feedback, and a fast preview. Spherify should adopt that ritual as the
default public experience.

Spherify should not discard its current advantages. Its ARCore SharedCamera,
Camera2 metadata, capture graph, validation gates, native OpenCV stitching,
local library, and metadata honesty are the right foundation for trustworthy
output.

The target architecture is therefore:

```text
Stanford-simple capture UX
+ Spherify-native evidence collection
+ fast preview renderer
+ OpenCV-grade final solver
+ strict Photo Sphere certification
+ field-testable policy switches
```

