package com.opentune.data.covers

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackArtworkTest {
    @Test fun laterMetadataCannotReplaceThePlayingCover() {
        var preferred: String? = null
        val art = PlaybackArtwork { preferred }
        val song = Song("one", "Song", "Artist", "https://images.test/cover=w120-h120")
        assertEquals("https://images.test/cover=w1200-h1200", art.choose(song))
        preferred = "https://images.test/other"
        assertEquals("https://images.test/cover=w1200-h1200", art.choose(song.copy(thumbnailUrl = "https://images.test/new")))
        art.choose(song.copy(videoId = "two"))
        assertEquals(preferred, art.choose(song))
    }

    @Test fun switchingTracksWithoutAResolvedCoverNeverShowsTheLastSongsCover() {
        val art = PlaybackArtwork { if (it.videoId == "one") "file:/saved-cover.jpg" else null }
        assertEquals("file:/saved-cover.jpg", art.choose(Song("one", "One", "Artist", null)))
        assertEquals("https://images.test/two", art.choose(Song("two", "Two", "Artist", "https://images.test/two")))
    }
}
