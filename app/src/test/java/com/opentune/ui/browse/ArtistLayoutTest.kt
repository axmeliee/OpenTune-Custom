package com.opentune.ui.browse

import com.materialkolor.hct.Hct
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArtistLayoutTest {
    private fun release(id: String, subtitle: String) = ShelfItem(id, subtitle, null, null, "MPREb_$id")

    @Test fun newestReleaseAcrossRows() {
        val latest = latestRelease(
            listOf(
                HomeShelf("Albums", listOf(release("dedh", "2013"), release("kabir", "2011"))),
                HomeShelf("Singles & EPs", listOf(release("aaye", "2026"), release("duniya", "2025"))),
            ),
        )!!
        assertEquals("aaye", latest.item.title)
        assertEquals("Single • 2026", latest.line)
    }

    /** Nothing says which came out later in the same year, so the row listed first wins. */
    @Test fun aTieGoesToTheFirstRow() {
        val latest = latestRelease(
            listOf(
                HomeShelf("Albums", listOf(release("album", "Album • 2026"))),
                HomeShelf("Singles & EPs", listOf(release("single", "Single • 2026"))),
            ),
        )!!
        assertEquals("album", latest.item.title)
        assertEquals("Album • 2026", latest.line)
    }

    @Test fun onlyReleasesCount() {
        assertNull(
            latestRelease(
                listOf(
                    HomeShelf("Featured on", listOf(ShelfItem("Bollywood Folk", "Playlist • YouTube Music", null, null, "VLRDCLAK"))),
                    HomeShelf("Fans might also like", listOf(ShelfItem("Harshdeep Kaur", "164M monthly audience", null, null, "UCx"))),
                ),
            ),
        )
        assertNull(latestRelease(emptyList()))
    }

    @Test fun aReleaseWithoutAYearStillShowsWhenItIsAllThereIs() {
        val latest = latestRelease(listOf(HomeShelf("Singles", listOf(release("x", "")))))!!
        assertEquals("x", latest.item.title)
        assertEquals("Single", latest.line)
    }

    @Test fun kindsAndYears() {
        assertEquals("EP", releaseKind("EP • 2024"))
        assertEquals("Single", releaseKind("Singles & EPs"))
        assertEquals("Album", releaseKind("Albums"))
        assertEquals("Album", releaseKind("Album • 2013"))
        assertNull(releaseKind("2013"))
        assertNull(releaseKind("Playlists by Ed Sheeran"))
        assertEquals(2026, releaseYear("Single • 2026"))
        assertEquals(2013, releaseYear("2013"))
        assertNull(releaseYear("Playlist • 10K views"))
        assertNull(releaseYear("12345 plays"))
    }

    /** A deep shade of the photo's hue in the dark, a pale one in the light; a grey photo stays grey. */
    @Test fun pageColourKeepsTheHue() {
        val red = 0xFFD32F2F.toInt()
        val dark = Hct.fromInt(artistPageColor(red, dark = true))
        val light = Hct.fromInt(artistPageColor(red, dark = false))
        val hue = Hct.fromInt(red).hue
        assertTrue(abs(dark.hue - hue) < 8)
        assertTrue(abs(light.hue - hue) < 8)
        assertTrue(abs(dark.tone - DARK_TONE) < 1.5)
        assertTrue(abs(light.tone - LIGHT_TONE) < 1.5)
        assertTrue(dark.chroma <= 27)

        val grey = Hct.fromInt(artistPageColor(0xFF808080.toInt(), dark = true))
        assertTrue(grey.chroma < 2)
    }
}
