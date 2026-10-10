package com.testplaybyte.loom.domain.mesh

import com.testplaybyte.loom.domain.model.Dot
import com.testplaybyte.loom.domain.model.DotKind
import com.testplaybyte.loom.domain.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * JVM tests for the deterministic mesh model (docs/04 §2 — "implement
 * EXACTLY, unit-test this"). The worked example uses the kitchen scene's
 * real anchors from reference/scenes.json: an 8-point ring around the
 * apple at ≈(182.5, 274.5), density 16.
 */
class MeshMathTest {

    /** Kitchen anchors — exact values from reference/scenes.json. */
    private val kitchen = listOf(
        Pt(140f, 282f), Pt(158f, 250f), Pt(186f, 234f), Pt(212f, 244f),
        Pt(222f, 272f), Pt(210f, 300f), Pt(180f, 312f), Pt(152f, 302f),
    )

    private fun centroid(pts: List<Pt>): Pt =
        Pt(pts.sumOf { it.x.toDouble() }.toFloat() / pts.size,
           pts.sumOf { it.y.toDouble() }.toFloat() / pts.size)

    // ── worked example ─────────────────────────────────────────────────────

    @Test
    fun buildMeshProducesExactDensityAroundTheApple() {
        val mesh = MeshMath.buildMesh(kitchen, density = 16, dots = emptyList())
        assertEquals(16, mesh.size)

        // "a 16-vertex closed ring roughly circumscribing the apple at
        // ~(181, 273) r≈45" (docs/04 §2 worked example).
        val c = centroid(mesh)
        assertEquals(182.5f, c.x, 12f)
        assertEquals(274.5f, c.y, 12f)
        val radii = mesh.map { hypot(it.x - c.x, it.y - c.y) }
        val avg = radii.average()
        assertTrue("avg radius $avg not ~45", avg in 28.0..70.0)
        // and the ring is actually closed around the centre
        assertTrue(radii.min() > 10.0)
    }

    @Test
    fun meshIsDeterministic() {
        val a = MeshMath.buildMesh(kitchen, 16, emptyList())
        val b = MeshMath.buildMesh(kitchen, 16, emptyList())
        assertEquals(a, b)
    }

    @Test
    fun positiveDotsPullVerticesTowardThemselvesWithExactMagnitude() {
        val base = MeshMath.buildMesh(kitchen, 16, emptyList())
        val dots = listOf(
            Dot("d1", DotKind.POS, 172f, 270f),
            Dot("d2", DotKind.POS, 190f, 288f),
            Dot("d3", DotKind.POS, 158f, 292f),
            Dot("d4", DotKind.POS, 196f, 258f),
        )
        // Exact semantics (mesh.ts): the displacement is ALONG (dot − vertex)
        // with magnitude w·PULL_MAX, w = exp(−d²/(2σ²)). When the vertex is
        // closer to the dot than w·PULL_MAX it overshoots slightly past the
        // dot — the prototype behaves identically; verify direction +
        // magnitude rather than raw distance.
        for (d in dots) {
            val sculpted = MeshMath.applyDotInfluence(
                MeshMath.resampleRing(MeshMath.smoothRingClosed(kitchen), 16),
                listOf(d),
            )
            val nearest = MeshMath.nearestVertex(base, Pt(d.x, d.y))
            val v0 = base[nearest.index]
            val v1 = sculpted[nearest.index]
            val dist = hypot(d.x - v0.x, d.y - v0.y)
            val displacement = hypot(v1.x - v0.x, v1.y - v0.y)
            val expected = Math.exp(-(dist * dist) / (2 * 78.0 * 78.0)) * 30.0
            assertEquals(
                "dot (${d.x},${d.y}) displacement magnitude",
                expected.toFloat(), displacement, 0.05f,
            )
            // direction: dot − vertex (dot product > 0, collinear)
            val cross = (d.x - v0.x) * (v1.y - v0.y) - (d.y - v0.y) * (v1.x - v0.x)
            assertEquals("dot (${d.x},${d.y}) not collinear", 0f, cross, 0.05f)
            assertTrue("dot (${d.x},${d.y}) pulled the wrong way", (d.x - v0.x) * (v1.x - v0.x) >= 0f)
        }

        // All four together must reshape the ring (not a no-op), and the
        // result stays inside the world.
        val all = MeshMath.applyDotInfluence(
            MeshMath.resampleRing(MeshMath.smoothRingClosed(kitchen), 16), dots,
        )
        val moved = all.indices.count { hypot(all[it].x - base[it].x, all[it].y - base[it].y) > 1f }
        assertTrue("expected several vertices to move, got $moved", moved >= 3)
        assertTrue(all.all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test
    fun negativeDotsPushNearbyVerticesAway() {
        val base = MeshMath.buildMesh(kitchen, 16, emptyList())
        val dots = listOf(
            Dot("n1", DotKind.NEG, 236f, 282f),
            Dot("n2", DotKind.NEG, 140f, 340f),
        )
        val sculpted = MeshMath.buildMesh(kitchen, 16, dots)
        for (d in dots) {
            val nearest = MeshMath.nearestVertex(base, Pt(d.x, d.y))
            val before = hypot(base[nearest.index].x - d.x, base[nearest.index].y - d.y)
            val after = hypot(sculpted[nearest.index].x - d.x, sculpted[nearest.index].y - d.y)
            assertTrue(
                "dot (${d.x},${d.y}) did not push its vertex: $before -> $after",
                after > before + 2f,
            )
        }
    }

    @Test
    fun dotsBeyondPullRadiusHaveNoInfluence() {
        val base = MeshMath.buildMesh(kitchen, 16, emptyList())
        val far = listOf(Dot("far", DotKind.POS, 700f, 500f)) // > PULL_RADIUS=150 away
        val sculpted = MeshMath.buildMesh(kitchen, 16, far)
        for (i in base.indices) {
            assertEquals(base[i].x, sculpted[i].x, 1e-4f)
            assertEquals(base[i].y, sculpted[i].y, 1e-4f)
        }
    }

    @Test
    fun dotExactlyOnAVertexDoesNotProduceNaN() {
        val v0 = kitchen[0]
        val sculpted = MeshMath.applyDotInfluence(
            listOf(v0, Pt(300f, 300f), Pt(300f, 400f)),
            listOf(Dot("on", DotKind.POS, v0.x, v0.y)),
        )
        assertTrue(sculpted.all { it.x.isFinite() && it.y.isFinite() })
        // Displacement direction is undefined at distance 0 — the guard
        // (dist = 1) keeps it finite; the vertex may move at most PULL_MAX.
        assertTrue(hypot(sculpted[0].x - v0.x, sculpted[0].y - v0.y) <= MeshMath.PULL_MAX + 1e-3f)
    }

    @Test
    fun pullMagnitudeMatchesTheExactFalloff() {
        // Single vertex at (0,0), single positive dot at (100,0):
        // w = exp(-100²/(2·78²)), displacement = w · PULL_MAX toward the dot.
        val sculpted = MeshMath.applyDotInfluence(
            listOf(Pt(0f, 0f)),
            listOf(Dot("p", DotKind.POS, 100f, 0f)),
        )
        val expected = Math.exp(-(100.0 * 100.0) / (2 * 78.0 * 78.0)) * 30.0
        assertEquals(expected.toFloat(), sculpted[0].x, 1e-3f)
        assertEquals(0f, sculpted[0].y, 1e-3f)
    }

    // ── resampling / smoothing ─────────────────────────────────────────────

    @Test
    fun resampleReturnsExactCountAndUniformSpacing() {
        // Circle → uniform arc-length resample is exactly even; measured as
        // CHORD length between consecutive vertices: 2r·sin(π/count).
        val circle = (0 until 64).map {
            val a = it / 64f * 2f * Math.PI.toFloat()
            Pt(200f + 100f * kotlin.math.cos(a), 200f + 100f * kotlin.math.sin(a))
        }
        val ring = MeshMath.resampleRing(circle, 9)
        assertEquals(9, ring.size)
        val gaps = ring.indices.map {
            val a = ring[it]
            val b = ring[(it + 1) % ring.size]
            hypot(b.x - a.x, b.y - a.y)
        }
        val expectedChord = 2 * 100.0 * kotlin.math.sin(Math.PI / 9)
        for (g in gaps) assertEquals(expectedChord, g.toDouble(), 0.5)
    }

    @Test
    fun smoothRingClosedSamplesEachSegment14Times() {
        val dense = MeshMath.smoothRingClosed(kitchen)
        assertEquals(kitchen.size * 14, dense.size)
        // Catmull-Rom passes through p1 at t=0 → every anchor appears.
        for (a in kitchen) {
            assertTrue(dense.any { abs(it.x - a.x) < 0.01f && abs(it.y - a.y) < 0.01f })
        }
    }

    // ── hit testing / edit helpers ─────────────────────────────────────────

    @Test
    fun nearestVertexFindsClosestIndex() {
        val poly = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f))
        assertEquals(1, MeshMath.nearestVertex(poly, Pt(95f, 8f)).index)
        assertEquals(3, MeshMath.nearestVertex(poly, Pt(4f, 90f)).index)
    }

    @Test
    fun insertVertexGoesIntoNearestEdgeAfterItsStart() {
        val square = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f))
        val out = MeshMath.insertVertex(square, Pt(50f, 4f)) // near edge 0 (top)
        assertEquals(5, out.size)
        assertEquals(Pt(50f, 4f), out[1]) // inserted after index 0
    }

    @Test
    fun edgeDistanceAndPointInPolygon() {
        val square = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f))
        assertEquals(10f, MeshMath.edgeDistance(square, Pt(50f, -10f)), 1e-3f)
        assertTrue(MeshMath.pointInPolygon(Pt(50f, 50f), square))
        assertTrue(!MeshMath.pointInPolygon(Pt(150f, 50f), square))
    }

    @Test
    fun thinStrokeDecimatesButKeepsEndpoints() {
        val pts = listOf(Pt(0f, 0f), Pt(1f, 0f)) + (1..10).map { Pt(it * 10f, 0f) } + Pt(101f, 0f)
        val thin = MeshMath.thinStroke(pts, 2.5f)
        assertEquals(pts.first(), thin.first())
        assertEquals(pts.last(), thin.last())
        assertTrue("expected decimation: ${thin.size} of ${pts.size}", thin.size < pts.size)
        // No two consecutive kept points closer than epsilon — except the
        // final pair: the endpoint is unconditionally appended (prototype
        // behavior: `out.push(pts[pts.length - 1])`).
        for (i in 1 until thin.size - 1) {
            assertTrue(hypot(thin[i].x - thin[i - 1].x, thin[i].y - thin[i - 1].y) >= 2.5f - 1e-3f)
        }
    }

    @Test
    fun polygonAreaAndBbox() {
        val square = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f))
        assertEquals(100f, MeshMath.polygonArea(square), 1e-3f)
        val bb = MeshMath.bbox(listOf(Pt(3f, 5f), Pt(9f, 2f)))
        assertEquals(3f, bb.x, 1e-4f)
        assertEquals(2f, bb.y, 1e-4f)
        assertEquals(6f, bb.w, 1e-4f)
        assertEquals(3f, bb.h, 1e-4f)
    }

    @Test
    fun ringQuadsStartsAtMidpointOfFirstEdgeAndCloses() {
        val quad = MeshMath.ringQuads(kitchen)
        assertNotNull(quad)
        val q = quad!!
        assertEquals(kitchen.size, q.segments.size)
        // start = midpoint of edge (v0 → v1) — the prototype's ringPath.
        assertEquals((kitchen[0].x + kitchen[1].x) / 2f, q.start.x, 1e-4f)
        assertEquals((kitchen[0].y + kitchen[1].y) / 2f, q.start.y, 1e-4f)
        // closes: last segment ends on the start point
        val last = q.segments.last().end
        assertEquals(q.start.x, last.x, 1e-4f)
        assertEquals(q.start.y, last.y, 1e-4f)
    }
}
