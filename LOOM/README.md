# Loom — image-annotation workbench for Android

Loom is a LabelMe-style **image-annotation workbench for building
segmentation training datasets on Android**. You create a *project* (a
folder on device + images + label classes), then annotate each image with
**Magic Touch** smart selection — plant **prompt dots** (+/−) on an object
or draw around it with the brush, and an on-device MediaPipe
InteractiveSegmenter masks the object; the mask is traced into an
**adaptive polygon mesh** that hugs curvature (dense on fingertips, two
points on straight runs — density is auto until you pick a manual count,
which then applies to future images too). One **Undo** removes a prompt dot
and the mask it produced together. An image holds any number of
**objects**, each saved as its own editable polygon in its class color. Every image gets a **LabelMe v5 JSON
sidecar**; datasets **export** to Loom JSON / COCO / YOLO / Pascal VOC.

Built from the full "Loom" design handoff package (warm graphite + signal
amber, Space Grotesk + JetBrains Mono; dark theme default, light switchable).
Sibling app of AIMESHVISION in the same repository; built **only by GitHub
Actions** (`build-loom.yml` — no local Gradle runs at all, per project
policy: local device is low-powered).

## Screens (all 8, in flow order)

| # | Screen | Notes |
|---|--------|-------|
| 1 | Splash | The brand mark IS the splash — logo-only, full draw-on/weave/pop animation, tap to skip |
| 2 | Permissions | Two real grants: media permission + SAF storage-folder picker; limited-access bypass |
| 3 | Projects | Project cards w/ stepped-blob progress panel, overlapping thumb stacks, rounded-square FAB, bottom nav |
| 4 | New project | Name + Labels only — the folder is the storage folder + the slug; starts empty |
| 5 | Project detail | Stats card, labels row, masonry grid, photo-picker "Add images", overflow sheet |
| 6 | Annotate | THE canvas: Magic Touch dots/brush → adaptive mesh, multi-object chips, vertex editing, loupe |
| 7 | Export | 4 formats with REAL writes into the project folder, share, JSON preview |
| 8 | Settings | Theme, canvas defaults, undo depth, autosave, storage readout, clear data |

## The workspace folder IS the database

The user requirement (and the first-run flow) makes the workspace folder the
single source of truth. The app asks for a folder once (Permissions →
"Storage folder", real SAF tree picker, persisted) and then uses it for
**everything**:

```
<workspace>/                          ← user-picked (SAF) or app-private fallback
  my-dataset/                         ← one folder per project
    loom-project.json                 app metadata (ids, labels, image list, options)
    annotations.json                  INTERNAL Loom JSON working file (world coords,
                                      dots/tags/status/strokes — fast whole-project
                                      state for the app's own rescan)
    hand.jpg
    hand.json                         ← LabelMe v5.11 sidecar PER IMAGE — the
                                      user-facing annotation file (real pixel
                                      coords, one shape per object)
    IMG_2041.jpg …                    the images (demo scenes render to real JPEGs;
                                      imported photos are copied in)
    labels/, xml/, README.txt         written by Export only
```

Consequences, by design:

- **Creating a project creates a real folder** in the workspace (name +
  slug; no location picker — that folder is wherever the user's storage
  folder is). Projects start empty; images come from the photo picker or
  are dropped into the folder by hand.
- **Images added by hand are detected**: every visit rescans each project
  folder (and the workspace root) for image files and gives them fresh,
  empty annotation states. Deleting a project removes only Loom's own data
  (metadata + sidecars) — user images on disk are never touched, and the
  folder is remembered as dismissed so it doesn't auto-reappear.
- **LabelMe sidecars are authoritative for objects**: hand-edited or
  externally produced `<image>.json` files are adopted on rescan. The
  in-progress shape is marked `description: "working"` so it restores as
  the working mesh, never as a duplicate object.
- The app works fully without any grant: with "Continue with limited
  access" the same layout lives in app-private storage
  (`filesDir/workspace/`), changeable later in Settings.

## Magic Touch (MediaPipe InteractiveSegmenter)

