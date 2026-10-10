package com.testplaybyte.loom.domain.export

import com.testplaybyte.loom.domain.mesh.MeshMath
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.Project
import com.testplaybyte.loom.domain.model.Pt
import com.testplaybyte.loom.domain.model.Stroke
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dataset writers for all four export formats (docs/05 §5).
 *
 * Every writer is PURE: it turns a [Project] into a list of
 * [ExportFile] (relative path + text content). The repository layer does
 * the actual file IO through the workspace (SAF or app-private), which
 * keeps the formats unit-tested on the JVM exactly like the prototype's
 * data model demands.
 *
 * Coordinate policy: world (800×600) → REAL image pixels via each image's
 * width/height. Consumers (COCO/VOC/YOLO) always see real pixels; Loom
 * JSON export is self-consistent the same way (see [LoomJsonCodec]).
 */
object DatasetWriters {

    data class ExportFile(val relPath: String, val content: String)

    /** Summary counts for the Export screen card (docs/03 §7). */
    data class Summary(val images: Int, val meshes: Int, val dots: Int, val strokes: Int)

    fun summary(project: Project): Summary {
        var meshes = 0
        var dots = 0
        var strokes = 0
        for (im in project.images) {
            val st = project.stateOf(im.id)
            if (st.mesh != null) meshes++
            dots += st.dots.size
            strokes += st.strokes.size
        }
        return Summary(project.images.size, meshes, dots, strokes)
    }

    /**
     * Builds every file for [project]'s selected format.
     * @param exportedAt epoch ms, stamped into the documents.
     */
    fun write(project: Project, exportedAt: Long): List<ExportFile> = when (project.format) {
        com.testplaybyte.loom.domain.model.ExportFormat.LOOM_JSON ->
            listOf(
                ExportFile(
                    "annotations.json",
                    LoomJsonCodec.encode(project, exportedAt, scaleToRealPixels = true, includeMasks = project.includeMasks),
                ),
                ExportFile("README.txt", loomReadme(project)),
            )

        com.testplaybyte.loom.domain.model.ExportFormat.COCO ->
            listOf(
                ExportFile("instances.json", coco(project)),
                ExportFile("README.txt", cocoReadme(project)),
            )

        com.testplaybyte.loom.domain.model.ExportFormat.YOLO ->
            yolo(project) + ExportFile("README.txt", yoloReadme(project))

        com.testplaybyte.loom.domain.model.ExportFormat.VOC ->
            voc(project) + ExportFile("README.txt", vocReadme(project))
    }

    // ── COCO (docs/05 §5) ──────────────────────────────────────────────────

