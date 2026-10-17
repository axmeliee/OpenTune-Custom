package com.opentune.ui.share

import com.opentune.data.history.History
import com.opentune.data.history.PlayRecord
import com.opentune.ui.share.ReplayCard.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayCardTest {
    private fun play(id: String, artist: String, minutes: Long, album: String? = null, title: String = "Song $id", at: Long = 1_000) =
        PlayRecord(id, title, artist, "https://img.invalid/$id=w60-h60", "3:00", album, at, minutes * 60_000)

    /** Eight songs by seven artists, played less the further down the list. */
    private val records = (0 until 8).flatMap { i ->
        List(8 - i) { play("s$i", if (i == 7) "Artist 0, Guest" else "Artist $i", 3, album = "Album ${i % 4}") }
    }

    @Test fun topListsStopAtFiveAndAlbumsAtThree() {
        val c = ReplayCard.content(History.replay(records, 0), 2026, "Last 4 weeks", "Shivam")
        assertEquals(listOf("Song s0", "Song s1", "Song s2", "Song s3", "Song s4"), c.topSongs.map { it.title })
        assertEquals(listOf("Artist 0", "Artist 1", "Artist 2", "Artist 3", "Artist 4"), c.topArtists.map { it.title })
        assertEquals(3, c.topAlbums.size)
        // Song s0 played eight times, three minutes each.
        assertEquals("24 min", c.topSongs[0].minutes)
        assertEquals("Artist 0", c.topSongs[0].subtitle)
        // Artist 0 also has the guest spot on s7: 8 + 1 plays.
        assertEquals("27 min", c.topArtists[0].minutes)
        // Their top song comes along, to look up their photo.
        assertEquals("s0", c.topArtists[0].song?.videoId)
    }

    @Test fun countsAreTheWholeWindowNotTheLists() {
        val c = ReplayCard.content(History.replay(records, 0), 2026, "All time", null)
        assertEquals(records.size, c.plays)
        assertEquals(8, c.songs)
        assertEquals(7, c.artists)
        assertEquals(records.size * 3L, c.minutes)
        assertEquals("36 plays · 8 songs · 7 artists", ReplayCard.counts(c))
    }

    @Test fun titleAndName() {
        val c = ReplayCard.content(History.replay(records, 0), 2026, "This year", "  ")
        assertEquals("Replay ’26", c.title)
        assertNull(c.name)
        assertEquals("Replay ’05", ReplayCard.title(2005))
    }

    @Test fun minutes() {
        assertEquals("<1 min", ReplayCard.minutes(0))
        assertEquals("<1 min", ReplayCard.minutes(59_000))
        assertEquals("1 min", ReplayCard.minutes(60_000))
        assertEquals("52 min", ReplayCard.minutes(52 * 60_000L + 30_000))
        assertEquals("%,d min".format(1240), ReplayCard.minutes(1240 * 60_000L))
    }

    @Test fun singularCounts() {
        val c = ReplayCard.content(History.replay(listOf(play("a", "Solo", 3)), 0), 2026, "All time", null)
        assertEquals("1 play · 1 song · 1 artist", ReplayCard.counts(c))
    }

    @Test fun emptySectionsAreLeftOut() {
        // No albums and no genres in the history: only the lists.
        val noAlbums = ReplayCard.content(History.replay(listOf(play("a", "Solo", 3)), 0), 2026, "All time", null)
        assertTrue(noAlbums.topAlbums.isEmpty())
        assertTrue(noAlbums.genres.isEmpty())
        assertEquals(listOf(Section.LISTS), ReplayCard.sections(noAlbums))

        val full = ReplayCard.content(History.replay(records, 0), 2026, "All time", null, genres = listOf("Hindi", " ", "hindi", "Pop"))
        assertEquals(listOf("Hindi", "Pop"), full.genres)
        assertEquals(listOf(Section.LISTS, Section.ALBUMS, Section.GENRES), ReplayCard.sections(full))

        // Nothing in the window: nothing under the minutes.
        val empty = ReplayCard.content(History.replay(records, Long.MAX_VALUE), 2026, "Last 4 weeks", null)
        assertEquals(emptyList<Section>(), ReplayCard.sections(empty))
    }

    @Test fun blankTitlesAreSkipped() {
        val list = listOf(play("a", "", 3, title = ""), play("b", "Real", 3))
        val c = ReplayCard.content(History.replay(list, 0), 2026, "All time", null)
        assertEquals(listOf("Song b"), c.topSongs.map { it.title })
        assertEquals(listOf("Real"), c.topArtists.map { it.title })
        assertEquals(1, c.artists)
    }
}