`data/seg/SmartSegmenter.kt` wraps MediaPipe's InteractiveSegmenter
("Magic Touch", the Edge Gallery Scrapbook stack): the bundled
`interactive_segmentation.task` model (30.5 MB, CPU, int8), per-stroke
POSITIVE/NEGATIVE polarity, confidence threshold 0.5 with a 0.3 fallback
pass from the same confidence map. On real photos every **+ dot is a
positive prompt stroke, every − dot a negative one, and the brush draws
positive** (draw around an object to select it); strokes are consumed on
success. The mask is traced DENSE by `domain/seg/MaskToPolygon.kt`
(marching squares → loop chaining → closed-shape RDP) and fitted
**adaptively** by `MeshMath.fitRing` — a binary search on the RDP epsilon
so vertices land where the boundary curves, not uniformly. `autoFitRing`
picks the optimal minimal count (eps = 0.006 × max bbox dim) and the
stepper range is **3–200 pts** (hold a stepper button and the repeat
accelerates 250→60 ms). The stepper reads **auto** until the user picks a
manual count — a manual value is kept per image, survives restarts, and
becomes the starting count for not-yet-annotated images. Demo scenes keep the deterministic anchor sculpt
from `mesh.ts` instead — they are synthetic art with known anchors.

## Multi-object model

An `ImageState` carries the working mesh (in progress) plus
`objects: List<ObjAnn>` (saved). "Save object" commits the working mesh,
clears the prompts, and you keep annotating; object chips in the dock
re-open a saved object for vertex editing; exports emit every object
(COCO/YOLO/VOC skip unlabeled ones). Canvas renders saved objects dimmed
under the working mesh; the loupe parks in a fixed corner (never under the
finger), the mesh renders as the straight-line polygon it exports as, and
zoom goes to 10×.

## Canvas interaction contract

- Tools: pan · +dot · −dot · brush (select on photos / exclude on scenes) ·
  mesh-vertex edit · eraser. Tap a dot = place; press a dot = move it.
- Camera: fit-contain, pinch 50–1000 %, double-tap 2×, pan with clamp;
  the drafting grid is world-fixed and drawn once, behind the art, inside
  the frame.
- Vertex edit: drag with the corner **loupe** (112 px @ 3.2×), tap-near-edge
  inserts, selected vertex deletes via the Delete chip; every completed
  gesture = exactly one undo unit.
- Autosave 300 ms debounce; **flush on image switch and canvas exit**
  (exiting a fully annotated image marks it DONE). Sidecars are
  ANNOTATION-ONLY — image bytes are never embedded, so `hand.json` stays
  tiny next to `hand.jpg`.
- First-run hint: square-ish card, 2 s, tap-dismiss, once ever.
- Image tags (max 8) ride the same history and export with the dataset.

## Architecture

```
app/src/main/java/com/testplaybyte/loom/
├── domain/
│   ├── model/    Models.kt (Pt/Dot/Stroke/ObjAnn/ImageState/Project/settings/perms)
│   │             + LoomRules (validation, slugs, clamps, split rule)
│   ├── mesh/     MeshMath.kt            ← mesh.ts port + fitRing/autoFitRing (tested)
│   ├── seg/      MaskToPolygon.kt       ← mask → polygon, pure Kotlin (tested)
│   ├── history/  UndoStack.kt           ← bounded undo/redo (tested)
│   └── export/   LoomJsonCodec.kt       ← internal working file + Loom JSON export
│                 LabelMeCodec.kt        ← per-image LabelMe v5 sidecars (tested)
│                 ProjectMetaCodec.kt    ← loom-project.json
│                 DatasetWriters.kt      ← COCO / YOLO / VOC (tested)
├── data/
│   ├── prefs/    LoomPreferences.kt     ← DataStore (settings/perms/URI/hint)
│   ├── workspace/Workspace.kt           ← SAF tree ⭄ app-private fallback
│   ├── scene/    SceneDsl.kt            ← scene art as pure data (SVG-port)
│                 SceneLibrary.kt        ← the six demo scenes, 1:1
│                 SceneImage.kt          ← demo scenes → real JPEG files
│   ├── seg/      SmartSegmenter.kt      ← MediaPipe InteractiveSegmenter wrapper
│   ├── image/    ImageStore.kt          ← bounded, cached workspace decodes
│   └── repo/     LoomRepository.kt      ← folder-first source of truth
├── ui/
│   ├── theme/    Tokens/Type/Motion/LoomTheme (exact design tokens)
│   ├── icons/    LoomIcons.kt (46 ImageVectors, stroke 1.8, 24dp grid)
│   ├── components/ controls, surfaces, StatBlobPanel, MasonryGrid, SceneViews
│   ├── nav/      Routes + LoomNavHost (+ missing-project fallback)
│   └── screens/  splash/ permissions/ projects/ newproject/ project/
│                 annotate/ (CanvasStage.kt = the world) / export/ settings/
```

Design rules kept from the handoff package:

1. **All annotation geometry lives in world space (800×600)** — stored
   internal data never depends on device size, zoom or photo resolution.
   Demo scenes are authored in that world; real photos are letterboxed
   into it; exports and LabelMe sidecars invert the letterbox exactly
   (uniform scale + centering — never an anisotropic stretch).
