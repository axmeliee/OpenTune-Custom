package com.opentune.data.library

import com.opentune.data.history.PlayRecord
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenListTest {
    private fun song(id: String, artist: String, artistId: String? = null) = Song(id, "Song $id", artist, null, artistId = artistId)

    private val hidden = HiddenList(
        songs = listOf(HiddenSong("skip", "Skip me", "Someone")),
        artists = listOf(HiddenArtist("Arijit Singh", "UCarijit"), HiddenArtist("Simon & Garfunkel"), HiddenArtist("Lil Nas X")),
    )

    @Test fun aHiddenSongIsCaughtByItsId() {
        assertTrue(hidden.hides(song("skip", "Anyone")))
        assertFalse(hidden.hides(song("keep", "Anyone")))
    }

    @Test fun aHiddenArtistIsCaughtWhereverTheyAreCredited() {
        assertTrue(hidden.hides(song("1", "Arijit Singh")))
        assertTrue(hidden.hides(song("2", "Shreya Ghoshal, Arijit Singh")))
        assertTrue(hidden.hides(song("3", "Pritam & Arijit Singh")))
        assertTrue(hidden.hides(song("4", "Badshah feat. Arijit Singh")))
        // Case and spacing don't matter.
        assertTrue(hidden.hides(song("5", "  arijit  singh ")))
        // A row that spells the name another way is still caught by the artist's id.
        assertTrue(hidden.hides(song("6", "Arijit", artistId = "UCarijit")))
        assertFalse(hidden.hides(song("7", "Shreya Ghoshal", artistId = "UCshreya")))
    }

    @Test fun namesWithSeparatorsInThemStillMatch() {
        assertTrue(hidden.hides(song("1", "Simon & Garfunkel")))
        assertTrue(hidden.hides(song("2", "Simon & Garfunkel, Someone Else")))
        assertTrue(hidden.hides(song("3", "Lil Nas X, Jack Harlow")))
        // Half of a duo isn't the duo.
        assertFalse(hidden.hides(song("4", "Simon")))
        assertFalse(hidden.hides(song("5", "Paul Simon")))
    }

    @Test fun theArtistToHideIsTheFirstCredited() {
        assertEquals("Shreya Ghoshal", HiddenList.mainArtist("Shreya Ghoshal, Arijit Singh"))
        assertEquals("Pritam", HiddenList.mainArtist("Pritam & Arijit Singh"))
        assertEquals("Badshah", HiddenList.mainArtist("Badshah feat. Arijit Singh"))
        assertEquals("Taylor Swift", HiddenList.mainArtist("Taylor Swift"))
    }

    @Test fun filteringKeepsOrderAndAnEmptyListChangesNothing() {
        val list = listOf(song("a", "Pritam"), song("skip", "Pritam"), song("b", "Arijit Singh"), song("c", "KK"))
        assertEquals(listOf("a", "c"), hidden.songs(list).map { it.videoId })
        assertEquals(list, HiddenList.EMPTY.songs(list))
    }

    @Test fun homeShelvesLoseOnlyHiddenSongCards() {
        val picks = HomeShelf(
            "Quick picks",
            listOf(
                ShelfItem("Song a", "Pritam", null, "a", null),
                ShelfItem("Song b", "Song • Arijit Singh • 2M plays", null, "b", null),
                ShelfItem("Skip me", "Someone", null, "skip", null),
            ),
        )
        // An album card opens a page; it doesn't play on its own, so it stays.
        val albums = HomeShelf("Albums", listOf(ShelfItem("An album", "Album • Arijit Singh", null, null, "MPREb_1")))
        val onlyHidden = HomeShelf("Because you played", listOf(ShelfItem("Song c", "Arijit Singh", null, "c", null)))
        val shown = hidden.shelves(listOf(picks, albums, onlyHidden))
        assertEquals(listOf("Quick picks", "Albums"), shown.map { it.title })
        assertEquals(listOf("a"), shown[0].items.map { it.videoId })
        assertEquals(albums, shown[1])
    }

    @Test fun historyRecordsAreFilteredForTheMixes() {
        val records = listOf(
            PlayRecord("a", "A", "Pritam", playedAt = 1),
            PlayRecord("b", "B", "Arijit Singh, Pritam", playedAt = 2),
            PlayRecord("skip", "C", "KK", playedAt = 3),
        )
        assertEquals(listOf("a"), hidden.records(records).map { it.videoId })
    }

    @Test fun anOldLibraryLoadsWithNothingHiddenAndKeepsItsSongs() {
        val old = """{"liked":[{"videoId":"x","title":"X","artist":"Y"}],"disliked":["z"]}"""
        LibraryStore.importJson(Json.parseToJsonElement(old))
        assertTrue(LibraryStore.hidden.value.isEmpty)
        assertTrue(LibraryStore.isLiked("x"))
        assertTrue(LibraryStore.isDisliked("z"))
    }

    @Test fun hidingAndUnhidingIsSavedWithTheLibrary() {
        LibraryStore.importJson(Json.parseToJsonElement("{}"))
        LibraryStore.hideSong(song("s1", "KK"))
        val artist = LibraryStore.hideArtist(song("s2", "Pritam & Arijit Singh", artistId = "UCpritam"))
        assertEquals("Pritam", artist?.name)
        assertEquals("UCpritam", artist?.browseId)
        assertNull(LibraryStore.hideArtist(song("s3", " ")))

        val saved = LibraryStore.exportJson().jsonObject
        assertEquals(1, saved.getValue("hiddenSongs").jsonArray.size)
        assertEquals(1, saved.getValue("hiddenArtists").jsonArray.size)
        // A restored backup hides the same things.
        LibraryStore.importJson(Json.parseToJsonElement("{}"))
        LibraryStore.importJson(saved)
        assertTrue(LibraryStore.hidden.value.hides(song("other", "Pritam")))
        assertTrue(LibraryStore.hidden.value.hidesSong("s1"))

        LibraryStore.unhideSong("s1")
        LibraryStore.unhideArtist(artist!!.key)
        assertTrue(LibraryStore.hidden.value.isEmpty)
    }

    @Test fun hidingAnArtistTwiceKeepsOneEntry() {
        LibraryStore.importJson(Json.parseToJsonElement("{}"))
        LibraryStore.hideArtist(song("1", "Pritam"))
        LibraryStore.hideArtist(song("2", "pritam, KK"))
        assertEquals(1, LibraryStore.hidden.value.artists.size)
        LibraryStore.importJson(Json.parseToJsonElement("{}"))
    }
}
