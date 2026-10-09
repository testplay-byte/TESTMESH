package com.example.aimeshvision.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
 *  3. the band lives on the FINE raster ([MaskGeometry.RING_UPSCALE] x the
 *     mesh grid) and is contiguous across the edge (the "outline must not
 *     share the mesh's low resolution" requirement);
 *  4. band width is ADAPTIVE with limits (small component = narrower halo,
 *     never below the floor, large component = full width);
 *  5. speckles are erased, real objects kept, component areas annotated;
 *  6. smoothing never grows the object past its boundary (spill cutoff),
 *     and passes = 0 is a faithful no-op;
 *  7. the user width multiplier scales the band but never kills the line.
 *
 * Coordinate conventions: the MESH ([Field.pix]) lives on the coarse grid
 * (w x h); the RING lives on the fine grid (w*S x h*S). [Field.ringAt]
 * probes the fine raster at the center of a coarse texel so assertions
 * stay readable in coarse coordinates.
 */
class MaskGeometryTest {

    private val s = MaskGeometry.RING_UPSCALE

    // ── harness ──────────────────────────────────────────────────────────────

    private class Field(val w: Int, val h: Int, val s: Int) {
        val pix = IntArray(w * h)
        val tmp = IntArray(w * h)
        val fw = w * s
        val fh = h * s
        val ring = IntArray(fw * fh)          // FINE raster
        val dOut = IntArray(w * h)
        val dIn = IntArray(w * h)
        val stack = IntArray(w * h)
        val seen = BooleanArray(w * h)
        val comp = IntArray(w * h)
        val compSize = IntArray(w * h)
        val outSize = IntArray(w * h)

        fun solid(x: Int, y: Int): Boolean = (pix[y * w + x] ushr 24) >= 104
        fun rawA(x: Int, y: Int): Int = (pix[y * w + x] ushr 24)

        /** Fine-raster alpha at the center of coarse texel (x, y). */
        fun ringAt(x: Int, y: Int): Int =
            (ring[(y * s + s / 2) * fw + (x * s + s / 2)] ushr 24)

        fun fineA(fi: Int): Int = (ring[fi] ushr 24)

        fun maxRingAlpha(): Int {
            var m = 0
            for (v in ring) {
                val a = v ushr 24
                if (a > m) m = a
            }
            return m
        }
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

    private fun field() = Field(64, 64, s)

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
     * Depth of the band OUTSIDE the object, in FINE texels: the farthest
     * lit exterior fine texel's distance to the NEAREST solid coarse texel
     * center (each lit texel first finds its own nearest center - the max
     * is then taken over those minima).
     */
    private fun outerDepth(f: Field): Float {
        val cs = s.toFloat()
        val solids = ArrayList<Pair<Float, Float>>()
        for (y in 0 until f.h) for (x in 0 until f.w) {
            if (f.solid(x, y)) {
                // coarse center -> fine coordinate: S*(c+0.5) mapped
                // through the raster's c = (2i-(S-1))/(2S) = x*S + S - 0.5
                solids.add(x * cs + cs - 0.5f to y * cs + cs - 0.5f)
            }
        }
        var best = 0f
        for (j in 0 until f.fh) for (i in 0 until f.fw) {
            if (f.solid(i / s, j / s) || f.fineA(j * f.fw + i) <= 0) continue
            var nearest = Float.MAX_VALUE
            for ((sx, sy) in solids) {
                val dx = i - sx
                val dy = j - sy
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d < nearest) nearest = d
            }
            if (nearest > best) best = nearest
        }
        return best
    }

    // ── 3 (resolution): the fine raster itself ──────────────────────────────

    @Test
    fun ringRasterIsFinerThanTheMeshGrid() {
        val f = field()
        fillSquare(f, 16, 16, 46, 46)
        pipeline(f)
        assertTrue("RING_UPSCALE must be >= 2", s >= 2)
        assertEquals("ring width", f.w * s, f.fw)
        assertTrue("ring is ${f.ring.size} px, mesh is ${f.pix.size} px",
            f.ring.size == f.pix.size * s * s)
        // And the raster is actually populated (not an empty buffer).
        assertTrue("band drawn", f.maxRingAlpha() > 0)
    }

    @Test
    fun rampIsContiguousAcrossTheEdgeOnTheFineRaster() {
        val f = field()
        fillSquare(f, 16, 16, 46, 46)
        pipeline(f)

        // Vertical fine column through coarse x=30 crosses the top edge
        // (mesh boundary at y=16.0 -> fine index (16*4)+1.5 = 65.5).
        // Scan only a window around THAT edge - the same column also
        // crosses the square's bottom edge, and the deep interior between
        // them is CORRECTLY band-free.
        val col = (30 * s + s / 2)
        val boundary = 16f * s + (s - 1) / 2f
        val lo = (boundary - 4f * s).toInt().coerceAtLeast(0)
        val hi = (boundary + 4f * s).toInt().coerceAtMost(f.fh - 1)
        var first = -1
        var last = -1
        for (j in lo..hi) {
            if (f.fineA(j * f.fw + col) > 0) {
                if (first < 0) first = j
                last = j
            }
        }
        assertTrue("no band found near top edge", first >= 0 && last >= 0)
        // Contiguity: every fine texel between first and last is lit.
        var internalZeros = 0
        for (j in first..last) {
            if (f.fineA(j * f.fw + col) <= 0) internalZeros++
        }
        assertEquals("ramp has internal gaps", 0, internalZeros)
        // The lit run straddles the boundary (both sides present)...
        assertTrue("run [$first..$last] must cover boundary $boundary",
            first < boundary && last > boundary)
        // ...with depth inside the profile envelope (ROUND-24 straddle):
        // extents are 0.5..3 coarse + LINE_FEATHER tail; measured from the
        // boundary (half-texel), per side <= extent + tail + 0.5 coarse.
        assertTrue("outer depth too small: ${boundary - first}",
            boundary - first >= 1.5f * s)
        assertTrue("inner depth too small: ${last - boundary}",
            last - boundary >= 0.75f * s)
        assertTrue("run too deep outward", boundary - first <= 4.4f * s)
        assertTrue("run too deep inward", last - boundary <= 2.5f * s)
    }

    // ── 1: solidity, corners, continuity ────────────────────────────────────

    @Test
    fun bandIsContinuousAlongSidesAndCorners() {
        val f = field()
        val x0 = 16; val y0 = 16; val x1 = 46; val y1 = 46
        fillSquare(f, x0, y0, x1, y1)           // 31x31 = 961 texels
        pipeline(f)

        // Every boundary-adjacent coarse texel along all 4 sides is lit
        // (a dotted line = any zero in this walk).
        for (x in x0..x1) {
            assertTrue("top gap at $x", f.ringAt(x, y0 - 1) > 0)
            assertTrue("bottom gap at $x", f.ringAt(x, y1 + 1) > 0)
        }
        for (y in y0..y1) {
            assertTrue("left gap at $y", f.ringAt(x0 - 1, y) > 0)
            assertTrue("right gap at $y", f.ringAt(x1 + 1, y) > 0)
        }
        // The four diagonal corner texels - the exact spot where the old
        // blur-feather band died and rendered "dotted / no corners".
        assertTrue("TL corner", f.ringAt(x0 - 1, y0 - 1) > 0)
        assertTrue("TR corner", f.ringAt(x1 + 1, y0 - 1) > 0)
        assertTrue("BL corner", f.ringAt(x0 - 1, y1 + 1) > 0)
        assertTrue("BR corner", f.ringAt(x1 + 1, y1 + 1) > 0)
    }

    @Test
    fun bandStraddlesEdgeAndStaysOutOfDeepInterior() {
        val f = field()
        fillSquare(f, 16, 16, 46, 46)
        pipeline(f)

        // Peak alpha (solid line) exists SOMEWHERE on the band.
        assertEquals("peak at boundary", 255, f.maxRingAlpha())
        // Boundary-adjacent exterior AND interior texels are lit.
        assertTrue("outer boundary lit", f.ringAt(30, 15) > 0)
        assertTrue("inner boundary lit", f.ringAt(30, 16) > 0)
        // Deep interior (>= 4 texels in): no band.
        assertEquals("deep interior clean", 0, f.ringAt(30, 20))
        // Far exterior (>= 6 texels out): no band.
        assertEquals("far exterior clean", 0, f.ringAt(30, 9))
    }

    // ── 4: adaptive width with limits ────────────────────────────────────────

    @Test
    fun smallObjectGetsThinnerBandButNeverLosesTheLine() {
        val small = field()
        fillDisc(small, 32, 32, 3)          // ~29-texel object (small!)
        pipeline(small)

        val large = field()
        fillSquare(large, 16, 16, 46, 46)   // 961-texel object
        pipeline(large)

        val smallDepth = outerDepth(small)   // FINE texels
        val largeDepth = outerDepth(large)

        // Smaller halo around the small object (the fix for "small object
        // grows a big mesh") ...
        assertTrue(
            "small depth $smallDepth should be < large depth $largeDepth",
            smallDepth < largeDepth,
        )
        // ... but with a FLOOR: the line still exists at full peak alpha.
        assertEquals("small line peak stays solid", 255, small.maxRingAlpha())
        assertTrue("small band has depth", smallDepth >= 1f)
        // Large object reaches the full classic width: extent 2 coarse
        // texels from the boundary (0.5 from the first solid centre) =
        // ~2.47 coarse ~= 9.9 FINE texels of depth.
        assertTrue(
            "large depth $largeDepth near full reach",
            largeDepth >= 2.25f * s,
        )
    }

    @Test
    fun componentAreasAreAnnotatedForAdaptiveSizing() {
        val f = field()
        fillSquare(f, 20, 20, 40, 40)        // 441 texels
        fillDisc(f, 8, 8, 2)                 // 13-texel satellite
        MaskGeometry.removeSpeckles(f.pix, f.w, f.h,
            f.stack, f.seen, f.comp, f.compSize)
        assertEquals("big component area", 441, f.compSize[30 * f.w + 30])
        assertEquals("small component area", 13, f.compSize[8 * f.w + 8])
    }

    // ── 5: speckle erasure ───────────────────────────────────────────────────

    @Test
    fun specklesAreErasedFromMeshAndNeverGrowAnOutline() {
        val f = field()
        fillSquare(f, 20, 20, 40, 40)        // real object (441 px)
        fillDisc(f, 8, 8, 2)                 // 13-px noise speckle
        pipeline(f)

        // Speckle: no tint, no outline (the whole 7x7 neighborhood, on
        // BOTH rasters - mesh coarse, band fine).
        for (y in 5..11) for (x in 5..11) {
            assertEquals("speckle tint at $x,$y", 0, f.rawA(x, y))
            assertEquals("speckle outline at $x,$y", 0, f.ringAt(x, y))
        }
        // Real object: kept (blur erodes only the outermost edge pixels;
        // the body must survive by a wide margin).
        var solidCount = 0
        for (y in 20..40) for (x in 20..40) if (f.solid(x, y)) solidCount++
        assertTrue("object kept (got $solidCount)", solidCount >= 300)
        assertTrue("object core kept", f.solid(30, 30))
    }

    // ── 6: smoothing behaviour ───────────────────────────────────────────────

    @Test
    fun smoothingNeverGrowsTheObjectPastItsBoundary() {
        val f = field()
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
        val f = Field(16, 16, s)
        f.pix[5 * f.w + 5] = (129 shl 24) or 0x00FFFFFF
        f.pix[5 * f.w + 6] = (255 shl 24) or 0x00FFFFFF
        f.pix[6 * f.w + 5] = (140 shl 24) or 0x00FFFFFF
        MaskGeometry.smoothMaskAlpha(f.pix, f.tmp, f.w, f.h, passes = 0)
        assertEquals(129, f.rawA(5, 5))
        assertEquals(255, f.rawA(6, 5))
        assertEquals(140, f.rawA(5, 6))
    }

    // ── 7: user width multiplier ─────────────────────────────────────────────

    @Test
    fun widthMultiplierScalesDepthButNeverKillsTheLine() {
        val full = field()
        fillSquare(full, 16, 16, 46, 46)
        pipeline(full, widthScale = 1f)

        val half = field()
        fillSquare(half, 16, 16, 46, 46)
        pipeline(half, widthScale = 0.5f)

        val fullDepth = outerDepth(full)
        val halfDepth = outerDepth(half)
        assertTrue(
            "half depth $halfDepth should be < full depth $fullDepth",
            halfDepth < fullDepth,
        )
        // Even at half scale the peak at the boundary stays solid.
        assertEquals("half-scale peak", 255, half.maxRingAlpha())
        // And the line is still present with measurable depth.
        assertTrue("half-scale ring present", halfDepth >= 1f)
    }

    @Test
    fun emptyMaskProducesEmptyBandWithoutCrashing() {
        val f = field()
        pipeline(f)
        for (i in f.ring.indices) assertEquals(0, f.ring[i])
    }

    // ── device reports (round 22): sharpness + thin minimum width ──────────

    @Test
    fun lineIsASingleMonotoneStrokeAcrossTheEdge() {
        val f = field()
        fillSquare(f, 16, 16, 46, 46)
        pipeline(f, widthScale = 1f)

        val col = (30 * s + s / 2)
        val boundary = 16f * s + (s - 1) / 2f
        val lo = (boundary - 4f * s).toInt().coerceAtLeast(0)
        val hi = (boundary + 4f * s).toInt().coerceAtMost(f.fh - 1)

        val alphas = (lo..hi).map { f.fineA(it * f.fw + col) }
        // Peak exists ON the straddling line.
        assertEquals("peak 255", 255, alphas.max())
        // ONE local maximum only - the "built with multiple lines" bug was
        // two separate bright bands (one per side) around the silhouette.
        // ONE bright REGION (contiguous run of alpha >= 200) - the old
        // per-side-peak design produced TWO such regions with a dark
        // hole between ("built with multiple lines"). Counting runs
        // rather than strict turning points, so a plateau of equal
        // values still reads as one region.
        var brightRuns = 0
        var inRun = false
        for (a in alphas) {
            val bright = a >= 200
            if (bright && !inRun) { brightRuns++; inRun = true }
            if (!bright) inRun = false
        }
        assertEquals("exactly ONE line peak (not two bands)", 1, brightRuns)
        // Contiguous: no internal 0-gap (holes = dashes / multiple
        // segment illusion).
        val first = alphas.indexOfFirst { it > 0 }
        val last = alphas.indexOfLast { it > 0 }
        assertTrue("no lit run found", first in 0 until last)
        for (j in first..last) {
            assertTrue("internal gap at $j", alphas[j] > 0)
        }
        // Monotone fall OUTWARD from the peak: no ringing / secondary
        // bumps (a strict-edge gradient has that signature).
        val peakIdx = alphas.withIndex().maxBy { it.value }.index
        for (j in peakIdx until last + 1) {
            if (j > peakIdx) {
                assertTrue("outward ringing at $j", alphas[j] <= alphas[j - 1])
            }
        }
    }

    @Test
    fun profileKernelStraddlesAtBoundaryWithoutSecondPeak() {
        // Round-24 kernel truths (the plaid old design's death warrant):
        // 1. alpha 255 AT the fitted boundary (fd = 0), monotonically
        //    falls per side - there is never a second bright band at the
        //    per-side extent (that produced two lines + a dark hole).
        assertEquals(255, MaskGeometry.profileAlpha(0f, 3f, 1f))
        var prev = 255
        for (i in 1..140) {
            val d = i * 0.05f
            val a = MaskGeometry.profileAlpha(d, 3f, 1f)
            assertTrue("must not rise past boundary: d=$d a=$a prev=$prev", a <= prev)
            prev = a
        }
        // 2. At the OLD per-side peak location (extent), alpha has already
        //    fallen well below max - no remnant plateau.
        val atExtent = MaskGeometry.profileAlpha(3f, 3f, 1f)
        assertTrue("alpha at extent must be mid-fall, got $atExtent", atExtent in 1..150)
        // 3. Tail ends exactly at extent + LINE_FEATHER * min(scale,1):
        //    zero beyond, and the <10% scale REALLY thins (device: hairline).
        assertEquals(0, MaskGeometry.profileAlpha(3.95f, 3f, 1f))
        assertEquals(0, MaskGeometry.profileAlpha(3.2f, 3f, 0.1f))
        // 4. A narrow body's deep interior is line-free: fingers keep tint
        //    ("mesh must stay visible").
        assertEquals(0, MaskGeometry.profileAlpha(1.9f, 0.5f, 1f))
        assertEquals(0, MaskGeometry.profileAlpha(6f, 3f, 1f))
    }

    @Test
    fun tenPercentWidthIsHairlineButStillASolidLine() {
        val full = field()
        fillSquare(full, 16, 16, 46, 46)
        pipeline(full, widthScale = 1f)

        val hair = field()
        fillSquare(hair, 16, 16, 46, 46)
        pipeline(hair, widthScale = 0.1f)   // slider minimum (10%)

        // The 10% line must be MUCH thinner than default...
        val fullDepth = outerDepth(full)
        val hairDepth = outerDepth(hair)
        assertTrue(
            "hairline depth $hairDepth not thinner than default $fullDepth",
            hairDepth < fullDepth * 0.5f,
        )
        // ...but still a real, solid line: peak alpha 255 and measurable
        // depth (the MIN_EXTENT floor keeps it visible).
        assertEquals("hairline peak stays solid", 255, hair.maxRingAlpha())
        assertTrue("hairline has depth", hairDepth >= 1f)
        // And bounded: at 10% the extent floor (0.5 coarse) + scaled tail
        // (0.09) + corner diagonal keep it under ~2 coarse texels - far
        // from the default width's depth.
        assertTrue(
            "hairline too fat: $hairDepth",
            hairDepth <= 2f * s,
        )
    }
}
