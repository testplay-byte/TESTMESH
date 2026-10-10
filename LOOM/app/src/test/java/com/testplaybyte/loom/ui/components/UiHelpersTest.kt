package com.testplaybyte.loom.ui.components

import com.testplaybyte.loom.data.prefs.PermsCodec
import com.testplaybyte.loom.data.prefs.SettingsCodec
import com.testplaybyte.loom.domain.model.ExportFormat
import com.testplaybyte.loom.domain.model.LoomSettings
import com.testplaybyte.loom.domain.model.Perms
import com.testplaybyte.loom.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the small deterministic helpers that shape the UI:
 * the masonry aspect hash (docs/06 §7 — exact FNV-1a port) and the
 * DataStore JSON codecs (settings + permissions).
 */
class UiHelpersTest {

    @Test
    fun tileAspectOnlyEverReturnsTheFiveDocumentedAspects() {
        val allowed = setOf(4f / 3f, 5f / 4f, 1f, 4f / 5f, 3f / 4f)
        for (i in 0 until 500) {
            val aspect = tileAspect("im-$i")
            assertTrue("aspect $aspect not in the documented set", aspect in allowed)
        }
    }

    @Test
    fun tileAspectIsDeterministicForTheSeedIds() {
        val ids = listOf(
            "im-seed-kitchen", "im-seed-street", "im-seed-workbench",
            "im-seed-shelf", "im-seed-park", "im-seed-desk",
        )
        val aspects = ids.map { tileAspect(it) }
        assertEquals(aspects, ids.map { tileAspect(it) })
    }

    @Test
    fun settingsCodecRoundTripsEveryField() {
        val settings = LoomSettings(
            dotSize = 14,
            defaultDensity = 24,
            magnifier = false,
            haptics = false,
            autosave = false,
            undoDepth = 30,
            defaultFormat = ExportFormat.YOLO,
            theme = ThemeMode.LIGHT,
        )
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
    }

    @Test
    fun settingsCodecFallsBackToDefaultsOnGarbage() {
        assertEquals(LoomSettings.DEFAULT, SettingsCodec.decode(null))
        assertEquals(LoomSettings.DEFAULT, SettingsCodec.decode("not json"))
        // clamps out-of-range values instead of trusting them
        val clamped = SettingsCodec.decode("""{"dotSize":99,"defaultDensity":99,"undoDepth":7}""")
        assertEquals(10, clamped.dotSize)
        assertEquals(36, clamped.defaultDensity)
        assertEquals(10, clamped.undoDepth)
    }

    @Test
    fun permsCodecRoundTripsAndToleratesGarbage() {
        val perms = Perms(
            media = true, files = true, folder = true,
            folderName = "LoomDatasets", skipped = false,
        )
        assertEquals(perms, PermsCodec.decode(PermsCodec.encode(perms)))
        assertEquals(Perms.DEFAULT, PermsCodec.decode(null))
        assertEquals(Perms.DEFAULT, PermsCodec.decode("{"))
        assertTrue(Perms(media = true).unlocked)
        assertTrue(Perms(skipped = true).unlocked)
        assertTrue(!Perms.DEFAULT.unlocked)
    }
}
