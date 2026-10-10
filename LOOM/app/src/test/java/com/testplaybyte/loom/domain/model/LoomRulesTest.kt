package com.testplaybyte.loom.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the validation rules (docs/05 §6 — reproduce exactly). */
class LoomRulesTest {

    @Test
    fun slugifyMatchesTheExactRule() {
        assertEquals("street-scenes-v1", LoomRules.slugify("Street scenes v1"))
        assertEquals("street-scenes-v1", LoomRules.slugify("  Street   scenes v1!! "))
        assertEquals("a-b-c", LoomRules.slugify("a_b-c"))
        assertEquals("dataset", LoomRules.slugify("!!!"))
        assertEquals("dataset", LoomRules.slugify(""))
        // max 24 chars
        assertEquals(24, LoomRules.slugify("a".repeat(64)).length)
        // trailing/leading dashes trimmed
        assertEquals("x", LoomRules.slugify("---x---"))
    }

    @Test
    fun projectNameTrimsClampsAndFallsBack() {
        assertEquals("My set", LoomRules.cleanProjectName("  My set  "))
        assertEquals(32, LoomRules.cleanProjectName("n".repeat(80)).length)
        assertEquals("Untitled dataset", LoomRules.cleanProjectName("   "))
    }

    @Test
    fun clampsMatchTheSpecRanges() {
        assertEquals(6, LoomRules.clampDensity(0))
        assertEquals(40, LoomRules.clampDensity(99))
        assertEquals(8, LoomRules.clampDefaultDensity(1))
        assertEquals(36, LoomRules.clampDefaultDensity(50))
        assertEquals(10, LoomRules.clampUndoDepth(3))
        assertEquals(100, LoomRules.clampUndoDepth(140))
        assertEquals(50, LoomRules.clampUndoDepth(53))
        assertEquals(0, LoomRules.clampSplit(-5))
        assertEquals(40, LoomRules.clampSplit(90))
        assertEquals(0f, LoomRules.clampWorldX(-20f), 1e-4f)
        assertEquals(800f, LoomRules.clampWorldX(900f), 1e-4f)
        assertEquals(600f, LoomRules.clampWorldY(700f), 1e-4f)
    }

    @Test
    fun labelAndTagNamesClampTo18() {
        assertEquals(18, LoomRules.cleanLabelName("x".repeat(40)).length)
        assertEquals("Bicycle", LoomRules.cleanLabelName("  Bicycle "))
        assertEquals(18, LoomRules.cleanTagName("t".repeat(30)).length)
    }

    @Test
    fun duplicateCheckIsCaseInsensitive() {
        assertTrue(LoomRules.isDuplicate(listOf("Fruit", "Tool"), "fruit"))
        assertFalse(LoomRules.isDuplicate(listOf("Fruit"), "Fruits"))
        assertFalse(LoomRules.isDuplicate(emptyList(), "Fruit"))
    }

    @Test
    fun splitAssignmentSpreadsValsDeterministically() {
        // 20% of 10 images: indices 4 and 9 are val (Bresenham spread).
        val vals10 = (0 until 10).filter { LoomRules.isValSplit(it, 20) }
        assertEquals(listOf(4, 9), vals10)
        // 0% → none; every image train.
        assertFalse(LoomRules.isValSplit(0, 0))
        assertFalse(LoomRules.isValSplit(9, 0))
        // 40% of 5 → 2 vals
        assertEquals(2, (0 until 5).count { LoomRules.isValSplit(it, 40) })
    }

    @Test
    fun imageStatusWireRoundTrip() {
        assertEquals(ImageStatus.DRAFT, ImageStatus.fromWire("draft"))
        assertEquals(ImageStatus.DONE, ImageStatus.fromWire("done"))
        assertEquals(ImageStatus.UNLABELED, ImageStatus.fromWire("nonsense"))
        assertEquals(ImageStatus.UNLABELED, ImageStatus.fromWire(null))
    }

    @Test
    fun exportFormatWireRoundTrip() {
        assertEquals(ExportFormat.COCO, ExportFormat.fromWire("coco"))
        assertEquals(ExportFormat.YOLO, ExportFormat.fromWire("yolo"))
        assertEquals(ExportFormat.VOC, ExportFormat.fromWire("voc"))
        assertEquals(ExportFormat.LOOM_JSON, ExportFormat.fromWire("other"))
        assertEquals("annotations.json", ExportFormat.LOOM_JSON.ext)
        assertEquals("instances.json", ExportFormat.COCO.ext)
        assertEquals("labels/*.txt", ExportFormat.YOLO.ext)
        assertEquals("xml/*.xml", ExportFormat.VOC.ext)
    }
}
