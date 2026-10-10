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
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the dataset writers: COCO, YOLO, VOC (docs/05 §5). */
class DatasetWritersTest {

    private fun project(format: ExportFormat): Project {
        val img1 = ProjectImage("im-1", "IMG_2041.png", "kitchen")           // 800×600
        val img2 = ProjectImage("im-2", "photo.jpg", null, 1600, 1200)       // 2× scale
        return Project(
            id = "pr", slug = "demo", name = "Demo", createdAt = 0, updatedAt = 0,
            images = listOf(img1, img2),
            labels = listOf(Label("lb-fruit", "Fruit", "c2"), Label("lb-tool", "Tool", "c3")),
            format = format,
            splitPct = 20,
            states = mapOf(
                "im-1" to ImageState(
                    mesh = listOf(Pt(100f, 100f), Pt(200f, 100f), Pt(200f, 200f), Pt(100f, 200f)),
                    dots = listOf(Dot("d", DotKind.POS, 150f, 150f)),
                    strokes = listOf(Stroke("s", listOf(Pt(10f, 10f), Pt(30f, 10f)))),
                    labelId = "lb-fruit",
                    status = ImageStatus.DONE,
                    density = 16,
                ),
                "im-2" to ImageState(
                    mesh = listOf(Pt(100f, 100f), Pt(300f, 100f), Pt(200f, 300f)),
                    labelId = null, // unlabeled → must not appear in COCO/VOC
                ),
            ),
        )
    }

    @Test
    fun yoloPolygonLineMatchesTheReferenceFormat() {
        val line = DatasetWriters.yoloPolygonLine(
            classIndex = 2,
            pts = listOf(Pt(960f, 540f), Pt(0f, 1080f), Pt(1920f, 0f)),
            imageWidth = 1920, imageHeight = 1080,
        )
        assertEquals("2 0.500000 0.500000 0.000000 1.000000 1.000000 0.000000", line)
    }

    @Test
    fun yoloWritesOneLabelFilePerLabeledMeshPlusClasses() {
        val files = DatasetWriters.write(project(ExportFormat.YOLO), exportedAt = 0L)
        val labels = files.first { it.relPath == "labels/IMG_2041.txt" }
        // class index 0 (Fruit) + 4 normalized points... in real px == world here
        assertTrue(labels.content.startsWith("0 "))
        assertEquals(4 * 2 + 1, labels.content.trim().split(" ").size)
        // unlabeled image produces no label file
        assertTrue(files.none { it.relPath == "labels/photo.txt" })
        val classes = files.first { it.relPath == "classes.txt" }
        assertEquals("Fruit\nTool\n", classes.content)
        assertTrue(files.any { it.relPath == "README.txt" })
    }

    @Test
    fun yoloScalesWorldToRealPixels() {
        // 1600×1200 → world (100,100) = real (200,200) = normalized (0.125, 0.166667)
        val line = DatasetWriters.yoloPolygonLine(
            classIndex = 0,
            pts = DatasetWriters.realPoints(listOf(Pt(100f, 100f)), 1600, 1200),
            imageWidth = 1600, imageHeight = 1200,
        )
        assertEquals("0 0.125000 0.166667", line)
    }

    @Test
    fun cocoHasImagesAnnotationsAndCategories() {
        val files = DatasetWriters.write(project(ExportFormat.COCO), exportedAt = 0L)
        val root = JSONObject(files.first { it.relPath == "instances.json" }.content)
        assertEquals(2, root.getJSONArray("images").length())
        val cat = root.getJSONArray("categories")
        assertEquals(2, cat.length())
        assertEquals("Fruit", cat.getJSONObject(0).getString("name"))
        assertEquals("c2", cat.getJSONObject(0).getString("color"))

        // only the labeled mesh gets an annotation
        val anns = root.getJSONArray("annotations")
        assertEquals(1, anns.length())
        val a = anns.getJSONObject(0)
        assertEquals(1, a.getInt("image_id"))
        assertEquals(1, a.getInt("category_id"))
        assertEquals(0, a.getInt("iscrowd"))
        val bb = a.getJSONArray("bbox")
        assertEquals(100.0, bb.getDouble(0), 1e-6)
        assertEquals(100.0, bb.getDouble(1), 1e-6)
        assertEquals(100.0, bb.getDouble(2), 1e-6) // 200-100
        assertEquals(100.0, bb.getDouble(3), 1e-6)
        assertEquals(10000.0, a.getDouble("area"), 1e-6)
        // segmentation: mesh polygon + mask stroke polygon (masks ON)
        assertEquals(2, a.getJSONArray("segmentation").length())
    }

    @Test
    fun cocoExclusionStrokesRespectTheMasksToggle() {
        val p = project(ExportFormat.COCO).copy(includeMasks = false)
        val root = JSONObject(DatasetWriters.write(p, 0L).first { it.relPath == "instances.json" }.content)
        val seg = root.getJSONArray("annotations").getJSONObject(0).getJSONArray("segmentation")
        assertEquals(1, seg.length()) // mesh only
    }

    @Test
    fun vocWritesXmlWithBndboxAndPolygonExtension() {
        val files = DatasetWriters.write(project(ExportFormat.VOC), exportedAt = 0L)
        val xml = files.first { it.relPath == "xml/IMG_2041.xml" }.content
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<filename>IMG_2041.png</filename>"))
        assertTrue(xml.contains("<width>800</width>"))
        assertTrue(xml.contains("<name>Fruit</name>"))
        assertTrue(xml.contains("<xmin>100</xmin>"))
        assertTrue(xml.contains("<xmax>200</xmax>"))
        assertTrue(xml.contains("<polygon>"))
        assertEquals(4, Regex("<pt>").findAll(xml).count() - 2) // 4 mesh pts + 2 stroke pts
        assertTrue(xml.contains("<exclusions>"))
        // unlabeled image → no XML
        assertTrue(files.none { it.relPath == "xml/photo.xml" })
    }

    @Test
    fun summaryCountsMatchTheProject() {
        val s = DatasetWriters.summary(project(ExportFormat.LOOM_JSON))
        assertEquals(2, s.images)
        assertEquals(2, s.meshes)
        assertEquals(1, s.dots)
        assertEquals(1, s.strokes)
    }

    @Test
    fun loomJsonExportIsSelfConsistentWithRealPixels() {
        val files = DatasetWriters.write(project(ExportFormat.LOOM_JSON), exportedAt = 42L)
        val root = JSONObject(files.first { it.relPath == "annotations.json" }.content)
        assertEquals(42L, root.getJSONObject("project").getLong("exportedAt"))
        val im2 = root.getJSONArray("images").getJSONObject(1)
        assertEquals(1600, im2.getInt("width"))
        // world (100,100) → real (200,200)
        assertEquals(200.0, im2.getJSONArray("mesh").getJSONArray(0).getDouble(0), 1e-6)
    }
}
