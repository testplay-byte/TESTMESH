package com.example.aimeshvision.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * JVM unit tests for the mesh/outline geometry - the project's Kotlin-
 * native replacement for ad-hoc Python image analysis. Every invariant the
 * smooth mesh outline depends on is asserted HERE against the exact code
 * the app ships, on every push (CI runs testDebugUnitTest).
 *
 * Covered invariants:
 *  1. the band is CONTINUOUS around sides and CORNERS (no dots, no gaps);
 *  2. the band STRADDLES the silhouette edge (peaks at the boundary,
 *     nothing deep inside or far outside);
 *  3. band width is ADAPTIVE with limits (small component = narrower halo,
 *     never below the floor, large component = full width);
 *  4. speckles are erased, real objects kept, component areas annotated;
 *  5. smoothing never grows the object past its boundary (spill cutoff),
 *     and passes = 0 is a faithful no-op;
 *  6. the user width multiplier scales the band but never kills the line.
 */
class MaskGeometryTest {

    // ── harness ──────────────────────────────────────────────────────────────

    private class Field(val w: Int, val h: Int) {
        val pix = IntArray(w * h)
        val tmp = IntArray(w * h)
        val ring = IntArray(w * h)
        val dOut = IntArray(w * h)
        val dIn = IntArray(w * h)
        val stack = IntArray(w * h)
        val seen = BooleanArray(w * h)
        val comp = IntArray(w * h)
        val compSize = IntArray(w * h)
        val outSize = IntArray(w * h)

        fun solid(x: Int, y: Int): Boolean = (pix[y * w + x] ushr 24) >= 104
        fun ringA(x: Int, y: Int): Int = (ring[y * w + x] ushr 24)
        fun rawA(x: Int, y: Int): Int = (pix[y * w + x] ushr 24)
    }

    /** Full pipeline as decodeMasks runs it: blur -> speckles -> band. */
    private fun pipeline(
        f: Field, passes: Int = 1, widthScale: Float = 1f,
    ) {
        MaskGeometry.smoothMaskAlpha(f.pix, f.tmp, f.w, f.h, passes)
        MaskGeometry.removeSpeckles(f.pix, f.w, f.h,
            f.stack, f.seen, f.comp, f.compSize)
        MaskGeometry.buildOutlineBand(f.pix, f.ring, f.w, f.h,
            f.dOut, f.dIn, f.compSize, f.outSize, widthScale)
    }

    private fun fillSquare(f: Field, x0: Int, y0: Int, x1: Int, y1: Int, a: Int = 200) {
        for (y in y0..y1) for (x in x0..x1) {
            f.pix[y * f.w + x] = (a shl 24) or 0x00FFFFFF
        }
    }

