package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlusTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun readsWordTimedLinesWithoutBackgroundVocals() {
        val lyrics = LyricsPlus.parse(resource("lyricsplus-blinding-lights.json"), 200_000) as Lyrics.Synced
        assertEquals("LyricsPlus", lyrics.source)
        val first = lyrics.lines.first()
        assertEquals("I been tryna call", first.text)
        assertEquals(27_395L, first.startMs)
        assertTrue(first.wordSynced)
        assertEquals(listOf("I ", "been ", "tryna ", "call"), first.words.map { it.text })
        assertEquals(28_077L, first.words.last().startMs)
        // Every timed word is the lead vocal's: no line carries the backing "(Hey)" sweeps.
        assertTrue(lyrics.lines.all { line -> line.words.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim() == line.text })
    }

    @Test fun lineTimedSongsSpreadTheirWords() {
        val lyrics = LyricsPlus.parse(resource("lyricsplus-aaja-piya.json"), 251_000) as Lyrics.Synced
        assertFalse(lyrics.lines.first().wordSynced)
        assertTrue(lyrics.lines.first().text.startsWith("Aaja piya"))
    }

    @Test fun anotherRecordingsLengthIsTurnedDown() {
        // The catalogue's cut is 4:11; a 4:50 upload would be out of time all the way.
        assertNull(LyricsPlus.parse(resource("lyricsplus-aaja-piya.json"), 290_000))
    }

    @Test fun aMissHasNoLyrics() {
        assertNull(LyricsPlus.parse("""{"error":{"message":"Lyrics not found","status":404}}""", 200_000))
        assertNull(LyricsPlus.parse("not json", 200_000))
    }
}
