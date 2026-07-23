# Spherify

Version: 0.4.2

Spherify is an Android app concept for creating 360-degree PhotoSphere and Tiny Planet images from a phone camera, device motion sensors, and optional location services, then saving them locally. Google Maps publishing is not implemented in version 0.4.2.

This repository contains the Android application code for the 0.4.2 reset. The old sweep/force-capture workflow, custom Java stitch renderer, and CameraX-only guided capture path are removed from the production route because they cannot provide the ARCore visual-inertial pose, tracking, feature-confidence, anchor, and Camera2 metadata contract required for a reliable seamless PhotoSphere. Production capture now routes through ARCore `SharedCamera` with Camera2 and fails closed when ARCore tracking, CPU image resolution, feature confidence, timestamp-paired metadata, or parallax constraints are not met. The existing local library, flat-image import, GPU-backed PhotoSphere/Tiny Planet viewers, adjustment controls, saved variants, thumbnails, metadata display, export, share, delete, debug capture diagnostics, and basic library management remain operational.

## Developer Build and Run Runbook

This section is intentionally basic and explicit. It describes how to build, install, and run the current Android project from VS Code and terminal commands.

Current status:

- The repository has a Gradle wrapper, Android app module, launcher activity, bundled test panorama, and debug build.
- The current application ID is `com.spherify.app`.
- The current debug build command is `.\gradlew.bat :app:assembleDebug` on Windows or `./gradlew :app:assembleDebug` on macOS/Linux.
- The current build is local-first, does not require broad photo-library permission for normal use, keeps the library/viewer/import/export surfaces operational, and exposes production capture through the ARCore `SharedCamera`/Camera2 backend. Production Spherify master export consumes only validated capture graphs and remains blocked for sessions that lack ARCore pose/tracking/feature-confidence provenance.

## Play Store Compliance Gate (Mandatory)

This section is normative. All new code, features, permissions, and release artifacts must satisfy these requirements before any Play Store submission.

Hard blockers (must be complete before upload):

- Release artifact must be an Android App Bundle from a release variant, signed for Play upload. Debug APKs are never upload candidates.
- Target API level must meet current Google Play target API requirements at release time.
- Data safety form entries must exactly match shipped behavior for collection, processing, and sharing.
- A public privacy policy URL must exist and describe camera, optional location metadata, local storage, sharing/export behavior, retention, and deletion controls.
- Store listing text, screenshots, and in-app copy must not claim Google Maps publish unless that exact behavior is implemented, tested, and user-visible.
- Permission declarations in the manifest must be minimal and justified by an active user-facing feature.

Current repository compliance snapshot (0.4.2):

- Implemented now: local-first storage, in-app import/export/share, optional location tagging for capture metadata, GPU-backed viewers, Tiny Planet/PhotoSphere reprojection, debug-only CameraX capture diagnostics, Camera2 metadata pairing, OpenCV feature/RANSAC overlap validation, and the native OpenCV detail stitcher when a full Android OpenCV build is configured.
- Removed now: the old sweep/force-capture production workflow, the custom Java stitch/blend renderer, and the CameraX-only capture path as a production entry point.
- Not implemented now: production Spherify master export, direct Google Maps publish flow, production release pipeline documentation.
- Code-level sensitive permissions present: CAMERA, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION.
- Background location permission is not declared.
- Broad media-library read permission is not declared for normal operation.
- Draft privacy/data handling policy file: `PRIVACY_DATA_HANDLING_POLICY_DRAFT.md`.

Engineering mandates for all future changes:

- Do not add a new permission until a concrete user flow exists with:
	- contextual in-app prompt text,
	- graceful deny path,
	- README update,
	- Play Console declaration impact documented.
- Do not ship hidden data collection. Any newly persisted metadata field (image, sensor, location, account, diagnostics) must be documented in README and reflected in Data safety inputs.
- Keep location optional. Core capture, local save, and reprojection must remain functional when location access is denied.
- Keep Google integrations optional. Local create/save must remain functional with no sign-in.
- Any feature touching public upload must include a preflight review UI and explicit user confirmation.

Pre-release policy checklist (required sign-off):

- Build and package:
	- release AAB built and signed,
	- debuggable false in release,
	- versionCode/versionName updated and consistent.
- Permissions and data handling:
	- manifest permissions reviewed for least privilege,
	- runtime request timing is contextual and skippable where possible,
	- Data safety responses updated from current code,
	- privacy policy reviewed against current behavior.
- Store/listing integrity:
	- no claims for unimplemented Google integrations,
	- ARCore-required-device behavior clearly disclosed,
	- known device limitations documented.
- Quality and safety:
	- crash/perf logging excludes sensitive payloads,
	- accessibility pass completed for critical flows,
	- basic regression pass on camera/sensor/location deny scenarios.

Definition of done for any Play-affecting pull request:

- README updated for feature and policy impact.
- Manifest and runtime permission rationale documented.
- Data-safety delta documented (or explicitly "no data-safety change").
- Reviewer confirms no Play policy blocker remains.

## Play Console Submission Checklist (Owners and Signoff)

Use this section as the final gate before creating or rolling out any Play release.

Release details:

- Release name: ____________________
- Version name: ____________________
- Version code: ____________________
- Track: Internal / Closed / Open / Production
- Build artifact (AAB path): ____________________
- Commit/Tag: ____________________
- Submission date: ____________________

Owners and signoff:

- Engineering owner: ____________________ | Date: __________ | Signoff: Approved / Blocked
- QA owner: ____________________ | Date: __________ | Signoff: Approved / Blocked
- Privacy/Data Safety owner: ____________________ | Date: __________ | Signoff: Approved / Blocked
- Release manager: ____________________ | Date: __________ | Signoff: Approved / Blocked

Strict go/no-go rules:

- Any row marked `No` in the table below is an automatic `NO-GO`.
- The release is `GO` only when every row is `Yes` and all owners are `Approved`.
- Do not substitute "known issue" notes for blocker rows.

| Gate | Owner | Evidence | Pass (Yes/No) | Blocker if No |
| --- | --- | --- | --- | --- |
| Release AAB built from release variant and signed for Play upload | Engineering | CI/build artifact and signing output |  | NO-GO |
| targetSdk meets current Play requirement at submission time | Engineering | `app/build.gradle` and Play policy check |  | NO-GO |
| `versionCode` is incremented and unique for this upload | Engineering | `app/build.gradle` and Play upload validation |  | NO-GO |
| Data Safety form exactly matches shipped behavior | Privacy/Data Safety | Completed Play Console questionnaire diff/review |  | NO-GO |
| Privacy policy URL is public, reachable, and behavior-accurate | Privacy/Data Safety | Published URL and content review |  | NO-GO |
| Store listing does not claim unimplemented features | Release manager | Listing text/screenshot review |  | NO-GO |
| Permission declarations are least-privilege and justified | Engineering | Manifest review and feature mapping |  | NO-GO |
| Runtime permission prompts are contextual with deny path verified | QA | Test evidence on deny/allow flows |  | NO-GO |
| Core app works without optional location permission | QA | Test run notes/screenshots |  | NO-GO |
| ARCore-required-device limitation is disclosed in listing | Release manager | Listing/device support text review |  | NO-GO |
| Crash/perf telemetry excludes sensitive image/location payloads | Engineering | Logging/telemetry configuration review |  | NO-GO |
| Final QA smoke pass complete on intended track build | QA | Test report and signoff |  | NO-GO |

Final decision:

- GO / NO-GO: ____________________
- Decision owner: ____________________
- Decision date: ____________________
- Notes (required if NO-GO): ____________________

---

## Known Issues — Medium Severity (Pending Resolution)

These issues do not block the current prototype but must be reviewed and resolved before any production or wide Play Store release. Each entry describes the problem in detail and proposes a concrete fix for the engineer who picks it up.

---

### M-1: Metadata file rewritten on every draft capture (O(items × frames) I/O)

**Location:** `SpherifyLibrary.save()`, called from `captureFrame()` in `CaptureActivity`.

**Description:** Every time a draft frame is captured, `SpherifyLibrary.save()` serialises the entire library metadata (all items, all fields) to `metadata.json` and overwrites the file in place. At prototype scale (tens of items, dozens of frames per session) this is imperceptible. As the library grows, the write amplification becomes significant: a session with 50 existing library entries and 30 captured frames performs 30 full rewrites of a file that grows with every import. On a mid-range device this will produce measurable latency spikes (50–200 ms per capture) and increases the risk of data loss from a partial write during a forced-stop.

**Additional risk:** The file is written with a direct `FileOutputStream` overwrite, not an atomic rename. A crash or force-stop mid-write leaves a truncated or empty `metadata.json`, destroying all library references permanently.

**Proposed fix:**
1. Write via a rename pattern: serialize to `metadata.json.tmp`, then `tmp.renameTo(metadata.json)`. This makes every write atomic — a crash leaves either the old complete file or the new complete file.
2. Add a dirty flag to `SpherifyLibrary` and expose a `flush()` method. Call `flush()` from `onPause()` in `CaptureActivity` and `MainActivity` rather than flushing on every mutation.
3. For draft frame metadata specifically, consider a separate append-only `drafts.json` that accumulates new entries and is merged into `metadata.json` on session close rather than on every frame.

---

### M-2: `readDraftMetadataOrThrow()` uses a single non-guaranteed `InputStream.read()` call

**Location:** `SpherifyLibrary.readDraftMetadataOrThrow()`.

**Description:** The method reads the entire draft metadata file with a single `inputStream.read(data, 0, data.length)` call. The contract of `InputStream.read(byte[], int, int)` only guarantees that at least one byte is transferred; it may legally return fewer bytes than requested on certain file systems or under OS buffer constraints. If the read is short, the remaining bytes are left as zeros, causing `new String(data, StandardCharsets.UTF_8)` to produce a string with embedded null bytes. The subsequent `new JSONObject(json)` call throws a `JSONException`, which is caught and reported as "metadata corrupt" — a false-positive data loss message.

**Proposed fix:** Replace the raw `InputStream.read()` call with either:
- `DataInputStream.readFully(data)` — throws `EOFException` only when the file is genuinely truncated.
- An explicit fill loop: `int offset = 0; int remaining = data.length; while (remaining > 0) { int n = stream.read(data, offset, remaining); if (n < 0) break; offset += n; remaining -= n; }`.

Either approach guarantees a complete fill before the JSON parse and eliminates the false-corruption path.

---

### M-3: `captureFrame()` spawns an untracked `new Thread()` per capture tap

**Location:** `captureFrame()` in `CaptureActivity`.

**Description:** Each tap of the Capture button calls `new Thread(() -> { ... }).start()` with no reference stored and no lifecycle management. If the user taps rapidly (five frames in quick succession) five threads run concurrently, all potentially calling `SpherifyLibrary.save()` at the same time — `save()` is not synchronized. After the user backs out or the system kills the Activity mid-capture, in-flight threads continue executing, calling `runOnUiThread()` on a destroyed Activity and potentially leaving draft metadata in an inconsistent state.

**Proposed fix:**
1. Replace ad-hoc threads with a single-threaded `ExecutorService` field: `private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor()`. Submit captures via `captureExecutor.submit(...)`. Single-thread serialisation eliminates the concurrent write race.
2. In `onDestroy()`, call `captureExecutor.shutdownNow()` to interrupt any in-flight capture.
3. Guard every `runOnUiThread()` callback inside the capture runnable with `if (!isFinishing())` to prevent updating destroyed UI.

---

### M-4: `TargetGuideView.drawTiltWarning()` allocates a `new LinearGradient` on every `onDraw()`

**Location:** `TargetGuideView.drawTiltWarning()`, called from `TargetGuideView.onDraw()`.

**Description:** `drawTiltWarning()` creates a `new LinearGradient(...)` and calls `paint.setShader(gradient)` on every redraw. During an active capture sweep the view invalidates continuously at sensor update rate (50–100 Hz). Each `LinearGradient` allocation involves both a Java heap object and a native Skia shader object. On older or memory-constrained devices this contributes to GC pressure and jank during the critical capture window when the user is physically rotating the phone.

**Proposed fix:**
1. Cache the `LinearGradient` objects as fields of `TargetGuideView`.
2. Invalidate the cache only when the view dimensions change (`onSizeChanged()`) or when the parameterizing colour/alpha values change.
3. Store the last-rendered tilt direction as a field; rebuild the gradient only when direction or dimensions differ from the cached state.

---

### M-5: No content descriptions on any interactive UI element (accessibility gap)

**Location:** All `makeButton()` calls in `CaptureActivity` and `MainActivity`; all `ImageView`s and custom `View`s that receive touch input.

**Description:** No `View.setContentDescription()` call exists anywhere in the codebase. Android Accessibility Services (TalkBack, Switch Access, Voice Access) identify interactive elements by content description when no visible text is available. Custom views (`TargetGuideView`, `CalibrationProgressView`, `CompassNeedleView`) and all icon-only buttons are completely invisible to assistive technology. This is a Play Store policy requirement for apps targeting API level 36 and is enforced during new-app review.

**Proposed fix:**
1. Add `setContentDescription(String)` calls to every `Button`, `ImageView`, and custom interactive `View` during construction in `CaptureActivity.onCreate()`, `MainActivity.onCreate()`, and the `makeButton()` helper.
2. For custom `View` subclasses, implement `onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info)` with a description that reflects current state (e.g. current tilt angle, calibration percentage).
3. Call `setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES)` on all interactive surfaces that lack text labels.

---

### M-6: `GLProjectionView.setPanorama()` calls `getPixels()` on the UI thread unconditionally

**Location:** `GLProjectionView.setPanorama()`.

**Description:** `setPanorama()` immediately calls `bitmap.getPixels(panoramaPixels, ...)` to populate a flat `int[]` array used exclusively by the CPU export path (`renderProjectionOnCpu()`). For a 4K panorama this allocates and fills up to a 32 MB `int[]` on the UI thread. This is unconditional — even for users who only browse the library without exporting. The allocation and fill time (typically 80–300 ms for 4K) produces a visible UI thread stall on every library navigation step.

