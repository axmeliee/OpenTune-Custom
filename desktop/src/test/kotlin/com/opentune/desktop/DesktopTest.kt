package com.opentune.desktop

import com.opentune.data.model.Song
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTest {
    @Test fun rangesAreClippedToTheStream() {
        assertEquals(0L to 999L, Ranges.parse(null, 1000))
        assertEquals(100L to 999L, Ranges.parse("bytes=100-", 1000))
        assertEquals(100L to 199L, Ranges.parse("bytes=100-199", 1000))
        assertEquals(900L to 999L, Ranges.parse("bytes=-100", 1000))
        assertEquals(500L to 999L, Ranges.parse("bytes=500-5000", 1000))
        assertNull(Ranges.parse("bytes=1000-", 1000))
    }

    @Test fun theArtistIsFoundInACardsGreyLine() {
        assertEquals("River Choir", Pages.artistIn("Song • River Choir • 2.1M plays"))
        assertEquals("River Choir", Pages.artistIn("River Choir • 1.2M views"))
        assertEquals("River Choir", Pages.artistIn("River Choir"))
    }

    @Test fun timesReadAsMinutesAndSeconds() {
        assertEquals("0:00", Time.format(0))
        assertEquals("3:21", Time.format(201_000))
        assertEquals("1:02:03", Time.format(3_723_000))
    }

    @Test fun likesAndRecentPlaysAreKeptBetweenRuns() {
        val file = Files.createTempDirectory("opentune").resolve("desktop.json").toFile()
        Library.useFile(file)
        val song = Song("dQw4w9WgXcQ", "Paper Boats", "River Choir", null)
        Library.setLiked(song, true)
        Library.played(song)
        Library.played(Song("aaaaaaaaaaa", "Lanterns", "River Choir", null))
        assertTrue(Library.isLiked(song.videoId))
        Library.useFile(file)
        assertTrue(Library.isLiked(song.videoId))
        assertEquals(listOf("aaaaaaaaaaa", "dQw4w9WgXcQ"), Library.recent.value.map { it.videoId })
        Library.setLiked(song, false)
        assertFalse(Library.isLiked(song.videoId))
    }
}
