package com.example.meshlabel.store

import com.example.meshlabel.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the YOLO-seg export line format. */
class YoloLineTest {

    @Test
    fun normalizesPointsToSixDecimals() {
        val line = YoloSegExporter.yoloLine(
            classId = 2,
            points = listOf(Pt(960f, 540f), Pt(0f, 1080f), Pt(1920f, 0f)),
            imageWidth = 1920,
            imageHeight = 1080,
        )
        assertEquals("2 0.500000 0.500000 0.000000 1.000000 1.000000 0.000000", line)
    }

    @Test
    fun clampsOutOfRangePoints() {
        val line = YoloSegExporter.yoloLine(
            classId = 0,
            points = listOf(Pt(-5f, 1200f)),
            imageWidth = 100,
            imageHeight = 1000,
        )
        assertEquals("0 0.000000 1.000000", line)
    }
}
