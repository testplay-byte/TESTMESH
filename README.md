# 📱 ANDROID — AIMESHVISION Track

> Status: **BUILT — first green GitHub Actions build** (2026-10-06) — code lives in
> [AIMESHVISION/](AIMESHVISION/), pushed to `github.com/testplay-byte/TESTMESH` (main);
> the `assembleDebug` APK artifact `AIMESHVISION-debug` is ready to download from the run page.

## 📂 Folder map

```
ANDROID/
├── README.md            ← this file
├── ANALYSIS.md          ← deep analysis: stack, flow, flaws F1–F11, crash-management status
├── REBUILD_PLAN.md      ← rebuild goals, flaw→fix map, GitHub Actions outline, open questions
└── AIMESHVISION/        ← clean source copy of the old app (build-ready)
    ├── settings.gradle.kts · build.gradle.kts · gradle.properties · .gitignore
    ├── gradlew / gradlew.bat
    ├── gradle/            (wrapper 9.2.1, version catalog, daemon-JVM 21 props)
    └── app/               (build.gradle.kts, proguard, src/main — all Kotlin + res)
```

Excluded from the copy (were 140 MB of the 140.3 MB backup): `.gradle/`, `.idea/`,
`.kotlin/`, `app/build/` (incl. a 31 MB debug APK), `local.properties`.

## 🧭 What the app is

A **bring-your-own-model YOLO v5/v8-segmentation `.tflite` detector**:
live CameraX feed → TFLite inference (CPU/GPU) → segmentation-mask overlays,
plus a multi-image gallery mode, pause-freeze, and a settings bottom sheet
(model swap via file picker, GPU toggle). Fully offline; CAMERA is the only permission.

## 🏗 Building — via GitHub Actions (per plan, not local)

Push `AIMESHVISION/` to a GitHub repo (workflow included at
`.github/workflows/android-build.yml`): every push to `main` runs
`assembleDebug` on ubuntu-latest (JDK 21 temurin + cached Gradle) and uploads
the debug APK as an artifact. No local build required — per the project rule.

**Status: WORKING** — first green build achieved after three CI/compile fixes:

1. `android-actions/setup-android@v3` removed — it tried to install the obsolete
   `tools` SDK package and aborted the job (run 37401758273). GitHub's
   `ubuntu-latest` runners already ship a preinstalled SDK with licenses accepted.
2. `chmod +x gradlew` added to the build step — committing `gradlew` from Windows
   drops its executable bit, so Linux CI got `Permission denied` (run 37402512400).
3. `InferenceEngine.kt` called `Handler.execute(...)` — `Handler` only has `post(...)`;
   caught by the first real Kotlin compile and fixed (run 37403081045 = green).
4. **Crash on launch** — `activity_main.xml` still referenced the pre-rebuild class
   path `com.example.aimeshvision.OverlayView` (now `...ui.OverlayView`); layout
   class names are only resolved at runtime inflation, so CI stayed green while the
   APK died before drawing anything. Fixed + whole-project stale-ref scan.

## 🔬 Model compatibility verification (2026-10-06)

The bundled `model.tflite` was parsed offline (flatbuffers schema) and checked
against what the app's inference code assumes:

| Check | Result |
|-------|--------|
| File integrity | ✅ valid FlatBuffer, min runtime 2.3.0 (app ships TFLite 2.16.1 ≥ OK) |
| Input tensor | ⚠️ **`[1, 3, 512, 512]` NCHW** — first graph op is `TRANSPOSE [0,2,3,1]` to NHWC |
| Output 0 | ✅ `[1, 38, 5376]` v8-transposed: 4 box + **2 classes** + 32 mask coeffs |
| Output 1 | ✅ `[1, 32, 128, 128]` prototypes (NCHW) — matches `YoloPostProcessor` |
| Class order | ✅ `labels.txt` (CAT, HAND) == training `dataset.yaml` (`0: CAT, 1: HAND`) |

**Fix that came out of it:** the app assumed NHWC (`shape[1]/[2]` = H/W, interleaved
RGB feed) → it would have read "3×512" as geometry and fed scrambled color planes,
producing silent garbage detections. `ModelManager` now *detects* the input layout
from the real tensor shape and `letterbox()` writes the matching memory layout
(planar for NCHW, interleaved for NHWC). Scratch buffers are allocated once per
model load instead of every frame (~8 MB prototype buffer was per-frame GC churn).

Also added: **Crash Reports viewer** in the settings sheet — tap to copy all saved
`filesDir/crash_logs/*.txt` reports to the clipboard (fully offline), then clear.

## 🧩 UX round 2 (2026-10-06, build 37441695447)

User-reported issues from live testing and the fixes shipped:

| Issue | Root cause | Fix |
|-------|-----------|-----|
| **Settings opens → app crashes** | Material `Slider` requires a Material Components theme; the app used `Theme.AppCompat` | Theme base switched to `Theme.MaterialComponents.NoActionBar` (visuals unchanged); sheet inflation also guarded |
| **Importing the first model crashed; after restart the model worked** | Crash dialog didn't exist yet — process died silently; saved prefs then made it work on relaunch | Full **crash screen** (see below) + guarded sheet; if anything still throws, the report now shows instead of a silent death |
| **Status bar area dead-black / bars hidden** | `configureFullscreen()` hid the system bars entirely | Edge-to-edge: bars are translucent over the app background, **notifications stay visible**, `fitsSystemWindows` pads content |
| **No indication of the active model** | — | Model name shown **top-center** above the centered LIVE FEED badge; updates on every model change |

### Crash screen (new)

`CrashReportActivity` (own `:crash` process) — app-styled dark/lime screen with the
full stack trace, **COPY LOG** / **RESTART** / **CLOSE** buttons. `CrashHandler`
now (1) archives the trace to `filesDir/crash_logs/`, (2) drops it in `cacheDir`,
(3) launches the crash screen, then ends the dying process — so a crash is never
silent again.

## 🎛 UX round 3 (2026-10-06, build 37445077463)

