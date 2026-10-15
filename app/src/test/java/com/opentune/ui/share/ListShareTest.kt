package com.opentune.ui.share

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListShareTest {
    @Test fun linksOpenWhereYouTubeMusicAndOpenTuneUnderstandThem() {
        assertEquals("https://music.youtube.com/playlist?list=PLabc123", ListShare.linkFor("VLPLabc123"))
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_xyz", ListShare.linkFor("OLAK5uy_xyz"))
        assertEquals("https://music.youtube.com/browse/MPREb_abc", ListShare.linkFor("MPREb_abc"))
    }

    @Test fun anAppPlaylistGoesAsItsSongs() {
        val songs = List(52) { Song("id$it", "Song $it", "Artist", null, "3:00") }
        val text = ListShare.localText("Road trip", songs)
        assertTrue(text.startsWith("Road trip · 52 songs"))
        assertTrue(text.contains("1. Song 0 – Artist\nhttps://music.youtube.com/watch?v=id0"))
        assertTrue(text.contains("50. Song 49"))
        assertTrue("only the first 50 are listed", !text.contains("Song 50 "))
        assertTrue(text.contains("…and 2 more"))
    }
}