**Proposed fix:**
1. Remove `getPixels()` from `setPanorama()`. Store only the `Bitmap` reference and its dimensions.
2. Call `getPixels()` lazily inside `exportProjection()` / `renderProjectionOnCpu()` immediately before the CPU render, on the background thread where export now executes (see fix C-3).
3. Cache the resulting `panoramaPixels` array and invalidate it when `setPanorama()` is called with a new bitmap. This eliminates the UI stall for all users who browse without exporting.

---

### M-7: `SpherifyLibrary.describe()` parses `drafts.json` synchronously on the UI thread

**Location:** `SpherifyLibrary.describe()`, called from `showMetadata()` in `MainActivity`.

**Description:** `describe()` calls `listDraftFrames(item.id)`, which opens, reads, and parses `drafts.json` to count frames. `showMetadata()` calls `describe()` directly from the button click handler on the UI thread. For a session with many draft frames (100+) this is a synchronous file read on the UI thread, contributing to jank and following the same unthreaded I/O pattern that was fixed for bitmap loads in C-2 and C-3.

**Proposed fix:**
1. Move `library.describe(currentItem)` inside `showMetadata()` to a background thread; post the resulting string via `runOnUiThread()` before showing the `AlertDialog`.
2. Alternatively, cache the draft frame count in the `LibraryItem` object when the item is first loaded or when a draft is saved, so `describe()` reads from memory instead of disk.
3. Long-term: introduce a `loadAsync(callback)` pattern on `SpherifyLibrary` that shifts all file reads to a dedicated executor, consistent with the background-thread approach adopted for bitmap decodes.

---

## Capture and Stitching Workflow Notes

Version 0.4.2 continues the Capture and Spherify reset from first principles. The old pipeline proved several useful facts, but it also showed that iterating on bug fixes inside the previous flow risked optimizing the wrong product. The stable viewer, flat-image, Tiny Planet, PhotoSphere browsing, export, and share paths remain intact. Capture has been rebuilt as the front end of the spherification process: guided source acquisition, graph validation, native OpenCV solving/compositing, GPano writing, and library master creation now belong to one product workflow.

The product goal is now narrower and stricter: an Android phone user should be able to create a high-quality, accurate, well-aligned, non-artefactual 360 x 180 spherical panorama suitable for local viewing and eventual Google Maps-style export. A beautiful Tiny Planet can tolerate flaws; a map-ready PhotoSphere cannot. The app should therefore stop treating capture as passive image collection and start treating it as the first stage of stitching.

### Why The Initial Design Had To Change

The first Spherify premise treated the phone camera as a generous image collector and treated spherification as a rendering problem that could be improved after capture. That was a useful exploratory premise because it proved the local library, projection viewer, Tiny Planet workflow, image import, draft-frame storage, and basic export surfaces. It also exposed the central flaw: a visually pleasing spherical viewer can hide that the source data was never measured in a way that a reliable PhotoSphere solver can trust.

The literature review changed the design standard. Google Photo Sphere, Brown and Lowe-style panorama stitching, OpenCV's detailed stitching pipeline, Hugin/PTGui-style control-point workflows, and the broader stitching literature all point to the same conclusion: the capture UI is part of the measurement system. The app cannot ask the user to sweep, paint, force-capture, or loosely aim, then expect a later blender to manufacture geometric truth. The UI must deliberately gather still frames with known approximate viewing directions, enough overlap, low motion, stable camera state, and immediate recapture feedback when the data is weak.

That forced the first major rework: capture moved from an image-acquisition feature to a graph-building instrument. A frame is no longer trusted because the user pressed a button or because it fills a visual gap. It is trusted only when it has image bytes, timestamp-paired Camera2 metadata, calibrated intrinsics, ARCore visual-inertial pose, feature-confidence evidence, parallax checks, and validated neighbour relationships. Accepted frames become graph nodes; visual overlaps become graph edges; weak or rejected candidates stay diagnostic rather than quietly becoming part of the public output.

The second rework was architectural. CameraX was suitable for broad preview and still capture, but the research-backed production path needs a single camera stream that can feed ARCore tracking and Camera2 metadata at the same time. Version 0.4.2 therefore routes production capture through ARCore `SharedCamera` with Camera2. The retired CameraX capture screen is isolated to debug builds because it cannot provide the full production contract: visual-inertial pose, tracking state, projection/view matrices, feature-confidence signals, timestamp-paired metadata, and graph-anchor records from the same capture backend.

The third rework was the stitcher. The original direction risked confusing spherification with custom reprojection and blending. The industry-standard path is registration first, then optimization, then warping, then exposure compensation, then seam finding, then multiband blending, then honest metadata. The current native OpenCV-detail pipeline was introduced to align with that sequence and to avoid reinventing established solver, seam, exposure, and blending machinery. If the native OpenCV stitching/detail symbols are unavailable or the capture graph is not valid, production master export fails closed.

The fourth rework was the product promise. Spherify should not label a partial, weak, or cosmetically blended result as a map-ready PhotoSphere. The app must distinguish an interrupted diagnostic capture session, a creative Tiny Planet source, a local master, a needs-review result, and a map-ready candidate. GPano metadata is not decoration; it is a certification record that must truthfully describe the pixels, crop, source count, dates, pose, and projection. A final output is therefore allowed only when the solved equirectangular image, metadata readback, coverage, poles, wrap seam, horizon, residuals, and file constraints support the claim.

The practical result is that Spherify's development direction moved from "make the current capture look better" to "make the capture produce solver-grade evidence." Every new edit should therefore be judged against the literature loop: guided acquisition, calibrated metadata, graph validation, global optimization, seam-aware compositing, multiband blending, GPano certification, and real-device proof.

Lessons from the retired prototype:

- Blending early hides geometry errors as blur and ghosting.
- Target yaw/pitch alone is not enough to place frames accurately.
- ARCore pose and camera intrinsics are valuable priors, but they are not final truth.
- Hand-held capture produces parallax; no single spherical warp can fully fix close-object movement.
- Post-fact quality gates are too late. Users need recapture feedback while they are still standing in the same position, and a successful capture session should proceed directly into solving/export rather than becoming a separate task.
- A sharp source-selected render is useful for diagnostics, but it is not the final public-quality rendering strategy.
- The app needs a capture graph with validated visual relationships, not a folder of draft JPEGs or a later manual "Spherify" button.

The 0.4.2 integrated PhotoSphere workflow:

1. The user chooses a capture mode: `Hand-held`, `Tripod / phone mount`, or later `External 360 camera import`.
2. The app performs a readiness check for ARCore tracking, gyro/rotation-vector quality, storage, camera intrinsics, focus, exposure, white balance, optional location, and scene risk.
3. The app locks or stabilizes exposure, white balance, and focus for the session unless an explicit HDR/bracketed mode is active.
4. The UI guides the user dot-by-dot or tile-by-tile around a sphere coverage map. Free sweeping is not the quality path.
5. Each candidate frame is captured only after alignment, stillness, tracking, focus, and metadata readiness pass.
6. The candidate frame then enters immediate analysis before it becomes accepted solver input.
7. The analysis layer checks blur, exposure clipping, low texture, noise, pose/intrinsics sanity, expected overlap, and whether the frame is likely to be useful for stitching.
8. OpenCV feature matching compares the candidate only with predicted neighboring accepted frames. ORB is the fast baseline; AKAZE/SIFT-like options can be considered for high-quality mode if licensing, binary size, and device performance are acceptable.
9. RANSAC validates the predicted overlaps. A frame is accepted only when it produces enough inlier control points with acceptable residuals, or when a documented special case applies, such as a pole frame with alternate support.
10. Failed candidates reopen the same target immediately with a short reason such as `Blurred`, `Could not align`, `Too dark`, `Moved too fast`, or `Weak overlap`.
11. Accepted frames become nodes in a persistent capture graph. Validated overlaps become graph edges with inlier count, residual, confidence, control points, and parallax-risk hints.
12. Completing capture immediately runs the spherification pipeline on that validated graph. There is no user-facing draft image set and no later manual Spherify action.
13. The pipeline removes weak, contradictory, or parallax-damaged graph edges.
14. It estimates camera/lens parameters from device intrinsics, EXIF/camera metadata, ARCore pose priors, gravity, and visual control points.
15. It runs global camera optimization. Sensor pose is a prior; optimized yaw, pitch, roll, focal/FOV, and radial distortion determine final placement.
16. It computes horizon leveling from gravity, visual alignment, and graph closure.
17. It performs exposure and color compensation, warps accepted frames to spherical/equirectangular space, chooses seams before blending, and applies multiband blending only after geometry and seams are credible.
18. It exports a full 2:1 equirectangular master with Google Photo Sphere XMP metadata, optional GPS/heading metadata, local library thumbnails/variants, and a final map-readiness review covering resolution, aspect ratio, XMP, coverage, poles, horizon, wrap seam, residual warnings, location status, and file size.

The industrial comparison remains the benchmark:

- Brown and Lowe-style panorama stitching uses invariant local features, RANSAC, bundle adjustment, gain compensation, straightening, and multiband blending.
- OpenCV's detailed stitcher separates feature finding, pairwise matching, camera estimation, bundle adjustment, warping, exposure compensation, seam finding, and blending.
- Hugin/PTGui-style workflows center on control points and global optimization of image/lens parameters.
- Google/Street View-style phone capture succeeds by constraining user movement and accepting frames only when the user aligns and holds still.
- Ricoh Theta/Insta360-style hardware has fixed, calibrated lenses and near-simultaneous capture, so phone-only Spherify must compensate with stricter capture validation and should recommend a tripod/phone-mount mode for publish-quality work.

Implementation mandate for the rework:

- Do not add more public polish to the retired split Capture/Spherify path unless it directly supports the integrated graph-based architecture.
- Preserve the local library/viewer/share/export behavior while capture-time spherification is rebuilt.
- Prefer small, testable subsystems: readiness scoring, candidate quality scoring, OpenCV overlap validation, capture graph persistence, final graph optimizer, seam finder, blender, and Google-ready export validator.
- Keep diagnostic tools, but do not confuse diagnostic renders with user-facing map-ready output.

### Version 0.7.x Capture/Spherify Rework Sub-Phases

These sub-phases preserve the research-backed working sequence for revising the entire Capture and Spherify functionality. Version labels in this section describe design milestones; the active app version remains 0.4.2.

#### Version 0.7.1: Capture Foundation and Session Graph

Goal: replace the current draft-frame mental model with a durable capture-session model that can support validation, recapture, final solving, diagnostics, and later export.

Scope:

- Keep the existing library, import, viewer, Tiny Planet, PhotoSphere, export, and share routes operational.
- Add a new capture-session data model without deleting the old draft-frame compatibility path yet.
- Define first-class session records, source-frame records, candidate-frame records, accepted-frame records, rejected-frame records, and graph-edge records.
- Store raw capture facts separately from analysis results. Raw facts include file path, timestamp, target yaw/pitch, captured pose, intrinsics, focus/exposure/white-balance metadata, location summary, capture profile, and device/camera identifiers.
- Store analysis facts separately. Analysis facts include blur score, exposure score, texture score, predicted-overlap set, OpenCV/RANSAC result, inlier count, residual score, confidence, parallax-risk hint, and rejection reason.
- Introduce capture modes: `Hand-held`, `Tripod / phone mount`, and reserved `External 360 camera import`.
- Add a readiness screen or readiness panel that checks ARCore tracking, gyro/rotation-vector stability, camera intrinsics availability, storage, camera permission, optional location, and exposure/focus lock status.
- Add a session-level state machine: `new`, `ready`, `capturing`, `candidate_pending_analysis`, `needs_recapture`, `capture_complete`, `valid_for_spherify`, `spherifying`, `master_created`, `needs_review`, and `failed`.
- Preserve recovery if capture is interrupted. A reopened session must show accepted, rejected, missing, and weak targets without corrupting the local library.

Deliverables:

- New or revised Java classes for capture sessions, frame records, graph edges, and session status.
- Persistence format documented in README, with migration notes for existing `drafts.json` records.
- UI path that can create a new session and display readiness/capture state even before OpenCV validation is fully enabled.
- A diagnostic session viewer that lists raw facts and empty analysis values for older or interrupted frames.

Acceptance criteria:

- A user can start a new 0.7.1-format capture session and return to the library without breaking existing imports/viewers.
- Existing pending/draft sessions remain visible or recoverable through compatibility handling.
- Session metadata survives app restart.
- No Spherify master is created from an unvalidated 0.7.1 session unless explicitly marked diagnostic.

Implemented persistence format:

- `library/metadata.json` remains the gallery index. A 0.7.1 session is exposed as a `draft_session` library item with `source: "capture_0_7_1"` and `projection: "capture_session"`. The item is only a visible handle; the durable session graph lives separately.
- `library/drafts.json` remains the compatibility frame index for existing draft sessions and Phase 5 research code. New captures still append compatible rows so old draft browsers and frame loaders can recover source JPEGs.
- `library/capture-sessions.json` is the 0.7.1 session graph index. It is a JSON array of session records:

```json
{
  "formatVersion": 71,
  "id": "26072312-123",
  "title": "Capture Session 26072312-123",
  "createdAt": 1784800000000,
  "updatedAt": 1784800000000,
  "captureMode": "handheld",
  "status": "candidate_pending_analysis",
  "diagnosticSpherifyAllowed": false,
  "readiness": {
    "cameraPermission": true,
    "arCoreTracking": true,
    "gyroRotationVectorStable": true,
    "cameraIntrinsicsAvailable": true,
    "storageAvailable": true,
    "locationOptional": false,
    "exposureFocusLockStatus": "AE 2, AWB 2, AF unknown",
    "captureProfile": "handheld",
    "deviceCameraId": "manufacturer model"
  },
  "frames": [],
  "graphEdges": []
}
```

