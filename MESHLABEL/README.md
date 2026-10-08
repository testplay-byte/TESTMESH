# MeshLabel — LabelMe-style annotation for Android

A LabelMe-like image-annotation app for building **segmentation datasets**
on a phone, with **MediaPipe "Magic Touch"** scribble segmentation. Sibling
app of AIMESHVISION in the same repository; built **only by GitHub Actions**
(no local APK builds — local `compileDebugKotlin`/`testDebugUnitTest` checks
are fine).

## Features

- **Library** — every project is a folder with the image and its JSON side
  by side, exactly LabelMe's convention:
  `filesDir/MeshLabel/<name>/<name>.jpg` + `<name>.json`.
  Import via the system photo picker (no storage permission needed).
- **Polygon editing (LabelMe parity)** — tap to place vertices, drag
  handles to move them, tap the first vertex to close; select/rename/delete
  shapes, `group_id` supported in the JSON; undo/redo (snapshot history).
- **Magic Touch** — MediaPipe `InteractiveSegmenter` (bundled
  `interactive_segmentation.task`, int8, magic_touch v2): draw scribbles
  with **Add to selection** / **Remove from selection** polarity; the model
  returns a full-image confidence mask (threshold 0.5) shown as a live
  overlay; **▶ Polygon** converts it to an editable polygon
  (marching squares → loop chaining → Ramer–Douglas–Peucker, traced on a
  ≤1024px downscale then scaled back to original pixels).
- **Save** — exact LabelMe JSON v7 schema:
  `version, flags, shapes[], imagePath, imageData(base64|null),
  imageHeight, imageWidth`; per shape
  `label, points[[x,y]…], group_id, shape_type, flags, description`;
  coordinates are **absolute original-image pixels** (decode-downscaled
  bitmaps are rescaled back — annotation space never depends on memory
  limits).
- **Export YOLO-seg dataset** — `dataset/images/*`, `dataset/labels/*.txt`
  (`class_id x1 y1 …` normalized, 6 decimals, Labelme2YOLO rules),
  `classes.txt` + `dataset.yaml`.

## Architecture

```
app/src/main/java/com/example/meshlabel/
├── model/Shape.kt                 # Pt/Shape + point-in-polygon, shoelace
├── labelme/LabelMeJson.kt         # exact schema (de)serialization, org.json
├── seg/MaskToPolygon.kt           # PURE: marching squares + chaining + RDP
├── seg/InteractiveSegmenterManager.kt  # MediaPipe wrapper (single thread,
│                                       # setImage once, stale-id guard)
├── store/ProjectStore.kt          # side-by-side file IO, decode scaling
├── store/YoloSegExporter.kt       # dataset export (+ pure yoloLine)
├── ui/AnnotationView.kt           # fit-center canvas; ALL coords = orig px
├── LibraryActivity.kt             # list/import/export
└── EditorActivity.kt              # history, labels, segmentation flow
```

Design rules:

1. **Coordinate space is the original image, always.** The view converts
   orig↔bitmap↔screen at its mapping boundary; the mask→polygon and
   segmenter crossings convert explicitly in `EditorActivity`.
2. **Pure cores are unit-tested on the JVM** (`app/src/test`): JSON schema
   round-trip, polygon tracing (bounds/area/concave corner/downscale),
   YOLO line format — the same verification philosophy as AIMESHVISION's
   `MaskGeometryTest`. CI runs them before assembling.
3. **Threading**: only `InteractiveSegmenterManager`'s single executor
   touches MediaPipe; results come back on the main thread tagged with a
   request id that the editor drops if stale.
4. **No permissions**: photo picker + bundled model + app-private storage.

## Model

`app/src/main/assets/interactive_segmentation.task`
(MediaPipe interactive_segmenter_v2 / magic_touch int8, official
`storage.googleapis.com/mediapipe-models/.../interactive_segmentation.task`,
Apache-2.0 MediaPipe model terms).

## Build

CI: `.github/workflows/build-meshlabel.yml` (repo root) — assembles debug
+ release (release is **unsigned**: no project keystore for this app; the
debug APK is the daily-driver artifact) and uploads `MESHLABEL-debug` /
`MESHLABEL-release`.

Local verification (no APK): `./gradlew :app:testDebugUnitTest`.

## Known limitations / deliberate choices

- Portrait-locked activities (annotation state survives rotation trivially).
- One polygon per Magic Touch selection (largest loop; holes/satellites
  dropped) — LabelMe polygons are single outlines anyway.
- `rectangle/line/...` shapes load and round-trip, but only `polygon`
  shapes are exported to YOLO-seg (a segmentation trainer consumes
  polygons).
- Undo is snapshot-based (deep copies, cap 50) — simple and auditable,
  fine for annotation-sized lists.