| Feedback | Change |
|----------|--------|
| "Model preloaded after clean reinstall" | Bundled model is now labeled **"Bundled (default)"** everywhere (top header + settings chip) so it reads as the factory default, not a leftover user pick |
| No loading feedback when swapping models | Dedicated amber **loading chip** appears under the badge during any model parse ("LOADING MODEL…" / "STARTING MODEL…"), badge blinks in sync |
| Settings too spacious, descriptions everywhere | Sheet v3: title is just **"Settings"**; compact rows; GPU row is title + toggle only; thresholds are **two individual cells** ("Minimum Confidence" / "NMS Overlap") with live value chips; **Diagnostics section removed** (crash handling is automatic) |
| Swap-model row style | Lime-outlined action row; the current model name is a chip on the **right of the row**, not underneath |
| Pause processing in settings | Live camera inference pauses while the sheet is open (`settingsOpen` gate in the analyzer) and resumes on dismiss |
| Prettier toggles | All switches use the lime thumb + dark track styling |
| Boxes optional | **Bounding Boxes** toggle (DISPLAY section). Off = clean mesh-only view, and the label chip then anchors to the **top edge of the actual mask** (sits on the object), not an invisible box corner |
| Smooth mesh outline | **Smooth Mesh Outline** toggle: contour-traces the prototype mask (Moore-neighborhood), simplifies the path, and strokes a highlight border around the object — soft colored glow + bright inner line |

## 🛟 Crash-screen round (2026-10-06, build 37447778520)

User's crash log (OnePlus KB2001, Android 14) exposed a real ordering bug from
UX round 3: `onCreate` touched `overlayView.showBoxes` **before** `bindViews()`
ran → `UninitializedPropertyAccessException` on every launch. The crash screen
itself worked exactly as designed (the log surfaced it), which validated the
pipeline — and its UI was then redesigned per feedback:

- Title tight to the top (no dead space), muted time + hint line under it
- Log console in a **rounded panel** with a small lime **Copy** chip at its top-right (toast on copy)
- Bottom bar: **Close** (rounded, muted, bottom-left) and **Restart App** (rounded, lime, bottom-right)
- `onCreate` now binds views first, then applies display prefs — and the rule "views before view properties" is commented at the call site

## 🎨 Round 5 — big polish pass (2026-10-06, build 37454354220)

**Settings v4** — Material `SwitchMaterial` (proper modern toggles, lime thumb on
dark track), toggle rows compressed to single-line height, **each threshold
slider on its own full-width row** (they were side-by-side before), **Swap Active
Model** is now a centered compact button (hugs its text; the current model name
no longer sits in it), sheet top corners rounded to 28dp, and opening settings
now **fully unbinds the camera** (sensor + analyzer stop) instead of just
skipping inference — re-binds on dismiss.

**Smooth mesh outline overhaul** — the outline is now:
- **class-colored, vibrant and opaque** (HSV-boosted variant of the mesh color — never white)
- **geometrically smooth**: contour resampled to 48 arc-length-even points, then
  quadratic-bezier curves (midpoint technique) — flowing line, no boxy corners
  unless the object shape truly demands them
- **airtight**: the tinted mask is *clipped to the outline path*, so no mesh
  pixels can ever show outside the border line
- **temporally smoothed**: an exponential moving average blends the contour and
  the label anchor across frames (jumps beyond a distance threshold re-acquire
  instantly, so fast moves never lag behind)

**Label anchoring** — with boxes off, the label now anchors to the smoothed top
edge of the actual object silhouette (scan of the mask bitmap, EMA-smoothed), so
it sits ON the object and glides with it instead of hovering at a box corner.

**Top section redesign** — LIVE FEED badge **top-left**; model name **top-right**;
under them a details line `512×512 · 11.1 MB`; below that a horizontally
scrollable row of class chips, each showing a class the model knows **in its
exact mesh color** (shared palette in the new `DetectionStyle` object used by
both chips and overlay).

## 🔧 Round 6 — glitch fix + polish (2026-10-06, build 37459370561)

**CRITICAL smooth-outline bug fixed.** Round 5's rewrite fed mask *pixel*
coordinates (0..128) into screen math as if they were normalized (0..1), so the
outline path landed ~100× off-screen and its clip erased the mask entirely —
that's the "mesh disappears / boxes glitch" the user saw. Fixes in OverlayView v4:

- contour is **normalized to 0..1 mask space** before any screen mapping
- all tracing/resampling/EMA runs **once per frame in `setResults`**; `onDraw`
  only draws cached geometry (v3 traced inside onDraw — UI-thread jank)
- if tracing fails, the plain tinted mask is drawn — the mesh never disappears
  because of the outline feature
- outline = vibrant class color (never white), bezier-smoothed, mask clipped
  to it, EMA temporal smoothing on contour and label anchor

**Also in this round:**
- Settings v5: **solid lime Swap Active Model button** (centered), **iOS-style
  custom toggles** (`IOSToggle` view — animated white knob, lime track), rows
  compressed further, **8dp-thick slider tracks**
- Top section: model name sits **immediately right of the LIVE FEED pill**
  (left-aligned, not pinned to screen edge), model details in a **pill**,
  class chips aligned to the **right edge**
- Settings sheet: rounded shape moved into a proper `bottomSheetStyle` theme —
  the Material backdrop no longer ghosts square corners behind the sheet
- **Signing**: `app/aimeshvision.keystore` (committed; password in
  `app/build.gradle.kts`) signs both debug and release with the same key, so
  every future APK installs as an update over the previous one. Workflow now
  uploads `AIMESHVISION-debug` **and** `AIMESHVISION-release` artifacts.