- Each captured JPEG is represented by candidate/source/accepted or rejected graph records so raw provenance and analysis remain separate.
- `rawFacts` stores file path, timestamp, target yaw/pitch, captured yaw/pitch/roll, pose availability, intrinsics, focus/exposure/white-balance metadata, location summary, capture profile, and device/camera identifiers.
- `analysisFacts` stores blur score, exposure score, texture score, predicted-overlap set, OpenCV/RANSAC result, inlier count, residual score, confidence, parallax-risk hint, and rejection reason. Before OpenCV analysis lands, numeric scores use pending sentinel values and RANSAC is `pending`.
- `graphEdges` stores validated overlap edges with frame ids, inlier count, residual score, confidence, parallax-risk hint, and future control points.

Migration notes:

- Existing `drafts.json` records are not rewritten. On library load, Spherify still reconciles them into visible `draft_session` records so pending captures remain browseable and recoverable.
- New 0.7.1 sessions can exist before the first frame is captured. The Pending library filter includes these recoverable sessions even when no representative JPEG exists yet.
- Existing legacy draft sessions do not bypass the 0.4.2 guided graph gate. Production master creation remains blocked unless the session is validated and the real optimizer/compositor dependency is available.

#### Version 0.7.2: Candidate Capture, Quality Gate, and OpenCV Acceptance

Goal: make capture an active acceptance process. A frame should become part of the source set only after metadata, stillness, image quality, and visual overlap checks pass.

Scope:

- Route all capture taps and auto-capture events through a single candidate pipeline.
- Capture candidates only when alignment, pitch target, angular velocity, ARCore tracking, image timestamp, and camera metadata readiness pass.
- Add image-quality scoring for blur/sharpness, exposure clipping, low-light noise, low texture, and likely motion smear.
- Lock or stabilize exposure, white balance, and focus for a session unless an explicit future HDR/bracketed mode is enabled.
- Predict which accepted frames should overlap the candidate from target geometry, captured pose, intrinsics, and capture mode.
- Run OpenCV feature detection and matching against predicted neighbors only, not the entire session.
- Use ORB as the default fast matcher. Keep AKAZE/SIFT-like alternatives behind a later high-quality/experimental switch only after licensing, APK size, and device performance are reviewed.
- Validate matches with RANSAC and store inlier control points, residuals, confidence, and pair-level rejection reasons.
- Accept, reject, or mark the candidate as needing recapture while the user is still on the same target.
- Reopen weak targets immediately with short user-facing reasons: `Blurred`, `Could not align`, `Too dark`, `Moved too fast`, `Weak overlap`, or `Metadata incomplete`.
- Keep analysis on background executors so the camera preview and guidance UI remain responsive.

Deliverables:

- Candidate capture queue with lifecycle-safe background execution.
- Quality scoring module.
- OpenCV overlap validator module.
- Capture graph updates when candidates are accepted.
- Recapture UI states for rejected or weak candidates.
- Debug output that explains why every candidate was accepted or rejected.

Acceptance criteria:

- A deliberately blurred candidate is rejected before becoming an accepted source frame.
- A candidate with missing timestamp-matched metadata is rejected or retried without writing partial source records.
- A candidate with weak predicted overlap is reopened for recapture.
- Accepted frames have at least one valid graph edge when overlap support is expected.
- The app can complete a small test session with accepted, rejected, and recaptured frames visible in diagnostics.

Implemented 0.7.2 behavior:

- Manual capture and sweep/polar auto-capture now flow through one candidate commit path backed by a single lifecycle-owned background executor.
- Timestamp-matched image and camera metadata remain a hard precondition. If metadata is missing, capture retries without writing a partial source record.
- Candidate JPEGs are quality-scored before acceptance. The current local scorer rejects excessive motion, blur, poor exposure, and low texture with the user-facing reasons `Moved too fast`, `Blurred`, `Too dark`, or `Weak overlap`.
- Accepted candidates are appended to legacy `drafts.json` for compatibility and written to `capture-sessions.json` as candidate, source, and accepted frame records.
- Rejected candidates are not appended to `drafts.json`; they remain visible in session diagnostics as candidate and rejected frame records with raw facts and analysis scores.
- Predicted overlap is limited to nearby accepted frames by target yaw/pitch. If overlap support is expected but no accepted neighbor is predicted, the target is reopened as `Weak overlap`.
- OpenCV ORB with Hamming matching and RANSAC validates predicted neighbor overlaps. Accepted overlap support creates graph-edge records with inlier count, residual, confidence, parallax hint, and sampled control points.
- The capture UI keeps recent candidate outcomes visible long enough for recapture decisions while leaving the camera preview responsive.

#### Version 0.7.3: Graph-Based Spherify Solver and Render Pipeline

Goal: make Spherify consume a validated capture graph and produce a geometrically credible equirectangular master through global optimization, not independent frame placement.

Scope:

- Block normal Spherify for sessions that do not meet graph-readiness requirements.
- Load accepted frames and graph edges from one capture session.
- Remove weak, contradictory, disconnected, or parallax-damaged edges before solving.
- Build a camera/lens prior from ARCore pose, camera intrinsics, focal metadata, target geometry, and capture profile.
- Implement a real global optimization layer or integrate an appropriate optimizer. Optimized variables should include yaw, pitch, roll, focal/FOV, and radial distortion at minimum.
- Treat ARCore/captured pose as a prior, not as final truth.
- Convert inlier control points into reprojection residuals rather than using only average dx/dy offsets.
- Add horizon leveling from gravity, graph closure, and optional visual horizon/line cues.
- Add exposure and color compensation, starting with per-image gain and leaving per-block compensation as the next quality step if needed.
- Render to a full 2:1 equirectangular master from optimized camera parameters.
- Keep sharp source-selected and contributor-map renders as diagnostics, but do not present them as final public output.

Deliverables:

- Graph-readiness gate for Spherify.
- Solver module with progress reporting and failure reasons.
- Optimized camera/lens model persisted into stitch diagnostics.
- Equirectangular render path driven by optimized poses.
- Post-solve diagnostic report: frames used, edges used/rejected, mean/max residual, coverage, closure quality, parallax warnings, and exposure compensation.

Acceptance criteria:

- Spherify refuses an incomplete or disconnected graph with a readable reason.
- A valid test capture produces a 2:1 equirectangular master from optimized frame placement.
- Diagnostic output reports residuals and graph health, not only frame counts.
- Re-running Spherify on the same session is deterministic enough for visual comparison.
- Old diagnostic stitch paths remain available only where they help engineering compare the new solver against the retired pipeline.

#### Version 0.7.4: Seam, Blend, Map-Ready Export, and Public Quality Review

Goal: turn a geometrically solved panorama into a polished, exportable PhotoSphere with explicit quality review before the app implies map readiness.

Scope:

- Add seam selection before normal blended export.
- Prefer seams through frame centers and low-residual regions.
- Penalize seams through moving objects, close-object parallax, low-confidence overlaps, poles with weak support, and exposure discontinuities.
- Add multiband blending after seam selection. Blending must be the cosmetic final stage, not a substitute for geometry.
- Add final hole/gap detection, wrap-seam validation, pole inspection, horizon check, and coverage review.
- Export full 360 x 180 equirectangular JPEG masters with exact 2:1 aspect ratio.
- Embed Google Photo Sphere XMP metadata, including `GPano:ProjectionType=equirectangular`, full/cropped dimensions, optional location, heading, timestamp, and software attribution where appropriate.
- Add a map-readiness screen that separates `Local master`, `Creative export`, `Needs review`, and `Map-ready` states.
- Keep direct Google Maps publishing out of scope unless implemented, tested, and covered by privacy/store-policy updates.
- Add a regression set of real capture sessions: indoor hand-held, outdoor hand-held, tripod/mount, low-light, low-texture, moving-object, and close-object parallax.

Deliverables:

- Seam finder module.
- Multiband blender or selected OpenCV/detail blender integration.
- Final export validator.
- Google Photo Sphere XMP writer/validator.
- Public quality review UI.
- Test capture corpus and documented QA checklist.

Acceptance criteria:

- A solved panorama exports as a valid 2:1 JPEG with Photo Sphere XMP recognized by common 360 viewers.
- The app flags missing location/heading separately from stitch quality.
- The app refuses or marks as `Needs review` any panorama with major gaps, broken wrap seam, excessive residuals, or weak pole coverage.
- Blended output improves appearance without hiding large geometry errors.
- The local viewer/share/export flows remain stable after the new master is created.

#### Version 0.7.5: Real Optimizer Core

Goal: replace the prototype graph-solver math with a proven global camera/lens optimization core while preserving the current capture graph, diagnostics, and public output contract.

Trigger phrase:

- `Implement Version 0.7.5 Real Optimizer Core`

Why this is separate:

- Geometry quality must be proven before seam finding or blending changes. A blender can hide small appearance differences, but it must not compensate for bad camera placement.
- The current graph-aware solver is deterministic and useful as scaffolding, but it is not equivalent to industrial bundle adjustment. This phase should establish the geometry foundation that later phases consume.

Scope:

- Evaluate OpenCV `detail` bundle adjustment via native NDK integration versus a dedicated nonlinear optimizer such as Ceres or g2o.
- Select one optimizer route and document why it is appropriate for Android, APK size, runtime, maintainability, and available Java/NDK bindings.
- Convert capture graph edges and sampled inlier control points into optimizer residual blocks.
- Optimize camera variables at minimum: yaw, pitch, roll, focal/FOV, principal point where usable, aspect where usable, and radial distortion.
- Treat ARCore pose, capture targets, gravity/horizon, intrinsics, focal metadata, and capture profile as priors with explicit weights.
- Add robust loss functions so moving-object and parallax-damaged residuals do not dominate the solve.
- Keep graph cleanup before solving: reject disconnected, contradictory, low-confidence, weak-inlier, and high-parallax edges.
- Add solver convergence reporting: initial cost, final cost, iteration count, residual distribution, parameter deltas, and failure reason.
- Preserve the current renderer as a downstream consumer of optimized cameras so visual changes can be attributed to geometry only.

Deliverables:

- Native or Java optimizer module with a stable interface from `CaptureSessionRecord` to optimized camera/lens results.
- Residual construction from real control points, not average dx/dy offsets.
- Robust priors for ARCore/capture-target/gravity/intrinsics/focal metadata.
- Solver diagnostic report persisted beside stitch/export diagnostics.
- Engineering comparison mode that runs prototype solver versus real optimizer on the same session without changing public output labels.

Acceptance criteria:

- The same valid graph produces deterministic optimized camera parameters across repeated runs within a small tolerance.
- Inlier reprojection residuals decrease from initial priors to final optimized cameras on real test captures.
- A disconnected or ill-conditioned graph fails with a readable solver reason.
- Horizon/closure quality improves or remains stable compared with the 0.7.4 prototype solver.
- Existing local viewer/share/export flows still open masters created from the new optimized parameters.

Implementation notes:

- Prefer proven optimization and camera-model code over hand-rolled gradient loops.
- Keep ORB/RANSAC as the default graph edge source for this phase; learned matching is deliberately deferred.
- Do not change seam selection, exposure compensation, or blending in this phase except where the old code cannot consume the new optimized camera model.

#### Version 0.7.6: Industrial Compositing

Goal: replace heuristic seam scoring, per-image-only exposure correction, and cosmetic smoothing with production-style exposure compensation, graph-cut seam masks, and true multiband blending.

Trigger phrase:

- `Implement Version 0.7.6 Industrial Compositing`

Why this is separate:

- Compositing quality depends on solved geometry. This phase assumes 0.7.5 has already produced credible optimized camera/lens parameters.
- Seam and blend changes affect visual appearance heavily, so they need independent diagnostics and regression comparisons.

Scope:

- Integrate OpenCV `detail::ExposureCompensator` equivalent behavior, preferably block gain compensation, or implement a documented block-based gain model if bindings require a local implementation.
- Integrate OpenCV `detail::GraphCutSeamFinder` or `DpSeamFinder` through NDK where Java bindings are insufficient.
- Generate seam masks at reduced seam resolution, then rescale/refine masks for final full-resolution composition.
- Penalize seam paths through low-confidence overlaps, high residuals, moving-object hints, close-object parallax hints, weak pole support, and exposure discontinuities.
- Replace cosmetic smoothing with real multiband blending using Laplacian pyramids or OpenCV `detail::MultiBandBlender`.
- Keep blending as the final cosmetic stage after geometry, exposure compensation, seam masks, and source warping.
- Produce diagnostic renders: seam mask map, source contributor map, exposure gain map, residual heatmap, and blended final.

Deliverables:

- Exposure compensator module with per-block gain diagnostics.
- Seam finder module producing explicit masks.
- True multiband blender or OpenCV/detail integration.
- Debug/export artifacts for seam masks, exposure gains, contributor map, and final blend.
- Performance telemetry for memory use, peak bitmap allocations, and processing time on representative devices.

Acceptance criteria:

- Seam masks are coherent connected regions, not only per-pixel source preferences.
- Block exposure compensation reduces visible exposure discontinuities without crushing local contrast.
- Multiband blending improves seam appearance without hiding large geometry errors.
- Diagnostic contributor-map and seam-mask renders remain available for engineering but are not presented as final public output.
- A valid 0.7.5 solved panorama exports through 0.7.6 compositing without breaking local viewer/share/export flows.

Implementation notes:

- OpenCV's detailed stitching pipeline defaults are a good baseline: spherical warp, graph-cut seam estimation, gain-block exposure compensation, and multiband blending.
- If NDK integration is required, keep the Java/Kotlin-facing API narrow: inputs are optimized cameras, source files, masks/diagnostic options, and output target.
- Do not loosen geometry validation thresholds just because blending looks better.

#### Version 0.7.7: Export Certification

Goal: make `Map-ready` a verified export state backed by metadata readback, raster validation, policy-safe UI, and real-capture regression evidence.

