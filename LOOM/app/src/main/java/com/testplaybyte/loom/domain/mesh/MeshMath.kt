package com.testplaybyte.loom.domain.mesh

import com.testplaybyte.loom.domain.model.Dot
import com.testplaybyte.loom.domain.model.Pt
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * MeshMath — the deterministic "smart annotation model" (docs/04 §2).
 *
 * EXACT port of the prototype's `lib/mesh.ts`. Constants included. This
 * is spec, not an approximation: the worked example in docs/04 §2 is
 * unit-tested in MeshMathTest.
 *
 * Pipeline:
 *   anchors (authored ring) → Catmull-Rom smoothing → uniform resample to
 *   `density` vertices → dot influence (pos pulls / neg pushes, gaussian
 *   falloff).
 *
 * Pure Kotlin on plain data — no Android types — so everything here runs
 * on the JVM test host.
 */
object MeshMath {

    // ── model constants (docs/04 §2 — exact) ──────────────────────────────
    /** Dot influence radius, world units. */
    const val PULL_RADIUS = 150f
    /** Gaussian falloff sigma. */
    const val PULL_SIGMA = 78f
    /** Max displacement toward a positive dot. */
    const val PULL_MAX = 30f
    /** Max displacement away from a negative dot. */
    const val PUSH_MAX = 42f
    /** Catmull-Rom samples per anchor segment. */
    const val SAMPLES_PER_SEG = 14
    /** Freehand decimation epsilon (world units). */
    const val BRUSH_THIN_EPSILON = 2.5f

