package com.opentune.data.reco

import com.opentune.data.ExploreFeed
import com.opentune.data.history.DailyMixes
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.releases.NewReleases
import com.opentune.ui.home.arrangeHomeShelves
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSeedTest {
    private fun song(id: String, artist: String) = Song(id, "Song $id", artist, "https://img/$id")
    private fun artist(id: String, name: String, top: Int, radio: Int) = HomeSeed.Artist(
        id, name,
        top = (1..top).map { song("$id-t$it", name) },
        radio = (1..radio).map { song("$id-r$it", "Someone like $name") },
    )

    @Test fun seedsFromTheNewestFollowsOnlyWhileHistoryIsShort() {
        val followed = (1..6).map { NewReleases.Followed("UC$it", "Artist $it", followedAt = it * 1000L) }
        val seeds = HomeSeed.artists(followed, plays = 3)
        assertEquals(listOf("UC6", "UC5", "UC4", "UC3"), seeds.map { it.browseId })
        assertTrue(HomeSeed.artists(followed, plays = HomeSeed.SHORT_HISTORY).isEmpty())
        assertTrue(HomeSeed.artists(emptyList(), plays = 0).isEmpty())
    }

    @Test fun quickPicksTakeEachArtistInTurn() {
        val seeds = listOf(artist("a", "Arijit Singh", 5, 10), artist("b", "Anuv Jain", 5, 10), artist("c", "Shreya Ghoshal", 5, 10))
        val shelf = HomeSeed.quickPicks(seeds)!!
        // Home's first page of picks shows four rows: no one artist fills it.
        assertEquals(listOf("a-t1", "b-t1", "c-t1", "a-t2"), shelf.items.take(4).map { it.videoId })
        assertEquals(20, shelf.items.size)
        assertEquals(shelf.items.size, shelf.items.distinctBy { it.videoId }.size)
        assertEquals("Because you like Arijit Singh, Anuv Jain and Shreya Ghoshal", shelf.title)
        // Radio songs come after every top song.
        assertTrue(shelf.items.drop(15).all { it.videoId!!.contains("-r") })
    }

    @Test fun tooFewSongsMakeNoPicks() {
        assertNull(HomeSeed.quickPicks(listOf(artist("a", "A", 2, 1))))
        assertNull(HomeSeed.quickPicks(emptyList()))
    }

    @Test fun theSeedLeadsHomeOverTheRadioAndAnyOtherSongs() {
        val seed = HomeSeed.quickPicks(listOf(artist("a", "Arijit Singh", 5, 5)))!!
        fun songs(title: String) = HomeShelf(title, (1..4).map { ShelfItem("$title $it", "X", null, "$title$it", null) })
        val radio = songs("Because you played Tum Hi Ho")
        val generic = songs("Quick picks")
        val sections = arrangeHomeShelves(listOf(generic, radio, seed))
        assertEquals(seed, sections.quickPicks)
        assertEquals(setOf(generic, radio), sections.cards.toSet())
        // Without a seed (history long enough), the radio leads as before.
        assertEquals(radio, arrangeHomeShelves(listOf(generic, radio)).quickPicks)
    }

    @Test fun mixesFillInAfterTheOnesFromListening() {
        val history = listOf(DailyMixes.Mix("artist0", "Daily Mix 1", "Anuv Jain, Prateek Kuhad", (1..8).map { song("h$it", "Anuv Jain") }))
        val seeds = listOf(artist("a", "Anuv Jain", 5, 20), artist("b", "Arijit Singh", 5, 20), artist("c", "Tiny", 2, 2))
        val mixes = HomeSeed.mixes(history, seeds, plays = 8)
        // Anuv Jain already leads a mix from listening, and Tiny has too few songs.
        assertEquals(listOf("artist0", "seed:b"), mixes.map { it.id })
        val seeded = mixes.last()
        assertEquals("Arijit Singh mix", seeded.title)
        assertEquals(listOf("b-t1", "b-r1", "b-t2", "b-r2"), seeded.songs.take(4).map { it.videoId })
        assertEquals(25, seeded.songs.size)
        // Once there's enough history, only listening makes mixes.
        assertEquals(history, HomeSeed.mixes(history, seeds, plays = HomeSeed.SHORT_HISTORY))
        // Nothing played yet: the seeds alone.
        assertEquals(listOf("seed:a", "seed:b"), HomeSeed.mixes(emptyList(), seeds, plays = 0).map { it.id })
    }

    @Test fun namesReadNaturally() {
        assertEquals("A", HomeSeed.names(listOf("A")))
        assertEquals("A and B", HomeSeed.names(listOf("A", "B")))
        assertEquals("A, B and 2 more", HomeSeed.names(listOf("A", "B", "C", "D")))
    }

    /** The welcome's starting grid: the charts' top artists, from a real answer for India. */
    @Test fun chartsGiveTheirTopArtists() {
        val response = Json.parseToJsonElement(javaClass.classLoader!!.getResource("ytm-charts-in.json")!!.readText()).jsonObject
        val artists = ExploreFeed.artistsOf(InnertubeParser.parseHome(response))
        assertEquals(listOf("Alka Yagnik", "Udit Narayan", "Arijit Singh", "Shreya Ghoshal", "Kumar Sanu", "Shilpi Raj"), artists.map { it.name })
        assertTrue(artists.all { it.browseId.startsWith("UC") && it.thumbnailUrl != null })
    }
}