Trigger phrase:

- `Implement Version 0.7.7 Export Certification`

Why this is separate:

- Export trust is a product and policy boundary, not just an image-processing step.
- Metadata, location, heading, quality review, and sharing behavior must be validated after the final JPEG is written.

Scope:

- Add a Photo Sphere metadata readback validator that reopens the saved JPEG and verifies GPano XMP after writing.
- Verify required full-sphere tags: `GPano:ProjectionType=equirectangular`, full/cropped dimensions, cropped offsets, viewer flag, stitching software, source photo count where available, first/last photo date where available, and orientation tags where available.
- Write and validate EXIF GPS tags when location permission/data exists; keep location optional and separate from stitch quality.
- Validate heading separately from location and stitch quality. Missing heading should prevent Google Maps readiness but not local master creation.
- Add post-export checks for exact 2:1 dimensions, JPEG readability, XMP packet placement, wrap seam, holes/gaps, poles, horizon/closure, residual thresholds, and largest valid interior region.
- Strengthen public quality review states:
  - `Local master`: valid local 360 image, but lacks map metadata or certification.
  - `Creative export`: visually useful but not suitable for map/public evidence because of parallax, missing metadata, or content/scene risks.
  - `Needs review`: major gaps, broken wrap seam, excessive residuals, weak poles, failed XMP readback, or horizon/closure failure.
  - `Map-ready`: all export, metadata, quality, and policy gates pass.
- Keep direct Google Maps publishing out of scope unless a later phase implements, tests, documents, and policy-reviews it.
- Automate the real-capture regression checklist as far as practical; keep manual visual signoff where viewers or physical scenes are required.

Deliverables:

- XMP/EXIF writer plus readback validator.
- Export certification report persisted with the master diagnostics.
- Public quality review UI that explains stitch blockers separately from location/heading metadata blockers.
- Regression corpus manifest for indoor hand-held, outdoor hand-held, tripod/mount, low-light, low-texture, moving-object, and close-object parallax captures.
- QA checklist updates with expected state for each corpus session.

Acceptance criteria:

- A certified full-sphere JPEG is recognized as a Photo Sphere by common 360 viewers.
- Missing location and missing heading are reported separately and do not masquerade as stitch failures.
- Any failed XMP readback, broken wrap seam, major gap, excessive residual, weak pole, or horizon/closure failure results in `Needs review`.
- `Map-ready` appears only when raster, metadata, and quality checks all pass.
- Local viewer/share/export flows remain stable for `Local master`, `Creative export`, `Needs review`, and `Map-ready` outputs.

Implementation notes:

- Use metadata readback as the source of truth, not assumptions from the write path.
- Privacy and Play Store text must be reviewed before any UI implies public map publishing.
- Do not add broad media-library permissions for certification.

#### Version 0.7.8: Learned Matching Fallback

Goal: add a selective learned-feature fallback for weak overlaps and low-texture captures without replacing the fast classical path or bloating normal capture latency.

Trigger phrase:

- `Implement Version 0.7.8 Learned Matching Fallback`

Why this is separate:

- Learned matching affects APK size, memory, heat, battery, latency, and device compatibility.
- It is valuable for hard cases, but it should not be required for every normal overlap when ORB/RANSAC is already sufficient.

Scope:

- Evaluate mobile deployment options for SuperPoint + LightGlue, DISK + LightGlue, and LoFTR-style matching through ONNX Runtime Mobile, TensorFlow Lite, or another Android-suitable runtime.
- Compare model size, supported acceleration, CPU fallback performance, memory peak, licensing, and offline behavior.
- Use learned matching only as a fallback for:
  - ORB/RANSAC failure on predicted overlaps,
  - low-texture regions,
  - repeated-pattern ambiguity,
  - low-light captures,
  - final graph repair before solve.
- Store learned-match provenance separately from ORB/OpenCV provenance in graph diagnostics.
- Add confidence calibration so learned edges and ORB edges can coexist in the same graph without over-trusting either source.
- Keep a user-facing performance posture: the camera UI must remain responsive and background analysis must have clear progress/failure states.

Deliverables:

- Model/runtime evaluation note in README or docs.
- Optional learned matcher module with feature flag or quality-mode gate.
- Graph edge provenance field for classical versus learned matching.
- Regression comparison across low-texture, low-light, moving-object, and close-parallax corpus sessions.
- Performance report for at least one mid-range device and one higher-end device.

Acceptance criteria:

- Learned fallback improves accepted valid edges or residual quality on low-texture/low-light sessions without regressing ordinary sessions.
- Normal ORB/OpenCV capture remains available when the learned runtime or model is unavailable.
- APK size, memory, and latency remain acceptable for Play distribution and common Android devices.
- Learned edges that disagree with graph closure or priors are rejected or downweighted rather than blindly accepted.
- Public output quality review remains conservative; learned matching cannot force `Map-ready` by itself.

Implementation notes:

- LightGlue is attractive for adaptive sparse matching; LoFTR is attractive for low-texture semi-dense matching but may be heavier on mobile.
- Do not add network inference. Matching must work offline on-device unless a future cloud workflow is explicitly designed, consented, and policy-reviewed.
- Keep this phase optional until 0.7.5-0.7.7 have made geometry, compositing, and export certification trustworthy.

Capture metadata reliability incident:

After the hard metadata gate was added, captures could stall with "capture waiting for exposure metadata". The immediate cause was that image bytes and metadata had been cached separately, and later that the app still depended on "latest" ARCore metadata rather than proving that an image and its metadata described the same sensor frame. The 0.5.14 workflow introduced capture-ready packets: metadata is stored by `SENSOR_TIMESTAMP`, the ARCore CPU `Image` is checked by `Image.getTimestamp()`, and a capture-ready packet is published only when the image and metadata timestamps can be paired and every required field passes validation. The Camera2 `TotalCaptureResult` callback path now writes into the same metadata buffer, which is the direction needed for the full industrial SharedCamera pipeline.

The 0.5.15 investigation showed that the remaining `buffer=0` failure was not a camera permission issue. Log output confirmed `cameraPermission=true`, ARCore session creation, CPU image delivery, and tracking, but `Frame.getImageMetadata()` processing failed because the app read `ImageMetadata.CONTROL_AE_STATE` with the wrong scalar accessor. ARCore reported `Wrong return type for ImageMetadata key: 65567`, aborting the entire metadata JSON build before any packet could be stored. The app now reads optional ARCore scalar metadata with type-aware fallbacks, records compact metadata key diagnostics, and keeps the downstream strict gate intact.

See `docs/CAPTURE_STITCHING_DEEP_DIVE.md` for the detailed research-backed direction and reference links.

---


### Version 0.4.5 Progress

This build tightens viewport manipulation:

- Makes Tiny Planet single-finger horizontal dragging adjust camera yaw instead of also spinning roll.
- Clamps viewer pitch in both Photo Sphere and Tiny Planet modes so dragging cannot flip the projected geometry through a pole.
- Clamps restored pitch state from older sessions before rendering.

### Version 0.4.4 Progress

This build separates viewer distance from zoom:

- Changes the former Field of view adjustment into Camera distance.
- Keeps pinch zoom as the focal-length-like magnification control.
- Applies camera distance to both GPU preview and exported Photo Sphere/Tiny Planet variants.

### Version 0.4.3 Progress

This build improves Photo Sphere and Tiny Planet touch interaction:

- Maps two-finger vertical dragging to Horizon reference adjustment.
- Maps two-finger horizontal dragging to Field of view adjustment.
- Keeps pinch zoom and two-finger rotation available alongside the new drag adjustments.

### Version 0.4.2 Progress

This build improves large draft-session cleanup:

- Adds `Remove All Drafts` to the Draft Frames browser.
- Requires a second confirmation dialog before bulk removal.
- Deletes all draft JPEGs from app storage and resets `drafts.json` to an empty array.
- Keeps single draft-frame swipe deletion available for targeted cleanup.

### Version 0.2.8 Progress

This build implements the Phase 4 guided capture reset:

- Enables auto-capture by default when the camera is aligned with the active target dot.
- Shows a one-second shrinking pie countdown over the active capture dot while target lock is held.
- Continues the countdown without requiring extra device movement, so a steady phone can trigger capture.
- Dampens the guide grid with additional yaw/pitch smoothing to reduce noisy dot movement.
- Lets Draft Frames use the same left-swipe confirmation deletion pattern as library images.
- Removes deleted draft JPEGs and their matching `drafts.json` metadata rows.

### Version 0.2.7 Progress

This build makes the first inroads into Phase 4 guided PhotoSphere capture:

- Adds a live yaw/pitch target grid over the CameraX preview.
- Adds a center reticle with alignment and steady-hold feedback.
- Adds target coverage progress so missing/weak coverage is visible before stitching exists.
- Gates manual capture on compass calibration, target alignment, and short stability dwell.
- Adds auto-capture that reuses the same alignment and stability checks when enabled.
- Adds pause/resume, undo, cancel, and finish controls for guided capture sessions.
- Persists a recoverable draft session id across interruptions until the user finishes or cancels.
- Records per-frame session id, measured heading, pitch, roll, target yaw/pitch, timestamp, location summary, Camera2 exposure/focus/white-balance metadata where available, and derived intrinsics in `drafts.json`.

### Version 0.2.6 Progress

This bugfix build tightens the capture-mode status experience:

- Simplifies the capture status bar to only show `Calibration needed`, `Capture ready`, or `Image captured`.
- Keeps detailed calibration data out of the status bar and in the graphical calibration panel or optional sensor overlay.
- Preserves `Image captured` after a successful frame instead of immediately overwriting it with live sensor updates.

### Version 0.2.5 Progress

This bugfix build advances Phase 3 capture readiness and app packaging:

- Uses `images/icon.png` as the Android app badge and launcher icon, including adaptive and density-specific launcher resources.
- Locks image capture mode to portrait orientation.
- Keeps a north pointer visible on the live camera preview.
- Requires a deliberate compass calibration step before draft frame capture can unlock.
- Explains compass calibration in simple language through a closeable popup.
- Dampens noisy compass calibration data with low-pass sensor filtering and circular heading smoothing.
- Adds a graphical calibration progress panel for time, accuracy, heading steadiness, rotation coverage, and tilt coverage.
- Keeps the sensor diagnostics overlay available through a toggle on the live capture image.

### Export APK for Previewers

Use this when you want to commit or share a downloadable APK from the repository for app previewers.

1. Build the debug APK from the repository root.

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:assembleDebug
```

2. Copy the generated APK into the repository preview folder with the current version and a short one- or two-word edit description in the filename. Use `spherify-{version}-{description}.apk`, not a generic `-debug` suffix.

Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force preview-apks
Copy-Item app\build\outputs\apk\debug\app-debug.apk preview-apks\spherify-{version}-{description}.apk
```

macOS/Linux:

```bash
mkdir -p preview-apks
cp app/build/outputs/apk/debug/app-debug.apk preview-apks/spherify-{version}-{description}.apk
```

3. Commit the copied APK only when you intentionally want previewers to download that exact build from the repository.

Previewers can install the APK on an Android device by downloading the latest descriptive APK in `preview-apks/`, such as `preview-apks/spherify-{version}-{description}.apk`, enabling installation from their browser or file manager if prompted, and opening the file on the device. Developers can also install it over USB with:

```powershell
adb install -r preview-apks\spherify-{version}-{description}.apk
```

The repository contains:

- `settings.gradle` or `settings.gradle.kts`.
- A root `build.gradle` or `build.gradle.kts`.
- A Gradle wrapper: `gradlew`, `gradlew.bat`, and the `gradle/wrapper/` directory.
- An Android app module, usually named `app/`.
- An Android manifest at a path like `app/src/main/AndroidManifest.xml`.
- A debug build variant, usually available as `app:assembleDebug`.
- A runnable launcher activity.

The goal of this runbook is to install and run the developer/debug version of Spherify from VS Code in two places:

- A physically linked Android phone or tablet connected by USB.
- An Android Emulator virtual device, which acts as a sandboxed developer device.

### Required Tools

Install these before attempting either run path.

1. Install Visual Studio Code.
2. Install Git.
3. Install Java/JDK at the version required by the Android Gradle Plugin chosen by the project.
4. Install the Android SDK.
5. Install Android SDK Platform-Tools.
6. Install Android SDK Build-Tools.
7. Install the Android SDK Platform for the target API level chosen by the project.
8. Install the Android Emulator package if emulator testing is needed.
9. Install Android command-line tools so `sdkmanager`, `avdmanager`, `adb`, and `emulator` are available.
10. Keep Android Studio installed if desired as the provider of the Android SDK and emulator backend, but do not use Android Studio as the IDE for normal Spherify development.
11. On Windows, install the Google USB Driver if using a Google Pixel or another compatible device.
12. On Windows, install the device manufacturer's USB driver if using a non-Google device that needs one.
13. Make sure there is enough free disk space for VS Code, Android SDK packages, Gradle caches, emulator images, build outputs, and app data.
14. Use a reliable USB data cable for physical-device testing. Some cables charge only and cannot transfer data.

Useful VS Code areas:

- Explorer: browse and edit project files.
- Source Control: inspect Git changes.
- Terminal: run Gradle, ADB, emulator, and SDK commands.
- Problems: inspect compiler, Kotlin, XML, and lint issues reported by extensions or tasks.
- Extensions: install Kotlin, Gradle, XML, Android, and debugger helpers if they are useful for the project.

Recommended VS Code extensions once app code exists:

- Kotlin language support.
- Gradle task support.
- XML support.
- Android resource/file helpers if preferred.
- A Java debugger if JVM debugging is needed.

Android Studio may be installed on the same machine, but the intended development rule is simple: edit, build, run, and inspect from VS Code and terminal commands. Only use Android Studio for rare SDK or emulator administration if the command-line tools are broken or missing.

Useful command-line tools:

- `adb`: Android Debug Bridge. It lists devices, installs APKs, opens shells, reads logs, and starts activities.
- `gradlew.bat`: the Windows Gradle wrapper script committed with the project.
- `./gradlew`: the macOS/Linux Gradle wrapper script committed with the project.
- `emulator`: the Android Emulator command-line launcher.
- `sdkmanager`: installs Android SDK packages from the command line.
- `avdmanager`: creates and manages Android Virtual Devices from the command line.
- `java`: runs Java tooling used by Gradle and Android builds.

On Windows PowerShell, run project commands from the repository root:

```powershell
cd C:\GitHub\Spherify
```

On macOS or Linux, run project commands from wherever the repository was cloned:

```bash
cd /path/to/Spherify
```

### First Repository Check

Do this before trying to run on either a real device or an emulator.

1. Open a terminal.
2. Change into the repository root.
3. Check that the repository has app code.

Windows PowerShell:

```powershell
Get-ChildItem
```

macOS/Linux:

```bash
ls
```

4. Look for `gradlew.bat` on Windows or `gradlew` on macOS/Linux.
5. Look for `settings.gradle` or `settings.gradle.kts`.
6. Look for an app module directory, usually `app`.
7. If those files are missing, stop and restore the Android project scaffold before building.
8. If those files exist, continue.

### Open the Project in VS Code

1. Start VS Code.
2. Choose `File > Open Folder`.
3. Browse to the local repository folder.
4. Select the repository root, not the `app/` directory.
5. Trust the workspace only if this is your local checkout of Spherify.
6. Open the VS Code terminal with ``Ctrl+` ``.
7. Confirm the terminal is at the repository root.
8. If it is not, change into the repository root.
9. Check the Java installation.

```bash
java -version
```

10. Check that ADB is available.

```bash
adb version
```

11. Check that the emulator command is available if emulator testing will be used.

```bash
emulator -version
```

12. Check that the Gradle wrapper exists once app code has been scaffolded.

Windows PowerShell:

```powershell
Test-Path .\gradlew.bat
```

macOS/Linux:

```bash
test -f ./gradlew && echo "gradlew found"
```

13. If `adb`, `emulator`, or Java are not found, fix the system `PATH` or Android SDK/JDK installation before continuing.
14. If `gradlew` or `gradlew.bat` is missing, stop. The Android project scaffold has not been created yet.
15. Use VS Code Explorer to edit files.
16. Use VS Code Source Control to review changes before committing.
17. Use the VS Code terminal for all build, install, run, reset, and log commands.

### Build the Debug App

This checks that the developer build can compile before attempting installation.

VS Code terminal path:

1. Open the repository in VS Code.
2. Open the integrated terminal.
3. Confirm the terminal is at the repository root.
4. Run the debug build command.

Command-line path on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

Command-line path on macOS/Linux:

```bash
./gradlew :app:assembleDebug
```

Expected successful result:

- Gradle ends with `BUILD SUCCESSFUL`.
- A debug APK exists under a path similar to `app/build/outputs/apk/debug/app-debug.apk`.

If the build fails:

1. Read the first real error, not only the final `BUILD FAILED` line.
2. If the error says an SDK is missing, install that SDK with `sdkmanager` or the installed Android SDK tooling.
3. If the error says Java is wrong, use the JDK version required by the Android Gradle Plugin.
4. If the error says a dependency cannot be resolved, check the internet connection and Gradle repositories.
5. If the error says a file is missing, confirm the file exists in Git and was not ignored.
6. Re-run the build after fixing the first error.

Example SDK install command:

```bash
sdkmanager "platform-tools" "build-tools;35.0.0" "platforms;android-35"
```

The exact API level and build-tools version should match the future project configuration.

### Run on a Physically Linked Android Device

This path installs and runs the debug version of Spherify on a real phone or tablet connected to the development computer.

Use this path when testing:

- Real camera behavior.
- Real gyroscope, accelerometer, compass, and rotation-vector behavior.
- Real location behavior.
- Real storage and MediaStore behavior.
- Real performance and thermal behavior.
- Real permission prompts.

The emulator is useful, but it cannot fully prove Spherify's capture experience because PhotoSphere capture depends heavily on physical camera and motion sensors.

#### Prepare the Android Device

1. Turn on the Android device.
2. Unlock the device.
3. Open the device's Settings app.
4. Scroll to `About phone` or `About tablet`.
5. Find `Build number`.
6. Tap `Build number` seven times.
7. Enter the device PIN, password, or pattern if Android asks.
8. Confirm that Android says developer options are enabled.
9. Go back to the main Settings screen.
10. Open `System`.
11. Open `Developer options`. The exact location varies by manufacturer.
12. Turn on `USB debugging`.
13. If the device has a `Default USB configuration` setting, set it to file transfer or data transfer if available.
14. Leave the device unlocked for the first connection.

#### Connect the Device by USB

1. Plug the USB cable into the computer.
2. Plug the USB cable into the Android device.
3. If the phone asks what to do with the USB connection, choose file transfer, data transfer, or the closest equivalent.
4. Watch the phone screen for an `Allow USB debugging?` prompt.
5. Check the computer's RSA fingerprint if you want to be careful.
6. Select `Always allow from this computer` if this is your own development machine.
7. Tap `Allow`.
8. Keep the device unlocked until the computer recognizes it.

#### Confirm That ADB Sees the Device

Run this from a terminal:

Windows PowerShell:

```powershell
adb devices
```

macOS/Linux:

```bash
adb devices
```

Expected result:

- A line appears with a device serial number.
- The state says `device`.

Common results and what they mean:

- `unauthorized`: unlock the phone and accept the USB debugging prompt.
- `offline`: unplug the cable, plug it back in, and re-run `adb devices`.
- No device listed: try a different USB cable, USB port, or driver.
- Multiple devices listed: disconnect extra devices or pass an explicit device serial to `adb`.

On Windows, if no device appears:

1. Install `Google USB Driver` if using a compatible Google device.
2. Install the manufacturer's USB driver if the device is not covered by the Google USB Driver.
3. Unplug and reconnect the phone.
4. Re-run `adb devices`.

#### Run on the Physical Device from VS Code

1. Build the debug APK.

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:assembleDebug
```

2. Confirm that the device is visible.

```bash
adb devices
```

3. Install the debug APK.

Windows PowerShell:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS/Linux:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

4. If more than one device or emulator is connected, copy the target serial from `adb devices`.
5. Install to that exact serial.

Windows PowerShell:

```powershell
adb -s DEVICE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS/Linux:

```bash
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

6. Start the app from the launcher on the phone, or start it with an `adb shell am start` command after the package name and launch activity are known.

The package name is expected to be something like `com.spherify.app`, but the actual package name must come from the future Android manifest or Gradle namespace.

Example only:

```bash
adb shell monkey -p com.spherify.app 1
```

#### Capture Metadata Debug Export

Debug builds automatically write a capture-session CSV after each analyzed candidate and again when integrated spherification succeeds or fails. The dump does not transform, upload, or export image pixels; it writes one CSV timeline containing the exact persisted metadata for each captured frame row, high-precision Camera2 sensor timestamps, flattened camera/image/analysis fields, and the next capture target computed by the live capture planner after each accepted frame.

The CLI endpoint remains available for manually replaying the latest or a specific saved session:

Export the latest capture session:

```bash
adb shell am start -n com.spherify.app/.CaptureDebugExportActivity
```

Export a specific session:

```bash
adb shell am start -n com.spherify.app/.CaptureDebugExportActivity --es sessionId SESSION_ID
```

Old `capture-debug-*.csv` files are deleted before each export by default. Preserve previous CSV exports for comparison with:

```bash
adb shell am start -n com.spherify.app/.CaptureDebugExportActivity --ez keepExisting true
```

The app logs and toasts the output path, normally under:

```text
/sdcard/Android/data/com.spherify.app/files/debug/capture-debug-*.csv
```

Pull the file with:

```bash
adb pull /sdcard/Android/data/com.spherify.app/files/debug/capture-debug-YYYYMMDD-HHMMSS-SSS.csv .
```

7. Read device logs while testing.

```bash
adb logcat
```

8. Stop logging with `Ctrl+C`.
9. Keep VS Code open for edits.
10. Repeat the edit, build, install, launch, log loop as needed.

Optional filtered logs after the final package name is known:

```bash
adb logcat --pid=$(adb shell pidof -s com.spherify.app)
```

On Windows PowerShell, use the simpler unfiltered `adb logcat` first unless a project script provides a reliable filtered command.

#### Reset the App on the Physical Device

Use this when the app has bad local state, stale permissions, or old test data.

1. Find the package name from the manifest or Gradle namespace.
2. Clear the app data.

Example only:

```bash
adb shell pm clear com.spherify.app
```

3. Reopen the app.
4. Repeat the first-run and permission flow.

To uninstall the debug app:

```bash
adb uninstall com.spherify.app
```

### Run in an Android Emulator Sandbox from VS Code

This path installs and runs the debug version of Spherify inside an Android Virtual Device.

Use this path when testing:

- Basic app launch.
- Navigation.
- Layout and Compose rendering.
- PhotoSphere viewer rendering.
- Tiny Planet reprojection controls.
- Import/export flows that do not require real camera behavior.
- Permission copy and denial paths.
- Different screen sizes, densities, orientations, and Android versions.
- Clean app state without touching a real phone.

Do not rely on the emulator alone for final capture validation. Camera, compass, gyroscope, location, GPU behavior, and storage behavior can differ from real hardware.

#### Create an Android Virtual Device

1. Open VS Code.
2. Open the repository folder.
3. Open the integrated terminal.
4. List installed SDK packages.

```bash
sdkmanager --list_installed
```

5. If no suitable emulator system image is installed, install one.

Example:

```bash
sdkmanager "system-images;android-35;google_apis;x86_64"
```

6. List available device profiles.

```bash
avdmanager list device
```

7. Create an AVD with a clear Spherify name.

Example:

```bash
avdmanager create avd -n Spherify_API_35_Pixel -k "system-images;android-35;google_apis;x86_64" -d pixel_7
```

8. If `avdmanager` asks whether to create a custom hardware profile, answer `no` unless the project needs special hardware settings.
9. Confirm the AVD exists.

```bash
emulator -list-avds
```

10. Prefer a recent stable Android API level that matches the project's compile and target plans.
11. Use a Google APIs or Google Play image if Google services are needed.
12. Use a plain AOSP image if Google services are not needed for the test.
13. Use hardware graphics acceleration when available.
14. Keep the AVD name stable so scripts and notes can refer to it.

#### Start the Emulator

1. Open VS Code.
2. Open the integrated terminal.
3. List available emulator devices.

```bash
emulator -list-avds
```

4. Start the chosen emulator.

```bash
emulator -avd Spherify_API_35_Pixel
```

5. Leave the terminal open while the emulator runs.
6. Open a second VS Code terminal.
7. Confirm that ADB sees the emulator.

```bash
adb devices
```

Expected result:

- A device appears with a name like `emulator-5554`.
- The state says `device`.

If the emulator is slow or fails to boot:

1. Confirm that the computer meets Android Emulator system requirements.
2. Enable hardware virtualization in BIOS or firmware if needed.
3. Use a smaller device profile.
4. Use a lower-resolution emulator.
5. Close other memory-heavy applications.
6. Wipe the emulator data if the virtual device state is corrupted.
7. Create a new AVD if wiping data does not help.

#### Run on the Emulator from VS Code

1. Start the emulator.
2. Confirm ADB sees it.

```bash
adb devices
```

3. Build the debug APK.

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:assembleDebug
```

4. Install the debug APK.

Windows PowerShell:

```powershell
adb -e install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS/Linux:

```bash
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
```

The `-e` flag targets the emulator. Use it when a physical device is also connected.

5. Start the app from the emulator launcher, or use an `adb shell monkey` command after the package name is known.

Example only:

```bash
adb -e shell monkey -p com.spherify.app 1
```

6. Read emulator logs.

```bash
adb -e logcat
```

7. Stop logging with `Ctrl+C`.
8. Keep VS Code open for edits.
9. Repeat the edit, build, install, launch, log loop as needed.

Useful emulator commands:

```bash
adb -e emu kill
```

```bash
emulator -avd Spherify_API_35_Pixel -wipe-data
```

The first command closes the running emulator. The second starts the AVD with its data wiped.

#### Reset the Emulator Sandbox

Use this when you want a clean developer app environment.

To clear only Spherify's app data:

```bash
adb -e shell pm clear com.spherify.app
```

To uninstall the debug app:

```bash
adb -e uninstall com.spherify.app
```

To wipe the whole emulator:

1. Close the emulator.
2. Start the emulator with `-wipe-data`.

```bash
emulator -avd Spherify_API_35_Pixel -wipe-data
```

3. Wait for the emulator to boot.
4. Reinstall the app.

Wiping the emulator deletes its apps, local files, settings, and test state. It does not delete the source code repository.

### Permission Checks During Developer Runs

Spherify is expected to request permissions contextually, not all at startup. During testing, check each permission at the moment the app needs it.

Physical device checks:

1. Start the app fresh.
2. Confirm it does not immediately request every sensitive permission.
3. Start a capture flow.
4. Confirm camera permission appears only when capture needs it.
5. Grant camera permission.
6. Confirm the camera preview opens.
7. Enable map-ready location tagging.
8. Confirm location permission appears at that moment.
9. Grant location permission.
10. Confirm location status appears in the app only where relevant.
11. Deny a permission on a second run.
12. Confirm the app explains the reduced feature without crashing.

Emulator checks:

1. Start from a clean emulator or clear app data.
2. Run the app.
3. Test first launch.
4. Test permission denial.
5. Test permission grant.
6. Use emulator location controls if location behavior is being tested.
7. Use emulator camera controls only for basic camera plumbing. Do not treat emulator camera behavior as proof of real PhotoSphere capture quality.

### Developer Run Checklist

Before each test run:

