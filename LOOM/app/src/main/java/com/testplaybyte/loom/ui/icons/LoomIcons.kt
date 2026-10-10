package com.testplaybyte.loom.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Loom icon set — port of the prototype's `components/icons.tsx`.
 *
 * Style contract (docs/02 §5): 24dp grid, stroke ~1.8dp (per-icon values
 * from the source), round caps/joins, fill none except tiny node dots,
 * tinted by the content color. Every icon is an [ImageVector] painted in
 * black and tinted at the call site (`Icon(tint = …)`), which is Compose's
 * equivalent of `currentColor`.
 *
 * Fidelity notes:
 *  · per-usage stroke widths in the prototype (e.g. plus at 2.2 in the
 *    FAB, check at 2.4 in switch thumbs) are baked at the width the icon
 *    predominantly uses; the 0.2–0.4dp differences at 15–24dp are
 *    sub-pixel at render time;
 *  · [iconLoom] is a factory (splash uses a thinner 1.4 brand mark).
 */

private fun loomIcon(
    name: String,
    block: ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply(block).build()

/** Stroked path — `fill: none; stroke: currentColor` in the source. */
private fun ImageVector.Builder.stroke(
    pathData: String,
    width: Float = 1.8f,
    alpha: Float = 1f,
) {
    addPath(
        pathData = addPathNodes(pathData),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        strokeAlpha = alpha,
        fill = null,
    )
}

/**
 * Dashed circle — the ± dot tool rings. ImageVector paths carry no dash
 * pattern, so the dashes are emitted as explicit arc segments: 6 dashes at
 * 52% duty on r 6.5 reproduces the source's `stroke-dasharray 3.4 3.1`
 * (dash ≈ 3.5, gap ≈ 3.3 world units) at icon scale.
 */
private fun ImageVector.Builder.dashedCircle(
    cx: Float,
    cy: Float,
    r: Float,
    width: Float = 1.8f,
    dashes: Int = 6,
    dashFraction: Float = 0.52f,
) {
    val step = 360f / dashes
    val f = java.util.Locale.US
    for (i in 0 until dashes) {
        val a0 = Math.toRadians((i * step).toDouble())
        val a1 = Math.toRadians((i * step + step * dashFraction).toDouble())
        val x0 = cx + r * kotlin.math.cos(a0)
        val y0 = cy + r * kotlin.math.sin(a0)
        val x1 = cx + r * kotlin.math.cos(a1)
        val y1 = cy + r * kotlin.math.sin(a1)
        stroke(
            "M " + String.format(f, "%.3f", x0) + " " + String.format(f, "%.3f", y0) +
                " A " + String.format(f, "%.3f", r) + " " + String.format(f, "%.3f", r) + " 0 0 1 " +
                String.format(f, "%.3f", x1) + " " + String.format(f, "%.3f", y1),
            width = width,
        )
    }
}

/** Filled path — `fill: currentColor; stroke: none` in the source. */
private fun ImageVector.Builder.fill(pathData: String, alpha: Float = 1f) {
    addPath(
        pathData = addPathNodes(pathData),
        fill = SolidColor(Color.Black),
        fillAlpha = alpha,
    )
}

// ── simple glyphs ─────────────────────────────────────────────────────────

val IconPlus: ImageVector by lazy {
    loomIcon("Plus") { stroke("M12 5v14M5 12h14", width = 2f) }
}

val IconMinus: ImageVector by lazy {
    loomIcon("Minus") { stroke("M5 12h14", width = 2f) }
}

val IconCheck: ImageVector by lazy {
    loomIcon("Check") { stroke("M4.5 12.5l5 5 10-11", width = 2.4f) }
}

val IconClose: ImageVector by lazy {
    loomIcon("Close") { stroke("M6 6l12 12M18 6L6 18", width = 2.2f) }
}

val IconChevronLeft: ImageVector by lazy {
    loomIcon("ChevronLeft") { stroke("M14.5 5.5L8 12l6.5 6.5", width = 2f) }
}

val IconChevronRight: ImageVector by lazy {
    loomIcon("ChevronRight") { stroke("M9.5 5.5L16 12l-6.5 6.5") }
}

val IconChevronDown: ImageVector by lazy {
    loomIcon("ChevronDown") { stroke("M5.5 9.5L12 16l6.5-6.5", width = 2f) }
}

// ── brand ─────────────────────────────────────────────────────────────────

/**
 * Raw android.graphics.Path forms of the brand mark's stroked parts, used
 * by the splash's stroke draw-on (PathMeasure trim needs a mutable Path,
 * which ImageVector cannot provide).
 */
fun loomVesicaPath(): android.graphics.Path =
    androidx.core.graphics.PathParser.createPathFromPathData(
        "M12 3.6c4.6 2.5 7 5.4 7 8.4s-2.4 5.9-7 8.4c-4.6-2.5-7-5.4-7-8.4s2.4-5.9 7-8.4Z",
    ) ?: android.graphics.Path()

/** The two inner crossing arcs of the brand mark (splash draw-on). */
fun loomArcPath(): android.graphics.Path =
    androidx.core.graphics.PathParser.createPathFromPathData(
        "M5.2 9.4c4.4 1.9 9.2 1.9 13.6 0M5.2 14.6c4.4-1.9 9.2-1.9 13.6 0",
    ) ?: android.graphics.Path()


/** The woven-knot brand mark; splash draws it at strokeWidth 1.4. */
fun iconLoom(strokeWidth: Float = 1.8f): ImageVector = loomIcon("Loom") {
    stroke(
        "M12 3.6c4.6 2.5 7 5.4 7 8.4s-2.4 5.9-7 8.4c-4.6-2.5-7-5.4-7-8.4s2.4-5.9 7-8.4Z",
        width = strokeWidth,
    )
    stroke("M5.2 9.4c4.4 1.9 9.2 1.9 13.6 0M5.2 14.6c4.4-1.9 9.2-1.9 13.6 0", width = strokeWidth, alpha = 0.75f)
    fill("M12 2.1a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0 -3z")
    fill("M19 10.5a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0 -3z")
    fill("M12 18.9a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0 -3z")
    fill("M5 10.5a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0 -3z")
}

val IconLoom: ImageVector by lazy { iconLoom() }

// ── the wider set (ported 1:1 from icons.tsx, source order) ───────────────

val IconUndo: ImageVector by lazy {
    loomIcon("Undo") {
        stroke("M8 5L3.5 9.5 8 14")
        stroke("M3.5 9.5h11a6 6 0 0 1 6 6v0a6 6 0 0 1-6 6h-3")
    }
}

val IconRedo: ImageVector by lazy {
    loomIcon("Redo") {
        stroke("M16 5l4.5 4.5L16 14")
        stroke("M20.5 9.5h-11a6 6 0 0 0-6 6v0a6 6 0 0 0 6 6h3")
    }
}

val IconFolder: ImageVector by lazy {
    loomIcon("Folder") {
        stroke("M3.5 7.5v10a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-8a2 2 0 0 0-2-2h-7l-2-2.5H5.5a2 2 0 0 0-2 2.5Z")
    }
}

val IconFolderPlus: ImageVector by lazy {
    loomIcon("FolderPlus") {
        stroke("M3.5 7.5v10a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-8a2 2 0 0 0-2-2h-7l-2-2.5H5.5a2 2 0 0 0-2 2.5Z")
        stroke("M12 11v5M9.5 13.5h5")
    }
}

val IconFolderOpen: ImageVector by lazy {
    loomIcon("FolderOpen") {
        stroke("M3.5 8.5v9a2 2 0 0 0 2 2h12.2a2 2 0 0 0 1.9-1.4l1.7-5.2a1.4 1.4 0 0 0-1.3-1.9H8.2a2 2 0 0 0-1.9 1.4L4.6 17")
        stroke("M3.5 8.5v-1a2 2 0 0 1 2-2h4l2 2.5h5a2 2 0 0 1 2 2v1")
    }
}

val IconImage: ImageVector by lazy {
    loomIcon("Image") {
        // <rect x=3.5 y=4.5 width=17 height=15 rx=3>
        stroke("M6.5 4.5H17.5A3 3 0 0 1 20.5 7.5V16.5A3 3 0 0 1 17.5 19.5H6.5A3 3 0 0 1 3.5 16.5V7.5A3 3 0 0 1 6.5 4.5Z")
        stroke("M9 8.1a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0 -3.8z")
        stroke("M3.5 17l4.8-4.6a2 2 0 0 1 2.7-.05L20.5 20")
    }
}

val IconImages: ImageVector by lazy {
    loomIcon("Images") {
        // <rect x=6.5 y=3.5 width=14 height=12 rx=2.5>
        stroke("M9 3.5H18A2.5 2.5 0 0 1 20.5 6V13A2.5 2.5 0 0 1 18 15.5H9A2.5 2.5 0 0 1 6.5 13V6A2.5 2.5 0 0 1 9 3.5Z")
        stroke("M3.5 7.5v9a2.5 2.5 0 0 0 2.5 2.5h11")
        stroke("M11 6.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0 -3.2z")
        stroke("M6.5 13.5l3.4-3a1.8 1.8 0 0 1 2.4 0l4.9 4.4")
    }
}

val IconGrid: ImageVector by lazy {
    loomIcon("Grid") {
        // four <rect width=7 height=7 rx=2> at (4,4) (13,4) (4,13) (13,13)
        stroke("M6 4H9A2 2 0 0 1 11 6V9A2 2 0 0 1 9 11H6A2 2 0 0 1 4 9V6A2 2 0 0 1 6 4Z")
        stroke("M15 4H18A2 2 0 0 1 20 6V9A2 2 0 0 1 18 11H15A2 2 0 0 1 13 9V6A2 2 0 0 1 15 4Z")
        stroke("M6 13H9A2 2 0 0 1 11 15V18A2 2 0 0 1 9 20H6A2 2 0 0 1 4 18V15A2 2 0 0 1 6 13Z")
        stroke("M15 13H18A2 2 0 0 1 20 15V18A2 2 0 0 1 18 20H15A2 2 0 0 1 13 18V15A2 2 0 0 1 15 13Z")
    }
}

val IconSettings: ImageVector by lazy {
    loomIcon("Settings") {
        stroke("M12 8.8a3.2 3.2 0 1 1 0 6.4a3.2 3.2 0 1 1 0 -6.4z")
        stroke("M12 3.2l1.2 2.4 2.6.5 2-1.7 1.8 1.8-1.7 2 .5 2.6 2.4 1.2-2.4 1.2-.5 2.6 1.7 2-1.8 1.8-2-1.7-2.6.5L12 20.8l-1.2-2.4-2.6-.5-2 1.7-1.8-1.8 1.7-2-.5-2.6L3.2 12l2.4-1.2.5-2.6-1.7-2L6.2 4.4l2 1.7 2.6-.5Z")
    }
}

val IconExport: ImageVector by lazy {
    loomIcon("Export") {
        stroke("M12 15V4.5M8 8l4-3.5L16 8")
        stroke("M5 13v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5")
    }
}

val IconShare: ImageVector by lazy {
    loomIcon("Share") {
        stroke("M6 9.6a2.4 2.4 0 1 1 0 4.8a2.4 2.4 0 1 1 0 -4.8z")
        stroke("M17.5 3.1a2.4 2.4 0 1 1 0 4.8a2.4 2.4 0 1 1 0 -4.8z")
        stroke("M17.5 16.1a2.4 2.4 0 1 1 0 4.8a2.4 2.4 0 1 1 0 -4.8z")
        stroke("M8.2 10.9l7-4.2M8.2 13.1l7 4.2")
    }
}

val IconPan: ImageVector by lazy {
    loomIcon("Pan") {
        stroke("M9.5 11.5V6.2a1.4 1.4 0 0 1 2.8 0v4.6")
        stroke("M12.3 10.6a1.4 1.4 0 0 1 2.8 0v1.2")
        stroke("M15.1 11.4a1.4 1.4 0 0 1 2.8 0v3.4a5.4 5.4 0 0 1-5.4 5.4h-1.1a5 5 0 0 1-4.3-2.4l-2.6-4.3a1.35 1.35 0 0 1 2.2-1.55l1.3 1.75")
    }
}

/** Matched pair: same dashed ring, ± glyph inside. */
val IconDotPlus: ImageVector by lazy {
    loomIcon("DotPlus") {
        dashedCircle(12f, 12f, 6.5f)
        stroke("M9.4 12h5.2M12 9.4v5.2")
    }
}

val IconDotMinus: ImageVector by lazy {
    loomIcon("DotMinus") {
        dashedCircle(12f, 12f, 6.5f)
        stroke("M9.4 12h5.2")
    }
}

val IconBrush: ImageVector by lazy {
    loomIcon("Brush") {
        stroke("M19.5 4.5c-3.6 1.2-7.3 4.4-9.4 7.5l2 2c3.1-2.1 6.3-5.8 7.4-9.5Z")
        stroke("M10 12.4c-1.9.3-3 1.5-3.4 3.2-.3 1.4-.7 2.3-2.1 3 1.7 1.1 4.4 1.2 5.8-.4 1-1.1 1.1-2.6.4-3.9")
    }
}

val IconEraser: ImageVector by lazy {
    loomIcon("Eraser") {
        stroke("M8.5 19.5h11")
        stroke("M13.2 4.9l6 6a1.8 1.8 0 0 1 0 2.6l-6.3 6.3a2 2 0 0 1-2.8 0l-5.5-5.5a2 2 0 0 1 0-2.8l6-6a1.8 1.8 0 0 1 2.6 0Z")
        stroke("M9 8.5l6.5 6.5")
    }
}

val IconNodes: ImageVector by lazy {
    loomIcon("Nodes") {
        stroke("M6.6 7.2L4.4 16.8l7.6 2.7 7.6-2.7-2.2-9.6-5.4-2.3Z")
        fill("M12 3a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0 -3.8z")
        fill("M4.4 14.9a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0 -3.8z")
        fill("M19.6 14.9a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0 -3.8z")
        fill("M12 17.6a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0 -3.8z")
    }
}

val IconMesh: ImageVector by lazy {
    loomIcon("Mesh") {
        stroke("M12 3.5l8 5.5-3 9.5H7L4 9Z")
        stroke("M12 3.5v16M4 9l16 0M7 18.5L20 9M17 18.5L4 9", alpha = 0.55f)
    }
}

val IconSparkles: ImageVector by lazy {
    loomIcon("Sparkles") {
        stroke("M12 4l1.7 4.6L18 10l-4.3 1.4L12 16l-1.7-4.6L6 10l4.3-1.4Z")
        stroke("M18.5 15.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8Z")
        stroke("M5 4l.6 1.5L7 6l-1.4.5L5 8l-.6-1.5L3 6l1.4-.5Z")
    }
}

val IconZoomIn: ImageVector by lazy {
    loomIcon("ZoomIn") {
        stroke("M11 4.5a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0 -13z")
        stroke("M15.8 15.8L20.5 20.5M8.5 11h5M11 8.5v5")
    }
}

val IconZoomOut: ImageVector by lazy {
    loomIcon("ZoomOut") {
        stroke("M11 4.5a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0 -13z")
        stroke("M15.8 15.8L20.5 20.5M8.5 11h5")
    }
}

val IconFit: ImageVector by lazy {
    loomIcon("Fit") {
        stroke("M4 9V6a2 2 0 0 1 2-2h3M15 4h3a2 2 0 0 1 2 2v3M20 15v3a2 2 0 0 1-2 2h-3M9 20H6a2 2 0 0 1-2-2v-3")
    }
}

val IconTrash: ImageVector by lazy {
    loomIcon("Trash") {
        stroke("M4.5 6.5h15M9.5 6V4.8A1.3 1.3 0 0 1 10.8 3.5h2.4a1.3 1.3 0 0 1 1.3 1.3V6.5")
        stroke("M6.5 6.5l.8 12a2 2 0 0 0 2 1.9h5.4a2 2 0 0 0 2-1.9l.8-12")
        stroke("M10 10.5v6M14 10.5v6")
    }
}

val IconInfo: ImageVector by lazy {
    loomIcon("Info") {
        stroke("M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0 -17z")
        stroke("M12 11v5")
        // source: r0.4 filled dot
        fill("M12 7.6a0.4 0.4 0 1 1 0 0.8a0.4 0.4 0 1 1 0 -0.8z")
    }
}

val IconLock: ImageVector by lazy {
    loomIcon("Lock") {
        // <rect x=5.5 y=10.5 width=13 height=9 rx=2.5>
        stroke("M8 10.5H16A2.5 2.5 0 0 1 18.5 13V17A2.5 2.5 0 0 1 16 19.5H8A2.5 2.5 0 0 1 5.5 17V13A2.5 2.5 0 0 1 8 10.5Z")
        stroke("M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5")
        fill("M12 13.8a1.2 1.2 0 1 1 0 2.4a1.2 1.2 0 1 1 0 -2.4z")
    }
}

val IconShield: ImageVector by lazy {
    loomIcon("Shield") {
        stroke("M12 3.5l7 2.6v5.2c0 4.6-3 8-7 9.2-4-1.2-7-4.6-7-9.2V6.1Z")
        stroke("M9 12l2.2 2.2L15.5 9.7")
    }
}

val IconPhotos: ImageVector by lazy {
    loomIcon("Photos") {
        // <rect x=3.5 y=6.5 width=13 height=12 rx=2.5>
        stroke("M6 6.5H14A2.5 2.5 0 0 1 16.5 9V16A2.5 2.5 0 0 1 14 18.5H6A2.5 2.5 0 0 1 3.5 16V9A2.5 2.5 0 0 1 6 6.5Z")
        stroke("M7 6.5V6a2 2 0 0 1 2-2h9.5A2 2 0 0 1 20.5 6v8.5a2 2 0 0 1-2 2H18")
        stroke("M8 9.5a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0 -3z")
        stroke("M3.5 16l3.4-3.2a1.8 1.8 0 0 1 2.4 0l4.7 4.4")
    }
}

val IconSun: ImageVector by lazy {
    loomIcon("Sun") {
        stroke("M12 8a4 4 0 1 1 0 8a4 4 0 1 1 0 -8z")
        stroke("M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M18.4 5.6L17 7M7 17l-1.4 1.4")
    }
}

val IconMoon: ImageVector by lazy {
    loomIcon("Moon") {
        stroke("M20 13.5A8 8 0 0 1 10.5 4a8 8 0 1 0 9.5 9.5Z")
    }
}

val IconHelp: ImageVector by lazy {
    loomIcon("Help") {
        stroke("M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0 -17z")
        stroke("M9.6 9.2a2.5 2.5 0 1 1 3.4 3c-.7.5-1 .9-1 1.9")
        // source: r0.4 filled dot
        fill("M12 16.4a0.4 0.4 0 1 1 0 0.8a0.4 0.4 0 1 1 0 -0.8z")
    }
}

val IconReset: ImageVector by lazy {
    loomIcon("Reset") {
        stroke("M4.5 5v5h5")
        stroke("M4.8 10A8 8 0 1 1 4 14")
    }
}

val IconSave: ImageVector by lazy {
    loomIcon("Save") {
        stroke("M5 5.5A1.5 1.5 0 0 1 6.5 4h9L20 8.5v10a1.5 1.5 0 0 1-1.5 1.5h-12A1.5 1.5 0 0 1 5 18.5Z")
        stroke("M8 4v5h7V4M8 20v-6h8v6")
    }
}

val IconArrowRight: ImageVector by lazy {
    loomIcon("ArrowRight") {
        stroke("M4.5 12h15M13.5 6l6 6-6 6")
    }
}

val IconMore: ImageVector by lazy {
    loomIcon("More") {
        // source: three r0.6 filled dots
        fill("M12 4.8a0.6 0.6 0 1 1 0 1.2a0.6 0.6 0 1 1 0 -1.2z")
        fill("M12 11.4a0.6 0.6 0 1 1 0 1.2a0.6 0.6 0 1 1 0 -1.2z")
        fill("M12 18a0.6 0.6 0 1 1 0 1.2a0.6 0.6 0 1 1 0 -1.2z")
    }
}

val IconDatabase: ImageVector by lazy {
    loomIcon("Database") {
        // <ellipse cx=12 cy=6 rx=7.5 ry=2.8>
        stroke("M4.5 6a7.5 2.8 0 1 0 15 0a7.5 2.8 0 1 0 -15 0z")
        stroke("M4.5 6v12c0 1.6 3.4 2.8 7.5 2.8s7.5-1.2 7.5-2.8V6")
        stroke("M4.5 12c0 1.6 3.4 2.8 7.5 2.8s7.5-1.2 7.5-2.8")
    }
}

val IconAlert: ImageVector by lazy {
    loomIcon("Alert") {
        stroke("M12 4L2.8 19.5h18.4Z")
        stroke("M12 10v4.2")
        // source: r0.4 filled dot
        fill("M12 16.4a0.4 0.4 0 1 1 0 0.8a0.4 0.4 0 1 1 0 -0.8z")
    }
}

val IconTag: ImageVector by lazy {
    loomIcon("Tag") {
        stroke("M12.6 3H5.4A2.4 2.4 0 0 0 3 5.4v7.2a2 2 0 0 0 .59 1.42l7.09 7.09a2.3 2.3 0 0 0 3.25 0l6.28-6.28a2.3 2.3 0 0 0 0-3.25l-7.09-7.09A2 2 0 0 0 12.6 3Z")
        fill("M7.9 6.7a1.2 1.2 0 1 1 0 2.4a1.2 1.2 0 1 1 0 -2.4z")
    }
}

val IconFile: ImageVector by lazy {
    loomIcon("File") {
        stroke("M6 3.5h8L19 8.5v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2Z")
        stroke("M13.5 3.5V9H19")
    }
}
