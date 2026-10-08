package com.example.meshlabel.seg

import com.example.meshlabel.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for mask -> polygon (the Magic Touch conversion core):
 * marching-square tracing, chaining, downscale round-trip, RDP.
 * The bounding box of a traced solid region must match the region's own
 * pixel bounds - that is the invariant the annotation workflow depends on.
 */
class MaskToPolygonTest {

    private fun fillRect(mask: BooleanArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        for (y in y0..y1) for (x in x0..x1) mask[y * w + x] = true
    }

    private fun bbox(pts: List<Pt>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** Approximate shoelace area (polygon must be closed implicitly). */
    private fun area(pts: List<Pt>): Float {
        var a = 0f
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            a += p.x * q.y - q.x * p.y
        }
        return kotlin.math.abs(a) / 2f
    }

    @Test
    fun rectangleTracedToItsExactPixelBounds() {
        // 32x32 solid (a realistic small-object scale): RDP's 1.5px
        // tolerance trims at most ~1.5px off each corner, which is
        // sub-1% here (on a 10px toy box the same tolerance would be
        // visible - that is a property of epsilon, not the tracer).
        val w = 96; val h = 96
        val mask = BooleanArray(w * h)
        // Solid pixels x=15..46, y=15..46 -> pixel bounds [15,47]².
        fillRect(mask, w, 15, 15, 46, 46)
        val poly = MaskToPolygon.trace(mask, w, h)
        assertTrue("expected a polygon, got ${poly.size} pts", poly.size >= 4)
        val b = bbox(poly)
        for (v in b) assertTrue("bbox finite", v.isFinite())
        assertEquals(15.0, b[0].toDouble(), 0.6)   // minX
        assertEquals(15.0, b[1].toDouble(), 0.6)   // minY
        assertEquals(47.0, b[2].toDouble(), 0.6)   // maxX
        assertEquals(47.0, b[3].toDouble(), 0.6)   // maxY
        // Tracer is exact (raw ring area 1023.5 = chamfered 1024); the
        // ~3% slack is RDP: its corner anchors carry the marching-squares
        // ±0.5px chamfer, which tilts each simplified edge by <=0.5px.
        // On real objects (100s of px) that is a <0.5% error.
        assertEquals(1024.0, area(poly).toDouble(), 35.0)
        // RDP must have simplified the staircase, not exploded it.
        assertTrue("too many points: ${poly.size}", poly.size <= 16)
    }

    @Test
    fun lShapeKeepsTheConcaveCorner() {
        val w = 60; val h = 60
        val mask = BooleanArray(w * h)
        fillRect(mask, w, 10, 10, 39, 19)   // horizontal bar
        fillRect(mask, w, 10, 20, 19, 39)   // vertical bar down-left
        val poly = MaskToPolygon.trace(mask, w, h)
        assertTrue(poly.size >= 5)
        val b = bbox(poly)
        assertEquals(10.0, b[0].toDouble(), 0.6)
        assertEquals(10.0, b[1].toDouble(), 0.6)
        assertEquals(40.0, b[2].toDouble(), 0.6)
        assertEquals(40.0, b[3].toDouble(), 0.6)
        // Area = 30x10 + 10x20 = 500.
        assertEquals(500.0, area(poly).toDouble(), 25.0)
        // The concave corner (20,20)-ish must survive simplification: at
        // least one vertex strictly inside the outer box on both axes.
        val inner = poly.count { it.x > 10.6f && it.x < 39.4f && it.y > 10.6f && it.y < 39.4f }
        assertTrue("concave corner lost (inner=$inner)", inner >= 1)
    }

    @Test
    fun emptyAndDegenerateMasksReturnEmpty() {
        assertEquals(0, MaskToPolygon.trace(BooleanArray(32 * 32), 32, 32).size)
        // Undersized buffer is rejected without throwing.
        assertEquals(0, MaskToPolygon.trace(BooleanArray(10), 32, 32).size)
        assertEquals(0, MaskToPolygon.trace(BooleanArray(16), 0, 4).size)
    }

    @Test
    fun largeMaskDownscalePathKeepsBounds() {
        // 1500 > MAX_TRACE_DIM(1024) -> downscale + scale-back path.
        val w = 1500; val h = 1200
        val mask = BooleanArray(w * h)
        fillRect(mask, w, 300, 200, 699, 599)   // 400x400 solid
        val poly = MaskToPolygon.trace(mask, w, h)
        assertTrue(poly.size >= 4)
        val b = bbox(poly)
        // Nearest-downscale round-trip error is bounded by the scale factor
        // (~1.5 px): allow 4px slack on every edge.
        assertEquals(300.0, b[0].toDouble(), 4.0)
        assertEquals(200.0, b[1].toDouble(), 4.0)
        assertEquals(700.0, b[2].toDouble(), 4.0)
        assertEquals(600.0, b[3].toDouble(), 4.0)
    }

    @Test
    fun rdpSimplifiesStraightRunsAndKeepsOutliers() {
        // A straight horizontal run with one spike far above it.
        val pts = listOf(
            Pt(0f, 0f), Pt(10f, 0f), Pt(20f, 0f), Pt(30f, 0f),
            Pt(40f, 0f), Pt(50f, 0f),
        )
        val simplified = MaskToPolygon.rdpClosed(pts, 1.5f)
        // All collinear interior points drop; endpoints stay.
        assertEquals(listOf(Pt(0f, 0f), Pt(50f, 0f)), simplified)

        val withSpike = pts + Pt(25f, 30f) + Pt(50f, 0f)
        val kept = MaskToPolygon.rdpClosed(withSpike, 1.5f)
        assertTrue("spike dropped", kept.any { it.y == 30f })
    }
}
