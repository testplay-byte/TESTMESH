package com.testplaybyte.loom.domain.export

import com.testplaybyte.loom.domain.model.Dot
import com.testplaybyte.loom.domain.model.DotKind
import com.testplaybyte.loom.domain.model.ExportFormat
import com.testplaybyte.loom.domain.model.ImageState
import com.testplaybyte.loom.domain.model.ImageStatus
import com.testplaybyte.loom.domain.model.Label
import com.testplaybyte.loom.domain.model.Project
import com.testplaybyte.loom.domain.model.ProjectImage
import com.testplaybyte.loom.domain.model.Pt
import com.testplaybyte.loom.domain.model.Stroke
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Loom JSON codec (docs/05 §5) — the working file AND
 * the export file, plus tolerance on decode.
 */
class LoomJsonCodecTest {

    private fun sampleProject(): Project {
        val img = ProjectImage(id = "im-1", file = "IMG_2041.png", sceneId = "kitchen")
        val state = ImageState(
            dots = listOf(Dot("d1", DotKind.POS, 172f, 270f), Dot("d2", DotKind.NEG, 236f, 282f)),
            mesh = listOf(Pt(140f, 282f), Pt(200f, 240f), Pt(220f, 300f)),
            strokes = listOf(Stroke("s1", listOf(Pt(10f, 10f), Pt(20f, 20f)))),
            density = 16,
            labelId = "lb-fruit",
            tags = listOf("Blurry", "Reviewed"),
            status = ImageStatus.DONE,
        )
        return Project(
            id = "pr-1",
            slug = "object-scan-demo",
            name = "Object scan — demo set",
            createdAt = 1L,
            updatedAt = 2L,
            images = listOf(img),
            labels = listOf(Label("lb-fruit", "Fruit", "c2")),
            format = ExportFormat.LOOM_JSON,
            states = mapOf("im-1" to state),
        )
    }

    @Test
    fun workingFileKeepsWorldCoordinatesAndReportsWorldSize() {
        val json = LoomJsonCodec.encode(sampleProject(), 123L, scaleToRealPixels = false, includeMasks = true)
        val root = JSONObject(json)
        assertEquals("loom-json", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals("Object scan — demo set", root.getJSONObject("project").getString("name"))

        val img = root.getJSONArray("images").getJSONObject(0)
        assertEquals("IMG_2041.png", img.getString("file"))
        assertEquals(800, img.getInt("width"))
        assertEquals(600, img.getInt("height"))
        assertEquals("done", img.getString("status"))
        assertEquals("Fruit", img.getString("label"))
        assertEquals(16, img.getInt("density"))
        assertEquals("train", img.getString("split"))

        // dots keep world coords exactly (2-decimal rounding only)
        val dots = img.getJSONArray("dots")
        assertEquals(2, dots.length())
        assertEquals(172.0, dots.getJSONObject(0).getDouble("x"), 1e-9)
        assertEquals("pos", dots.getJSONObject(0).getString("kind"))
        assertEquals("neg", dots.getJSONObject(1).getString("kind"))

        // mesh + strokes present
        assertEquals(3, img.getJSONArray("mesh").length())
        assertEquals(1, img.getJSONArray("strokes").length())
        assertEquals(listOf("Blurry", "Reviewed"), (0 until 2).map { img.getJSONArray("tags").getString(it) })
    }

    @Test
    fun exportFileScalesToRealPixels() {
        val img = ProjectImage(id = "im-2", file = "photo.jpg", width = 1600, height = 1200)
        val p = sampleProject().copy(
            images = listOf(img),
            states = mapOf(
                "im-2" to ImageState(
                    dots = listOf(Dot("d", DotKind.POS, 100f, 100f)),
                    mesh = listOf(Pt(100f, 100f), Pt(200f, 100f), Pt(200f, 200f)),
                ),
            ),
        )
        val json = LoomJsonCodec.encode(p, 0L, scaleToRealPixels = true, includeMasks = true)
        val root = JSONObject(json)
        val out = root.getJSONArray("images").getJSONObject(0)
        assertEquals(1600, out.getInt("width"))
        assertEquals(1200, out.getInt("height"))
        // world (100,100) → real (200,200) at 2× scale
        assertEquals(200.0, out.getJSONArray("dots").getJSONObject(0).getDouble("x"), 1e-6)
        val meshPt = out.getJSONArray("mesh").getJSONArray(0)
        assertEquals(200.0, meshPt.getDouble(0), 1e-6)
        assertEquals(200.0, meshPt.getDouble(1), 1e-6)
    }

    @Test
    fun masksToggleOmitsStrokes() {
        val json = LoomJsonCodec.encode(sampleProject(), 0L, scaleToRealPixels = false, includeMasks = false)
        val img = JSONObject(json).getJSONArray("images").getJSONObject(0)
        assertEquals(0, img.getJSONArray("strokes").length())
    }

    @Test
    fun decodeRoundTripRestoresState() {
        val project = sampleProject()
        val json = LoomJsonCodec.encode(project, 0L, scaleToRealPixels = false, includeMasks = true)
        val decoded = LoomJsonCodec.decode(json)
        assertEquals(1, decoded.classes.size)
        assertEquals("Fruit", decoded.classes[0].name)
        assertEquals("c2", decoded.classes[0].color)

        val im = decoded.images.single()
        assertEquals("IMG_2041.png", im.file)
        assertTrue(im.spaceIsWorld)
        assertEquals(ImageStatus.DONE, im.status)
        assertEquals("Fruit", im.labelName)
        assertEquals(16, im.density)
        assertEquals(listOf("Blurry", "Reviewed"), im.tags)
        assertEquals(2, im.dots.size)
        assertEquals(DotKind.NEG, im.dots[1].kind)
        assertEquals(3, im.mesh!!.size)
        assertEquals(1, im.strokes.size)
    }

    @Test
    fun decodeRejectsNonLoomJsonWithClearError() {
        try {
            LoomJsonCodec.decode("definitely not json")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Loom"))
        }
        try {
            LoomJsonCodec.decode("""{"format":"loom-json"}""")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("images"))
        }
    }

    @Test
    fun decodeIsTolerantOfMissingOptionalFields() {
        val decoded = LoomJsonCodec.decode(
            """{"images":[{"file":"a.png"}]}""",
        )
        val im = decoded.images.single()
        assertEquals("a.png", im.file)
        assertEquals(ImageStatus.UNLABELED, im.status)
        assertEquals(null, im.labelName)
        assertEquals(800, im.width)
        assertEquals(emptyList<String>(), im.tags)
        assertEquals(null, im.mesh)
    }

    @Test
    fun previewShowsFirstThreeImagesThenEllipsis() {
        val images = (1..6).map { ProjectImage(id = "im-$it", file = "IMG_$it.png") }
        val states = images.associate { im ->
            im.id to ImageState(
                mesh = listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f)),
                dots = listOf(Dot("d", DotKind.POS, 0f, 0f), Dot("n", DotKind.NEG, 1f, 1f)),
                labelId = null,
            )
        }
        val p = Project("pr", "s", "P", 0, 0, images, emptyList(), states = states)
        val preview = LoomJsonCodec.previewJson(p, 3)
        assertNotNull(preview)
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.contains("\"IMG_1.png\""))
        assertTrue(preview.contains("\"vertices\": 3"))
        assertTrue(preview.contains("\"pos\": 1"))
        assertTrue(preview.contains("\"neg\": 1"))
        assertTrue(!preview.contains("IMG_4.png"))
    }
}
