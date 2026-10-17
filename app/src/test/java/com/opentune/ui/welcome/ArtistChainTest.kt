package com.opentune.ui.welcome

import com.opentune.data.ExploreFeed
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistChainTest {
    private fun artistCard(id: String, name: String) = ShelfItem(name, "1M subscribers", "https://img/$id", null, id)
    private fun a(id: String) = ExploreFeed.Artist(id, id, null)

    @Test fun similarTakesTheAllArtistRowFirstAndLeavesOutTheArtistThemself() {
        val page = ArtistPage(
            songs = emptyList(),
            moreSongsBrowseId = null,
            sections = listOf(
                HomeShelf("Albums", listOf(ShelfItem("Folklore", "Album", null, null, "MPREb_1"))),
                HomeShelf("Featured on", listOf(ShelfItem("Mix", "Playlist", null, null, "VLPL1"), artistCard("UCz", "Zed"))),
                HomeShelf("Fans might also like", listOf(artistCard("UCa", "Ana"), artistCard("UCself", "Me"), artistCard("UCb", "Bo"))),
                HomeShelf("Videos", listOf(ShelfItem("Clip", "Video", null, "vid1", "UCa"))),
            ),
        )
        val found = ArtistChain.similar(page, "UCself")
        assertEquals(listOf("UCa", "UCb", "UCz"), found.map { it.browseId })
        assertEquals("Ana", found.first().name)
        assertEquals("https://img/UCa", found.first().thumbnailUrl)
    }

    @Test fun fromRadioCountsHowOftenEachArtistComesUp() {
        fun s(v: String, artist: String, id: String?) = Song(v, v, artist, null, artistId = id)
        val radio = listOf(
            s("1", "Me", "UCself"), s("2", "Bo & Cy", "UCb"), s("3", "Ana", "UCa"),
            s("4", "Bo", "UCb"), s("5", "Unknown", null), s("6", "Bo, Dee", "UCb"),
        )
        assertEquals(listOf("UCb" to "Bo", "UCa" to "Ana"), ArtistChain.fromRadio(radio, "UCself"))
    }

    @Test fun growPlacesNewArtistsRightAfterThePick() {
        val grid = listOf(a("1"), a("2"), a("3"))
        val grown = ArtistChain.grow(grid, "2", listOf(a("3"), a("x"), a("y")))
        assertEquals(listOf("1", "2", "x", "y", "3"), grown.map { it.browseId })
    }

    @Test fun growTakesNoMoreThanItsShareAndNothingTwice() {
        val found = (1..10).map { a("n$it") } + a("n1")
        val grown = ArtistChain.grow(listOf(a("p")), "p", found, max = 4)
        assertEquals(listOf("p", "n1", "n2", "n3", "n4"), grown.map { it.browseId })
    }

    @Test fun growWithNothingNewLeavesTheGridAlone() {
        val grid = listOf(a("1"), a("2"))
        assertEquals(grid, ArtistChain.grow(grid, "1", listOf(a("2"))))
    }

    @Test fun growPutsThemAtTheEndWhenThePickIsGone() {
        assertEquals(listOf("1", "x"), ArtistChain.grow(listOf(a("1")), "gone", listOf(a("x"))).map { it.browseId })
    }
}
