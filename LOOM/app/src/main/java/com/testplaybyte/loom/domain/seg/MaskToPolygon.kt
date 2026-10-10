package com.testplaybyte.loom.domain.seg

import com.testplaybyte.loom.domain.model.Pt
import kotlin.math.abs

/**
 * Binary mask -> polygon (the "Convert to polygon" step of Magic Touch).
 *
 * Pure Kotlin on plain arrays - no Android types - so the whole conversion
 * is unit-tested on the JVM (MaskToPolygonTest), like the sibling app's
 * MaskGeometry suite.
 *
 * Pipeline:
 *  1. OPTIONAL downscale (nearest) when max(w,h) > [MAX_TRACE_DIM] -
 *     tracing a 4000x3000 mask cell-by-cell is wasteful and the extra
 *     resolution only adds staircase vertices that RDP would remove anyway.
 *  2. MARCHING SQUARES on the pixel-center lattice (corners = pixel
 *     centers, image border closed by sampling out-of-bounds as empty),
 *     emitting one segment per boundary crossing inside each cell.
 *  3. CHAINING by shared edge keys (exact integer lattice keys, no float
 *     tolerance) into closed loops; the LARGEST loop (|shoelace area|) is
 *     the outer contour - LabelMe polygons are single outlines, holes and
 *     satellite blobs are dropped by design.
 *  4. RAMER-DOUGLAS-PEUCKER simplification with [DEFAULT_EPSILON] px so
 *     the staircase becomes a clean, editable polygon (typical 1024px
 *     mask: thousands of lattice points -> dozens of vertices).
 *
 * Saddle cases (5/10: two diagonal solid pixels meeting at a corner) are
 * resolved as 4-connected SEPARATE lobes - deterministic, and irrelevant
 * for feathered model masks where saddles essentially never occur.
 */
object MaskToPolygon {

    /** Trace cap: masks larger than this are downscaled first. */
    const val MAX_TRACE_DIM = 1024

    /** Ramer-Douglas-Peucker tolerance in (output) pixels. */
    const val DEFAULT_EPSILON = 1.5f

    /**
     * @param mask row-major binary mask, true = object
     * @return outer contour as polygon points in the SAME coordinate space
     *   as [mask]; empty list when nothing (or only degenerate noise) is
     *   found. Never throws.
     */
    fun trace(
        mask: BooleanArray,
        w: Int,
        h: Int,
        epsilon: Float = DEFAULT_EPSILON,
    ): List<Pt> {
        if (w <= 0 || h <= 0 || mask.size < w * h) return emptyList()

        // 1. downscale to the trace cap (nearest, keeps booleans exact).
        var src = mask
        var sw = w
        var sh = h
        val maxDim = maxOf(w, h)
        if (maxDim > MAX_TRACE_DIM) {
            val scale = maxDim.toFloat() / MAX_TRACE_DIM
            val nw = (w / scale).toInt().coerceAtLeast(1)
            val nh = (h / scale).toInt().coerceAtLeast(1)
            val small = BooleanArray(nw * nh)
            for (y in 0 until nh) {
                val sy = (y * h / nh).coerceIn(0, h - 1)
                for (x in 0 until nw) {
                    val sx = (x * w / nw).coerceIn(0, w - 1)
                    small[y * nw + x] = mask[sy * w + sx]
                }
            }
            src = small; sw = nw; sh = nh
        }

        // 2 + 3: marching squares segments, then chain into loops.
        val loops = chainLoops(marchingSegments(src, sw, sh))
        if (loops.isEmpty()) return emptyList()

        // Largest outer contour by |shoelace|.
        var best = loops[0]
        var bestArea = abs(shoelace(best))
        for (i in 1 until loops.size) {
            val a = abs(shoelace(loops[i]))
            if (a > bestArea) { best = loops[i]; bestArea = a }
        }
        if (bestArea < 1f) return emptyList()   // sub-pixel junk

        // Scale back to original coordinates when we downscaled.
        val out =
            if (sw != w || sh != h) {
                val sx = w.toFloat() / sw
                val sy = h.toFloat() / sh
                best.map { Pt(it.x * sx, it.y * sy) }
            } else best

        // 4. simplify; degenerate results (< 3 points) mean the region was
        // a thin line at this epsilon - fall back to the unsimplified loop.
        val simplified = rdpClosed(out, epsilon)
        return if (simplified.size >= 3) simplified else out
    }

    // ── marching squares ────────────────────────────────────────────────────

    private data class Seg(val k1: Long, val k2: Long, val p1: Pt, val p2: Pt)

