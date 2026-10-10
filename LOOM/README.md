# Loom — image-annotation workbench for Android

Loom is a LabelMe-style **image-annotation workbench for building
segmentation training datasets on Android**. You create a *project* (a
folder on device + images + label classes), annotate each image by planting
**prompt dots** (+/−) around an object — a polygon **mesh** sculpts itself
around the object through a deterministic local algorithm — then **export**
the dataset in a standard ML format (Loom JSON / COCO / YOLO / Pascal VOC).

Built from the full "Loom" design handoff package (warm graphite + signal
amber, Space Grotesk + JetBrains Mono; dark theme default, light switchable).
Sibling app of AIMESHVISION in the same repository; built **only by GitHub
Actions** (`build-loom.yml` — no local Gradle runs at all, per project
policy: local device is low-powered).

## Screens (all 8, in flow order)

| # | Screen | Notes |
|---|--------|-------|
| 1 | Splash | Stroke draw-on brand mark, auto-advance 2250ms, tap to skip |
| 2 | Permissions | REAL grants: media permission + SAF folder picker; limited-access bypass |
| 3 | Projects | Project cards w/ stepped-blob progress panel, thumb stacks, FAB, bottom nav |
| 4 | New project | Name → Location → Images (6 demo scenes) → Labels (max 8) |
| 5 | Project detail | Stats card, labels row, masonry grid, overflow sheet |
| 6 | Annotate | THE canvas: prompt dots → auto-mesh, vertex editing + loupe, brush, eraser |
| 7 | Export | 4 formats with REAL writes into the project folder, share, JSON preview |
| 8 | Settings | Theme, canvas defaults, undo depth, autosave, storage readout, clear data |

## The workspace folder IS the database

The user requirement (and the first-run flow) makes the workspace folder the
single source of truth. The app asks for a folder once (Permissions →
"Storage folder", real SAF tree picker, persisted) and then uses it for
**everything**:

```
<workspace>/                          ← user-picked (SAF) or app-private fallback
  object-scan-demo/                   ← one folder per project
    loom-project.json                 app metadata (ids, labels, image list, options)
    annotations.json                  Loom JSON — every dot/mesh/stroke/tag/status,
                                      kept up to date on every save (world coords)
    IMG_2041.jpg …                    the images (demo scenes render to real JPEGs;
                                      imported photos are copied in)
    labels/, xml/, README.txt         written by Export only
```

Consequences, by design:

- **Creating a project creates a real folder** in the workspace; demo
  images are rendered into it so the folder is a genuine dataset.
- **Images added by hand are detected**: every visit rescans each project
  folder (and the workspace root) for image files and gives them fresh,
  empty annotation states. Deleting a project removes only Loom's own data
  (metadata + annotations) — user images on disk are never touched, and the
  folder is remembered as dismissed so it doesn't auto-reappear.
- **Every JSON is written there** — folders are portable; drop
  `annotations.json` back in and the project re-opens (adopted datasets).
- The app works fully without any grant: with "Continue with limited
  access" the same layout lives in app-private storage
  (`filesDir/workspace/`), changeable later in Settings.

## The "smart model" (deterministic, not ML)

`domain/mesh/MeshMath.kt` is an exact port of the prototype's `lib/mesh.ts`
— constants included (`PULL_RADIUS 150, PULL_SIGMA 78, PULL_MAX 30,
PUSH_MAX 42`, Catmull-Rom ×14). No Suggest button: when dots change the
mesh re-sculpts itself after a **600 ms debounce** (deferred while a gesture
is live, suppressed across undo/redo/switch/clear/mesh-removal), with a
"sculpting…" status pill and a haptic pulse. Density (6–40 pts) live-remeshes
on the stepper and commits 450 ms after the last press.

## Canvas interaction contract (docs/04)

- Tools: pan · +dot · −dot · exclusion brush · mesh-vertex edit · eraser.
- Camera: fit-contain, pinch 50–500 %, double-tap 2×, pan with clamp; the
  drafting grid is world-fixed (pans and zooms with the image).
- Vertex edit: drag with **loupe** (112 px @ 3.2×, 72 px above the finger),
  tap-near-edge inserts, long-press (550 ms) deletes, nudge arrows move by
  8 world units; every completed gesture = exactly one undo unit.
- Autosave 300 ms debounce; **flush on image switch and canvas exit**.
- Image tags (max 8) ride the same history and export with the dataset.

## Architecture

```
app/src/main/java/com/testplaybyte/loom/
├── domain/
│   ├── model/    Models.kt (Pt/Dot/Stroke/ImageState/Project/settings/perms)
│   │             + LoomRules (validation, slugs, clamps, split rule)
│   ├── mesh/     MeshMath.kt            ← pure port of mesh.ts (tested)
│   ├── history/  UndoStack.kt           ← bounded undo/redo (tested)
│   └── export/   LoomJsonCodec.kt       ← the primary annotation format
│                 ProjectMetaCodec.kt    ← loom-project.json
│                 DatasetWriters.kt      ← COCO / YOLO / VOC (tested)
├── data/
│   ├── prefs/    LoomPreferences.kt     ← DataStore (settings/perms/URI)
│   ├── workspace/Workspace.kt           ← SAF tree ⭄ app-private fallback
│   ├── scene/    SceneDsl.kt            ← scene art as pure data (SVG-port)
│                 SceneLibrary.kt        ← the six demo scenes, 1:1
│                 SceneImage.kt          ← demo scenes → real JPEG files
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

1. **All annotation geometry lives in world space (800×600)** — stored data
   never depends on device size, zoom or photo resolution. Demo scenes are
   authored in that world; real photos are letterboxed into it and exports
   scale world → real pixels (see `LoomJsonCodec`'s header for the
   working-file vs export-file coordinate policy).
2. **Pure cores are JVM-tested** (50 tests): mesh math incl. the docs' worked
   example, undo/redo semantics, the Loom JSON codec, COCO/YOLO/VOC writers,
   validation rules, masonry hash, DataStore codecs. CI runs them before
   assembling.
3. **No invented screens, colors or copy.** Values come from
   `reference/tokens.json`; icons are verbatim path ports; the copy deck is
   verbatim (one intentional change: Settings → About shows the real
   repository name).

## Documented deviations from the handoff package

| Deviation | Why |
|---|---|
| Storage is folder-first, not Room | The owner's requirement: the picked workspace folder is used for everything and must detect hand-added images. `loom-project.json` + `annotations.json` are the database; DataStore holds settings/perms. |
| Persistence has no Room at all | With the folder as source of truth, a second DB would only risk divergence. |
| "Add images" action on Project detail | Necessary for a real dataset tool (the spec defers the photo picker; the folder scanner alone would leave imported photos with no entry point). Uses the system photo picker; files are copied into the project folder. |
| Settings → About repository string | Shows the real repository (`testplay-byte/TESTMESH`) instead of the prototype's demo repo name. |
| Splash mark fades/draws via PathMeasure trim | Compose `ImageVector` cannot trim paths; the mark's strokes draw on 0→1 (PathMeasure), arcs fade at 55 %, node dots pop at 75 %. |
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
- Dependencies are minimal: Compose (BOM 2024.12.01), Navigation Compose,
  Lifecycle, DataStore, DocumentFile. No Room, no Coil (hand-rolled,
  workspace-aware bitmap loading), no kotlinx.serialization (org.json, which
  the JVM tests stub with the standard artifact).
- Fonts: Space Grotesk + JetBrains Mono as variable TTFs in `res/font`,
  weights declared via `FontVariation`.