1. Pull or fetch the latest intended code.
2. Confirm the working tree changes are understood.
3. Open the repository in VS Code.
4. Open the VS Code integrated terminal at the repository root.
5. Run a debug build.
6. Choose exactly one target device unless deliberately testing multiple devices.
7. Confirm `adb devices` shows the target.
8. Install and run the debug app.
9. Watch `adb logcat`.
10. Record the device model, Android version, and app commit when reporting a bug.

For physical-device capture testing, also record:

- Device manufacturer and model.
- Android version.
- Camera lens used.
- Whether gyroscope is present.
- Whether accelerometer is present.
- Whether magnetometer/compass is present.
- Whether rotation vector is present.
- Whether location was enabled.
- Lighting conditions.
- Approximate capture duration.
- Whether source frames were saved.

For emulator testing, also record:

- AVD name.
- Hardware profile.
- Android API level.
- System image type, such as AOSP, Google APIs, or Google Play.
- Graphics mode.
- Whether the emulator was cold booted, quick booted, or wiped.

### Common Problems

`gradlew` or `gradlew.bat` is missing:

1. The Android project scaffold has not been created yet.
2. Stop and create or restore the Gradle wrapper before trying to build.

The build cannot find an Android SDK:

1. Confirm `ANDROID_HOME` or `ANDROID_SDK_ROOT` points to the installed Android SDK.
2. Confirm `platform-tools` is on `PATH`.
3. Run `adb version`.
4. Install the SDK Platform required by the project with `sdkmanager`.
5. Install Platform-Tools with `sdkmanager` if needed.
6. Re-run the Gradle build from the VS Code terminal.

The device does not appear in ADB:

1. Run `adb devices`.
2. If the device does not appear there, VS Code and Gradle install commands cannot target it.
3. Check the cable.
4. Check USB debugging.
5. Check the debugging authorization prompt.
6. Check Windows USB drivers if on Windows.

The device appears as `unauthorized`:

1. Unlock the device.
2. Look for the USB debugging prompt.
3. Tap `Allow`.
4. If the prompt does not appear, turn USB debugging off and on again.
5. Unplug and reconnect the cable.

The emulator boots but the app will not install:

1. Confirm the emulator is fully booted and unlocked.
2. Run `adb devices`.
3. Confirm the emulator state is `device`.
4. Rebuild the debug APK.
5. If install still fails, uninstall the old package or wipe app data.

The app starts and immediately closes:

1. Run `adb logcat`.
2. Filter by the app package name.
3. Look for `FATAL EXCEPTION`.
4. Fix the first crash stack trace.
5. Rebuild and reinstall.

The app behaves differently on emulator and real phone:

1. Prefer the real phone result for camera, sensors, compass, storage, thermal, and location behavior.
2. Prefer emulator results only for controlled UI, navigation, layout, and clean-state tests.
3. Add a device-specific note if a hardware sensor is missing or unreliable.

### Official References

- VS Code documentation: https://code.visualstudio.com/docs
- Android command-line tools: https://developer.android.com/tools
- Run apps on a local device: https://developer.android.com/studio/run/device
- Google USB Driver for Windows: https://developer.android.com/studio/run/win-usb
- Run apps on the Android Emulator: https://developer.android.com/studio/run/emulator
- Start the emulator from the command line: https://developer.android.com/studio/run/emulator-commandline
- Android Emulator troubleshooting: https://developer.android.com/studio/run/emulator-troubleshooting
- Android Emulator hardware acceleration: https://developer.android.com/studio/run/emulator-acceleration

## Development Blog

### 2026-07-20: First Research Pass

The original spark is nicely ambitious: make a free Android app that can guide a user through capturing a full 360 x 180 scene, use the camera plus accelerometer, gyroscope, compass, and location services to stabilize and orient the capture, stitch and exposure-balance the frames, save the result to device storage, and later browse or reproject the saved images between PhotoSphere and Tiny Planet views.

The core file format target should be an equirectangular JPEG master at a 2:1 aspect ratio. Google Maps' own Photo Sphere guidance expects 7.5 MP or larger, a 2:1 image, no more than 75 MB, no horizon gaps, and no major stitching errors. That makes a 3840 x 1920 output the minimum credible target, with higher resolutions desirable on modern phones.

The best product direction is probably not "one-click magic" at first. A realistic first version would be a guided capture tool with a clear sphere/cube coverage map, live horizon/orientation feedback, local saves, a gallery, and reprojection/export. Full automatic high-quality stitching is the hard part. Publishing and browsing are approachable; capture guidance is approachable; reliable stitching across phones, lenses, parallax, moving objects, low light, and exposure shifts is the real research project.

### Current Market Notes

I did not find a clearly free current Android or PWA offering that does all of the desired pieces in one place: guided phone-only PhotoSphere capture, sensor-assisted stabilization/orientation, image matching and exposure compensation, Tiny Planet generation, local gallery, reversible reprojection browser, and Google Maps publishing.

There are partial products:

- Google Maps can publish Photo Spheres and Google documents that developers can build publishing tools with the Street View Publish API, but Google's standalone Street View app was discontinued in 2023.
- Panorra 360 appears to focus on 360-photo sharing and one-tap Google Street View publishing.
- Go Street View Photo Sphere and 360 Photo Cam advertise phone-based 360 capture/stitching flows.
- Tiny Planet - Global Photo focuses on turning photos/panoramas into Tiny Planet or wormhole-style images, with import/camera/export features.
- Web libraries such as Photo Sphere Viewer, Pannellum, and Marzipano are excellent for viewing equirectangular panoramas in browsers, but they are viewers/toolkits rather than complete phone capture, stitch, local-gallery, and Google-publishing products.

The gap seems real: the market has viewers, editors, uploaders, and capture apps, but not a polished free app that combines the whole workflow and treats Tiny Planet and PhotoSphere as two projections of the same saved master.

### Google Platform Reality

Google Maps publishing is possible, but must be treated carefully. Google says Photo Spheres can be uploaded with the Android Google Maps app or browser, and developers can create tools with the Street View Publish API. The API can publish 360 photos to Google Maps with position, orientation, and connectivity metadata. That is promising for Spherify, especially if each image stores GPS, compass heading, capture time, and XMP Photo Sphere metadata.

Device storage is straightforward in principle. Android MediaStore supports saving app-created images into shared media storage, and Android 10+ does not require broad storage permission for photos the app itself creates. Reading unrelated images should go through the Android photo picker where possible.

### Technical Feasibility

The app is buildable, but not small.

The feasible first release is an Android-native app using CameraX or Camera2, Android SensorManager, fused location/orientation data, MediaStore, a local database, and a GPU-backed viewer. Capture can start with a structured grid of overlapping photos around the user, using sensor fusion to suggest the next target direction. The app should save all source frames plus a generated equirectangular master so reprocessing can improve over time.

The hard engineering work is the stitching pipeline:

- Feature detection and image matching across overlapping camera frames.
- Sensor-informed initial pose estimates from gyroscope, accelerometer, magnetometer/compass, and rotation vector.
- Exposure/white-balance compensation across frames.
- Seam selection, blending, and ghost handling for moving objects.
- Horizon correction and nadir/zenith handling.
- Robust behavior across ultra-wide, wide, and telephoto lenses.

The Tiny Planet feature is easier if it is treated as a reprojection of an equirectangular master using stereographic projection. The reverse direction is only lossless when the original equirectangular master is retained. A flattened Tiny Planet export alone does not contain enough information to reconstruct a full PhotoSphere.

### PWA Versus Android Native

A PWA could be useful for viewing and reprojection experiments, but a Play Store Android-native app is the better primary route.

PWAs can use WebGL and libraries like Photo Sphere Viewer, Pannellum, or Marzipano for interactive viewing. Some browsers expose camera, geolocation, orientation, and motion APIs, but capture permissions, sensor availability, background behavior, file access, performance, and Play Store distribution are less predictable than native Android. A PWA or web module might still be valuable later as a browser-based gallery/export companion, or wrapped inside a native shell for the viewer only.

### Product Shape

The first useful app could be:

- Capture: guided sphere capture with orientation targets, overlap hints, exposure lock, and live progress.
- Process: stitch source frames into an equirectangular PhotoSphere master, store source frames for later reprocessing, and generate preview thumbnails.
- Browse: local gallery of Spherify captures and imports.
- Reproject: interactive PhotoSphere view, Tiny Planet view, wormhole/inverted view, horizon rotation, zoom, roll, and export.
- Save: write masters and exported projections to device storage.
- Publish: share/export to Google Maps manually, and later add Street View Publish API support.

### Design Prerequisites

Before the first line of app code, the product needs a few design decisions pinned down. These are not visual-polish questions; they determine whether the app can feel trustworthy, stable, and feasible.

The app should be designed around a local-first library. Google Maps should feel like export destinations, not the place where the app's primary state lives. The local library should keep a durable record for every capture: source frame set, stitched equirectangular master, exported Tiny Planet variants, capture metadata, processing status, and publishing status. That gives the user confidence that a failed upload or later API change will not strand their work.

The permission model should be contextual and progressive. Android guidance says runtime permissions should be requested when the user starts the feature that needs them, not at app startup. For Spherify this means:

- Camera permission appears when the user starts a new capture.
- Location permission appears when the user enables map-ready geotagging or publishing metadata.
- Photo/media access is avoided for app-created images and handled through MediaStore; imports from other apps should use the Android photo picker where possible.
- Google account authorization appears only when the user chooses Google Maps/Street View publishing.
- Motion sensors should be used while capture is visible and active, with no background collection.

The app should also be useful when optional permissions are denied. Without location, the user can still create and save PhotoSpheres and Tiny Planets, but exports will not be map-ready until location is added manually. Without Google sign-in, the user can still save locally and share files. Without photo-library access, the user can still use the Spherify library and select individual external images through the picker.

The minimum viable technical surface should include:

- Camera preview and capture with exposure/focus controls.
- Motion/orientation sensor fusion for capture guidance.
- Foreground-only location capture for optional geotagging.
- Local storage with app-owned image access.
- Equirectangular image viewer.
- Stereographic Tiny Planet renderer/exporter.
- Processing queue with resumable stitch/reproject jobs.
- Metadata editor for title, location, orientation, projection, and publishing readiness.

The minimum viable design surface should include:

- A home/gallery view that opens straight into existing work, not a marketing page.
- One primary action: start capture or import image.
- A capture screen that is mostly camera preview, with a sphere coverage guide and minimal controls.
- A processing screen that explains progress without pretending stitching is instant.
- A viewer/editor that treats PhotoSphere and Tiny Planet as modes of the same master image.
- A publish/export sheet that separates local save, Google Maps publish/share, and generic Android share.

### Workflow Investigation

The intended user journey has six major phases.

1. First launch and setup

The first launch should be short and practical. The app should show the local gallery, empty if new, with a primary capture action and a secondary import action. It should not ask for camera, location, files, or Google sign-in immediately. A small first-run note can explain that capture needs camera access, map-ready exports need location, and cloud publishing is optional.

The empty state should offer:

- Capture PhotoSphere.
- Import 360 image.
- Open sample panorama, if bundled later.

2. Capture preparation

When the user taps Capture PhotoSphere, the app checks the environment and requested capture mode. This screen should detect available cameras, gyroscope, accelerometer, magnetometer/compass, rotation vector, current battery state, storage availability, and whether location is enabled. It should warn without blocking where possible.

The ideal prep checklist:

- Camera ready.
- Motion tracking ready.
- Compass quality acceptable or needs calibration.
- Enough free storage for source frames and stitched output.
- Location available, optional.
- Lighting stable enough for exposure lock, optional but recommended.

The user should be able to choose:

- Capture quality: standard, high, archival.
- Lens: main wide, ultra-wide if supported, manual choice if multiple lenses are reliable.
- Save source frames: always, ask, or storage saver.
- Location tagging: on/off.
- Exposure mode: auto, locked after first frame, manual compensation.

3. Guided capture

The capture UI should behave like a calm instrument panel. The camera preview takes the whole screen. On top of it, the app overlays a sphere coverage guide: target dots or tiles arranged around yaw/pitch positions. The user turns slowly and aligns a reticle with each target. A frame is captured automatically when alignment, stability, and overlap are acceptable, or manually if the user chooses.

The screen needs:

- Center reticle and next target marker.
- Coverage mini-map showing captured, missing, and weak-overlap regions.
- Horizon/level indicator.
- Stability indicator based on gyro movement.
- Exposure lock status.
- Undo last frame.
- Pause/resume capture.
- Finish when enough coverage exists.
- Cancel with a clear "keep source frames?" choice.

Capture should prefer fewer, better prompts over dense instruction text. The app can use short states: Move slower, Hold steady, Aim higher, Recapture weak area, Too dark, Exposure shifted, Complete. These states should be visible but not block the preview.

The capture flow should support failure recovery:

- If compass quality drops, continue with gyro-relative orientation and flag metadata for review.
- If a frame is blurred, prompt for recapture.
- If a moving object crosses a frame, mark it as likely ghosting risk.
- If capture is interrupted, save a draft session.
- If coverage is incomplete, allow partial save but label it as not map-ready.

4. Processing and quality review

After capture, processing should be explicit. Stitching can be slow, so the app needs a queue rather than a single spinner. The processing screen should show stages:

- Align frames.
- Match overlap.
- Balance exposure and white balance.
- Blend seams.
- Fill/flag zenith and nadir gaps.
- Generate equirectangular master.
- Generate preview projections.
- Validate PhotoSphere metadata.

The review screen should show the equirectangular master first, with quality flags:

- Resolution and aspect ratio.
- Location present or missing.
- Compass/orientation confidence.
- Horizon quality.
- Stitching confidence.
- Gap/blur/ghosting warnings.
- Google Maps readiness against known requirements.

The user should be able to fix what can be fixed:

- Rotate horizon.
- Adjust heading/north.
- Crop or pad only if the output remains valid 2:1.
- Re-run stitch with alternate seam/blend settings.
- Hide nadir with blur/patch if allowed.
- Add or edit location.
- Save as draft, master, or export.