    /** Doubled-lattice integer key for an edge midpoint. */
    private fun key(kx: Int, ky: Int): Long = (kx.toLong() shl 32) or (ky.toLong() and 0xffffffffL)

    private fun marchingSegments(mask: BooleanArray, w: Int, h: Int): List<Seg> {
        // Corners are pixel CENTERS: cell (i, j) has corners (i,j) (i+1,j)
        // (i+1,j+1) (i,j+1), pixel indices -1..w / -1..h so the image border
        // is traced too (out-of-bounds samples as empty).
        fun solid(x: Int, y: Int): Boolean =
            x in 0 until w && y in 0 until h && mask[y * w + x]

        val segs = ArrayList<Seg>()
        for (j in -1 until h) {
            for (i in -1 until w) {
                val tl = solid(i, j)
                val tr = solid(i + 1, j)
                val br = solid(i + 1, j + 1)
                val bl = solid(i, j + 1)
                val c = (if (tl) 1 else 0) or (if (tr) 2 else 0) or
                    (if (br) 4 else 0) or (if (bl) 8 else 0)
                if (c == 0 || c == 15) continue

                // Edge midpoints (pixel space) + doubled-lattice keys.
                // N between (i,j)-(i+1,j); E (i+1,j)-(i+1,j+1);
                // S (i,j+1)-(i+1,j+1); W (i,j)-(i,j+1).
                val nK = key(2 * i + 1, 2 * j)
                val eK = key(2 * i + 2, 2 * j + 1)
                val sK = key(2 * i + 1, 2 * j + 2)
                val wK = key(2 * i, 2 * j + 1)
                val nP = Pt(i + 1f, j + 0.5f)
                val eP = Pt(i + 1.5f, j + 1f)
                val sP = Pt(i + 1f, j + 1.5f)
                val wP = Pt(i + 0.5f, j + 1f)

                // Per-case segment table (interior = solid). Saddle cases
                // 5/10 resolved as separate 4-connected lobes (see header).
                fun add(aK: Long, aP: Pt, bK: Long, bP: Pt) {
                    segs.add(Seg(aK, bK, aP, bP))
                }
                when (c) {
                    1 -> add(wK, wP, nK, nP)
                    2 -> add(nK, nP, eK, eP)
                    3 -> add(wK, wP, eK, eP)
                    4 -> add(eK, eP, sK, sP)
                    5 -> { add(wK, wP, nK, nP); add(eK, eP, sK, sP) }
                    6 -> add(nK, nP, sK, sP)
                    7 -> add(wK, wP, sK, sP)
                    8 -> add(sK, sP, wK, wP)
                    9 -> add(nK, nP, sK, sP)
                    10 -> { add(nK, nP, eK, eP); add(sK, sP, wK, wP) }
                    11 -> add(eK, eP, sK, sP)
                    12 -> add(wK, wP, eK, eP)
                    13 -> add(nK, nP, eK, eP)
                    14 -> add(wK, wP, nK, nP)
                }
            }
        }
        return segs
    }

    /**
     * Chains segments into closed loops via shared edge keys.
     *
     * Each key has exactly 2 segments on a manifold grid (the two cells
     * sharing that edge both emit the crossing), so walking FORWARD from
     * one end of a start segment - at every key take the segment not yet
     * used - necessarily wraps the whole ring and terminates when the far
     * key returns to the start segment's OTHER end. Single direction, no
     * prepending: the emitted order is the ring order (a two-sided walk
     * folds the loop and corrupts the shoelace area).
     */
    private fun chainLoops(segs: List<Seg>): List<List<Pt>> {
        if (segs.isEmpty()) return emptyList()
        // key -> indices of segments touching it (max 2 in a manifold grid).
        val byKey = HashMap<Long, MutableList<Int>>(segs.size * 2)
        segs.forEachIndexed { idx, s ->
            byKey.getOrPut(s.k1) { ArrayList(2) }.add(idx)
            byKey.getOrPut(s.k2) { ArrayList(2) }.add(idx)
        }
        val used = BooleanArray(segs.size)
        val loops = ArrayList<List<Pt>>()
        for (start in segs.indices) {
            if (used[start]) continue
            used[start] = true
            val first = segs[start]
            val loop = ArrayList<Pt>()
            loop.add(first.p1)
            loop.add(first.p2)
            var curKey = first.k2
            var closed = false
            var steps = 0
            while (steps++ < segs.size) {
                val candidates = byKey[curKey] ?: break
                var next = -1
                for (ci in candidates) {
                    if (!used[ci]) { next = ci; break }
                }
                if (next < 0) break               // dead end (partial: drop)
                used[next] = true
                val s = segs[next]
                val atK1 = s.k1 == curKey
                val farKey = if (atK1) s.k2 else s.k1
                if (farKey == first.k1) {          // ring closed at origin
                    closed = true
                    break                          // its end point == loop[0]
                }
                loop.add(if (atK1) s.p2 else s.p1)
                curKey = farKey
            }
            if (closed && loop.size >= 3) loops.add(loop)
        }
        return loops
    }

