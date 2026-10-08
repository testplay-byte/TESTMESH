package com.example.meshlabel.labelme

import com.example.meshlabel.model.Pt
import com.example.meshlabel.model.Shape
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exact LabelMe JSON serialization (schema of wkentaro/labelme v7.x,
 * `labelme/_label_file.py`):
 *
 * Top level (written in this key order):
 *   { "version", "flags", "shapes", "imagePath", "imageData",
 *     "imageHeight", "imageWidth" }
 * Per shape:
 *   { "label", "points": [[x,y],...], "group_id", "shape_type",
 *     "flags", "description" }
 *
 * Coordinates are ABSOLUTE pixels of the original image. `imageData` is
 * base64 of the stored image bytes (or JSON null when omitted - LabelMe
 * treats it as optional and falls back to `imagePath`).
 *
 * Loading is tolerant but strict about the essentials: a file without a
 * `shapes` array or with malformed points throws [IllegalArgumentException]
 * so the caller can surface a clear error instead of silently dropping
 * the user's work.
 */
object LabelMeJson {

    /** Version string written to "version" (our own, not labelme's). */
    const val APP_VERSION = "1.0.0"

    data class Loaded(
        val shapes: List<Shape>,
        val imageWidth: Int,
        val imageHeight: Int,
    )

    fun toJson(
        shapes: List<Shape>,
        imagePath: String,
        imageWidth: Int,
        imageHeight: Int,
        imageDataBase64: String?,
    ): String {
        val root = JSONObject()
        root.put("version", APP_VERSION)
        root.put("flags", JSONObject())
        val arr = JSONArray()
        for (s in shapes) {
            val o = JSONObject()
            o.put("label", s.label)
            val pts = JSONArray()
            for (p in s.points) {
                val xy = JSONArray()
                // LabelMe stores numbers; 2-decimal px is far below any
                // visible quantization and keeps files small.
                xy.put(round2(p.x.toDouble()))
                xy.put(round2(p.y.toDouble()))
                pts.put(xy)
            }
            o.put("points", pts)
            o.put("group_id", s.groupId ?: JSONObject.NULL)
            o.put("shape_type", s.shapeType)
            o.put("flags", JSONObject())
            o.put("description", s.description)
            arr.put(o)
        }
        root.put("shapes", arr)
        root.put("imagePath", imagePath)
        // JSONObject.put(String, Any?) with null writes JSONObject.NULL.
        root.put("imageData", imageDataBase64 ?: JSONObject.NULL)
        root.put("imageHeight", imageHeight)
        root.put("imageWidth", imageWidth)
        return root.toString(4) // 4-space indent like labelme's editor files
    }

    fun fromJson(text: String): Loaded {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid LabelMe JSON file", e)
        }
        if (!root.has("shapes")) {
            throw IllegalArgumentException("Missing required key \"shapes\"")
        }
        val arr = root.optJSONArray("shapes") ?: JSONArray()
        val shapes = ArrayList<Shape>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label", "")
            val ptsArr = o.optJSONArray("points")
                ?: throw IllegalArgumentException("shape[$i] has no points")
            val type = o.optString("shape_type", "polygon")
            val minPoints = if (type == "rectangle" || type == "line") 2 else 3
            if (ptsArr.length() < minPoints) {
                // LabelMe itself refuses degenerate shapes; skip them so one
                // bad row cannot block loading the whole annotation.
                continue
            }
            val pts = ArrayList<Pt>(ptsArr.length())
            for (j in 0 until ptsArr.length()) {
                val xy = ptsArr.optJSONArray(j)
                if (xy == null || xy.length() < 2) continue
                pts.add(Pt(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat()))
            }
            if (pts.size < minPoints) continue
            val gid = if (o.isNull("group_id")) null else o.optLong("group_id")
            shapes.add(
                Shape(
                    label = label,
                    points = pts,
                    groupId = gid,
                    shapeType = type,
                    description = o.optString("description", ""),
                )
            )
        }
        return Loaded(
            shapes = shapes,
            imageWidth = root.optInt("imageWidth", 0),
            imageHeight = root.optInt("imageHeight", 0),
        )
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