    /**
     * Catmull-Rom through a CLOSED ring of anchors → dense outline.
     * `smoothRingClosed(anchors, samplesPerSeg = 14)` in the prototype.
     */
    fun smoothRingClosed(anchors: List<Pt>, samplesPerSeg: Int = SAMPLES_PER_SEG): List<Pt> {
        val n = anchors.size
        if (n < 3) return anchors.toList()
        val out = ArrayList<Pt>(n * samplesPerSeg)
        for (i in 0 until n) {
            val p0 = anchors[(i - 1 + n) % n]
            val p1 = anchors[i]
            val p2 = anchors[(i + 1) % n]
            val p3 = anchors[(i + 2) % n]
            for (s in 0 until samplesPerSeg) {
                val t = s.toFloat() / samplesPerSeg
                val t2 = t * t
                val t3 = t2 * t
                out.add(
                    Pt(
                        x = 0.5f * (
                            2 * p1.x + (-p0.x + p2.x) * t +
                                (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                                (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3
                            ),
                        y = 0.5f * (
                            2 * p1.y + (-p0.y + p2.y) * t +
                                (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                                (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3
                            ),
                    )
                )
            }
        }
        return out
    }

    /**
     * Uniformly resample a closed polyline to exactly [count] vertices —
     * `resampleRing` in the prototype, including its exact walk semantics
     * (the target-distance walk never enters the final segment index, so
     * the closing edge is sampled with t = 1 semantics at the seam).
     */
    fun resampleRing(dense: List<Pt>, count: Int): List<Pt> {
        if (dense.isEmpty()) return emptyList()
        val c = max(3, count)
        val n = dense.size
        val segLen = FloatArray(n)
        var perim = 0f
        for (i in 0 until n) {
            val a = dense[i]
            val b = dense[(i + 1) % n]
            val l = hypot(b.x - a.x, b.y - a.y)
            segLen[i] = l
            perim += l
        }
        val step = perim / c
        val out = ArrayList<Pt>(c)
        for (v in 0 until c) {
            val target = v * step
            var i = 0
            var base = 0f
            while (i < n - 1 && base + segLen[i] < target) {
                base += segLen[i]
                i++
            }
            val t = if (segLen[i] > 0f) (target - base) / segLen[i] else 0f
            val a = dense[i]
            val b = dense[(i + 1) % n]
            out.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
        return out
    }

    /**
     * Sculpt the ring with the user's prompt dots — `applyDotInfluence`.
     * Positive dots pull nearby vertices toward themselves, negative dots
     * push them away; gaussian falloff, exact constants above.
     */
    fun applyDotInfluence(ring: List<Pt>, dots: List<Dot>): List<Pt> {
        if (dots.isEmpty()) return ring
        return ring.map { v ->
            var dx = 0f
            var dy = 0f
            for (d in dots) {
                val vx = d.x - v.x
                val vy = d.y - v.y
                val dist = hypot(vx, vy).let { if (it == 0f) 1f else it }
                if (dist > PULL_RADIUS) continue
                val w = exp(-(dist * dist) / (2 * PULL_SIGMA * PULL_SIGMA))
                val k = if (d.kind == com.testplaybyte.loom.domain.model.DotKind.POS) {
                    (w * PULL_MAX) / dist
                } else {
                    (-w * PUSH_MAX) / dist
                }
                dx += vx * k
                dy += vy * k
            }
            Pt(v.x + dx, v.y + dy)
        }
    }

    /** Build (or rebuild) the mesh from anchors + density + dots. */
    fun buildMesh(anchors: List<Pt>, density: Int, dots: List<Dot>): List<Pt> {
        val dense = smoothRingClosed(anchors)
        val ring = resampleRing(dense, density)
        return applyDotInfluence(ring, dots)
    }

    // ── hit-testing / edit helpers (docs/04 §2 "rendering helpers") ───────

    /** Ray-casting point-in-polygon (even-odd), world coords. */
    fun pointInPolygon(p: Pt, poly: List<Pt>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]
            val pj = poly[j]
            if ((pi.y > p.y) != (pj.y > p.y) &&
                p.x < (pj.x - pi.x) * (p.y - pi.y) / (pj.y - pi.y) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Polygon area (absolute, shoelace). */
    fun polygonArea(poly: List<Pt>): Float {
        var a = 0f
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            a += p.x * q.y - q.x * p.y
        }
        return abs(a / 2f)
    }

    /** Axis-aligned bounding box of a point list. */
    data class BBox(val x: Float, val y: Float, val w: Float, val h: Float)

    fun bbox(poly: List<Pt>): BBox {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in poly) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return BBox(minX, minY, maxX - minX, maxY - minY)
    }

    /** Nearest vertex to [p]; index -1 and dist ∞ for an empty ring. */
    data class Nearest(val index: Int, val dist: Float)

    fun nearestVertex(poly: List<Pt>, p: Pt): Nearest {
        var index = -1
        var best = Float.MAX_VALUE
        for (i in poly.indices) {
            val d = hypot(poly[i].x - p.x, poly[i].y - p.y)
            if (d < best) {
                best = d
                index = i
            }
        }
        return Nearest(index, if (index < 0) Float.MAX_VALUE else best)
    }

    /** Insert a vertex into the ring edge closest to [p] (after edge start). */
    fun insertVertex(poly: List<Pt>, p: Pt): List<Pt> {
        val n = poly.size
        if (n < 3) return poly
        var bestEdge = 0
        var bestDist = Float.MAX_VALUE
        for (i in 0 until n) {
            val d = distToSegment(p, poly[i], poly[(i + 1) % n])
            if (d < bestDist) {
                bestDist = d
                bestEdge = i
            }
        }
        val out = poly.toMutableList()
        out.add(bestEdge + 1, Pt(p.x, p.y))
        return out
    }

    /** Distance from [p] to segment a–b. */
    fun distToSegment(p: Pt, a: Pt, b: Pt): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val apx = p.x - a.x
        val apy = p.y - a.y
        val len2 = if (abx * abx + aby * aby == 0f) 1f else abx * abx + aby * aby
        val t = min(1f, max(0f, (apx * abx + apy * aby) / len2))
        return hypot(a.x + abx * t - p.x, a.y + aby * t - p.y)
    }

    /** Distance from [p] to the nearest edge of a closed ring. */
    fun edgeDistance(poly: List<Pt>, p: Pt): Float {
        if (poly.size < 3) return Float.MAX_VALUE
        var best = Float.MAX_VALUE
        val n = poly.size
        for (i in 0 until n) {
            val d = distToSegment(p, poly[i], poly[(i + 1) % n])
            if (d < best) best = d
        }
        return best
    }

    /** Simplify a freehand stroke: keep points that move > epsilon. */
    fun thinStroke(pts: List<Pt>, epsilon: Float = BRUSH_THIN_EPSILON): List<Pt> {
        if (pts.size <= 2) return pts
        val out = ArrayList<Pt>(pts.size)
        out.add(pts[0])
        for (i in 1 until pts.size - 1) {
            val last = out[out.size - 1]
            if (hypot(pts[i].x - last.x, pts[i].y - last.y) >= epsilon) out.add(pts[i])
        }
        out.add(pts[pts.size - 1])
        return out
    }

    /**
     * Midpoint-smoothed outline for a closed ring — the exact geometry of
     * the prototype's `ringPath(pts, smooth = true)`: the path starts at
     * the midpoint of edge (vertex0 → vertex1), then one quadratic per
     * vertex (control = the next vertex, end = the following edge's
     * midpoint), closing back onto the start point. Vertices stay the
     * handles while the contour reads organic. Renderers convert this to
     * Path calls; keeping it as data makes the contour testable.
     */
    data class Quad(val start: Pt, val segments: List<Segment>) {
        data class Segment(val control: Pt, val end: Pt)
    }

    fun ringQuads(pts: List<Pt>): Quad? {
        val n = pts.size
        if (n < 3) return null
        fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val start = mid(pts[0], pts[1])
        val segs = ArrayList<Quad.Segment>(n)
        for (i in 0 until n) {
            val next = pts[(i + 1) % n]
            val after = pts[(i + 2) % n]
            segs.add(Quad.Segment(control = next, end = mid(next, after)))
        }
        return Quad(start, segs)
    }
}