    // ── geometry helpers ────────────────────────────────────────────────────

    private fun shoelace(pts: List<Pt>): Float {
        var a = 0f
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2f
    }

    /**
     * Ramer-Douglas-Peucker for a CLOSED polyline: anchors the first point,
     * simplifies the open chain to the last point, then drops the duplicate
     * closure. Iterative (explicit stack) so a pathological mask cannot
     * overflow the call stack.
     */
    fun rdpClosed(pts: List<Pt>, epsilon: Float): List<Pt> {
        val n = pts.size
        if (n <= 4) return dedupe(pts)

        // Closed-shape anchors: A = lexicographically extreme corner,
        // B = point farthest from A. Anchoring at pts[0]/pts[n-1] instead
        // would use two ADJACENT ring points as the chord, which slants
        // every simplified edge ~0.5px off the true (integer) boundary and
        // loses that much area; extreme-to-extreme chords run exactly
        // along real edges, so collinear edge points drop at distance 0
        // while true corners always exceed epsilon and survive.
        var a = 0
        for (i in 1 until n) {
            val p = pts[i]
            val q = pts[a]
            if (p.x < q.x || (p.x == q.x && p.y < q.y)) a = i
        }
        var b = if (a == 0) 1 else 0
        var best = -1.0
        for (i in 0 until n) {
            if (i == a) continue
            val dx = (pts[i].x - pts[a].x).toDouble()
            val dy = (pts[i].y - pts[a].y).toDouble()
            val d = dx * dx + dy * dy
            if (d > best) { best = d; b = i }
        }

        val keep = BooleanArray(n)
        keep[a] = true
        keep[b] = true
        simplifyChain(pts, a, b, keep, epsilon)
        simplifyChain(pts, b, a, keep, epsilon)

        // Emit in original ring order (both anchors already kept).
        val out = ArrayList<Pt>(n)
        for (i in 0 until n) if (keep[i]) out.add(pts[i])
        return dedupe(out)
    }

    /** Iterative RDP over the wrapped index chain [from..to] (inclusive). */
    private fun simplifyChain(
        pts: List<Pt>, from: Int, to: Int, keep: BooleanArray, epsilon: Float,
    ) {
        val n = pts.size
        val chain = ArrayList<Int>(n)
        var i = from
        var guard = 0
        while (guard++ <= n) {
            chain.add(i)
            if (i == to) break
            i = (i + 1) % n
        }
        if (chain.size <= 2) return
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, chain.size - 1))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            if (e <= s + 1) continue
            val ai = pts[chain[s]]
            val bi = pts[chain[e]]
            val abx = bi.x - ai.x
            val aby = bi.y - ai.y
            val abLen = kotlin.math.sqrt(abx * abx + aby * aby)
            var maxDist = -1f
            var maxIdx = -1
            for (k in s + 1 until e) {
                val p = pts[chain[k]]
                val d =
                    if (abLen < 1e-6f) {
                        val dx = p.x - ai.x
                        val dy = p.y - ai.y
                        kotlin.math.sqrt(dx * dx + dy * dy)
                    } else {
                        abs(abx * (ai.y - p.y) - (ai.x - p.x) * aby) / abLen
                    }
                if (d > maxDist) { maxDist = d; maxIdx = k }
            }
            if (maxDist > epsilon && maxIdx > 0) {
                keep[chain[maxIdx]] = true
                stack.addLast(intArrayOf(s, maxIdx))
                stack.addLast(intArrayOf(maxIdx, e))
            }
        }
    }

    /** Removes consecutive duplicates (and a trailing copy of the first). */
    private fun dedupe(pts: List<Pt>): List<Pt> {
        if (pts.isEmpty()) return pts
        val out = ArrayList<Pt>(pts.size)
        for (p in pts) {
            val last = out.lastOrNull()
            if (last == null || abs(last.x - p.x) > 1e-3f || abs(last.y - p.y) > 1e-3f) {
                out.add(p)
            }
        }
        while (out.size > 1) {
            val first = out.first()
            val last = out.last()
            if (abs(first.x - last.x) <= 1e-3f && abs(first.y - last.y) <= 1e-3f) {
                out.removeAt(out.size - 1)
            } else break
        }
        return out
    }
}
