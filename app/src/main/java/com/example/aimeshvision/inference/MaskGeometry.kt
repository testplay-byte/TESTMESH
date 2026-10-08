package com.example.aimeshvision.inference

import kotlin.math.sqrt

/**
 * Pure, framework-free geometry of the mesh silhouette and the smooth mesh
 * outline band. Everything here operates on plain [IntArray] alpha fields
 * (white RGB, alpha byte = coverage) - no Android types - so the WHOLE
 * outline pipeline is verifiable with plain JVM unit tests
 * (`MaskGeometryTest`), in CI, on every push. That test suite is the
 * project's replacement for ad-hoc Python image analysis: it asserts the
 * actual geometric invariants of the band (solidity, straddle, adaptive
 * width, speckle erasure) directly against the shipped Kotlin code.
 *
 * Pipeline order (driven by [YoloPostProcessor.decodeMasks]):
 *  1. [smoothMaskAlpha]  - blur passes + spill cutoff -> mesh silhouette
 *  2. [removeSpeckles]   - erase noise blobs, annotate component sizes
 *  3. [buildOutlineBand] - distance ramp around the FINAL silhouette
 *
 * Invariants (each covered by a test):
 *  - ONE boundary definition serves mesh AND outline (the cutoff level
 *    set), so double lines / gaps between the two layers are unrepresentable;
 *  - the band peaks 255 AT the boundary and straddles it (half in, half
 *    out), so it is solid around corners and ON the edge by construction;
 *  - band width is ADAPTIVE to component size with hard min/max limits -
 *    small objects get a proportionally thinner halo (the "small object
 *    grows a huge mesh" report) but never thinner than the floor, large
 *    objects keep the full classic width.
 */
object MaskGeometry {

    // ── MESH SILHOUETTE ─────────────────────────────────────────────────────

    /**
     * Spill cutoff: after each smoothing pass, alpha below this is zeroed.
     * One separable 3x3 pass spreads OUTSIDE the object by at most
     * (255+0+0)/3 = 85, while genuine interior edge pixels start at 113 -
     * so 104 provably removes the entire outside feather while keeping
     * every interior gradient pixel (85 < 104 < 113). The mesh silhouette
     * is exactly this level set; the outline band derives from it.
     */
    const val MESH_ALPHA_CUTOFF = 104

    /** Max blur passes the "mesh smoothness" knob can request (settings). */
    const val MAX_SMOOTH_PASSES = 2

    // ── OUTLINE BAND WIDTH (adaptive, with limits) ──────────────────────────

    /**
     * Full band half-widths in texels at [BAND_FULL_RADIUS] and above:
     * outer ramp fades over [RING_OUT_TEXELS] outside the silhouette,
     * inner ramp over [RING_IN_TEXELS] inside it (the band straddles the
     * edge, peak 255 at the boundary texel).
     */
    const val RING_OUT_TEXELS = 2f
    const val RING_IN_TEXELS = 1f

    /**
     * Floor for the adaptive ramp (the "smaller, but not too small - there
     * should be a limit" requirement): a component of any size keeps at
     * least this half-width, so the line never disappears or breaks on
     * small objects. [ABS_MIN_REACH] is the last-resort clamp after the
     * user's outline-width multiplier.
     */
    const val RING_OUT_MIN_TEXELS = 1f
    const val RING_IN_MIN_TEXELS = 0.5f
    private const val ABS_MIN_REACH = 0.4f

    /**
     * Component radius (texels) at which the band reaches full width.
     * Reach interpolates linearly from the floor at radius 0 to the full
     * width at this radius: radius = sqrt(area/PI), so a ~450-texel object
     * (~24x24) gets the full classic band, while a 40-texel object gets a
     * halo roughly half as deep instead of the old fixed 2-texel halo that
     * dwarfed it on screen (each texel is ~8-16 screen px).
     */
    const val BAND_FULL_RADIUS = 12f
    private const val INV_PI = 0.31830988f

    // ── SPECKLE FILTER ──────────────────────────────────────────────────────

