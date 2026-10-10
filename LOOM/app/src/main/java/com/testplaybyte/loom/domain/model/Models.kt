package com.testplaybyte.loom.domain.model

/**
 * Loom domain model (docs/05-data-model.md §1).
 *
 * All annotation geometry lives in WORLD SPACE — the image's own
 * 800×600 coordinate system (docs/05 header). Stored data therefore never
 * depends on device size, zoom level, or the decoded bitmap's resolution;
 * exports scale world → real image pixels when the image is a real photo.
 */

/** A point in world space (800×600 units). */
data class Pt(val x: Float, val y: Float)

/** A prompt point for the deterministic "smart model". */
data class Dot(val id: String, val kind: DotKind, val x: Float, val y: Float) {
    val isPos: Boolean get() = kind == DotKind.POS
}

enum class DotKind { POS, NEG }

/** One freehand brush stroke (exclusion mask), world space. */
data class Stroke(val id: String, val pts: List<Pt>)

enum class ImageStatus(val wire: String) {
    UNLABELED("unlabeled"), DRAFT("draft"), DONE("done");

    companion object {
        fun fromWire(s: String?): ImageStatus = entries.firstOrNull { it.wire == s } ?: UNLABELED
    }
}

/**
 * Per-image annotation state — the unit of undo/redo AND persistence
 * (docs/05 §1 `ImageState`).
 */
data class ImageState(
    val dots: List<Dot> = emptyList(),
    /** Closed polygon; null until the model has sculpted a mesh. */
    val mesh: List<Pt>? = null,
    val strokes: List<Stroke> = emptyList(),
    /** Target vertex count (6–40; canvas default from settings). */
    val density: Int = 16,
    /** Class label of the mesh's object (null until named). */
    val labelId: String? = null,
    /** Quality flags (max 8). */
    val tags: List<String> = emptyList(),
    val status: ImageStatus = ImageStatus.UNLABELED,
) {
    companion object {
        fun empty(density: Int): ImageState = ImageState(density = density)
    }
}

/**
 * One image in a project. [file] is the file name inside the project
 * folder; demo scenes also carry [sceneId] so their vector art can be
 * re-rendered even if the exported PNG is missing. [width]/[height] are
 * the REAL image pixel dimensions (800×600 for demo scenes) — exports
 * scale world coordinates by width/800, height/600.
 */
data class ProjectImage(
    val id: String,
    val file: String,
    val sceneId: String? = null,
    val width: Int = WORLD_W,
    val height: Int = WORLD_H,
) {
    companion object {
        const val WORLD_W = 800
        const val WORLD_H = 600
    }
}

/** Categorical class label; [color] is a token name "c1".."c8". */
data class Label(val id: String, val name: String, val color: String)

/** Export/annotation formats (docs/05 §5). */
enum class ExportFormat(
    val wire: String,
    val displayName: String,
    val ext: String,
    val note: String,
) {
    LOOM_JSON("loom-json", "Loom JSON", "annotations.json", "Points, mesh + masks in one file"),
    COCO("coco", "COCO", "instances.json", "Polygon segmentation, dataset-wide"),
    YOLO("yolo", "YOLO", "labels/*.txt", "One text file per image"),
    VOC("voc", "Pascal VOC", "xml/*.xml", "One XML per image");

    companion object {
        fun fromWire(s: String?): ExportFormat =
            entries.firstOrNull { it.wire == s } ?: LOOM_JSON
    }
}

/** A dataset project — a folder on device + images + labels. */
data class Project(
    val id: String,
    /** Folder name (slug) inside the workspace. */
    val slug: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val images: List<ProjectImage> = emptyList(),
    val labels: List<Label> = emptyList(),
    val format: ExportFormat = ExportFormat.LOOM_JSON,
    /** Export options (docs/03 §7). */
    val includeMasks: Boolean = true,
    val splitPct: Int = 20,
    /**
     * Per-image annotation states, keyed by [ProjectImage.id]. Loaded from
     * `annotations.json` (matched by file name) + `loom-project.json`.
     */
    val states: Map<String, ImageState> = emptyMap(),
) {
    fun stateOf(imageId: String): ImageState = states[imageId] ?: ImageState.empty(16)

    val doneCount: Int get() = images.count { stateOf(it.id).status == ImageStatus.DONE }
    val draftCount: Int get() = images.count { stateOf(it.id).status == ImageStatus.DRAFT }
    val annotatedPct: Float
        get() = if (images.isEmpty()) 0f else doneCount * 100f / images.size
}

/** Canvas defaults + annotation behavior (docs/05 §2 `LoomSettings`). */
data class LoomSettings(
    /** Prompt-dot radius, world units: S=8, M=10, L=14. */
    val dotSize: Int = 10,
    /** Default mesh density for new images/projects (8–36). */
    val defaultDensity: Int = 16,
    /** Loupe while dragging vertices. */
    val magnifier: Boolean = true,
    /** Haptic feedback on commit actions. */
    val haptics: Boolean = true,
    /** Persist after every gesture. */
    val autosave: Boolean = true,
    /** Undo depth (10–100 step 10). */
    val undoDepth: Int = 50,
    /** Default export format for NEW projects. */
    val defaultFormat: ExportFormat = ExportFormat.LOOM_JSON,
    /** Theme (docs/03 §8). */
    val theme: ThemeMode = ThemeMode.DARK,
) {
    companion object {
        val DEFAULT = LoomSettings()
        val DOT_SIZES = listOf(8, 10, 14)
    }
}

