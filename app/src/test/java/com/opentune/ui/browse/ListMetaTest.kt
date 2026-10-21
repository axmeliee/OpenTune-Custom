package com.opentune.ui.browse

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class ListMetaTest {
    private fun song(length: String?) = Song("v", "t", "a", null, length)

    @Test fun countsTracksAndTheirTotalLength() {
        val songs = List(146) { song("4:01") }
        // 146 × 241 s = 35 186 s: 9 h 46 m.
        assertEquals("Playlist · 146 Tracks · 9h 46m", listMeta("Playlist", songs))
    }

    @Test fun underAnHourReadsInMinutes() {
        assertEquals("Album · 3 Tracks · 9 min", listMeta("Album", listOf(song("3:00"), song("3:10"), song("3:00"))))
    }

    @Test fun unknownLengthsAreLeftOut() {
        assertEquals("Playlist · 1 Track", listMeta("Playlist", listOf(song(null))))
    }
}