2. **Pure cores are JVM-tested** (60 tests): mesh math incl. the docs'
   worked example, adaptive fitting, mask→polygon, undo/redo semantics,
   the LabelMe codec, the Loom JSON codec, COCO/YOLO/VOC writers,
   validation rules, masonry hash, DataStore codecs. CI runs them before
   assembling.
3. **No invented screens, colors or copy.** Values come from
   `reference/tokens.json`; icons are verbatim path ports; the copy deck is
   verbatim (intentional changes: Settings → About shows the real
   repository name; the canvas guide copy documents the shipped behavior).

## Documented deviations from the handoff package

| Deviation | Why |
|---|---|
| Storage is folder-first, not Room | The owner's requirement: the picked workspace folder is used for everything and must detect hand-added images. `loom-project.json` + sidecars are the database; DataStore holds settings/perms. |
| Persistence has no Room at all | With the folder as source of truth, a second DB would only risk divergence. |
| Multi-object annotations | The prototype allowed ONE mesh per image; the owner requires several objects per image with save/re-open semantics. |
| Per-image LabelMe sidecars are the user-facing format | Owner requirement ("EXAMPLE LABEL JSON"): each image gets its own LabelMe v5.11 `.json` in real pixels, annotation data only (no embedded `imageData`). `annotations.json` remains as the app's internal whole-project state (dots/tags/status are not representable in LabelMe). |
| Magic Touch is a real ML model, not the deterministic "smart model" | Owner requirement: Edge Gallery Scrapbook semantics (MediaPipe InteractiveSegmenter). The deterministic mesh.ts sculpt remains for demo scenes and as the dot-influence fallback. |
| Density range 3–200, adaptive vertex placement, auto/manual | Owner requirement; the prototype spec'd 6–40 uniform arc-length points. Auto picks the optimal minimal count; a manual pick persists (per image + as the default for future images). |
| No location step in New Project, no image picking | Owner requirement: the folder is the storage folder + slug; projects start empty and images arrive via picker/folder drop. |
| Permissions screen shows exactly two grants | Owner requirement: no "All files" card — SAF tree access is the scoped permission. |
| Two-step project deletion (optional whole-folder delete) | Owner requirement: the first confirm removes Loom data only; a second confirm warns that the ENTIRE folder (images + annotations) will be deleted and really deletes it. | for a real dataset tool (the spec defers the photo picker; the folder scanner alone would leave imported photos with no entry point). Uses the system photo picker; files are copied into the project folder. |
| Settings → About repository string | Shows the real repository (`testplay-byte/TESTMESH`) instead of the prototype's demo repo name. |
| Splash is the logo only, fully animated | Owner feedback: no bar/tagline; the animation lives on the mark (PathMeasure trim + weaving arcs + spring dots + settle scale). |
| YOLO export = segmentation format | Meshes are polygons; `labels/*.txt` carry `classIndex x1 y1 …` and the export README documents the choice (per docs/05 §5). |

## Build & release

- CI: `.github/workflows/build-loom.yml` — unit tests → `assembleDebug` →
  `assembleRelease` → artifacts `LOOM-debug` / `LOOM-release`.
- Both variants sign with the committed project keystore (`app/loom.keystore`)
  so every CI APK installs as an update over the previous one.
- Cutting a release: bump `versionCode`/`versionName` in
  `app/build.gradle.kts`, commit to `main`, then tag `loom-vX.Y` and push —
  CI publishes a GitHub Release with `LOOM.apk`.
- **Never build locally** (project owner directive; low-powered device).
  All verification happens on GitHub Actions; local runs of Gradle are not
  performed at all.

## Model / deps notes

- minSdk 26, targetSdk 35, compileSdk 36, arm64-v8a only.
- AGP 9.0.1 with built-in Kotlin + the Compose compiler plugin
  (`org.jetbrains.kotlin.plugin.compose`) — same toolchain as the sibling
  apps; verified against their CI-proven Gradle wrapper (9.2.1).
- Dependencies: Compose (BOM 2024.12.01), Navigation Compose, Lifecycle,
  DataStore, DocumentFile, **MediaPipe tasks-vision 1.1.0** (Magic Touch;
  the 30.5 MB model ships as a noCompress asset so it can be mmap'd). No
  Room, no Coil (hand-rolled, workspace-aware bitmap loading), no
  kotlinx.serialization (org.json, which the JVM tests stub with the
  standard artifact).
- Fonts: Space Grotesk + JetBrains Mono as variable TTFs in `res/font`,
  weights declared via `FontVariation`.
