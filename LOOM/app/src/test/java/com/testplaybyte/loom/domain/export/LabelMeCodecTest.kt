package com.testplaybyte.loom.domain.export

import com.testplaybyte.loom.domain.model.ExportFormat
import com.testplaybyte.loom.domain.model.ImageState
import com.testplaybyte.loom.domain.model.Label
import com.testplaybyte.loom.domain.model.ObjAnn
import com.testplaybyte.loom.domain.model.Project
import com.testplaybyte.loom.domain.model.Pt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the per-image LabelMe v5 sidecar — the user-facing
 * annotation format (example: "EXAMPLE LABEL JSON/camera-image.json").
 * The contract: same keys/shape as LabelMe 5.11, REAL image pixel coords,
 * one shape per saved object + the working mesh.
 */
class LabelMeCodecTest {

    private val labels = listOf(
        Label("lb-1", "CAMERA", "c1"),
        Label("lb-2", "HAND", "c2"),
    )

    private fun project(images: List<com.testplaybyte.loom.domain.model.ProjectImage>) = Project(
        id = "pr-t", slug = "t", name = "T", createdAt = 0, updatedAt = 0,
        images = images, labels = labels, format = ExportFormat.LOOM_JSON,
        states = images.associate { it.id to ImageState.empty(16) },
    )

    @Test
    fun encodesTheExactLabelmeSchema() {
        val im = com.testplaybyte.loom.domain.model.ProjectImage("im-1", "hand.jpg", width = 2000, height = 1600)
        val st = ImageState(
            density = 24,
            objects = listOf(
                ObjAnn("obj-1", "lb-1", listOf(Pt(400f, 300f), Pt(600f, 300f), Pt(600f, 450f))),
            ),
        )
        val text = LabelMeCodec.encode(project(listOf(im)), im, st, imageBytes = null)
        val root = JSONObject(text)
        assertEquals("5.11.4", root.getString("version"))
        assertEquals("hand.jpg", root.getString("imagePath"))
        assertEquals(1600, root.getInt("imageHeight"))
        assertEquals(2000, root.getInt("imageWidth"))
        assertTrue(root.isNull("imageData"))
        val shapes = root.getJSONArray("shapes")
        assertEquals(1, shapes.length())
        val sh = shapes.getJSONObject(0)
        assertEquals("CAMERA", sh.getString("label"))
        assertEquals("polygon", sh.getString("shape_type"))
        assertNull(sh.opt("group_id")?.takeIf { it != JSONObject.NULL })
        // world (400,300) on a 2000×1600 letterbox: s = min(0.4, 0.375) = 0.375
        // dx = (800 - 2000*0.375)/2 = 25, dy = 0 → real = (400-25)/0.375 = 1000
        assertEquals(1000.0, sh.getJSONArray("points").getJSONArray(0).getDouble(0), 0.01)
        assertEquals(800.0, sh.getJSONArray("points").getJSONArray(0).getDouble(1), 0.01)
    }

    @Test
    fun embedsImageDataWhenAsked() {
        val im = com.testplaybyte.loom.domain.model.ProjectImage("im-1", "a.png", width = 800, height = 600)
        val st = ImageState(objects = listOf(ObjAnn("o", null, listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f)))))
        val text = LabelMeCodec.encode(project(listOf(im)), im, st, imageBytes = byteArrayOf(1, 2, 3))
        val root = JSONObject(text)
        assertEquals("AQID", root.getString("imageData"))
    }

    @Test
    fun workingMeshIsAlsoAShapeAndRoundTrips() {
        val im = com.testplaybyte.loom.domain.model.ProjectImage("im-1", "a.png", width = 800, height = 600)
        val st = ImageState(
            mesh = listOf(Pt(10f, 10f), Pt(50f, 10f), Pt(50f, 40f)),
            labelId = "lb-1",
            objects = listOf(ObjAnn("o", "lb-2", listOf(Pt(0f, 0f), Pt(5f, 0f), Pt(5f, 5f)))),
        )
        val text = LabelMeCodec.encode(project(listOf(im)), im, st, imageBytes = null)
        val shapes = LabelMeCodec.decode(text)
        assertEquals(2, shapes.size)
        assertEquals("HAND", shapes[0].label) // objects first
        assertEquals("CAMERA", shapes[1].label) // working mesh second
        assertEquals(3, shapes[0].points.size)
    }

    @Test
    fun decodeIsTolerantOfGarbage() {
        assertEquals(0, LabelMeCodec.decode("not json").size)
        assertEquals(0, LabelMeCodec.decode("""{"version":"5.11.4"}""").size)
        // non-polygon shapes are skipped
        val shapes = LabelMeCodec.decode(
            """{"shapes":[{"label":"X","shape_type":"rectangle","points":[[1,1],[2,2],[3,3]]},
                {"label":"Y","shape_type":"polygon","points":[[0,0],[10,0],[10,10]]}]}""",
        )
        assertEquals(1, shapes.size)
        assertEquals("Y", shapes[0].label)
    }
}
