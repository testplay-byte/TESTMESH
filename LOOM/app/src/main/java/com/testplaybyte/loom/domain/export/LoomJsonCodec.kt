package com.testplaybyte.loom.domain.export

import com.testplaybyte.loom.domain.model.Dot
import com.testplaybyte.loom.domain.model.DotKind
import com.testplaybyte.loom.domain.model.ImageState
import com.testplaybyte.loom.domain.model.ImageStatus
import com.testplaybyte.loom.domain.model.Label
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.Project
import com.testplaybyte.loom.domain.model.Pt
import com.testplaybyte.loom.domain.model.Stroke
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loom JSON — the primary annotation format (docs/05 §5, "Loom JSON
 * (annotations.json) — the primary format"). One file holds classes and
 * per-image dots / mesh / strokes / tags / status.
 *
 * TWO ROLES, deliberately distinguished:
 *
 *  1. WORKING FILE (`autosave`): written next to the images as
 *     `annotations.json` after every gesture (docs/04 §8). Coordinates are
 *     WORLD space (800×600, the annotation space) and every image entry
 *     reports width/height 800×600 — the annotation space never depends on
 *     the photo's resolution, so re-loading the working file is lossless.
 *
 *  2. EXPORT FILE (Export screen): a self-consistent dataset artifact —
 *     coordinates are scaled to the REAL image pixels and width/height
 *     report the real dimensions, so third-party tooling can consume it
 *     directly (docs/05 §5 example).
 *
 * Decoding accepts both shapes: [decode] reads whichever is present; the
 * caller decides whether to scale (the working file is loaded unscaled).
 */
object LoomJsonCodec {

    const val FORMAT = "loom-json"
    const val VERSION = 1

    /** One class entry in the file. */
    data class ClassDef(val id: String?, val name: String, val color: String)

    /** One decoded image entry. */
    data class DecodedImage(
        val file: String,
        val width: Int,
        val height: Int,
        val status: ImageStatus,
        /** Class NAME as written (resolved to a label id by the caller). */
        val labelName: String?,
        val tags: List<String>,
        val density: Int,
        val dots: List<Dot>,
        val mesh: List<Pt>?,
        val strokes: List<Stroke>,
        /** true when coordinates are real pixels (export) — caller scales. */
        val spaceIsWorld: Boolean,
    )

    data class Decoded(val classes: List<ClassDef>, val images: List<DecodedImage>)

    // ── encode ─────────────────────────────────────────────────────────────

    /**
     * Builds a full Loom JSON document.
     *
     * @param scaleToRealPixels false → working file (world coords, 800×600
     *   reported); true → export file (real pixels/dimensions).
     * @param includeMasks when false, brush strokes are omitted (the
     *   "Include brush masks" export option, docs/03 §7).
     */
    fun encode(
        project: Project,
        exportedAt: Long,
        scaleToRealPixels: Boolean,
        includeMasks: Boolean,
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put(
            "project",
            JSONObject().apply {
                put("name", project.name)
                put("exportedAt", exportedAt)
            },
        )
        root.put("classes", classesArray(project.labels))

        val images = JSONArray()
        for (im in project.images) {
            val st = project.stateOf(im.id)
            val sx = if (scaleToRealPixels) im.width / 800.0 else 1.0
            val sy = if (scaleToRealPixels) im.height / 600.0 else 1.0
            val o = JSONObject()
            o.put("file", im.file)
            o.put("width", if (scaleToRealPixels) im.width else 800)
            o.put("height", if (scaleToRealPixels) im.height else 600)
            o.put("status", st.status.wire)
            o.put("label", project.labels.firstOrNull { it.id == st.labelId }?.name ?: JSONObject.NULL)
            o.put("tags", JSONArray(st.tags))
            o.put("density", st.density)
            o.put("dots", dotsArray(st.dots, sx, sy))
            o.put("mesh", meshArray(st.mesh, sx, sy))
            o.put("strokes", strokesArray(st.strokes, sx, sy, includeMasks))
            o.put(
                "split",
                if (LoomRules.isValSplit(project.images.indexOf(im), project.splitPct)) "val" else "train",
            )
            images.put(o)
        }
        root.put("images", images)
        return root.toString(2)
    }

    private fun classesArray(labels: List<Label>): JSONArray {
        val arr = JSONArray()
        for (l in labels) {
            arr.put(
                JSONObject().apply {
                    put("id", l.id)
                    put("name", l.name)
                    put("color", l.color)
                },
            )
        }
        return arr
    }

    private fun dotsArray(dots: List<Dot>, sx: Double, sy: Double): JSONArray {
        val arr = JSONArray()
        for (d in dots) {
            arr.put(
                JSONObject().apply {
                    put("x", round2(d.x * sx))
                    put("y", round2(d.y * sy))
                    put("kind", if (d.isPos) "pos" else "neg")
                },
            )
        }
        return arr
    }

    private fun meshArray(mesh: List<Pt>?, sx: Double, sy: Double): Any {
        if (mesh == null) return JSONObject.NULL
        val arr = JSONArray()
        for (p in mesh) {
            arr.put(JSONArray().apply { put(round2(p.x * sx)); put(round2(p.y * sy)) })
        }
        return arr
    }

    private fun strokesArray(
        strokes: List<Stroke>,
        sx: Double,
        sy: Double,
        includeMasks: Boolean,
    ): JSONArray {
        val arr = JSONArray()
        if (!includeMasks) return arr
        for (s in strokes) {
            val line = JSONArray()
            for (p in s.pts) {
                line.put(JSONArray().apply { put(round2(p.x * sx)); put(round2(p.y * sy)) })
            }
            arr.put(line)
        }
        return arr
    }

    // ── decode ─────────────────────────────────────────────────────────────

    /**
     * Tolerant decode of a Loom JSON document (working file or export).
     * Throws [IllegalArgumentException] only when the text is not JSON or
     * the required `images` array is missing.
     */
    fun decode(text: String): Decoded {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid Loom JSON file", e)
        }
        val classesArr = root.optJSONArray("classes") ?: JSONArray()
        val classes = ArrayList<ClassDef>(classesArr.length())
        for (i in 0 until classesArr.length()) {
            val c = classesArr.optJSONObject(i) ?: continue
            classes.add(
                ClassDef(
                    id = c.optString("id", "").ifEmpty { null },
                    name = c.optString("name", ""),
                    color = c.optString("color", "c1"),
                ),
            )
        }
        val imagesArr = root.optJSONArray("images")
            ?: throw IllegalArgumentException("Missing required key \"images\"")
        val images = ArrayList<DecodedImage>(imagesArr.length())
        for (i in 0 until imagesArr.length()) {
            val o = imagesArr.optJSONObject(i) ?: continue
            val file = o.optString("file", "").ifEmpty { continue }
            val w = o.optInt("width", 800).let { if (it > 0) it else 800 }
            val h = o.optInt("height", 600).let { if (it > 0) it else 600 }
            val world = w == 800 && h == 600
            images.add(
                DecodedImage(
                    file = file,
                    width = w,
                    height = h,
                    status = ImageStatus.fromWire(o.optString("status", null)),
                    labelName = if (o.isNull("label")) null else o.optString("label", "").ifEmpty { null },
                    tags = stringList(o.optJSONArray("tags")),
                    density = LoomRules.clampDensity(o.optInt("density", 16)),
                    dots = decodeDots(o.optJSONArray("dots")),
                    mesh = decodeMesh(o.optJSONArray("mesh")),
                    strokes = decodeStrokes(o.optJSONArray("strokes")),
                    spaceIsWorld = world,
                ),
            )
        }
        return Decoded(classes, images)
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun decodeDots(arr: JSONArray?): List<Dot> {
        if (arr == null) return emptyList()
        val out = ArrayList<Dot>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Dot(
                    id = "dot-restored-$i",
                    kind = if (o.optString("kind", "pos") == "neg") DotKind.NEG else DotKind.POS,
                    x = o.optDouble("x", 0.0).toFloat(),
                    y = o.optDouble("y", 0.0).toFloat(),
                ),
            )
        }
        return out
    }

    private fun decodeMesh(arr: JSONArray?): List<Pt>? {
        if (arr == null || arr.length() < 3) return null
        val out = ArrayList<Pt>(arr.length())
        for (i in 0 until arr.length()) {
            val xy = arr.optJSONArray(i) ?: continue
            if (xy.length() < 2) continue
            out.add(Pt(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat()))
        }
        return out.ifEmpty { null }
    }

    private fun decodeStrokes(arr: JSONArray?): List<Stroke> {
        if (arr == null) return emptyList()
        val out = ArrayList<Stroke>(arr.length())
        for (i in 0 until arr.length()) {
            val line = arr.optJSONArray(i) ?: continue
            val pts = ArrayList<Pt>(line.length())
            for (j in 0 until line.length()) {
                val xy = line.optJSONArray(j) ?: continue
                if (xy.length() < 2) continue
                pts.add(Pt(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat()))
            }
            if (pts.size >= 2) out.add(Stroke("st-restored-$i", pts))
        }
        return out
    }

    // ── export preview (docs/03 §7) ───────────────────────────────────────

    /** The Export screen's preview: first [count] image entries, `{file,
     *  label, vertices, dots{pos,neg}}` shape, then "  …". */
    fun previewJson(project: Project, count: Int = 3): String {
        val arr = JSONArray()
        val shown = project.images.take(count)
        for (im in shown) {
            val st = project.stateOf(im.id)
            arr.put(
                JSONObject().apply {
                    put("file", im.file)
                    put("label", project.labels.firstOrNull { it.id == st.labelId }?.name ?: JSONObject.NULL)
                    put("vertices", st.mesh?.size ?: 0)
                    put(
                        "dots",
                        JSONObject().apply {
                            put("pos", st.dots.count { it.isPos })
                            put("neg", st.dots.count { !it.isPos })
                        },
                    )
                },
            )
        }
        val pretty = arr.toString(2)
        return if (project.images.size > count) "$pretty\n  …" else pretty
    }

    // ── shared helpers ─────────────────────────────────────────────────────

    /** 2-decimal rounding — sub-pixel precision, compact files. */
    fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0
}
