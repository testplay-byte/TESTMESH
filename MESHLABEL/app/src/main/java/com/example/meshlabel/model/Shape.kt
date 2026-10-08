package com.example.meshlabel.model

/**
 * One annotation point in ORIGINAL-IMAGE pixel coordinates (the LabelMe
 * coordinate space: absolute pixels of the source image, never normalized
 * and never screen-relative).
 */
data class Pt(val x: Float, val y: Float)

/**
 * One LabelMe shape. Fields map 1:1 to the LabelMe JSON schema
 * (wkentaro/labelme `_dump_shape_to_json_obj`):
 *
 *  - [label]      -> "label"        (required string)
 *  - [points]     -> "points"       ([[x,y], ...] absolute pixels)
 *  - [groupId]    -> "group_id"     (int|null - parts of one instance)
 *  - [shapeType]  -> "shape_type"   ("polygon" for manual + Magic Touch)
 *  - [description]-> "description"  ("" default)
 *  - "flags" is always written as {} (no flag feature in this app).
 *
 * Points are mutated in place by the editor (vertex dragging), so the list
 * is [MutableList]; [deepCopy] exists for the editor's undo snapshots.
 */
data class Shape(
    var label: String,
    val points: MutableList<Pt>,
    var groupId: Long? = null,
    var shapeType: String = "polygon",
    var description: String = "",
) {
    /** Independent copy for undo/redo snapshots (points copied, not shared). */
    fun deepCopy(): Shape =
        Shape(label, points.toMutableList(), groupId, shapeType, description)

    /** Signed shoelace area (positive = clockwise in screen coords). */
    fun area(): Float {
        var a = 0f
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2f
    }

    /** Ray-casting point-in-polygon (even-odd rule). */
    fun contains(x: Float, y: Float): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val pi = points[i]
            val pj = points[j]
            if ((pi.y > y) != (pj.y > y) &&
                x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
            ) inside = !inside
            j = i
        }
        return inside
    }
}
