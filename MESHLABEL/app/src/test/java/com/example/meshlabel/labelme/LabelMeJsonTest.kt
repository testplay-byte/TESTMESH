package com.example.meshlabel.labelme

import com.example.meshlabel.model.Pt
import com.example.meshlabel.model.Shape
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the exact LabelMe JSON schema round-trip - the file this
 * app writes must open in desktop LabelMe and vice versa.
 */
class LabelMeJsonTest {

    private fun sampleShapes(): List<Shape> = listOf(
        Shape(
            label = "cat",
            points = mutableListOf(Pt(10f, 20f), Pt(110.5f, 20f), Pt(60f, 90.25f)),
            groupId = null,
        ),
        Shape(
            label = "cat",
            points = mutableListOf(Pt(200f, 300f), Pt(240f, 300f), Pt(240f, 340f), Pt(200f, 340f)),
            groupId = 7L,
            shapeType = "rectangle",
            description = "second instance",
        ),
    )

    @Test
    fun writesAllSchemaKeys() {
        val json = LabelMeJson.toJson(
            sampleShapes(), "img/cat.jpg", 640, 480, imageDataBase64 = null,
        )
        val root = JSONObject(json)
        // Key ORDER is not part of the JSON data model (and the JVM org.json
        // artifact does not preserve it) - presence is what labelme reads.
        val keys = root.keys().asSequence().toSet()
        assertEquals(
            setOf("version", "flags", "shapes", "imagePath", "imageData",
                "imageHeight", "imageWidth"),
            keys,
        )
        assertEquals("img/cat.jpg", root.getString("imagePath"))
        assertEquals(640, root.getInt("imageWidth"))
        assertEquals(480, root.getInt("imageHeight"))
        assertTrue(root.isNull("imageData"))
        assertTrue(root.getJSONObject("flags").length() == 0)

        val shapes = root.getJSONArray("shapes")
        assertEquals(2, shapes.length())
        val s0 = shapes.getJSONObject(0)
        assertEquals("cat", s0.getString("label"))
        assertEquals("polygon", s0.getString("shape_type"))
        assertTrue(s0.isNull("group_id"))
        assertEquals(3, s0.getJSONArray("points").length())
        val p0 = s0.getJSONArray("points").getJSONArray(0)
        assertEquals(10.0, p0.getDouble(0), 1e-9)
        assertEquals(20.0, p0.getDouble(1), 1e-9)
        val s1 = shapes.getJSONObject(1)
        assertEquals(7L, s1.getLong("group_id"))
        assertEquals("rectangle", s1.getString("shape_type"))
        assertEquals("second instance", s1.getString("description"))
    }

    @Test
    fun roundTripPreservesShapes() {
        val original = sampleShapes()
        val json = LabelMeJson.toJson(original, "a.png", 1920, 1080, "QUJD")
        val loaded = LabelMeJson.fromJson(json)
        assertEquals(1920, loaded.imageWidth)
        assertEquals(1080, loaded.imageHeight)
        assertEquals(original.size, loaded.shapes.size)
        for (i in original.indices) {
            val a = original[i]
            val b = loaded.shapes[i]
            assertEquals(a.label, b.label)
            assertEquals(a.groupId, b.groupId)
            assertEquals(a.shapeType, b.shapeType)
            assertEquals(a.description, b.description)
            assertEquals(a.points.size, b.points.size)
            for (j in a.points.indices) {
                assertEquals(a.points[j].x.toDouble(), b.points[j].x.toDouble(), 0.01)
                assertEquals(a.points[j].y.toDouble(), b.points[j].y.toDouble(), 0.01)
            }
        }
    }

    @Test
    fun imageDataPresentIsPreserved() {
        val json = LabelMeJson.toJson(emptyList(), "a.png", 10, 10, "SUNDCTEyMw==")
        val root = JSONObject(json)
        assertEquals("SUNDCTEyMw==", root.getString("imageData"))
    }

    @Test
    fun malformedFilesThrowClearErrors() {
        // Not JSON at all.
        try {
            LabelMeJson.fromJson("definitely not json")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("LabelMe"))
        }
        // JSON but no shapes key.
        try {
            LabelMeJson.fromJson("""{"imageWidth": 1}""")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("shapes"))
        }
        // Degenerate polygon (<3 points) is skipped, not fatal.
        val mixed = """{"shapes":[
            {"label":"x","points":[[1,1],[2,2]],"shape_type":"polygon"},
            {"label":"y","points":[[1,1],[2,1],[2,2]],"shape_type":"polygon"}],
            "imageWidth":10,"imageHeight":10}"""
        val loaded = LabelMeJson.fromJson(mixed)
        assertEquals(1, loaded.shapes.size)
        assertEquals("y", loaded.shapes[0].label)
    }
}
