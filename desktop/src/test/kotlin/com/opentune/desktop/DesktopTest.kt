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

    @Test fun playlistsKeepTheirSongsInOrderBetweenRuns() {
        val file = Files.createTempDirectory("opentune").resolve("desktop.json").toFile()
        Library.useFile(file)
        val a = Song("aaaaaaaaaaa", "Lanterns", "River Choir", "https://example.com/a.jpg")
        val b = Song("bbbbbbbbbbb", "Paper Boats", "River Choir", "https://example.com/b.jpg")
        val id = Library.createPlaylist("  Evening  ")
        Library.addToPlaylist(id, a)
        Library.addToPlaylist(id, b)
        Library.addToPlaylist(id, a) // already there
        Library.useFile(file)
        val p = Library.playlists.value.single()
        assertEquals("Evening", p.name)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), p.songs.map { it.id })
        assertEquals(listOf("https://example.com/a.jpg", "https://example.com/b.jpg"), p.covers)
        Library.removeFromPlaylist(id, "aaaaaaaaaaa")
        Library.renamePlaylist(id, "")
        assertEquals("Evening", Library.playlists.value.single().name)
        assertEquals(listOf("bbbbbbbbbbb"), Library.playlists.value.single().songs.map { it.id })
        Library.deletePlaylist(id)
        assertTrue(Library.playlists.value.isEmpty())
    }

    /** The stand-in for Android's preferences, which the shared settings and Subsonic code save through. */
    @Test fun preferencesAreWrittenAndReadBack() {
        val root = Files.createTempDirectory("opentune").toFile()
        val prefs = android.content.Context(root).getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("state", "{\"a\":1}").putInt("n", 3).putBoolean("on", true).apply()
        val again = android.content.Context(root).getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        assertEquals("{\"a\":1}", again.getString("state", null))
        assertEquals(3, again.getInt("n", 0))
        assertTrue(again.getBoolean("on", false))
        assertTrue(again.contains("n"))
        again.edit().remove("n").commit()
        assertFalse(android.content.Context(root).getSharedPreferences("settings", 0).contains("n"))
    }

    @Test fun settingsSurviveARestart() {
        val root = Files.createTempDirectory("opentune").toFile()
        com.opentune.data.settings.AppSettings.init(android.content.Context(root))
        com.opentune.data.settings.AppSettings.addRecentSearch("lofi")
        com.opentune.data.settings.AppSettings.setLyricsOffset("dQw4w9WgXcQ", 250)
        com.opentune.data.settings.AppSettings.init(android.content.Context(root))
        assertEquals(listOf("lofi"), com.opentune.data.settings.AppSettings.recentSearches.value)
        assertEquals(250L, com.opentune.data.settings.AppSettings.lyrics.value.offsets["dQw4w9WgXcQ"])
    }
}