5. Local browsing and reprojection

The gallery should be the emotional center of the app. It should make later re-use pleasant, not merely list files. Each item should show a thumbnail, projection badges, capture date, location status, and publish status. Filters should include Drafts, Masters, Tiny Planets, Map-ready, Uploaded, Needs review, and Imports.

Opening an item should launch the viewer/editor. The main viewer modes:

- PhotoSphere: interactive immersive viewer with pan, tilt, zoom, gyro look-around toggle, compass overlay, and reset view.
- Tiny Planet: stereographic projection with planet/wormhole toggle, roll, zoom, center point, horizon bend, rotation, and field-of-view controls.
- Equirectangular: flat technical view for checking seams, metadata, and map readiness.

The bonus "on-the-fly image browser" should be designed as a non-destructive projection workspace. The master image remains untouched. Each Tiny Planet or adjusted PhotoSphere is a saved variant linked back to the master. If the user imports a Tiny Planet-only flat image, the app should treat it as a flat image and not pretend it can reconstruct the original sphere.

6. Export and publish

Export should be a bottom sheet or dedicated publish screen with four distinct choices:

- Save to device: writes the selected master or projection to shared media storage.
- Share: opens Android share for any exported image.
- Publish to Google Maps/Street View: either hand off to Google Maps/manual upload in early versions, or use the Street View Publish API later.

For Google Maps, the app should run a readiness check before publishing:

- Equirectangular 2:1 output.
- At least 3840 x 1920.
- File under 75 MB.
- GPS location present.
- Heading/orientation present or user-reviewed.
- No known major stitch/gap errors.
- User confirms imagery is appropriate for public Maps contribution.

Publishing status should be visible after the export:

- Local only.
- Saved to device.
- Submitted to Google Maps.
- Processing on Google Maps.
- Published.
- Failed, with retry.

### Probable User Interface

The likely UI architecture is a bottom-navigation Android app with four destinations:

- Library: local gallery and capture history.
- Capture: camera-driven PhotoSphere capture.
- Create: import/reproject/export workspace.
- Settings: permissions, storage, accounts, quality defaults, privacy, and diagnostics.

The Library screen should use a dense but friendly grid. The top app bar can contain search, filter, sort, and account/status. A prominent capture button should be available, likely a floating action button because capture is the primary creation action. Import can sit beside it in a small action menu or as a secondary button in the empty state.

The Capture screen should hide bottom navigation while active. It should use the whole display for preview, with a top strip for close, flash/exposure, lens, and help, and a bottom strip for shutter/auto-capture, undo, pause, and finish. The sphere coverage mini-map should be visible but compact. Accessibility matters here: indicators should not rely on color alone, and capture state should be readable through labels/haptics.

The Processing screen should look like a job card list, not a modal trap. Users should be able to leave processing and return later. Each job shows thumbnail, current stage, elapsed time, estimated remaining time if meaningful, and actions for pause, cancel, or view draft.

The Viewer/Editor screen should have a full-bleed image viewer with a compact mode switch: Sphere, Tiny Planet, Flat. Editing controls should slide up only when needed. For Tiny Planet, the most important controls are center, zoom, twist/roll, invert, and export. For PhotoSphere, the important controls are heading, horizon, gyro toggle, metadata, and export.

The Publish/Export UI should be conservative and explicit. It should never make a public upload feel like a casual save. Google Maps publishing especially needs a preflight page with a preview, location, account, visibility implication, and readiness warnings. 

The Settings screen should include:

- Capture defaults: quality, source-frame retention, auto-capture sensitivity, exposure lock, lens preference.
- Storage: local library size, source frames size, cache size, cleanup controls.
- Accounts: Google Maps/Street View publishing connection.
- Privacy: location tagging default, metadata export choices, permission status.
- Diagnostics: sensor availability, compass calibration, camera capabilities, export logs.
- About: current version, license, acknowledgements.

### Suggested Setup-First UI Workflow

Although Android and Google Play guidance generally favors requesting permissions in context, Spherify can still offer an immediate setup path if it is framed as a friendly readiness flow. The app cannot force permissions or account access; the user must grant them through Android and Google consent screens. The design goal is to make the fastest route obvious: "Get Spherify ready now," while still allowing the user to skip and use local features.

The setup-first flow should use simple splash screens with one clear action per screen.

1. Welcome splash

Purpose: establish the app's promise in one glance.

Primary text: Create PhotoSpheres and Tiny Planets.

Supporting text: Capture, reproject, save, and publish 360 images from your phone.

Buttons:

- Set up Spherify.
- Browse without setup.

2. Readiness splash

Purpose: explain why the app asks for several sensitive capabilities.

Primary text: A few things make the sphere work.

Supporting text: Camera captures the scene, motion sensors guide the sphere, location prepares map-ready images, and Google accounts enable optional upload.

Buttons:

- Grant essentials.
- Choose one by one.

3. Camera permission screen

Purpose: unlock capture.

UI:

- Large camera icon.
- Short copy: Camera access lets Spherify capture the frames for your PhotoSphere.
- Button: Allow camera.
- Secondary: Skip for now.

Result:

- If granted, continue.
- If denied, mark Capture as unavailable and continue to import/gallery setup.

4. Motion/orientation readiness screen

Purpose: check sensors without making the screen feel technical.

UI:

- Simple animated phone-orbit graphic.
- Status chips: Gyroscope, Accelerometer, Compass, Rotation vector.
- Button: Check motion tracking.
- Secondary: Continue anyway.

Result:

- If sensors are present, show Ready.
- If compass quality is weak, offer Calibrate compass.
- If a sensor is missing, continue with reduced capture guidance.

5. Location permission screen

Purpose: enable map-ready images.

UI:

- Map pin over a small sphere thumbnail.
- Short copy: Location helps Google Maps place your PhotoSphere correctly. You can still create images without it.
- Buttons: Allow location, Use without location.

Preferred permission:

- Foreground location only.
- Precise location if the user wants Google Maps publishing.
- Approximate location is acceptable for private/local organization but should be flagged as not ideal for Maps.

6. Local storage setup screen

Purpose: reassure the user that their work is saved on the device first.

UI:

- Folder/gallery icon.
- Short copy: Spherify saves your masters and exports locally first.
- Buttons: Create local library, Change later.

Notes:

- App-created media should use app-owned storage and MediaStore.
- Broad photo/video permission should be avoided unless future requirements truly need it.
- Imports should use the Android photo picker.

7. Google account setup screen

Purpose: connect Google Maps publishing after core device permissions.

UI:

- Google Maps / Street View: Publish map-ready PhotoSpheres.

Buttons:

- Connect Google.
- Skip cloud setup.

Result:

- If connected, show the selected Google account and enabled destinations.
- If skipped, local save and Android share remain available.

8. Setup complete splash

Purpose: land the user with confidence and next steps.

Primary text: Ready to make a sphere.

Status checklist:

- Camera ready.
- Motion tracking ready.
- Local library ready.
- Location ready or skipped.
- Google upload ready or skipped.

Buttons:

- Capture PhotoSphere.
- Import 360 image.
- Open Library.

This setup-first approach keeps the interface friendly while satisfying the desire to grant permissions and accounts immediately. The important product rule is that every skipped or denied item must degrade gracefully: the app should never punish the user with a dead end.

### Simple Function Buttons

The home screen after setup should avoid clutter. A user should be able to understand the app from four large function buttons:

- Capture: start a guided PhotoSphere capture.
- Import: choose an existing 360 image or panorama.
- Browse: open the local Spherify library.
- Create Tiny Planet: open the reprojection editor from a selected master or imported image.

Secondary actions can live behind smaller icons or menus:

- Save to device.
- Publish to Google Maps.
- Share.
- Settings.

The button language should remain concrete. Prefer Capture, Import, Browse, Create, Save, Upload, Publish, and Share over abstract words like Workspace, Assets, Pipeline, or Render.

### Design Risks

The biggest UX risk is over-promising capture quality. The app should be honest about draft, partial, and map-ready states. A beautiful Tiny Planet can tolerate flaws that Google Maps should reject, so the UI must separate creative exports from public 360 imagery.

The second risk is permission fatigue. Asking for camera, location, media, motion, and Google account access in one clump would feel alarming. Contextual requests and graceful degradation are central to the design.

The third risk is losing the user during capture. PhotoSphere capture is physical: the user is rotating, aiming, holding the phone steady, and trying not to lose their place. The UI should behave like a guide, not a control panel.

The fourth risk is treating Tiny Planet and PhotoSphere as reversible when they are not always reversible. The app's data model and UI should make "master" and "exported variant" clear.

The fifth risk is Google platform dependency. Direct publishing features should be modular, so a Google API policy change does not break local creation, browsing, and export.

### Open Questions

- Should version 0.1 aim for high-quality stitching, or should it start with import/reprojection/gallery and add capture later?
- Is the project willing to depend on native image-processing libraries such as OpenCV, Hugin/Panorama Tools-inspired algorithms, or commercial SDKs if open-source quality is insufficient?
- Should Google Maps publishing be direct through Street View Publish API from the start, or should the first release hand off exported Photo Spheres to Google Maps?
- Should the app preserve all capture frames by default, or offer a storage-saving mode that keeps only the stitched master?
- Should the first UI expose advanced stitching controls, or keep them behind a diagnostics/advanced panel?
- Should the app include a bundled sample panorama so users can try Tiny Planet mode before granting camera permission?
- Should incomplete captures be allowed as creative Tiny Planet drafts even when they are not valid PhotoSpheres?

## Research Sources

- Google Maps Help: Create and publish Photo Spheres to Google Maps: https://support.google.com/maps/answer/7012050
- Google Street View Publish API: https://developers.google.com/streetview/publish
- Google Street View contribution guidance: https://www.google.com/streetview/contribute/
- Android MediaStore and shared storage: https://developer.android.com/training/data-storage/shared/media
- Android CameraX configuration and exposure compensation: https://developer.android.com/media/camera/camerax/configuration
- Android sensors overview: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- Android permissions overview: https://developer.android.com/guide/topics/permissions/overview
- Android runtime permission requests: https://developer.android.com/training/permissions/requesting
- Android location permissions: https://developer.android.com/develop/sensors-and-location/location/permissions
- Android core app quality guidelines: https://developer.android.com/docs/quality-guidelines/core-app-quality
- Google Play policies: https://developer.android.com/distribute/play-policies
- Material Design floating action button guidance: https://m3.material.io/components/floating-action-button/guidelines
- Photo Sphere Viewer: https://photo-sphere-viewer.js.org/
- Pannellum: https://pannellum.org/
- Marzipano: https://www.marzipano.net/
- Panorra 360 on Google Play: https://play.google.com/store/apps/details?id=com.gothru.panorra
- Go Street View Photo Sphere on Google Play: https://play.google.com/store/apps/details?id=com.gostreetview.camera
- 360 Photo Cam on Google Play: https://play.google.com/store/apps/details?id=com.dospace.photo360
- Tiny Planet - Global Photo on Google Play: https://play.google.com/store/apps/details?id=globe.tiny.planet.globalphoto.tinyplanet

## Development Workflow and Timeline

This roadmap assumes the project starts from no application code and grows through small, testable milestones. The order is intentionally local-first: prove image handling, projection, and storage before depending on Google account flows or public publishing.

### Phase 0: Repository and Product Groundwork

Goal: prepare the project without committing to the full implementation too early.

Work:

- Choose the Android baseline: Kotlin, Jetpack Compose, CameraX/Camera2 decision, minimum Android version, and target Play Store requirements.
- Choose a local image metadata model: capture session, source frame, equirectangular master, exported variant, location metadata, and publish status.
- Decide whether stitching research starts with OpenCV or another native/image-processing library.
- Define sample assets for testing equirectangular viewing and Tiny Planet reprojection.
- Create a privacy and permissions matrix before app code begins.

Exit criteria:

- A clear app architecture note.
- A local-first data model.
- A short list of candidate image-processing libraries.
- A decision on minimum Android version.

### Phase 1: Barebones Technology Proof of Concept

Goal: prove the core image manipulation idea with the least possible UI.

Work:

- Load a bundled equirectangular test image.
- Display it in a basic interactive PhotoSphere viewer.
- Reproject it into Tiny Planet/stereographic view.
- Allow simple manipulation: rotate, zoom, invert, reset.
- Export the current projection as a JPEG or PNG into app storage.
- Save a generated thumbnail.

Exit criteria:

- One test panorama can be viewed as PhotoSphere.
- The same panorama can be transformed into Tiny Planet.
- The transformed output can be saved and reopened.

### Phase 2: Local Storage and Image Library

Goal: make image work durable and user-visible.

Work:

- Build the local Spherify library.
- Store masters, variants, thumbnails, and metadata.
- Save app-created images through MediaStore where appropriate.
- Import external images through the Android photo picker.
- Add gallery filters for Masters, Tiny Planets, Imports, Drafts, and Saved.
- Add delete, rename/title, duplicate/export variant, and metadata view.

Exit criteria:

- A user can import an image, create a Tiny Planet variant, save it, close the app, reopen, and continue working.
- App-created images are visible in the device gallery when exported.
- The app does not require broad photo-library permission for normal use.
- Interrupted capture sessions are recoverable as diagnostic local library records, not loose frame files or a separate production processing queue.

### Phase 3: Capture Shell and Sensor Readiness

Goal: prove the phone can guide a capture before attempting full stitching.

Work:

- Create the setup-first splash workflow.
- Request camera/location/account permissions through friendly, skippable screens.
- Build camera preview with simple still capture.
- Read accelerometer, gyroscope, compass/magnetometer, and rotation vector where available.
- Show sensor readiness and compass calibration status.
- Save captured source evidence into a recoverable capture session.
- Save foreground location metadata when enabled.

Exit criteria:

- A user can grant camera/location, begin capture, and recover an interrupted capture session without corrupting the library.
- Sensor status is visible and does not block unsupported devices unnecessarily.
- Location remains optional.

### Phase 4: Guided PhotoSphere, Stitching and Equirectangular Master Generation

Goal: replace the current capture and Spherify implementation with one end-to-end PhotoSphere pipeline based on guided still capture, validated overlap graphs, global camera optimization, seam-aware compositing, and honest spherical metadata. This phase must not patch the existing sweep/force-capture/rendering path into production shape. Version 0.4.2 removes that path from production and makes the capture UI the measurement and control surface for the stitcher.

Product direction:

- Capture should feel like aligning a reticle with targets on a virtual sphere, not like operating a technical stitching console.
- The user should not need to understand yaw, pitch, roll, overlap, graph edges, intrinsics, or residuals.
- The app should show a small connected frontier of nearby targets rather than forcing a single brittle direction when more than one graph-valid neighbour is available. The user still sees one active target, one hold/steady state, one compact coverage map, and short actionable warnings such as `Move slower`, `Hold steady`, `Too close`, `Recapture this area`, or `Complete`, but adjacent above/below/left/right choices remain available when they preserve overlap with accepted frames. The first accepted frame should be user-anchored: capture begins wherever the user chooses to point the camera, then the remaining target lattice expands around that first view.
- Free sweeping should not be the quality path for a map-ready sphere. It may remain later as a fast creative Tiny Planet mode, clearly labelled as lower confidence.
- `Force Capture` should be removed from the normal workflow. A weak frame should trigger recapture, partial capture, or diagnostic-only acceptance, not become trusted source data.
- `Skip Poles` should become an explicit partial-panorama choice with correct metadata and user-facing state, not a workaround inside a full-sphere promise.
- ARCore visual-inertial tracking, gyroscope, accelerometer, rotation vector, compass, and camera metadata should guide capture and initialize the solve. Final alignment must come from visual registration and global optimization. A CameraX-only sensor path is diagnostic only and must not be treated as production capture because it cannot feed ARCore pose, tracking state, feature/depth confidence, or anchors into the capture graph.

Architecture replacement:

- Use ARCore `SharedCamera` with Camera2 as the only production capture path. This backend must own preview/capture/control, ARCore visual-inertial tracking, camera pose, tracking state, projection/view matrices, depth or feature-confidence signals, timestamp-paired image metadata, and graph-anchor records.
- Select the ARCore `CameraConfig` before session configuration. Prefer rear-facing 30 fps configs and choose the largest supported CPU image stream, with GPU texture size as a tie-breaker. If the best supported CPU stream is still too small for production PhotoSphere capture, fail closed and show the full ARCore CPU/GPU/FPS config list for device diagnosis.
- Treat the guided CameraX capture module and any old non-shared ARCore CPU-frame JPEG path as retired diagnostic history. They may exist only in debug builds for metadata and target-planner investigation, never as the route to production PhotoSphere source data.
- Prefer moderate, reliable source images over maximum-resolution frames that stall capture or break metadata timing. A consistent 2-4 MP source set may produce a better sphere than unstable full-resolution JPEGs.
- Store every accepted source frame with timestamped raw facts: file path, capture timestamp, target yaw/pitch, measured pose, gravity/heading state, intrinsics, focal metadata, exposure, ISO, AE/AWB/focus state where Camera2 provides it, location summary, capture mode, device, camera id, and image dimensions.
- Keep raw facts immutable. Store analysis results separately so later algorithms can be rerun without falsifying what was captured.
- Use a durable capture graph as the central object. Frames are graph nodes. Validated overlaps are graph edges with neighbor ids, inlier count, inlier distribution, reprojection residuals, confidence, control points, parallax/motion hints, and rejection reasons.
- Preserve interrupted sessions as diagnostics/recovery records. They must show accepted, missing, weak, and rejected targets with enough context for recapture, but they must not become a separate public "capture now, spherify later" workflow.

Guided capture work:

- Generate an adaptive spherical target lattice from the selected camera field of view, desired overlap, and the first accepted view. The target spacing must be smaller than the usable horizontal and vertical field of view, with local neighbours first, horizon completion next, and extra care near the zenith and nadir.
- Support at least two capture quality profiles: `Hand-held` and `Tripod / phone mount`. Hand-held mode should warn more aggressively about translation and nearby objects. Tripod mode can use stricter map-ready assumptions.
- Start capture with a readiness check for camera capability, motion sensors, ARCore availability, ARCore shared-camera backend availability, depth or feature-confidence availability, storage, location preference, exposure/focus stability, and low-light risk.
- Drive the production live overlay from ARCore visual-inertial camera pose, tracking state, feature/depth confidence, and calibrated projection. Targets outside the current camera view must remain discoverable through edge cues inspired by off-screen visualisation research rather than disappearing beyond the display.
- Keep the capture UI as a closed feedback loop. The active target must remain visible or discoverable until accepted/rejected; nearby connected frontier targets should be visible as secondary choices; accepted frames must produce immediate visual/haptic confirmation plus persistent world-registered and mini-map coverage marks; rejected frames must reopen the same target with a plain reason; and the compact mini-sphere/target map must show current, accepted, weak, rejected, and remaining coverage.
- In debug builds, automatically write a capture-metadata CSV for the active session after candidate analysis and final solve/failure. The optional CLI command must replay the same persisted metadata timeline, camera/image properties, and next-target coordinates without triggering stitching, library export, or any image transformation.
- Auto-capture only when the target is aligned, angular velocity is low, focus/exposure/white balance are stable enough, and a capture-ready still frame can be paired with complete metadata.
- Immediately analyze each candidate against expected neighbors before advancing permanently. The first anchor frame is exempt from overlap because no overlap exists yet, but it must still pass tracking, texture, blur, exposure, focus, pose, and metadata gates. After the anchor is accepted, missing predicted overlap or failed visual registration is a true weak-overlap failure.
- After each ring or major region, run a lightweight graph health check. If loop closure, cross-row links, or pole support are weak, ask for recapture while the user is still physically in position.
- Detect parallax risk during capture from ARCore translation, feature-map confidence, depth/depth-edge cues where supported, and residual clusters in matched features. Severe parallax should produce immediate user feedback before capture rather than being hidden by blending.

Vision and stitching work:

- Use OpenCV as the first production stitching dependency. Kotlin or Java should own UI, permissions, camera lifecycle, and persistence; native/OpenCV code should own feature extraction, matching, camera optimization, warping, seam finding, and blending.
- Build on the OpenCV detail-style pipeline or a Ceres-backed native solver rather than a custom weighted-pixel renderer: ORB/SIFT/AKAZE feature detection as available, robust matching, camera estimation, bundle adjustment, spherical warping, exposure compensation, graph-cut seam finding, and multi-band blending. The currently packaged OpenCV Android AAR does not expose/link these stitching/detail symbols, so production export remains blocked until that dependency gap is closed.
- Version 0.4.2 includes an optional native OpenCV detail pipeline in `app/src/main/cpp`. It is enabled through `local.properties` when `spherify.opencvNativeDir` points to a full Android OpenCV build whose `OpenCVConfig.cmake` exports stitching symbols. The pipeline uses ORB features, BestOf2Nearest matching, homography estimation, ray bundle adjustment, wave correction, spherical warping, block gain exposure compensation, graph-cut seams, and multiband blending.
- Treat sensor and ARCore pose as priors, not final placement. The optimizer should solve camera rotations, shared or per-frame focal parameters where justified, radial distortion terms, and gravity/horizon correction.
- Use robust loss or outlier rejection so moving people, cars, trees, water, reflections, and repeated textures do not dominate the solve.
- Estimate photometric correction from overlapping pixels, suppressing saturated regions, deep shadows, moving objects, and poorly aligned areas.
- Use graph-cut or equivalent seam selection that penalizes strong edges, faces, text, moving objects, high residuals, exposure jumps, and optional depth discontinuities.
- Use real Laplacian pyramid or OpenCV multi-band blending. A local blur pass is not sufficient for production PhotoSphere output.
- Compose the final equirectangular master in memory-aware tiles where possible. Avoid holding every full-resolution source image and every full output buffer in memory at once on mid-range devices.

Integrated output and review work:

- When guided capture is completed, immediately consume the validated graph and export a 2:1 equirectangular JPEG only when the solved panorama genuinely supports that claim.
- Write GPano/XMP metadata for projection type, full panorama dimensions, cropped dimensions, crop offsets, pose heading/pitch/roll where available, create date, source count, and stitching software.
- For partial captures, write honest cropped-area metadata and display the result as partial/local rather than map-ready.
- Validate the output before adding a confident master to the library: coverage, hole count, wrap seam, pole support, horizon consistency, reprojection residuals, exposure residuals, and metadata readback.
- Assign a clear review state: `Interrupted capture`, `Partial`, `Creative export`, `Local master`, `Needs review`, or `Map-ready candidate`.
- Keep source frames and the graph at least until the user deletes the session or chooses a storage-saving cleanup. Reprocessing should not require another physical capture when the captured data is still valid.

Migration work:

- Preserve the existing library, import, viewer, Tiny Planet, flat-image, export, share, thumbnail, rename, delete, and metadata screens unless they directly depend on the retired capture path.
- Keep the legacy sweep/paint/force-capture code out of user-facing production capture.
- Do not carry forward the custom Phase 5 renderer as the production stitching engine. Version 0.4.2 removes it from the production source path.
- Update the storage schema deliberately. Legacy `drafts.json` sessions may remain readable, but new sessions should use the capture graph as the authoritative source.
- Add regression fixtures from real devices before declaring the new path complete: outdoor distant scene, indoor room, tripod/mount capture, low light, low texture, moving people/vehicles, close-object parallax, and incomplete/partial capture.

Exit criteria:

- A user can complete a guided dot-based full-sphere capture without seeing developer diagnostics.
- Every accepted production source frame has complete image bytes, timestamped pose/intrinsics/exposure metadata, and at least the expected graph validation state.
- The capture graph is connected, loop closure is checked, cross-row overlap is verified, and weak regions produce recapture prompts before final processing.
- Completing capture consumes only the validated capture graph. There is no public production action that spherifies an unverified folder of JPEGs or a later draft session.
- The stitcher uses a proven OpenCV-detail or Ceres-backed global alignment, spherical warp, seam, exposure, and multi-band blend pipeline.
- The exported master is a valid 2:1 equirectangular JPEG with GPano metadata, or no master is created.
- A real-device test corpus demonstrates that the new pipeline handles at least one outdoor full sphere and one indoor full sphere better than the retired prototype.
- The old split capture/spherify path is removed from user-facing builds or isolated as diagnostics so the product has one primary truth.



### Phase 5: Solver, Export, and GPano Certification

Goal: tune and certify the industrial optimizer/compositor that runs at guided-capture completion before any Google Maps handoff is considered.

Work:

- Maintain and tune the native OpenCV-detail pipeline that is now linked into Android: feature extraction, robust pairwise matching, camera estimation, bundle adjustment, spherical warping, exposure compensation, graph-cut seam selection, and multiband blending.
- Validate 2:1 aspect ratio, minimum 3840 x 1920 resolution, file size under 75 MB, complete coverage, poles, wrap seam, horizon, residuals, and GPano XMP readback.
- Write GPano metadata only when the output is a truthful equirectangular PhotoSphere: projection type, full/cropped dimensions, crop offsets, source count, photo dates, creator tool, and pose heading/pitch/roll where available.
- Add a metadata editor for optional location and heading review, keeping missing location separate from stitch quality.
- Keep any Google Maps export or share path disabled until local solver/export certification passes.

Exit criteria:

- Completing a validated capture graph produces a local 2:1 equirectangular JPEG through a real optimizer/compositor without a separate post-capture Spherify step.
- GPano metadata readback passes and common 360 viewers recognize the file as a PhotoSphere.
- The app refuses to label any output map-ready if coverage, residuals, poles, wrap seam, aspect ratio, resolution, file size, or metadata validation fail.

### Phase 6: Direct Street View Publish API Integration

Goal: submit app-created 360 imagery directly to Google Maps/Street View where policy and API access allow.

Work:

- Complete Street View Publish API authorization and OAuth scopes.
- Upload 360 photos with required metadata.
- Handle API errors, quota issues, retries, and user cancellation.
- Track submitted, processing, published, failed, and removed states.
- Add optional linking/connectivity for tours if this becomes a core use case.
- Add clear public-publishing confirmation before submission.

Exit criteria:

- A user can submit a validated PhotoSphere from Spherify to Google Maps/Street View.
- The app records submission status and exposes retry/failure details.

### Phase 7: Play Store Readiness and Beta

Goal: turn the prototype into a testable Play Store app.

Work:

- Run privacy, permissions, data safety, and Play policy review.
- Add crash reporting and performance diagnostics without logging sensitive image/location data.
- Test on multiple camera/sensor combinations.
- Optimize memory use for large panoramas.
- Add accessibility pass for capture controls, labels, color contrast, and haptics.
- Prepare closed testing release.
- Collect real-world capture examples and failure cases.

Exit criteria:

- Closed beta users can capture, save, reproject, upload, and attempt Maps-ready export.
- Known device limitations are documented.
- Data safety and permission justifications are ready for Play Console.

### Phase 8: Public Release and Iteration

Goal: launch carefully, then improve quality from real captures.

Work:

- Release a conservative 1.0 focused on local creation, save/export, reprojection, Google Maps-ready export or direct Maps publishing if stable.
- Improve stitching based on real-world failures.
- Add capture presets for indoor, outdoor, low light, and fast creative Tiny Planet.
- Add batch export and cloud backup options only if privacy and API limits permit.
- Explore web/PWA companion for viewing and sharing if useful.

Exit criteria:

- Users can reliably create and keep their images without cloud dependency.
- Google integrations enhance the product but do not define whether it works.
- The app has a feedback loop for capture quality, device support, and publishing success.

## License

GPL-3.0. See [LICENSE](LICENSE).
