package com.opentune.playback

import com.opentune.data.library.HiddenArtist
import com.opentune.data.library.HiddenList
import com.opentune.data.library.HiddenSong
import com.opentune.data.library.LibraryStore
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartShuffleTest {
    private fun song(id: String, artist: String = "Artist $id") = Song(id, "Song $id", artist, null)
    private val list = (1..12).map { song("l$it") }
    private val recs = (1..10).map { song("r$it") }

    @Test fun oneAddedSongAfterEveryFourOfTheList() {
        val mixed = SmartShuffle.mix(list, recs, HiddenList.EMPTY, seed = 7)
        assertEquals(15, mixed.size)
        assertEquals(listOf(4, 9, 14), mixed.indices.filter { mixed[it].smartShuffle })
        // Every list song is there once, and only the picks are marked.
        assertEquals(list.map { it.videoId }.toSet(), mixed.filterNot { it.smartShuffle }.map { it.videoId }.toSet())
        assertTrue(mixed.filter { it.smartShuffle }.all { it.videoId.startsWith("r") })
        assertEquals(listOf("r1", "r2", "r3"), mixed.filter { it.smartShuffle }.map { it.videoId })
    }

    @Test fun theSameSeedGivesTheSameOrderAndTheListOrderDoesntDependOnThePicks() {
        val a = SmartShuffle.mix(list, recs, HiddenList.EMPTY, seed = 42)
        val b = SmartShuffle.mix(list, recs, HiddenList.EMPTY, seed = 42)
        assertEquals(a, b)
        // The queue is shuffled first with no picks; the picks slot in later without moving anything.
        val alone = SmartShuffle.mix(list, emptyList(), HiddenList.EMPTY, seed = 42)
        assertEquals(alone, a.filterNot { it.smartShuffle })
        assertTrue(alone.none { it.smartShuffle })
        assertTrue(SmartShuffle.mix(list, recs, HiddenList.EMPTY, seed = 43) != a)
    }

    @Test fun picksRespectTheHiddenListAndSkipWhatTheListHas() {
        val hidden = HiddenList(listOf(HiddenSong("r1", "", "")), listOf(HiddenArtist("Pritam")))
        val picks = listOf(song("r1"), song("l3"), song("r2", "Pritam, KK"), song("r3"), song("r3"), song("r4"))
        val mixed = SmartShuffle.mix(list, picks, hidden, seed = 1)
        assertEquals(listOf("r3", "r4"), mixed.filter { it.smartShuffle }.map { it.videoId })
        assertEquals(list.size + 2, mixed.size)
    }

    @Test fun theListIsNeverFilteredOrCutShort() {
        val hidden = HiddenList(listOf(HiddenSong("l1", "", "")), listOf(HiddenArtist("Artist l2")))
        val mixed = SmartShuffle.mix(list, listOf(song("r1")), hidden, seed = 3)
        // The user's own songs stay even when hidden; one pick, then the rest of the list.
        assertEquals(list.size + 1, mixed.size)
        assertTrue(mixed.any { it.videoId == "l1" } && mixed.any { it.videoId == "l2" })
        assertEquals(4, mixed.indexOfFirst { it.smartShuffle })
    }

    @Test fun aShortListGetsWhatFits() {
        assertEquals(3, SmartShuffle.mix(list.take(3), recs, HiddenList.EMPTY, seed = 1).size)
        assertEquals(emptyList<Song>(), SmartShuffle.mix(emptyList(), recs, HiddenList.EMPTY, seed = 1))
        // A song marked by an earlier smart shuffle counts as the list's own when it's in the list.
        val again = SmartShuffle.mix(list.take(4).map { it.copy(smartShuffle = true) }, recs, HiddenList.EMPTY, seed = 1)
        assertEquals(listOf(false, false, false, false, true), again.map { it.smartShuffle })
    }

    @Test fun insertionsSlotThePicksIntoTheQueueAsItIs() {
        val mixed = SmartShuffle.mix(list, recs, HiddenList.EMPTY, seed = 5)
        val upcoming = mixed.filterNot { it.smartShuffle }.map { it.videoId }
        val plan = SmartShuffle.insertions(upcoming, mixed)!!
        assertEquals(listOf(4, 9, 14), plan.map { it.first })
        // Inserted in order, they rebuild the mix.
        val queue = upcoming.toMutableList()
        plan.forEach { (at, s) -> queue.add(at, s.videoId) }
        assertEquals(mixed.map { it.videoId }, queue)
    }

    @Test fun aQueueChangedMeanwhileIsNotPatchedBlindly() {
        val mixed = SmartShuffle.mix(list, recs, HiddenList.EMPTY, seed = 5)
        val upcoming = mixed.filterNot { it.smartShuffle }.map { it.videoId }
        assertNull(SmartShuffle.insertions(upcoming.drop(1), mixed))
        assertNull(SmartShuffle.insertions(upcoming + "added", mixed))
        assertNull(SmartShuffle.insertions(upcoming.reversed(), mixed))
    }

    @Test fun turningItOffRemovesOnlyAddedSongsNotKept() {
        val upcoming = listOf(song("l1"), song("r1").copy(smartShuffle = true), song("l2"), song("r2").copy(smartShuffle = true), song("r3").copy(smartShuffle = true))
        // Highest first, so removing in order doesn't shift the rest.
        assertEquals(listOf(4, 1), SmartShuffle.toRemove(upcoming, kept = setOf("r2")))
        assertEquals(listOf(4, 3, 1), SmartShuffle.toRemove(upcoming, kept = emptySet()))
    }

    @Test fun hidingASongDropsItFromTheAddedOnes() {
        val upcoming = listOf(song("l1"), song("r1", "Pritam").copy(smartShuffle = true), song("r2").copy(smartShuffle = true), song("l2", "Pritam"))
        val hidden = HiddenList(artists = listOf(HiddenArtist("Pritam")))
        // Only the added Pritam song goes; the list's own one stays.
        assertEquals(listOf(1), SmartShuffle.toRemove(upcoming, emptySet(), hidden))
    }

    @Test fun theShuffleButtonCyclesThroughSmartShuffleOnlyForLists() {
        assertEquals(ShuffleMode.ON, ShuffleMode.OFF.next(smartAvailable = true))
        assertEquals(ShuffleMode.SMART, ShuffleMode.ON.next(smartAvailable = true))
        assertEquals(ShuffleMode.OFF, ShuffleMode.SMART.next(smartAvailable = true))
        // A plain radio queue: off and on only.
        assertEquals(ShuffleMode.OFF, ShuffleMode.ON.next(smartAvailable = false))
        assertFalse(ShuffleMode.entries.any { it.next(false) == ShuffleMode.SMART })
    }

    @Test fun aListRemembersItsPlaylistAndWhetherItCanBeMixed() {
        LibraryStore.importJson(Json.parseToJsonElement("{}"))
        val id = LibraryStore.createPlaylist("Mine")
        val mine = SmartShuffle.startList(list.map { it.copy(playbackSourceId = id) })
        assertEquals(id, mine.playlistId)
        assertTrue(mine.canMix)
        // An album or someone else's playlist: no "Keep in playlist".
        assertNull(SmartShuffle.startList(list).playlistId)
        // Files on the phone give the engines nothing to start from.
        assertFalse(SmartShuffle.startList(listOf(song("${LocalMusic.PREFIX}1"), song("${LocalMusic.PREFIX}2"))).canMix)
        SmartShuffle.clear()
        LibraryStore.importJson(Json.parseToJsonElement("{}"))
    }
}