    /**
     * A component below this fraction of the LARGEST component (absolute
     * floor [MIN_SPECKLE_PX]) is erased from the mask - proto noise
     * produces small satellite blobs that would tint and outline
     * anything. Genuine same-size objects stay far above the floor.
     */
    const val SPECKLE_FRACTION = 0.10f
    const val MIN_SPECKLE_PX = 12

    /** Shared solid-pixel predicate: alpha at or above the spill cutoff. */
    private fun isSolid(pix: IntArray, i: Int): Boolean =
        (pix[i] ushr 24) >= MESH_ALPHA_CUTOFF

    // ── 1. smoothing + cutoff ───────────────────────────────────────────────

    /**
     * Mesh smoothing: [passes] rounds of ONE separable 3x3 box-blur pass
     * over the alpha bytes of [pix] (white RGB preserved), each round
     * ending with the [MESH_ALPHA_CUTOFF] spill cutoff:
     *
     *  - passes = 0: no smoothing - the raw graded mask (crisp but
     *    staircase-edged on the 128px proto grid);
     *  - passes = 1 (default): anti-aliased edge, bounded ~1-texel feather
     *    that the cutoff then snaps back to the object boundary;
     *  - passes = 2 ([MAX_SMOOTH_PASSES]): visibly smoother edge, at the
     *    cost of eroding sub-3px features and merging 1px gaps - the
     *    documented trade-off behind the settings "Mesh Smoothness" knob.
     *
     * The cutoff runs after EVERY pass so the outside feather can never
     * accumulate past 104 across passes (an uncutoff second pass would let
     * interior 255 bleed outward: (255+85+0)/3 = 113 > 104 = spill).
     *
     * Uses [tmp] as row-pass scratch (same length as [pix], fully rewritten
     * each pass, so stale contents are never read).
     */
    fun smoothMaskAlpha(
        pix: IntArray, tmp: IntArray, w: Int, h: Int, passes: Int,
    ) {
        val rounds = passes.coerceIn(0, MAX_SMOOTH_PASSES)
        for (round in 0 until rounds) {
            // Horizontal pass: pix -> tmp (edge pixels duplicate the neighbor).
            for (y in 0 until h) {
                val r = y * w
                var a0 = pix[r] ushr 24
                for (x in 0 until w) {
                    val a1 = pix[r + x] ushr 24
                    val a2 = if (x + 1 < w) pix[r + x + 1] ushr 24 else a1
                    val av = (a0 + a1 + a2) / 3
                    tmp[r + x] = if (av > 0) (av shl 24) or 0x00FFFFFF else 0
                    a0 = a1
                }
            }
            // Vertical pass: tmp -> pix, with the spill cutoff (snaps the
            // tint edge back to the object boundary).
            for (x in 0 until w) {
                var a0 = tmp[x] ushr 24
                for (y in 0 until h) {
                    val i = y * w + x
                    val a1 = tmp[i] ushr 24
                    val a2 = if (y + 1 < h) tmp[(y + 1) * w + x] ushr 24 else a1
                    val av = (a0 + a1 + a2) / 3
                    pix[i] =
                        if (av >= MESH_ALPHA_CUTOFF) (av shl 24) or 0x00FFFFFF
                        else 0
                    a0 = a1
                }
            }
        }
    }

    // ── 2. speckle erasure + component annotation ───────────────────────────

