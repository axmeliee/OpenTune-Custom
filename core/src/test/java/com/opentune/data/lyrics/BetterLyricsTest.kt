package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BetterLyricsTest {
    private fun body(dur: String) = """{"ttml":"<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"><head><metadata><ttm:agent type=\"person\" xml:id=\"v1\"/></metadata></head><body dur=\"$dur\"><div><p begin=\"2.629\" end=\"8.267\" ttm:agent=\"v1\"><span begin=\"2.629\" end=\"3.117\">I </span><span begin=\"3.117\" end=\"3.483\">found </span><span begin=\"3.483\" end=\"3.667\">a </span><span begin=\"3.667\" end=\"4.355\">love</span></p></div></body></tt>"}"""

    @Test fun readsWordTimedLines() {
        val lyrics = BetterLyrics.parse(body("4:23.400"), 263_000) as Lyrics.Synced
        assertEquals("Better Lyrics", lyrics.source)
        val line = lyrics.lines.single()
        assertEquals(2_629L, line.startMs)
        assertEquals("I found a love", line.text)
        assertTrue(line.wordSynced)
        assertEquals(3_117L, line.words[1].startMs)
    }

    @Test fun skipsAnotherVersionOfTheSong() {
        // Ten seconds longer than the track: another recording, timed differently.
        assertNull(BetterLyrics.parse(body("4:33.400"), 263_000))
    }

    @Test fun anUncachedSongIsSimplyNotFound() {
        assertNull(BetterLyrics.parse("""{"error":"API key required"}""", 263_000))
    }
}