The bundled `assets/model.tflite` (CAT/HAND, from training run 20261005) is
committed with the repo (~11 MB, under GitHub's 100 MB limit).

## 🔑 Key rules for the rebuild

1. **The UI stays.** Dark glassmorphism layouts and the interaction flow are locked — functionality and error handling change, the look does not.
2. Every flaw in [ANALYSIS.md](ANALYSIS.md) (F1–F11) gets an explicit fix — see the map in [REBUILD_PLAN.md](REBUILD_PLAN.md).
3. Builds happen through GitHub Actions (see the outline in REBUILD_PLAN.md).

## ⏪ Round 7 reverted + performance pass (2026-10-06, build 37475918757)

The round-7 behavior changes (full-frame masks, contour rework, persistence
hold, chip highlight) introduced regressions and were **fully reverted**
(git revert 485c059). What remains from that cycle is a strictly
**output-identical performance pass**:

| Hotspot | Optimization |
|---------|--------------|
| `decodeMasks` sigmoid | `sigmoid(x) > 0.5` ⇔ `x > 0` — the `exp()` call per pixel per detection (the hottest line in the pipeline) is skipped entirely |
| `decodeMasks` allocation | one pixel-scratch `IntArray` reused across detections instead of a fresh ~66 KB array per detection per frame |
| `OverlayView` draw path | `PorterDuffColorFilter` instances cached per class color; label uppercase strings cached per class id (no per-frame string churn) |
| `ModelManager.letterbox` | the 512×512 letterbox `Bitmap` + `Canvas` are reused across frames instead of ~1 MB bitmap allocation per pass |

No visual or behavioral change — same pixels in, same pixels out, fewer
allocations and less CPU per frame.

## 🖍 Round 8 — accurate ring outline (2026-10-06, build 37497103723)

User: outline looks good but is not *accurate* — fingertips get meshed while
the border line "starts from below"; mask must never be cut off anywhere.

Root cause: the outline was a fitted bezier over a traced contour, and the
mask was **clipped to that fitted line** — wherever the fit lagged (thin
fingertips, fast motion) the line missed the mesh and the clip removed it.

OverlayView v5 replaces the whole contour-tracing approach with a **ring built
from the mask's own pixels**:

1. Stamp the silhouette in **16 directions** at a 2px (mask-space) radius →
   dilated shape, rounded by the circular stamp pattern.
2. **Subtract the original mask** (Porter-Duff `DST_OUT`) → a ring that hugs
   the true boundary *by construction* — fingers, corners, thin protrusions
   included. Nothing is fitted, so nothing can lag.
3. **No clipping anywhere** — the tinted mask is always drawn in full under
   the ring, making "mask cut off" geometrically impossible.
4. Rings come from a small scratch pool (reused bitmaps, no per-frame churn),
   and the entire contour/EMA/clip machinery (~260 lines) is deleted.

## 🖍 Round 9 — thin, smooth ring (2026-10-06, build 37501230933)

User: v5's ring adapts correctly but is too thick and pixelated; the old
bezier look was smoother. Middle ground delivered, no approach change:

- **Thin line, centered on the boundary** — outer band (dilation) + inner band
  (erosion via sequential DST_OUT), total ≈ 1.5 mask px (~6 screen px vs the
  old 2px/8px band and the old bezier's 5px stroke).
- **Feathered edges** — stamps land on sub-pixel offsets (0.75px radius, 24
  directions) with bilinear filtering, so the line edge is soft, not
  staircased.
- **Temporal blend** — each frame's ring is unioned with a faded (alpha 120)
  copy of the previous frame's ring: single-frame edge flicker melts away,
  matching the old EMA line's calmness with zero curve fitting. Truly-dropped
  regions decay geometrically in ~4 frames — no ghost trails.

Tuning knobs, all single constants: `RING_RADIUS_PX` (line width),
`RING_DIRECTIONS` (roundness), `RING_TEMPORAL_ALPHA` (smoothness).

## 🛠 Ring fix — "no border shows" (2026-10-06, build 37504406232)

The v6 temporal-smoothness update broke the ring entirely: it derived the
line's inner half via "erosion" using sequential `DST_OUT` with inward-shifted
masks. That is mathematically wrong — sequential DST_OUT computes
*base − union(shifts)*, and the union of **all-direction** inward shifts is
itself a dilation covering the whole canvas → the ring rendered fully
transparent (toggle did nothing).

Correct construction (v7): the line is the difference of two dilations,

    line = dilate(mask, 1.05px) − dilate(mask, 0.30px)

Both are stamp-based, provably visible, ~0.75 mask px wide (~3 screen px),
hugging the mask boundary everywhere, feathered by sub-pixel bilinear stamps,
with the temporal blend retained. The line now sits immediately outside the
mesh edge (touching it), adapting to fingertips and all silhouette detail.

## ✏️ Round 10 — smooth line done right (2026-10-06, build 37628645437)

User feedback on v7: line was pixelated (it shared the mask's resolution),
sat outside the mesh instead of on it, and moving objects left a "shadow".

OverlayView v8 - the middle ground, built from the lessons of every round:

- **Smooth**: back to the quadratic-bezier curve fitted to the contour —
  resolution-independent, anti-aliased stroke (the old look). The ring's
  pixelation came from bitmap dilation at mask resolution; a fitted curve
  has none of that.
- **Centered on the boundary**: the 5px stroke is drawn ON the contour, so
  half lies on the mesh and half outside — exactly as requested.
- **Nothing outside the line**: the mesh is clipped to the fitted path.
- **No shadow**: the ghost was v6/v7's temporal bitmap blend (last frame's
  ring unioned into the current one). Removed entirely. Smoothing now lives
  only in the point EMA, which moves WITH the mesh.
- **Both historic bezier bugs fixed at the source**: contour normalized to
  0..1 before screen mapping (v3 off-screen bug) and rotation-aligned to the
  previous frame's start point before EMA (round-7 scribble bug). All
  analysis runs once per frame in setResults; onDraw only draws.

## 🔗 Round 11 — local border chaining (2026-10-07, build 37635133986)

User: v9's outline "tries to connect every single part of the mesh" — a dot on
the top right would connect to one on the top left. Cause: the greedy
nearest-neighbor chain searched the WHOLE dot set each step, so when the local
neighbor was used up it jumped across the shape.

v9.1 replaces global greedy with a **local border walk**:

- each step moves only to an unused dot within the 8-neighborhood on the dot
  grid (Chebyshev distance <= 2 cells) — a jump across the shape is impossible
- steps prefer continuing the current walk direction (straighter border), so
  the chain follows the outline instead of zig-zagging
- if no adjacent dot is free, the chain **ends** — never forced across a gap;
  remaining dots start new chains
- multiple chains supported; the longest is drawn as the object's main outline
  (separate blobs on the mesh are simply not connected)

Lookup is O(1) per step via a grid hash, so the walk is faster than the old
O(n²) greedy pass.

## 👐 Round 12 — per-instance outlines (2026-10-07, build 37653129310)

User's six-hands test exposed the deepest flaw yet: the temporal smoothing
tracked **one outline per class**, not per object. Two hands of the same class
shared a single track, so their border dots blended into one path — which is
why (a) only one hand got outlined, (b) the "clip to the line" step erased the
mesh, (c) some meshes turned solid, and (d) labels/counts mis-merged.

Fixes, all in OverlayView v10 + a stats upgrade:

1. **Per-instance tracking** — detections are matched to the previous frame
   by bounding-box IoU (same class, IoU > 0.25). Each instance owns its own
   point track; unmatched detections render raw and register new instances.
   Expired instances (not seen for 200ms) are dropped.
2. **ALL chains render** — every blob of every detection gets its own outline
   (the v9.1 "longest chain only" restriction is gone).
3. **Sane-clip guard** — a clip path is validated (must overlap ≥ 15% of the
   mesh rect) before clipping; a degenerate path skips the clip instead of
   erasing the mesh. Belt and braces on top of the instance fix.
4. **Detected counts in the details pill** —
   `512×512 · 11.1 MB · 6 HAND · 0 CAT`: per-class live census, 0 shown when
   none. Resolution/size info retained as the prefix.

## 🛠 Round 13 — four regression fixes (2026-10-07, build 37655931467)

1. **Outline completely gone** — the v10 sane-clip guard gated BOTH the clip
   and the stroke, and calibrated "sanity" as ≥15% of the *frame-sized* mask
   rect. A hand is typically <15% of the frame, so every valid outline was
   rejected. Now the **stroke draws whenever a path exists** (an outline can
   never silently vanish), and the clip applies only when the path bounds are
   real and sit inside the mesh rect — a degenerate path skips just the clip,
   never the line, never the mesh.
2. **Camera wouldn't resume after the gallery** — pause/resume had no binding
   state: resume was skipped while `isPaused`, leaving the camera unbound →
   black feed until app restart. Added a `cameraBound` flag; the pause/resume
   toggle now guarantees a rebind (`resumeCamera()`) whenever the camera
   isn't live.
3. **Counts were in the wrong place** — detected counts now live ON the class
   chips ("6 HAND · 0 CAT" as chip text, right side); the details pill returns
   to pure `512×512 · 11.1 MB`.
4. Label anchoring with boxes off remains the v10 per-instance path (each
   hand's label follows its own outline's top edge).

## 🧪 Round 14 — three-agent review loop (2026-10-07, build 37664181151)

Per the user's process request, three independent reviewer agents audited the
outline/mask/tracking pipeline in sequence; every finding was fixed before the
next review. Final verdict: **CONFIDENT - solved**.

**Reviewer 1 (14 findings, all fixed):**
- CRITICAL: reused mask pixel buffer never zeroed -> every mask after the
  first contained the previous detection's blob as solid pixels (root cause
  of solid meshes, blob contamination, merged labels). Fixed: Arrays.fill(0)
  per detection.
- Only the longest chain was rendered -> ALL chains now outline (one per blob)
- Open chains were force-closed -> a straight line cut across the mesh; paths
  now close only when the chain genuinely loops
- chip.tag was never set -> counts never appeared; fixed
- Wall-clock instance expiry disabled smoothing on slow frames -> frame-count
  grace (GRACE_FRAMES=3), refreshed even when mask decode fails
- Walk neighborhood Chebyshev 2 -> 1 (no border-gap jumps), grid lookups
  bounds-checked, chain-level jump fallback (no Frankenstein frames), pause in
  gallery no longer overwrites the gallery overlay, labelCache invalidated on
  model change

**Reviewer 2 (4 findings, all fixed):**
- HIGH: clipPath INTERSECTS, so stacking clips for multiple blobs erased the
  mesh -> clip+draw per path in its own save/restore
- Open chains' clip FILL used the implicit closing chord -> only genuinely
  closed chains may clip (parallel closedFlags gate)
- Instance keep-alive hoisted before the mask-null branch; dead `now` removed

**Reviewer 3: verified all fixes correct (save/restore balance, closure-test
consistency, instance tracking end-to-end), flows A-E all PASS, verdict
CONFIDENT.** Its one cosmetic finding (bezier control point should be the dot
shared between the two midpoint anchors) was also fixed.

## 🎯 Outline improvement plan — planned, reviewed, executed (2026-10-07, final build 37684558491)

The user asked for a solid plan built with sub-agent exploration + adversarial
review of the draft. Process: explorer agent mapped the current pipeline's
exact limits → draft plan → adversarial reviewer corrected 2 technical errors
in the draft (saturating alpha mapping; index-stability assumption under
winding flips) → 6 stages shipped one per commit:

1. **1A full-frame mask decode** (bbox-crop removed; protrusions now decoded;
   MAX_FULL_DECODE=6 fallback + decode-ms log)
2. **1B subpixel border dots** (decode writes a linearized-sigmoid alpha byte;
   dots lerp to the true 0.5-level crossing; chaining on rounded cells with
   float positions)
3. **3F canonical winding** (negative-signed-area chains reversed - kills the
   silent EMA direction-flip)
4. **2D soft temporal gate** (per-dot blend weight fades with deviation - no
   more snap-to-raw pops)
5. **2C top-3 chain EMA** (secondary blobs smooth too; length + first-dot
   proximity guards)
6. **3E stroke-under-mask draw order** (line sits flush on the boundary)

Then the final reviewer agent found **4 MAJOR defects in the execution** — all
fixed: inverted lerp direction (weak-edge dots placed ~1px outside), a shadowed
`anchor` variable that silently killed silhouette labels, single-value grid
cells dropping dots on diagonal borders (now per-cell lists + actual-delta
scoring + same-cell connectivity + 3px step cap), and a closure threshold in
screen px that sat below the real inter-dot distance (now mask-px, letterbox-
safe via the real bitmap size). A confirmation reviewer verified all fixes and
returned **CONFIDENT - solved**.

## 🩺 Round 15 — outline root cause found & real contour pipeline (2026-10-08)

The user reported the smooth outline was "very bad, not showing properly, looks
mad" — after six rounds of incremental "stages". This round refused to guess and
**reproduced the failure offline** by porting the exact Kotlin outline code to
Python and running it on synthetic masks.

**Root cause (proven, not guessed):** `extractBorderDots` sampled only
**odd-odd** pixel coordinates on a 2px grid. On a plain circle that captured just
**48 of 228 border pixels** (33 on the top arc), so the chained walk hit a ~14px
gap, exceeded `MAX_STEP_PX = 3`, and died after 2 dots; chains under 8 dots were
then discarded. Offline result: a **circle produced ZERO chains**, a hand only a
single fragment. Every earlier "stage" was built on this broken foundation — it
was never going to work.

**Fix — a true contour pipeline** (validated offline first, then ported to
Kotlin and parity-tested against the validated Python):

1. **4-connected component labelling** — one outline per blob (multi-hand scenes
   get one outline per hand).
2. **Moore-neighbour boundary tracing** — walks EVERY border pixel in order, so
   fingertips and sharp tips are exact (no sampling holes).
3. **Douglas-Peucker simplification** (~1.3 mask px) — removes the pixel
   staircase, keeps real corners.
4. **Canonicalize** (winding + topmost start) THEN **arc-length resample to a
   fixed 72 points** — index i maps to the same boundary location across frames,
   so temporal smoothing is stable.
5. Contours are **closed by construction**, so they are always valid clip
   regions (the old "open path implicit chord erases the mesh" bug cannot
   recur). Rendering: tinted mask clipped to each contour, then the vibrant
   stroke **on top** (`onDraw`).

Offline parity output (circle / hand / notched / ring): **one clean closed
contour each**, hugging every fingertip — versus 0–1 fragments before.

**Adversarial reviewer** (separate agent, forced a real Kotlin compile — BUILD
SUCCESSFUL) found no functional/crash defects and rated it quality-issues only;
all addressed:

- canonicalization moved **before** resampling so index 0 is exactly the same
  point per frame (was approximate);
- secondary blobs now matched to the nearest unused previous track by
  first-point distance (was by sorted index — could blend one blob's track into
  another);
- mask-sized scratch buffers hoisted to reusable fields and the DP stack made a
  primitive `IntArray` (removes per-detection-per-frame allocation → no GC jank
  at camera frame rates); `HashSet` boxing in the tracer replaced by a
  `BooleanArray`;
- DP join no longer drops a real ring vertex.

Files: `ui/OverlayView.kt` (contour pipeline rewrite), `inference/YoloPostProcessor.kt`
(unchanged this round — the graded-alpha byte from round 14's stage 1B is now
unused by the new trace but left in place, harmless).

## 🎯 Round 16 — device-feedback round (2026-10-08, builds d87ba11 → dc1678d)

Device test of build `b9bc910`: **perf "properly improved"**, labels
**satisfactory**, mesh smooth (keep it). Remaining: 500ms-class stutter with
everything off, mesh spilling past the hand, labels moving when boxes hidden,
outline double-lines. Fixed as four attributable commits, each CI-green:

| Commit | Change | File |
|---|---|---|
| `9088edd` | Perf: contour extraction gated while outline off (was main-thread per frame regardless); composite buffer skipped entirely for non-overlapping masks (direct alpha-110 draws are pixel-equivalent), dirty-rect clear/blit when overlapping; `util/Perf.kt` logs every stage ≥16ms (tag `Perf`). Labels always at box top, silhouette-anchor machinery deleted. | OverlayView, MainActivity, ModelManager, **util/Perf.kt** |
| `87e3221` | Mesh spill: `MESH_ALPHA_CUTOFF` (blur output below 96 zeroed — outside-feather maxes at 85 after one separable pass, interior edge starts at 113) | YoloPostProcessor |
| `911972a` + `d87ba11` | Double lines: geometric `offsetOutward` deleted (crossing normals at corners self-intersected → looped strokes); stroke now pristine-boundary path clipped to view-minus-own-fill | OverlayView |
| `c481888` | Outline quality: **noise-peak removal** (closed-detour vertices — net turn ~0, sharp ≥34°, depth <3.5 mask px — deleted before corner detection, so the line stops chasing pixel jaggies; fingertips/real notches protected structurally); **phase-aligned EMA** (`alignTo` cyclic offset minimization before the correspondence gate — kills lattice re-phase when the top-anchor hops on flat edges); **per-object clip** (one object's outline can never erase a neighbour's); stroke 10px reference @1080 with width scaling (visible band ≈5px, was 2.5px — the "too thin" complaint); outline toggle re-extracts cached detections (`onOutlineEnabled`, wired in settings — outline appears instantly even paused) | OverlayView, MainActivity |
| `dc1678d` | Mesh cutoff 96→104 (soft low-confidence edge residual; still provably below interior 113) | YoloPostProcessor |

Checkpoint: tag **`checkpoint-mesh-stable`** on `b9bc910` (the build whose
mesh/labels the user approved) — recover with
`git checkout -f checkpoint-mesh-stable` if anything regresses.

Note: a transient git ref/index corruption occurred mid-round (zeroed
`refs/heads/main`); all objects were intact, ref repointed at `911972a`,
index rebuilt with `read-tree` — backups kept as `.git/index.corrupt.bak`
and `.git/broken-main-ref.bak`.

Still open (user-acknowledged, later): split-and-detect → two-pass refine
(full detection first, then targeted passes around found objects);
AppConfig centralization of tunables; further outline polish.

## 🔁 Round 17 — outline redo research launched + split & detect redesign (2026-10-08)

**Outline:** per user instruction ("redo the whole system from scratch...
research first, then plan, then execute"), three research agents launched in
parallel with distinct angles: (1) prior art / production techniques, (2)
adversarial architecture critique of our pipeline, (3) Android feasibility +
performance. Agent 2 completed: diagnosis — two disagreeing boundary
definitions (tint cutoff 104 vs contour iso 128), clip-vs-stroke
non-determinism, and correspondence machinery (anchor/align/gate/EMA) as the
three structural failure families; ranked **image-space band** (outline
derived from the graded alpha field the tint already draws — same iso
level, so double lines are unrepresentable) as #1 with a full deletion map
for OverlayView.kt. Agents 1 & 3 pending; **plan to be presented to the
user for approval before any outline code changes.**

**Split & detect redesigned to spec** (`7cdb5eb`, CI green run 37769048690):
two-pass refine — full-frame detection first, then targeted re-inference
ONLY around the top-4 found objects (box x1.6, min 384px crop), replacing
their box/mask when the refine holds >=80% confidence, adding close-only
objects IoU-deduped, never downgrading a baseline detection. Latency
1+up to 4 inferences (was up to 12) and scales with actual object count.
`Detection` gained `maskLeft/Top/Width/Height` (normalized source rect of
the mask bitmap) so crop masks map through `OverlayView.maskRectFor` — no
compositing canvas, no resample. Equal-grid code deleted.

**AppConfig:** deferred by user until the outline redo lands.

## 🚀 Round 18 — outline REWRITE: image-space band (2026-10-08)

Per user demand ("completely redo the whole smooth mesh outline system from
scratch"), three research agents were launched in parallel: prior-art
survey, adversarial architecture critique, Android feasibility/perf. Agents
**B and C independently converged on the same architecture** — the
image-space band — and the critique agent's deletion map matched the
feasibility agent's cost table line-for-line. The prior-art agent was still
in flight when execution began (user's go-ahead); its findings fold in later.

**Diagnosis (why the old pipeline kept failing):** three structural failure
families — (1) *two disagreeing boundary definitions* (tint cutoff vs
contour iso → the line and the mesh could never agree: double lines, half-
swallowed strokes), (2) clip-vs-stroke `Path.op` machinery that is
non-deterministic on hardware canvases, (3) the whole anchor/align/gate/EMA
correspondence apparatus (~20 tunables compensating for each other).

**New architecture — outline as an image-space band:**

- `YoloPostProcessor.smoothMaskAlpha` now harvests, in the SAME vertical
  blur pass that builds the mesh, an outline band: texels whose final
  blurred alpha lies in `[RING_LOW_ALPHA=40, MESH_ALPHA_CUTOFF=104)` get a
  doubled/clamped ramp (bright at the mesh edge, soft tail to the feather
  floor) written into `Detection.outlineBitmap`. **One threshold serves
  both layers** — band inner edge ≡ mesh outer edge by construction: no
  gap, no double lines, no clip, no path, smoothness = the field's own
  smoothed gradient (bilinear ~8 screen px/texel). Band = ~1-2 texels ≈
  8-16 screen px soft border. Bbox-only tail decodes skip the band (their
  mask is cut at the box).
- **Speckle filter moved into decode** (mesh + band, both states) — fixes
  the pre-existing "speckles tint the frame with outline OFF" bug and
  prevents speckles from growing outline rings. Two flood sweeps on the
  16k-texel mask, zero allocation (caller scratch).
- **`OverlayView.kt rewritten: 1364 → 403 lines.** Deleted: flood fill,
  Moore tracing, Douglas-Peucker, noise-peak heuristics, corner angles,
  arc resample, phase alignment, EMA/instances/anchors, bezier paths,
  `Path.op` outside-clips, `isSaneClipPath`, per-path tint clipping. Kept:
  mesh composite overlap route (dirty-rect, fast-path), boxes/corners/
  labels (box-top), split&detect `maskRectFor`, Perf logs. The outline
  toggle now just gates one `drawBitmap` per detection (invalidate on both
  edges — appears instantly, even paused).
- `Perf.MIN_MS` 16 → 4 (the overlay budget is 3-8ms; 16ms hid every
  interesting reading).
- Temporal note: the band is a pure per-frame image — jitter sources are
  structurally gone; if field flicker shows on device, the researched next
  step is a bbox-warped field EMA in decode (agent C's phase 2, ~0.1ms/det
  background).

Checkpoint for rollback: tag `checkpoint-mesh-stable` (mesh/labels-approved
build) and commit `7cdb5eb` (pre-rewrite, split&detect green).

## 🔧 Round 18b — band v2: distance-based (fixes dotted outline / missing corners) (2026-10-08)

Device report on the band rewrite: outline rendered **dotted** and **did not
follow the corners** (line only appeared on straight-ish edges, outside the
mesh). Root cause identified analytically: the band was harvested from the
**separable box blur's exterior feather**, and a separable (axis-aligned)
blur only spreads along rows/columns — at corners and diagonal edges the
feather value falls below the write floor, so those texels never got band
alpha → dotted line, missing corners.

Fix — `buildOutlineBand`: a **3-4 chamfer distance transform** on both sides
of the thresholded silhouette (dist-to-mesh for exterior texels,
dist-to-exterior for interior texels), converted to an alpha ramp peaking AT
the boundary: `alpha(t) = 255·clamp01((R+1−t)/R)` with `RING_OUT_TEXELS=2`
(outer fade) and `RING_IN_TEXELS=1` (inner). Consequences:
- **solid around corners by construction** — every texel within range gets a
  value; no threshold gaps exist to create dots;
- the line **straddles the edge** (half in the mesh, half out) so it sits ON
  the silhouette/corners instead of floating outside it;
- speckle erasure now runs on the mesh BEFORE band derivation (halo sweep
  deleted — erased speckles can't grow outlines by construction);
- cost: 5 sweep passes over 16k texels, zero allocation, inference thread.

Tunables (documented, decode-side): RING_OUT_TEXELS, RING_IN_TEXELS.


## 🧱 Round 19 — outline robustness: Kotlin-verified MaskGeometry + adaptive band + advanced controls (2026-10-08)

Device report (round 19): the smooth mesh outline still had "a lot of
issues"; specifically **small objects grew a proportionally huge halo**
("if the images were small, the mesh could become bigger; if the image was
bigger, the mesh stayed the same"). User directives: use a *proper, solid
method* with real understanding; **build a Kotlin-native verification
system instead of Python scripts**; add advanced adjustment features
(mesh smoothness etc.); everything modular, documented, commented, CI-only
builds.

### Root cause of the small-object halo
The outline band width was **fixed in texels** (2 out / 1 in), and each
texel is ~8-16 screen px. A large object (1500+ texels) barely notices a
2-texel rim; a 30-texel object gets a halo comparable to (or larger than)
its own radius — the mesh "becomes bigger" exactly when the object is
small. Nothing about the geometry scaled with the component.

### Changes

| # | Change | File |
|---|--------|------|
| 1 | **`MaskGeometry.kt` extracted** — the entire mesh/outline geometry (blur+speckle+cutoff, speckle flood, chamfer band) moved into a pure, Android-free `object` with every constant documented; `YoloPostProcessor` now only orchestrates tensor→decode→bitmaps | `inference/MaskGeometry.kt` (new), `inference/YoloPostProcessor.kt` |
| 2 | **Adaptive band width with limits** — the speckle flood stamps each texel with its component area (`compSize`); the chamfer sweeps propagate the nearest owner's area to exterior texels (`outSize`); reach = `floor + (full−floor)·clamp01(radius/12)`, `radius = sqrt(area/π)`. Outer ∈ [1, 2] texels, inner ∈ [0.5, 1] — small objects get a proportionally thinner rim, **never below the floor**, large objects keep the full classic width. The ramp peak (255 at the boundary texel) is reach-independent, so the line can never vanish or dim at any size. | `inference/MaskGeometry.kt` |
| 3 | **Kotlin-native verification (replaces Python analysis)** — `MaskGeometryTest`, 9 JVM tests asserting the actual shipped code: side/corner continuity (no dots), straddle + clean deep interior/far exterior, adaptive small<large width with floor + full-width large, speckle erasure (mesh AND band) + area annotation, spill never grows the object, passes=0 exact no-op, width multiplier scales depth but keeps a 255 peak, empty mask safe. CI runs `testDebugUnitTest` **before** assembling — a geometry regression now fails the build. | `app/src/test/.../MaskGeometryTest.kt`, `.github/workflows/android-build.yml` |
| 4 | **Advanced controls — "MESH & OUTLINE" section** (all persisted, applied live): **Mesh Smoothness** 0/1/2 blur+cutoff passes (`@Volatile` per decode; cutoff runs after every pass so feather can't accumulate — (255+85+0)/3=113>104), **Outline Width** 50–150% (multiplier on the adaptive reach), **Outline Opacity** 25–100%, **Mesh Opacity** 10–100% (render-side `maskAlpha`/`outlineAlpha` fields, invalidate on change). Sheet root wrapped in a `ScrollView` so the taller sheet never clips. | `res/layout/bottom_sheet_settings.xml`, `MainActivity.kt`, `inference/ModelManager.kt`, `inference/YoloPostProcessor.kt`, `ui/OverlayView.kt` |
| 5 | Stale `OverlayView` architecture doc (still described the deleted feather-harvest band) rewritten to the distance-band + adaptive-width reality | `ui/OverlayView.kt` |

### Verification
- Local `gradlew testDebugUnitTest` (JVM only, **no APK**): **MaskGeometryTest 9/9, 0 failures** + template test — first run green.
- `compileDebugKotlin` green (only pre-existing CameraX deprecation warnings).
- CI: unit-test step added ahead of `assembleDebug`; push → Actions green.

Knobs (single source of truth): `MaskGeometry` — `MESH_ALPHA_CUTOFF=104`,
`RING_OUT_TEXELS=2`, `RING_IN_TEXELS=1`, `RING_OUT_MIN=1`, `RING_IN_MIN=0.5`,
`BAND_FULL_RADIUS=12`, `MAX_SMOOTH_PASSES=2`, `SPECKLE_FRACTION=0.10`,
`MIN_SPECKLE_PX=12`.


## 🏷️ Round 20 — MESHLABEL: LabelMe-style annotation app (same repo, sibling directory) (2026-10-09)

New requirement: a **LabelMe-like Android app for image segmentation /
dataset creation** — draw masks, save exact LabelMe JSON, plus Google AI
Edge Gallery's "Magic Touch" scribble segmentation (add/remove selection
strokes via MediaPipe interactive segmenter). Same GitHub repository, own
directory, CI-only builds.

**Repo restructure (prerequisite):** git root promoted from `AIMESHVISION/`
to `ANDROID/` (commit `0bb6da4`, 72 renames, history intact) so both apps
live side by side in `testplay-byte/TESTMESH`; the AIMESHVISION workflow
now runs with `working-directory: AIMESHVISION`.

**Research** (dedicated agent, report kept in project notes): exact
MediaPipe `InteractiveSegmenter` Kotlin API (`setImage` once →
`segment(List<Stroke>)`, per-stroke POSITIVE/NEGATIVE polarity, normalized
keypoints, `ByteBufferExtractor` confidence mask > 0.5), the gallery's
Scrapbook pipeline (index-based stroke undo, stale-id guard), the exact
LabelMe v7 JSON schema from `wkentaro/labelme/_label_file.py`, and
mask→polygon options (hand-rolled chosen: no 20-80MB OpenCV).

**MESHLABEL/** (new Gradle project, AGP 9.0.1/JDK21 like its sibling):

| Piece | File | Notes |
|---|---|---|
| LabelMe JSON (exact schema) | `labelme/LabelMeJson.kt` | version/flags/shapes/imagePath/imageData/base64/imageHeight/imageWidth; shape: label/points(px)/group_id/shape_type/flags/description; tolerant loader |
| Mask → polygon | `seg/MaskToPolygon.kt` | pure Kotlin: marching squares on the pixel-center lattice → integer-key loop chaining (single-direction closure walk) → Ramer-Douglas-Peucker; ≤1024px downscale path; saddle rule documented |
| Magic Touch wrapper | `seg/InteractiveSegmenterManager.kt` | bundled `interactive_segmentation.task` (30MB, verified zip), single-thread executor, setImage-once, requestId stale-guard, 0.5 threshold |
| Storage | `store/ProjectStore.kt` | labelme side-by-side folders `MeshLabel/<name>/<name>.jpg|.json`, bounded decode (4096), base64 imageData |
| YOLO-seg export | `store/YoloSegExporter.kt` | `class_id x1 y1…` 6-decimal normalized lines, classes.txt, dataset.yaml (pure `yoloLine` unit-tested) |
| Canvas | `ui/AnnotationView.kt` | ALL annotation coords = ORIGINAL image px (decode-downscale rescaled at the mapping boundary); vertex drag, draft polygon w/ close-on-first-dot, stroke rendering, mask preview overlay |
| Screens | `LibraryActivity` / `EditorActivity` | photo-picker import, snapshot undo/redo (cap 50), label prompt, shapes list actions, save/unsaved-guard |

**Verification (Kotlin-native, no Python):** `MaskToPolygonTest` +
`LabelMeJsonTest` + `YoloLineTest` — **11/11 green locally**
(`gradlew :app:testDebugUnitTest`; debug+test compile clean). Bugs the
tests caught during development: two-sided loop walk folding the ring
(−12% area), RDP anchored on adjacent ring points (edge slants), Builder
API misuse. CI: new `.github/workflows/build-meshlabel.yml` (unit tests →
assembleDebug → assembleRelease(unsigned) → MESHLABEL-debug/-release
artifacts).

Notes: portrait-locked; one polygon per Magic Touch selection (largest
loop); release unsigned (no project keystore for this app — debug APK is
the installable artifact); the dedicated builder sub-agent was
safety-rejected mid-task, so the app was built in the main session
(agent delivered the model asset download + the research report).


## 📦 GitHub Releases (arm64-v8a only)

Both apps build **arm64-v8a exclusively** (`ndk.abiFilters` in each
`app/build.gradle.kts` — the only device family in use; re-add ABIs there
if ever needed) and publish to the repository **Releases page** on version
tags:

| App | Tag pattern | Release asset | Notes |
|---|---|---|---|
| AIMESHVISION | `aimeshvision-v*` (e.g. `aimeshvision-v2.1`) | `AIMESHVISION-release.apk` | signed with the project keystore → installs as an **update** over existing installs |
| MESHLABEL | `meshlabel-v*` (e.g. `meshlabel-v1.0`) | `MESHLABEL.apk` | this app has no release keystore → the asset is the **debug-key** build (installable) |

Cutting a release (CI-only, no local builds):
1. Bump `versionCode`/`versionName` in the app's `app/build.gradle.kts`
   (AIMESHVISION: must increase `versionCode` for Play-style update
   semantics), commit to `main`, CI green.
2. `git tag aimeshvision-v2.1 && git push <remote> aimeshvision-v2.1`
   (or `meshlabel-vX.Y`).
3. The matching workflow runs tests + both variants, then
   `softprops/action-gh-release@v2` creates the Release and attaches the
   APK. Regular `main` pushes keep producing the usual CI artifacts
   (`AIMESHVISION-debug/-release`, `MESHLABEL-debug/-release`).


## 📐 Round 21 — high-resolution outline: the band leaves the mesh's grid (2026-10-09)

Device report: **"the smooth mesh outline's resolution is way too low -
it must not have the same low resolution as the mesh itself."**

Root cause: the band was rasterized on the SAME grid as the mesh (~114²
proto texels, one texel = ~9-10 SCREEN px). The mesh tint reads fine at
that size, but a full-alpha bright LINE exposes every quantization step:
corners locked to a ~9px staircase, ramp values only sampled at texel
centers - i.e. the outline was structurally as coarse as the mesh.

**Fix — the band now lives on its own fine grid:**

| Piece | File | What |
|---|---|---|
| `RING_UPSCALE = 4` fine rasterizer | `inference/MaskGeometry.kt` | band bitmap is now `(w·4)×(h·4)` (~456², one fine texel ≈ 2.4 screen px). Sub-texel precision by **bilinearly interpolating the SIGNED coarse distance** (+dIn inside / −dOut outside): its zero-crossing sits BETWEEN texel centers - on the true boundary - so the ramp is sampled where the edge really is; the adaptive reach/widthScale ramp runs unchanged, per fine texel |
| Cheap everywhere-except-the-edge cost | same | two-stage gate: nearest-texel `|signed| > maxReach+8` skips (one array read; slack covers the field's max gradient), then the exact bilinear only in the boundary shell |
| Bitmap pool (swap-on-return) | `inference/YoloPostProcessor.kt`, `ui/OverlayView.kt`, `inference/ModelManager.kt` | the fine outline bitmaps (830KB) + masks are acquired from a per-size single-slot pool at decode and returned by the overlay at the next `setResults` (main thread, after the old list is dropped) - no per-frame bitmap allocation churn |
| Resolution invariants | `MaskGeometryTest` | **12/12 green**: ring is `S²×` the mesh raster; a column crossing the edge is CONTIGUOUS (0 internal gaps) and straddles the boundary with bounded depth (≥6 / ≥3 fine texels, ≤14 / ≤7); plus all prior invariants (corners, adaptive, speckles, spill, width multiplier) |

Coordinate note: mesh decode, speckles, chamfer sweeps and adaptive
`compSize`/`outSize` stay on the coarse grid (correct - they define the
boundary); only the VISIBLE ramp is upsampled. One boundary definition,
both layers (double lines still unrepresentable).

Version: AIMESHVISION **2.2** (versionCode 4) - released as
`aimeshvision-v2.2` (arm64-v8a only, signed release APK).