    fun coco(project: Project): String {
        val sb = StringBuilder()
        sb.append("{\n")
        // images
        sb.append("  \"images\": [\n")
        project.images.forEachIndexed { i, im ->
            sb.append("    {")
            sb.append("\"id\": ").append(i + 1).append(", ")
            sb.append("\"file_name\": ").append(jsonString(im.file)).append(", ")
            sb.append("\"width\": ").append(im.width).append(", ")
            sb.append("\"height\": ").append(im.height)
            sb.append("}").append(if (i < project.images.size - 1) "," else "").append("\n")
        }
        sb.append("  ],\n")
        // annotations
        val anns = StringBuilder()
        var annId = 1
        project.images.forEachIndexed { i, im ->
            val st = project.stateOf(im.id)
            val mesh = st.mesh ?: return@forEachIndexed
            val scale = realPoints(mesh, im.width, im.height)
            val cats = project.labels
            val catId = cats.indexOfFirst { it.id == st.labelId }
            if (catId < 0) return@forEachIndexed // unlabeled meshes have no category — skip
            val segs = ArrayList<List<Double>>()
            segs.add(flatten(scale))
            if (project.includeMasks) {
                for (s in st.strokes) {
                    if (s.pts.size >= 2) segs.add(flatten(realPoints(s.pts, im.width, im.height)))
                }
            }
            val area = MeshMath.polygonArea(scale).toDouble()
            val bb = MeshMath.bbox(scale)
            if (anns.isNotEmpty()) anns.append(",\n")
            anns.append("    {")
            anns.append("\"id\": ").append(annId++).append(", ")
            anns.append("\"image_id\": ").append(i + 1).append(", ")
            anns.append("\"category_id\": ").append(catId + 1).append(", ")
            anns.append("\"segmentation\": [").append(segs.joinToString(", ") { s -> "[" + s.joinToString(", ") { fmt(it) } + "]" }).append("], ")
            anns.append("\"area\": ").append(fmt(area)).append(", ")
            anns.append("\"bbox\": [")
                .append(fmt(bb.x.toDouble())).append(", ")
                .append(fmt(bb.y.toDouble())).append(", ")
                .append(fmt(bb.w.toDouble())).append(", ")
                .append(fmt(bb.h.toDouble()))
                .append("], ")
            anns.append("\"iscrowd\": 0")
            anns.append("}")
        }
        sb.append("  \"annotations\": [\n").append(anns).append("\n  ],\n")
        // categories
        sb.append("  \"categories\": [\n")
        project.labels.forEachIndexed { i, l ->
            sb.append("    {")
            sb.append("\"id\": ").append(i + 1).append(", ")
            sb.append("\"name\": ").append(jsonString(l.name)).append(", ")
            sb.append("\"color\": ").append(jsonString(l.color))
            sb.append("}").append(if (i < project.labels.size - 1) "," else "").append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    // ── YOLO (segmentation format — documented in the export README) ──────

    fun yolo(project: Project): List<ExportFile> {
        val files = ArrayList<ExportFile>()
        val classIndex = project.labels.withIndex().associate { (i, l) -> l.id to i }
        for (im in project.images) {
            val st = project.stateOf(im.id)
            val mesh = st.mesh ?: continue
            val cat = classIndex[st.labelId] ?: continue
            val pts = realPoints(mesh, im.width, im.height)
            val line = yoloPolygonLine(cat, pts, im.width, im.height)
            val base = im.file.substringBeforeLast('.', im.file)
            files.add(ExportFile("labels/$base.txt", line + "\n"))
        }
        files.add(
            ExportFile(
                "classes.txt",
                project.labels.joinToString("\n") { it.name } + (if (project.labels.isEmpty()) "" else "\n"),
            ),
        )
        return files
    }

    /**
     * One YOLO-segmentation line: `classIndex x1 y1 x2 y2 …` with points
     * normalized to [0,1] against the image size, 6 decimals. Pure and
     * unit-tested (same format the old MESHLABEL exporter used and the
     * rooneysh/Labelme2YOLO reference produces).
     */
    fun yoloPolygonLine(classIndex: Int, pts: List<Pt>, imageWidth: Int, imageHeight: Int): String {
        val sb = StringBuilder().append(classIndex)
        for (p in pts) {
            val nx = (p.x / imageWidth).coerceIn(0f, 1f)
            val ny = (p.y / imageHeight).coerceIn(0f, 1f)
            sb.append(' ').append(String.format(Locale.US, "%.6f", nx))
            sb.append(' ').append(String.format(Locale.US, "%.6f", ny))
        }
        return sb.toString()
    }

    // ── Pascal VOC (mesh polygon extension, documented) ───────────────────

    fun voc(project: Project): List<ExportFile> {
        val files = ArrayList<ExportFile>()
        for (im in project.images) {
            val st = project.stateOf(im.id)
            val mesh = st.mesh
            val label = project.labels.firstOrNull { it.id == st.labelId }
            // Emit an XML for every image with a labeled mesh (mirrors COCO).
            if (mesh == null || label == null) continue
            val pts = realPoints(mesh, im.width, im.height)
            val bb = MeshMath.bbox(pts)
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            sb.append("<annotation>\n")
            sb.append("  <filename>").append(xmlEscape(im.file)).append("</filename>\n")
            sb.append("  <size>\n")
            sb.append("    <width>").append(im.width).append("</width>\n")
            sb.append("    <height>").append(im.height).append("</height>\n")
            sb.append("    <depth>3</depth>\n")
            sb.append("  </size>\n")
            sb.append("  <segmented>1</segmented>\n")
            sb.append("  <object>\n")
            sb.append("    <name>").append(xmlEscape(label.name)).append("</name>\n")
            sb.append("    <pose>Unspecified</pose>\n")
            sb.append("    <truncated>0</truncated>\n")
            sb.append("    <difficult>0</difficult>\n")
            sb.append("    <bndbox>\n")
            sb.append("      <xmin>").append(bb.x.roundToInt()).append("</xmin>\n")
            sb.append("      <ymin>").append(bb.y.roundToInt()).append("</ymin>\n")
            sb.append("      <xmax>").append((bb.x + bb.w).roundToInt()).append("</xmax>\n")
            sb.append("      <ymax>").append((bb.y + bb.h).roundToInt()).append("</ymax>\n")
            sb.append("    </bndbox>\n")
            sb.append("    <polygon>\n")
            for (p in pts) {
                sb.append("      <pt><x>").append(p.x.roundToInt())
                    .append("</x><y>").append(p.y.roundToInt()).append("</y></pt>\n")
            }
            sb.append("    </polygon>\n")
            sb.append("  </object>\n")
            // Exclusion strokes as a documented extension (not standard VOC).
            if (project.includeMasks && st.strokes.isNotEmpty()) {
                sb.append("  <exclusions>\n")
                for (s in st.strokes) {
                    if (s.pts.size < 2) continue
                    sb.append("    <polygon>\n")
                    for (p in realPoints(s.pts, im.width, im.height)) {
                        sb.append("      <pt><x>").append(p.x.roundToInt())
                            .append("</x><y>").append(p.y.roundToInt()).append("</y></pt>\n")
                    }
                    sb.append("    </polygon>\n")
                }
                sb.append("  </exclusions>\n")
            }
            sb.append("</annotation>\n")
            val base = im.file.substringBeforeLast('.', im.file)
            files.add(ExportFile("xml/$base.xml", sb.toString()))
        }
        return files
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** World points → real image pixels. */
    fun realPoints(pts: List<Pt>, width: Int, height: Int): List<Pt> {
        if (width == 800 && height == 600) return pts
        val sx = width / 800f
        val sy = height / 600f
        return pts.map { Pt(it.x * sx, it.y * sy) }
    }

    private fun flatten(pts: List<Pt>): List<Double> {
        val out = ArrayList<Double>(pts.size * 2)
        for (p in pts) {
            out.add(p.x.toDouble())
            out.add(p.y.toDouble())
        }
        return out
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    // ── export-folder READMEs (document the format choices) ───────────────

    private fun header(project: Project, lines: List<String>): String = buildString {
        append("Loom dataset export\n")
        append("===================\n\n")
        append("Project: ${project.name}\n")
        append("Images:  ${project.images.size}\n")
        append("Labels:  ${project.labels.joinToString(", ") { it.name }}\n\n")
        lines.forEach { append(it).append('\n') }
        append('\n')
        append("Coordinate space: world (800x600) scaled to each image's real pixels.\n")
        if (!project.includeMasks) {
            append("Brush masks: NOT included in this export (option was off).\n")
        }
    }

    private fun loomReadme(project: Project) = header(
        project,
        listOf(
            "Format: Loom JSON (annotations.json).",
            "One entry per image with dots, mesh polygon, mask strokes, tags,",
            "status, density and the train/val split assignment.",
        ),
    )

    private fun cocoReadme(project: Project) = header(
        project,
        listOf(
            "Format: COCO instances (instances.json).",
            "annotations[].segmentation holds the mesh polygon (flattened",
            "x1,y1,x2,y2,...) in real image pixels; bbox + area included.",
            if (project.includeMasks) {
                "Mask strokes are appended to segmentation[] of their image's annotation."
            } else {
                "Mask strokes omitted (option was off)."
            },
        ),
    )

    private fun yoloReadme(project: Project) = header(
        project,
        listOf(
            "Format: YOLO — SEGMENTATION labels (one file per image in labels/).",
            "Each line: classIndex x1 y1 x2 y2 ... (normalized 0..1, 6 decimals).",
            "Class index order = classes.txt order.",
            "Brush masks are not representable in YOLO and are omitted.",
        ),
    )

    private fun vocReadme(project: Project) = header(
        project,
        listOf(
            "Format: Pascal VOC XML (one file per image in xml/).",
            "Each object carries bndbox + a non-standard <polygon> extension",
            "holding the mesh outline in real image pixels.",
            if (project.includeMasks) {
                "<exclusions> holds brush mask polygons (documented extension)."
            } else {
                "Brush masks omitted (option was off)."
            },
        ),
    )
}