    /**
     * Erases noise-speckle components (below [SPECKLE_FRACTION] of the
     * largest component, floor [MIN_SPECKLE_PX]) from the mesh [pix], and
     * annotates every solid component's area into [compSize] (0 elsewhere).
     *
     * [compSize] is the size signal the adaptive band width is built on:
     * pass 1 (which floods every component anyway to find the largest)
     * stamps each texel with its component's area before the floor is even
     * decided - so even the sub-floor survivors (<= 11 px, kept on
     * purpose) carry a correct size.
     *
     * Two cheap sweeps over the ~16k-texel mask (background thread), no
     * allocation: caller provides [stack]/[seen]/[comp] scratch.
     * The outline band is built AFTER this pass, so erased speckles never
     * grow an outline either.
     */
    fun removeSpeckles(
        pix: IntArray, w: Int, h: Int,
        stack: IntArray, seen: BooleanArray, comp: IntArray,
        compSize: IntArray,
    ) {
        val n = w * h

        // Pass 1: largest component size + per-texel component area.
        java.util.Arrays.fill(seen, false)
        var maxPx = 0
        for (start in 0 until n) {
            if (!isSolid(pix, start) || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var cc = 0
            while (sp > 0) {
                val j = stack[--sp]
                comp[cc++] = j
                val x = j % w
                val y = j / w
                if (x > 0) { val k = j - 1; if (isSolid(pix, k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (x < w - 1) { val k = j + 1; if (isSolid(pix, k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y > 0) { val k = j - w; if (isSolid(pix, k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y < h - 1) { val k = j + w; if (isSolid(pix, k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
            }
            for (i in 0 until cc) compSize[comp[i]] = cc
            if (cc > maxPx) maxPx = cc
        }
        val floor = maxOf(MIN_SPECKLE_PX, (maxPx * SPECKLE_FRACTION).toInt())
        if (maxPx < floor) return

        // Pass 2: erase every component below the floor (mesh only - their
        // compSize entries become unreachable since pix is zeroed).
        java.util.Arrays.fill(seen, false)
        for (start in 0 until n) {
            if (!isSolid(pix, start) || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var cc = 0
            while (sp > 0) {
                val j = stack[--sp]
                comp[cc++] = j
                val x = j % w
                val y = j / w
                if (x > 0) { val k = j - 1; if (isSolid(pix, k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (x < w - 1) { val k = j + 1; if (isSolid(pix, k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y > 0) { val k = j - w; if (isSolid(pix, k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y < h - 1) { val k = j + w; if (isSolid(pix, k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
            }
            if (cc < floor) {
                for (i in 0 until cc) pix[comp[i]] = 0
            }
        }
    }

    // ── 3. outline band ─────────────────────────────────────────────────────

    /**
     * Builds the outline band around the mesh silhouette in [pix] (alpha >=
     * [MESH_ALPHA_CUTOFF] = solid): a 3-4 chamfer distance transform from
     * the boundary on BOTH sides, converted to an alpha ramp that peaks AT
     * the boundary (255) and fades over an ADAPTIVE reach:
     *
     *   reach = floor + (full - floor) * clamp01(radius / BAND_FULL_RADIUS)
     *   radius = sqrt(componentArea / PI),  then * [widthScale] (settings)
     *
     * - Exterior texels use the size of the NEAREST component: the owner
     *   area is propagated through the same chamfer sweeps (when a
     *   distance improves from a neighbor, that neighbor's size comes
     *   along), seeded from [compSize] on mesh texels. Interior texels
     *   read [compSize] directly.
     * - Distance-based (NOT harvested from the blur's exterior feather):
     *   the separable box blur only spreads along axes, so its feather
     *   dies at corners/diagonals - that rendered the first band attempt
     *   as a dotted line missing corners (device report). Every texel
     *   within reach gets a value here by construction.
     * - Adaptive sizing fixes the "small object grows a big mesh" report:
     *   a 40-texel blob gets roughly half the halo depth of a 1500-texel
     *   one, floored at [RING_OUT_MIN_TEXELS]/[RING_IN_MIN_TEXELS] so the
     *   line stays visible and continuous at any size.
     *
 * Cost: 4 sweep passes + 1 write pass over w*h (16k texels) on the
     * inference thread, zero allocation (caller scratch [dOut]/[dIn]/
     * [outSize]).
     *
     * @param compSize per-texel component area from [removeSpeckles]
     * @param outSize  scratch: nearest-component area for exterior texels
     * @param widthScale user outline-width multiplier (>= 0), applied last
     */
    fun buildOutlineBand(
        pix: IntArray, ring: IntArray, w: Int, h: Int,
        dOut: IntArray, dIn: IntArray,
        compSize: IntArray, outSize: IntArray,
        widthScale: Float,
    ) {
        val n = w * h
        val BIG = 0x3fffffff
        // Seeds: distance-to-mesh starts 0 ON the mesh (owner area =
        // component area); distance-to-exterior starts 0 OFF the mesh.
        java.util.Arrays.fill(dOut, BIG)
        java.util.Arrays.fill(dIn, BIG)
        java.util.Arrays.fill(outSize, 0)
        for (i in 0 until n) {
            if (isSolid(pix, i)) {
                dOut[i] = 0
                outSize[i] = compSize[i]
            } else {
                dIn[i] = 0
            }
        }

        // 3-4 chamfer: cardinals cost 3, diagonals 4 (texel unit = 3).
        // Forward sweep: propagate distance AND nearest-owner area from
        // top/left neighbours...
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var o = dOut[i]
                var d = dIn[i]
                var s = outSize[i]
                if (x > 0) {
                    val k = i - 1
                    val t = dOut[k] + 3
                    if (t < o) { o = t; s = outSize[k] }
                    val u = dIn[k] + 3; if (u < d) d = u
                }
                if (y > 0) {
                    val k = i - w
                    var t = dOut[k] + 3
                    if (t < o) { o = t; s = outSize[k] }
                    var u = dIn[k] + 3; if (u < d) d = u
                    if (x > 0) {
                        val k2 = k - 1
                        t = dOut[k2] + 4; if (t < o) { o = t; s = outSize[k2] }
                        u = dIn[k2] + 4; if (u < d) d = u
                    }
                    if (x < w - 1) {
                        val k2 = k + 1
                        t = dOut[k2] + 4; if (t < o) { o = t; s = outSize[k2] }
                        u = dIn[k2] + 4; if (u < d) d = u
                    }
                }
                dOut[i] = o
                dIn[i] = d
                outSize[i] = s
            }
        }
        // ... then a backward sweep from bottom/right neighbours completes it.
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                var o = dOut[i]
                var d = dIn[i]
                var s = outSize[i]
                if (x < w - 1) {
                    val k = i + 1
                    val t = dOut[k] + 3
                    if (t < o) { o = t; s = outSize[k] }
                    val u = dIn[k] + 3; if (u < d) d = u
                }
                if (y < h - 1) {
                    val k = i + w
                    var t = dOut[k] + 3
                    if (t < o) { o = t; s = outSize[k] }
                    var u = dIn[k] + 3; if (u < d) d = u
                    if (x < w - 1) {
                        val k2 = k + 1
                        t = dOut[k2] + 4; if (t < o) { o = t; s = outSize[k2] }
                        u = dIn[k2] + 4; if (u < d) d = u
                    }
                    if (x > 0) {
                        val k2 = k - 1
                        t = dOut[k2] + 4; if (t < o) { o = t; s = outSize[k2] }
                        u = dIn[k2] + 4; if (u < d) d = u
                    }
                }
                dOut[i] = o
                dIn[i] = d
                outSize[i] = s
            }
        }

        // Convert distances to an alpha ramp peaking at the boundary:
        //   alpha(t) = 255 * clamp01((reach + 1 - t) / reach),  t in texels.
        // The peak (t = 1 -> 255) is independent of reach, so even the
        // narrowest adaptive band keeps a solid, unbroken line.
        val scale = if (widthScale > 0f) widthScale else 1f
        for (i in 0 until n) {
            val solid = isSolid(pix, i)
            val dist = if (solid) dIn[i] else dOut[i]
            if (dist <= 0 || dist >= BIG) continue   // wrong side / unreached
            val t = dist / 3f
            val size = if (solid) compSize[i] else outSize[i]
            val k =
                if (size <= 0) 0f
                else (sqrt(size * INV_PI) / BAND_FULL_RADIUS).coerceAtMost(1f)
            val reach =
                if (solid) {
                    (RING_IN_MIN_TEXELS +
                        (RING_IN_TEXELS - RING_IN_MIN_TEXELS) * k) * scale
                } else {
                    (RING_OUT_MIN_TEXELS +
                        (RING_OUT_TEXELS - RING_OUT_MIN_TEXELS) * k) * scale
                }.coerceAtLeast(ABS_MIN_REACH)
            if (t > reach + 1f) continue
            val ra = (255f * ((reach + 1f - t) / reach)).toInt().coerceIn(0, 255)
            if (ra > 0) ring[i] = (ra shl 24) or 0x00FFFFFF
        }
    }
}
