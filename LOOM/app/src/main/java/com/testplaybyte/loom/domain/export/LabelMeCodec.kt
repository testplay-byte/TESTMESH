package com.testplaybyte.loom.domain.export

import com.testplaybyte.loom.domain.model.ImageState
import com.testplaybyte.loom.domain.model.Label
import com.testplaybyte.loom.domain.model.ObjAnn
import com.testplaybyte.loom.domain.model.Project
import com.testplaybyte.loom.domain.model.ProjectImage
import com.testplaybyte.loom.domain.model.Pt
import com.testplaybyte.loom.domain.model.Ids
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * LabelMe v5 per-image JSON — THE user-facing annotation format.
 *
 * Each image gets its own `<image-name>.json` beside it (e.g. `hand.jpg`
 * → `hand.json`), exactly the schema LabelMe 5.11 writes and the example
 * handoff uses:
 *
 *     { "version": "5.11.4", "flags": {},
 *       "shapes": [ { "label": "CAMERA",
 *                     "points": [[x, y], ...],      // REAL image pixels
 *                     "group_id": null, "description": "",
 *                     "shape_type": "polygon", "flags": {}, "mask": null } ],
 *       "imagePath": "camera-image.webp",
 *       "imageData": "<base64, optional>",
 *       "imageHeight": 1600, "imageWidth": 2000 }
 *
 * Multi-object: every saved object (and the in-progress working mesh) is
 * one entry in `shapes`. `imageData` embeds the full image bytes base64 —
 * included on explicit saves, omitted (null) on 300ms autosaves so the
 * debounce never base64-encodes megabytes. Files without `imageData` are
 * still first-class LabelMe documents.
 */
object LabelMeCodec {

    const val VERSION = "5.11.4"

    /** `hand.jpg` → `hand.json` (collision-free: images are unique per folder). */
    fun fileNameFor(imageFile: String): String =
        imageFile.substringBeforeLast('.', imageFile) + ".json"

    /**
     * Builds the LabelMe document for one image.
     *
     * @param imageBytes raw image bytes for `imageData`, or null to omit.
     */
    fun encode(
        project: Project,
        image: ProjectImage,
        state: ImageState,
        imageBytes: ByteArray?,
    ): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("flags", JSONObject())
        val shapes = JSONArray()
        for (obj in state.objects + listOfNotNull(workingObj(state))) {
            val labelName = project.labels.firstOrNull { it.id == obj.labelId }?.name ?: "unlabeled"
            shapes.put(
                JSONObject().apply {
                    put("label", labelName)
                    put("points", pointsArray(DatasetWriters.realPoints(obj.mesh, image.width, image.height)))
                    put("group_id", JSONObject.NULL)
                    put("description", "")
                    put("shape_type", "polygon")
                    put("flags", JSONObject())
                    put("mask", JSONObject.NULL)
                },
            )
        }
        root.put("shapes", shapes)
        root.put("imagePath", image.file)
        root.put(
            "imageData",
            if (imageBytes != null) Base64.getEncoder().encodeToString(imageBytes) else JSONObject.NULL,
        )
        root.put("imageHeight", image.height)
        root.put("imageWidth", image.width)
        return root.toString(2)
    }

    private fun workingObj(state: ImageState): ObjAnn? =
        state.mesh?.takeIf { it.size >= 3 }?.let { ObjAnn("working", state.labelId, it, state.density) }

    private fun pointsArray(pts: List<Pt>): JSONArray {
        val arr = JSONArray()
        for (p in pts) {
            arr.put(JSONArray().apply { put(LoomJsonCodec.round2(p.x.toDouble())); put(LoomJsonCodec.round2(p.y.toDouble())) })
        }
        return arr
    }

    /** One decoded shape: label name + polygon in REAL image pixels. */
    data class DecodedShape(val label: String?, val points: List<Pt>)

    /**
     * Tolerant decode of a LabelMe document — returns its polygon shapes.
     * Empty list when the text is not LabelMe-like JSON (callers treat that
     * as "no annotation file").
     */
    fun decode(text: String): List<DecodedShape> {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return emptyList()
        }
        val shapes = root.optJSONArray("shapes") ?: return emptyList()
        val out = ArrayList<DecodedShape>(shapes.length())
        for (i in 0 until shapes.length()) {
            val sh = shapes.optJSONObject(i) ?: continue
            val type = sh.optString("shape_type", "polygon")
            if (type != "polygon") continue
            val pts = ArrayList<Pt>()
            val arr = sh.optJSONArray("points") ?: continue
            for (j in 0 until arr.length()) {
                val xy = arr.optJSONArray(j) ?: continue
                if (xy.length() < 2) continue
                pts.add(Pt(xy.optDouble(0, 0.0).toFloat(), xy.optDouble(1, 0.0).toFloat()))
            }
            if (pts.size >= 3) {
                out.add(DecodedShape(sh.optString("label", "").ifEmpty { null }, pts))
            }
        }
        return out
    }

    /**
     * Converts decoded shapes into saved [ObjAnn]s in WORLD space (the
     * inverse of [DatasetWriters.realPoints]' letterbox mapping).
     */
    fun toObjects(shapes: List<DecodedShape>, labels: List<Label>, width: Int, height: Int): List<ObjAnn> =
        shapes.map { sh ->
            ObjAnn(
                id = Ids.uid("obj"),
                labelId = labels.firstOrNull { it.name.equals(sh.label ?: "", ignoreCase = true) }?.id,
                mesh = worldPoints(sh.points, width, height),
            )
        }

    /** Real image pixels → world (800×600 letterbox). */
    private fun worldPoints(pts: List<Pt>, width: Int, height: Int): List<Pt> {
        if (width == 800 && height == 600) return pts
        val s = minOf(800f / width, 600f / height)
        val dx = (800f - width * s) / 2f
        val dy = (600f - height * s) / 2f
        return pts.map { Pt(it.x * s + dx, it.y * s + dy) }
    }
}