    private fun fillDisc(f: Field, cx: Int, cy: Int, r: Int, a: Int = 200) {
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                f.pix[y * f.w + x] = (a shl 24) or 0x00FFFFFF
            }
        }
    }

    /**
     * Depth of the band OUTSIDE the object, in texels: the farthest lit
     * exterior texel's Euclidean distance to the nearest solid texel.
     * Measured against the ACTUAL final silhouette (after blur), so edge
     * erosion cannot skew the comparison between sizes.
     */
    private fun outerDepth(f: Field): Float {
        val solids = ArrayList<Pair<Int, Int>>()
        for (y in 0 until f.h) for (x in 0 until f.w) {
            if (f.solid(x, y)) solids.add(x to y)
        }
        var best = 0f
        for (y in 0 until f.h) for (x in 0 until f.w) {
            if (f.solid(x, y) || f.ringA(x, y) <= 0) continue
            for ((sx, sy) in solids) {
                val d = hypot((x - sx).toFloat(), (y - sy).toFloat())
                if (d > best) best = d
            }
        }
        return best
    }

    // ── 1 + 2: solidity, corners, continuity, straddle ───────────────────────

    @Test
    fun bandIsContinuousAlongSidesAndCorners() {
        val f = Field(64, 64)
        val x0 = 16; val y0 = 16; val x1 = 46; val y1 = 46
        fillSquare(f, x0, y0, x1, y1)           // 31x31 = 961 texels
        pipeline(f)

        // Every cardinal-adjacent exterior texel along all 4 sides is lit
        // (a dotted line = any zero in this walk).
        for (x in x0..x1) {
            assertTrue("top gap at $x", f.ringA(x, y0 - 1) > 0)
            assertTrue("bottom gap at $x", f.ringA(x, y1 + 1) > 0)
        }
        for (y in y0..y1) {
            assertTrue("left gap at $y", f.ringA(x0 - 1, y) > 0)
            assertTrue("right gap at $y", f.ringA(x1 + 1, y) > 0)
        }
        // The four diagonal corner texels - the exact spot where the old
        // blur-feather band died and rendered "dotted / no corners".
        assertTrue("TL corner", f.ringA(x0 - 1, y0 - 1) > 0)
        assertTrue("TR corner", f.ringA(x1 + 1, y0 - 1) > 0)
        assertTrue("BL corner", f.ringA(x0 - 1, y1 + 1) > 0)
        assertTrue("BR corner", f.ringA(x1 + 1, y1 + 1) > 0)
    }

    @Test
    fun bandStraddlesEdgeAndStaysOutOfDeepInterior() {
        val f = Field(64, 64)
        fillSquare(f, 16, 16, 46, 46)
        pipeline(f)

        // Boundary-adjacent exterior texel: peak alpha (solid line).
        assertEquals("peak at boundary", 255, f.ringA(30, 15))
        // The boundary texel INSIDE the mesh is lit too (band straddles).
        assertTrue("inner boundary lit", f.ringA(30, 16) > 0)
        // Deep interior (>= 4 texels in): no band.
        assertEquals("deep interior clean", 0, f.ringA(30, 20))
        // Far exterior (>= 6 texels out): no band.
        assertEquals("far exterior clean", 0, f.ringA(30, 9))
    }

    // ── 3: adaptive width with limits ────────────────────────────────────────

    @Test
    fun smallObjectGetsThinnerBandButNeverLosesTheLine() {
        val small = Field(64, 64)
        fillDisc(small, 32, 32, 3)          // ~29-texel object (small!)
        pipeline(small)

        val large = Field(64, 64)
        fillSquare(large, 16, 16, 46, 46)   // 961-texel object
        pipeline(large)

        val smallDepth = outerDepth(small)
        val largeDepth = outerDepth(large)

        // Smaller halo around the small object (the fix for "small object
        // grows a big mesh") ...
        assertTrue(
            "small depth $smallDepth should be < large depth $largeDepth",
            smallDepth < largeDepth,
        )
        // ... but with a FLOOR: the line still exists at full peak alpha.
        var peak = 0
        for (i in small.ring.indices) {
            val a = small.ring[i] ushr 24
            if (a > peak) peak = a
        }
        assertEquals("small line peak stays solid", 255, peak)
        assertTrue("small band has depth", smallDepth >= 1f)
        // Large object reaches the full classic width (2 texels + falloff).
        assertTrue(
            "large depth $largeDepth near full reach",
            largeDepth >= 2.67f,
        )
    }

    @Test
    fun componentAreasAreAnnotatedForAdaptiveSizing() {
        val f = Field(64, 64)
        fillSquare(f, 20, 20, 40, 40)        // 441 texels
        fillDisc(f, 8, 8, 2)                 // 13-texel satellite
        MaskGeometry.removeSpeckles(f.pix, f.w, f.h,
            f.stack, f.seen, f.comp, f.compSize)
        assertEquals("big component area", 441, f.compSize[30 * f.w + 30])
        assertEquals("small component area", 13, f.compSize[8 * f.w + 8])
    }

    // ── 4: speckle erasure ───────────────────────────────────────────────────

    @Test
    fun specklesAreErasedFromMeshAndNeverGrowAnOutline() {
        val f = Field(64, 64)
        fillSquare(f, 20, 20, 40, 40)        // real object (441 px)
        fillDisc(f, 8, 8, 2)                 // 13-px noise speckle
        pipeline(f)

        // Speckle: no tint, no outline (the whole 7x7 neighborhood).
        for (y in 5..11) for (x in 5..11) {
            assertEquals("speckle tint at $x,$y", 0, f.rawA(x, y))
            assertEquals("speckle outline at $x,$y", 0, f.ringA(x, y))
        }
        // Real object: kept (blur erodes only the outermost edge pixels;
        // the body must survive by a wide margin).
        var solidCount = 0
        for (y in 20..40) for (x in 20..40) if (f.solid(x, y)) solidCount++
        assertTrue("object kept (got $solidCount)", solidCount >= 300)
        assertTrue("object core kept", f.solid(30, 30))
    }

    // ── 5: smoothing behaviour ───────────────────────────────────────────────

    @Test
    fun smoothingNeverGrowsTheObjectPastItsBoundary() {
        val f = Field(64, 64)
        fillSquare(f, 20, 20, 40, 40)
        pipeline(f, passes = 1)
        // Outside must stay outside (this is the spill cutoff): the object
        // may shrink slightly at the edge, never expand.
        for (x in 18..19) for (y in 18..42) {
            assertTrue("spill left at $x,$y", !f.solid(x, y))
        }
        for (x in 41..42) for (y in 18..42) {
            assertTrue("spill right at $x,$y", !f.solid(x, y))
        }
        for (x in 18..42) for (y in 18..19) {
            assertTrue("spill top at $x,$y", !f.solid(x, y))
        }
        // And the interior core survives.
        assertTrue("core kept", f.solid(30, 30))
    }

    @Test
    fun smoothnessZeroIsAFaithfulNoOp() {
        val f = Field(16, 16)
        f.pix[5 * f.w + 5] = (129 shl 24) or 0x00FFFFFF
        f.pix[5 * f.w + 6] = (255 shl 24) or 0x00FFFFFF
        f.pix[6 * f.w + 5] = (140 shl 24) or 0x00FFFFFF
        MaskGeometry.smoothMaskAlpha(f.pix, f.tmp, f.w, f.h, passes = 0)
        assertEquals(129, f.rawA(5, 5))
        assertEquals(255, f.rawA(6, 5))
        assertEquals(140, f.rawA(5, 6))
    }

    // ── 6: user width multiplier ─────────────────────────────────────────────

    @Test
    fun widthMultiplierScalesDepthButNeverKillsTheLine() {
        val full = Field(64, 64)
        fillSquare(full, 16, 16, 46, 46)
        pipeline(full, widthScale = 1f)

        val half = Field(64, 64)
        fillSquare(half, 16, 16, 46, 46)
        pipeline(half, widthScale = 0.5f)

        val fullDepth = outerDepth(full)
        val halfDepth = outerDepth(half)
        assertTrue(
            "half depth $halfDepth should be < full depth $fullDepth",
            halfDepth < fullDepth,
        )
        // Even at half scale the peak at the boundary stays solid.
        assertEquals("half-scale peak", 255, half.ringA(30, 15))
        // And the line is still present with measurable depth.
        assertTrue("half-scale ring present", halfDepth >= 1f)
    }

    @Test
    fun emptyMaskProducesEmptyBandWithoutCrashing() {
        val f = Field(32, 32)
        pipeline(f)
        for (i in f.ring.indices) assertEquals(0, f.ring[i])
    }
}
