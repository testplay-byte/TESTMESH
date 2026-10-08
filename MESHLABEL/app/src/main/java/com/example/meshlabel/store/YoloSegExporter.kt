package com.example.meshlabel.store

import android.content.Context
import com.example.meshlabel.labelme.LabelMeJson
import java.io.File

/**
 * YOLO-seg dataset export from the saved LabelMe projects
 * (rule mirrors rooneysh/Labelme2YOLO):
 *
 *   dataset/
 *     images/<basename>.<ext>       copied originals
 *     labels/<basename>.txt         one line per polygon:
 *       class_id x1 y1 x2 y2 ...    points normalized to [0,1] of
 *                                   imageWidth/imageHeight, 6 decimals
 *     classes.txt                   union of labels, first-seen order
 *     dataset.yaml                  names + paths (YOLO training config)
 *
 * Non-polygon shapes (rectangle/line/...) are skipped - export only what
 * a segmentation trainer can consume; the README documents this.
 */
object YoloSegExporter {

    data class Result(
        val projectsExported: Int,
        val shapesExported: Int,
        val skippedShapes: Int,
        val outDir: File,
        val error: String? = null,
    )

    /**
     * One YOLO-seg label line: `class_id x1 y1 x2 y2 ...` with points
     * normalized to [0,1] against the image size, 6 decimals (pure and
     * unit-tested; matches rooneysh/Labelme2YOLO's polygon format).
     */
    fun yoloLine(classId: Int, points: List<com.example.meshlabel.model.Pt>,
                 imageWidth: Int, imageHeight: Int): String {
        val sb = StringBuilder().append(classId)
        for (pt in points) {
            val nx = (pt.x / imageWidth).coerceIn(0f, 1f)
            val ny = (pt.y / imageHeight).coerceIn(0f, 1f)
            sb.append(' ').append(String.format(java.util.Locale.US, "%.6f", nx))
            sb.append(' ').append(String.format(java.util.Locale.US, "%.6f", ny))
        }
        return sb.toString()
    }

    fun export(context: Context): Result {
        val projects = ProjectStore.list(context)
        val out = File(context.filesDir, "dataset").apply {
            deleteRecursively()
            mkdirs()
        }
        val imagesDir = File(out, "images").apply { mkdirs() }
        val labelsDir = File(out, "labels").apply { mkdirs() }

        val classNames = LinkedHashMap<String, Int>() // label -> class id
        var exportedProjects = 0
        var exportedShapes = 0
        var skipped = 0

        for (p in projects) {
            val loaded = try {
                if (!p.jsonFile.exists()) continue
                LabelMeJson.fromJson(p.jsonFile.readText())
            } catch (e: Exception) {
                skipped += 1
                continue
            }
            if (loaded.shapes.isEmpty()) continue
            val imgW = loaded.imageWidth.takeIf { it > 0 } ?: continue
            val imgH = loaded.imageHeight.takeIf { it > 0 } ?: continue

            val lines = ArrayList<String>(loaded.shapes.size)
            for (s in loaded.shapes) {
                if (s.shapeType != "polygon" || s.points.size < 3) {
                    skipped++
                    continue
                }
                val cls = classNames.getOrPut(s.label) { classNames.size }
                lines.add(yoloLine(cls, s.points, imgW, imgH))
                exportedShapes++
            }
            if (lines.isEmpty()) continue

            p.imageFile.copyTo(File(imagesDir, p.imageFile.name), overwrite = true)
            File(labelsDir, "${p.name}.txt").writeText(lines.joinToString("\n"))
            exportedProjects++
        }

        File(out, "classes.txt").writeText(classNames.keys.joinToString("\n"))
        File(out, "dataset.yaml").writeText(
            """
            path: .
            train: images
            val: images
            names:
            """.trimIndent() + "\n" + classNames.entries.joinToString("\n") { (name, id) ->
                "  $id: $name"
            } + "\n"
        )

        return Result(
            projectsExported = exportedProjects,
            shapesExported = exportedShapes,
            skippedShapes = skipped,
            outDir = out,
        )
    }
}
