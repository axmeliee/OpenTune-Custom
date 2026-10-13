package com.opentune.data

import com.opentune.data.settings.LyricsSource
import com.opentune.data.settings.SettingsState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDefaultsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    @Test fun missingSavedSettingsUseTheNewDefaults() {
        for (body in listOf("{}", """{"playback":{},"lyrics":{}}""")) {
            val settings = json.decodeFromString<SettingsState>(body)
            assertTrue(settings.playback.stopOnTaskRemoved)
            assertFalse(settings.playback.losslessUnmeteredOnly)
            assertTrue(settings.lyrics.preferWordSynced)
            assertTrue(settings.lyrics.romanize)
            assertEquals(LyricsSource.entries.toList(), settings.lyrics.ordered.map { it.source })
            assertTrue(settings.lyrics.ordered.all { it.enabled })
        }
        // The iOS 26 look: Liquid Glass and the dock that minimizes.
        val ui = json.decodeFromString<SettingsState>("{}").ui
        assertTrue(ui.liquidGlass)
        assertEquals(com.opentune.data.settings.GlassStyle.LIQUID, ui.glassStyle)
        assertEquals(com.opentune.data.settings.DockMotion.MINIMIZE, ui.dockMotion)
    }

    @Test fun anUpdateAndBackupKeepExplicitSavedChoices() {
        val body = """{
            "playback":{"stopOnTaskRemoved":false,"losslessUnmeteredOnly":true},
            "lyrics":{"preferWordSynced":false,"romanize":false,
                "sources":[{"source":"SIMPMUSIC","enabled":false}],
                "offsets":{"4NRXx6U8ABQ":500},"chosen":{"4NRXx6U8ABQ":"SIMPMUSIC"}}
        }"""
        val settings = json.decodeFromString<SettingsState>(body)
        assertFalse(settings.playback.stopOnTaskRemoved)
        assertTrue(settings.playback.losslessUnmeteredOnly)
        assertFalse(settings.lyrics.preferWordSynced)
        assertFalse(settings.lyrics.romanize)
        assertEquals(LyricsSource.SIMPMUSIC, settings.lyrics.ordered.first().source)
        assertFalse(settings.lyrics.ordered.first().enabled)
        assertEquals(500L, settings.lyrics.offsets["4NRXx6U8ABQ"])
        assertEquals(LyricsSource.SIMPMUSIC, settings.lyrics.chosen["4NRXx6U8ABQ"])
        assertEquals(settings, json.decodeFromString<SettingsState>(json.encodeToString(settings)))
    }
}