enum class ThemeMode(val wire: String) {
    DARK("dark"), LIGHT("light");

    companion object {
        fun fromWire(s: String?): ThemeMode = entries.firstOrNull { it.wire == s } ?: DARK
    }
}

/**
 * Storage grants (docs/05 §2 `Perms`). On Android:
 *  - [media]  real READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE grant;
 *  - [files]  granted together with the storage folder (SAF tree);
 *  - [folder] a SAF workspace folder is picked AND persisted.
 */
data class Perms(
    val media: Boolean = false,
    val files: Boolean = false,
    val folder: Boolean = false,
    /** Display name of the workspace folder, e.g. "LoomDatasets". */
    val folderName: String? = null,
    /** True once the user chose "continue with limited access". */
    val skipped: Boolean = false,
) {
    val allGranted: Boolean get() = media && files && folder
    /** Gate rule (docs/04 §1): any single grant OR skip unlocks the app. */
    val unlocked: Boolean get() = media || files || folder || skipped

    companion object {
        val DEFAULT = Perms()
    }
}

// ── validation rules (docs/05 §6 — reproduce exactly) ──────────────────────

object LoomRules {
    const val PROJECT_NAME_MAX = 32
    const val LABEL_MAX = 8
    const val LABEL_NAME_MAX = 18
    const val TAG_MAX = 8
    const val TAG_NAME_MAX = 18
    const val SLUG_MAX = 24
    const val DENSITY_MIN = 6
    const val DENSITY_MAX = 40
    const val DEFAULT_DENSITY_MIN = 8
    const val DEFAULT_DENSITY_MAX = 36
    const val UNDO_MIN = 10
    const val UNDO_MAX = 100
    const val UNDO_STEP = 10
    const val SPLIT_MAX = 40
    const val DOT_SIZES_MIN = 8
    const val DOT_SIZES_MAX = 14

    /** Project name: trim, max 32; empty → "Untitled dataset". */
    fun cleanProjectName(raw: String): String =
        raw.trim().take(PROJECT_NAME_MAX).ifEmpty { "Untitled dataset" }

    /**
     * Folder slug: lowercase, `[^a-z0-9]+ → "-"`, trim "-", max 24,
     * fallback "dataset" (docs/05 §6 — exact rule).
     */
    fun slugify(raw: String): String {
        val s = raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(SLUG_MAX)
        return s.ifEmpty { "dataset" }
    }

    /** Label names: trim, max 18. */
    fun cleanLabelName(raw: String): String = raw.trim().take(LABEL_NAME_MAX)

    /** Tag names: trim, max 18. */
    fun cleanTagName(raw: String): String = raw.trim().take(TAG_NAME_MAX)

    /** Case-insensitive duplicate check for labels/tags. */
    fun isDuplicate(existing: List<String>, candidate: String): Boolean =
        existing.any { it.equals(candidate, ignoreCase = true) }

    /** Density clamp for the canvas (6–40). */
    fun clampDensity(v: Int): Int = v.coerceIn(DENSITY_MIN, DENSITY_MAX)

    /** Density clamp for the settings default (8–36). */
    fun clampDefaultDensity(v: Int): Int = v.coerceIn(DEFAULT_DENSITY_MIN, DEFAULT_DENSITY_MAX)

    /** Undo depth clamp (10–100 step 10). */
    fun clampUndoDepth(v: Int): Int =
        (v.coerceIn(UNDO_MIN, UNDO_MAX) / UNDO_STEP) * UNDO_STEP

    /** Train/val split percent clamp (0–40). */
    fun clampSplit(v: Int): Int = v.coerceIn(0, SPLIT_MAX)

    /** World-space clamp for dots/vertices (0..800 / 0..600). */
    fun clampWorldX(x: Float): Float = x.coerceIn(0f, ProjectImage.WORLD_W.toFloat())
    fun clampWorldY(y: Float): Float = y.coerceIn(0f, ProjectImage.WORLD_H.toFloat())

    /**
     * Train/val split assignment — deterministic Bresenham-style spread:
     * with pct% of n images, the val picks distribute evenly by index.
     * Round-robin by index is explicitly allowed by docs/05 §5.
     */
    fun isValSplit(index: Int, pct: Int): Boolean {
        if (pct <= 0) return false
        return ((index + 1) * pct) / 100 > (index * pct) / 100
    }
}

/** Human prefixes for generated ids (docs/05 §3). */
object Ids {
    fun uid(prefix: String): String = "$prefix-${java.util.UUID.randomUUID()}"
}
