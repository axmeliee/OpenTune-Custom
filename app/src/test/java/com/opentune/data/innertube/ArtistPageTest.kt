package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistPageTest {
    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun fixture(name: String) = parse(javaClass.classLoader!!.getResource(name)!!.readText())

    /** A real page as YouTube Music served it signed out: header, two video rows, playlists and the bio. */
    @Test fun readsARealArtistPage() {
        val page = InnertubeParser.parseArtistPage(fixture("ytm-artist-rekha-bhardwaj.json"))
        assertEquals("Rekha Bhardwaj", page.name)
        assertTrue(page.thumbnailUrl!!.startsWith("https://yt3.googleusercontent.com/"))
        // The button prints "109K" beside the word Subscribe; on its own it needs its noun.
        assertEquals("109K subscribers", page.subscriberCountText)
        assertEquals("128M monthly audience", page.monthlyListenerCount)
        assertTrue(page.description!!.startsWith("Rekha Bhardwaj is an Indian singer and live performer."))
        // "Videos" and the video-only "Live performances" lead nowhere playable, so only the playlists stay.
        assertEquals(listOf("Playlists by Rekha Bhardwaj"), page.sections.map { it.title })
        val playlists = page.sections.single().items
        assertEquals(listOf("Albums & Singles", "Discography", "Live performance", "Covers"), playlists.map { it.title })
        assertEquals("Playlist • Rekha Bhardwaj • 10K views", playlists.first().subtitle)
        assertEquals("VLPLP6YR5Z9fQU4GxellOFP5Tj_3ZREvsTjM", playlists.first().browseId)
        // This answer had no Top songs shelf, and nothing is made up in its place.
        assertTrue(page.songs.isEmpty())
        assertNull(page.moreSongsBrowseId)
    }

    @Test fun subscriberCountGetsItsNounOnlyWhenBare() {
        assertEquals("109K subscribers", InnertubeParser.subscriberLabel("109K"))
        assertEquals("8,412 subscribers", InnertubeParser.subscriberLabel("8,412"))
        assertEquals("1.2M subscribers", InnertubeParser.subscriberLabel("1.2M subscribers"))
        assertEquals("1.2 M abonnés", InnertubeParser.subscriberLabel("1.2 M abonnés"))
    }

    private fun page(sections: String) = parse(
        """
        {"header": {"musicImmersiveHeaderRenderer": {"title": {"runs": [{"text": "Artist"}]}}},
         "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {
           "contents": [$sections]
         }}}}]}}}
        """,
    )

    private val songRow = """
        {"musicResponsiveListItemRenderer": {
          "playlistItemData": {"videoId": "v1"},
          "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Song one"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "12M plays"}]}}}
          ]
        }}
    """

    /** The current layout's Top songs link is a "Show all" under the rows, not the heading. */
    @Test fun topSongsLinkUnderTheRows() {
        val parsed = InnertubeParser.parseArtistPage(
            page(
                """
                {"musicShelfRenderer": {
                  "title": {"runs": [{"text": "Top songs"}]},
                  "contents": [$songRow],
                  "bottomText": {"runs": [{"text": "Show all"}]},
                  "bottomEndpoint": {"browseEndpoint": {"browseId": "VLOLAK5uy_top"}}
                }}
                """,
            ),
        )
        assertEquals(listOf("Song one"), parsed.songs.map { it.title })
        assertEquals("Artist", parsed.songs.single().artist)
        assertEquals("VLOLAK5uy_top", parsed.moreSongsBrowseId)
    }

    @Test fun topSongsLinkOnTheHeadingStillReads() {
        val parsed = InnertubeParser.parseArtistPage(
            page(
                """
                {"musicShelfRenderer": {
                  "title": {"runs": [{"text": "Songs", "navigationEndpoint": {"browseEndpoint": {"browseId": "VLOLAK5uy_old"}}}]},
                  "contents": [$songRow]
                }}
                """,
            ),
        )
        assertEquals("VLOLAK5uy_old", parsed.moreSongsBrowseId)
    }

    private fun card(title: String, subtitle: String, browseId: String) = """
        {"musicTwoRowItemRenderer": {
          "title": {"runs": [{"text": "$title"}]},
          "subtitle": {"runs": [{"text": "$subtitle"}]},
          "navigationEndpoint": {"browseEndpoint": {"browseId": "$browseId"}}
        }}
    """

    /** Each row keeps where its "Show all" goes, off the More button, and rows without one have none. */
    @Test fun rowsKeepTheirShowAllLink() {
        val parsed = InnertubeParser.parseArtistPage(
            page(
                """
                {"musicCarouselShelfRenderer": {
                  "header": {"musicCarouselShelfBasicHeaderRenderer": {
                    "title": {"runs": [{"text": "Albums"}]},
                    "moreContentButton": {"buttonRenderer": {"text": {"runs": [{"text": "More"}]},
                      "navigationEndpoint": {"browseEndpoint": {"browseId": "MPADUCartist", "params": "albums=="}}}}
                  }},
                  "contents": [${card("Dedh Ishqiya", "2013", "MPREb_one")}]
                }},
                {"musicCarouselShelfRenderer": {
                  "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Fans might also like"}]}}},
                  "contents": [${card("Harshdeep Kaur", "164M monthly audience", "UCother")}]
                }}
                """,
            ),
        )
        val (albums, fans) = parsed.sections
        assertEquals("MPADUCartist", albums.moreBrowseId)
        assertEquals("albums==", albums.moreParams)
        assertEquals("2013", albums.items.single().subtitle)
        assertNull(fans.moreBrowseId)
        assertEquals("164M monthly audience", fans.items.single().subtitle)
    }

    /** A "Show all" page: the grid's cards and the token for more, with the header's own image left out. */
    @Test fun showAllGridAndItsNextPage() {
        val first = InnertubeParser.parseShelfItemsPage(
            parse(
                """
                {"header": {"musicHeaderRenderer": {"title": {"runs": [{"text": "Albums"}]}}},
                 "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {
                   "contents": [{"gridRenderer": {
                     "items": [${card("One", "Album • 2020", "MPREb_1")}, ${card("Two", "EP • 2018", "MPREb_2")}, ${card("One again", "Album • 2020", "MPREb_1")}],
                     "continuations": [{"nextGridContinuationData": {"continuation": "next"}}, {"nextContinuationData": {"continuation": "grid2"}}]
                   }}]
                 }}}}]}}}
                """,
            ),
        )
        assertEquals(listOf("One", "Two"), first.items.map { it.title })
        assertEquals("grid2", first.continuation)

        val later = InnertubeParser.parseShelfItemsPage(
            parse(
                """
                {"onResponseReceivedActions": [{"appendContinuationItemsAction": {"continuationItems": [
                  ${card("Three", "Single • 2016", "MPREb_3")}
                ]}}]}
                """,
            ),
        )
        assertEquals(listOf("Three"), later.items.map { it.title })
        assertNull(later.continuation)
    }

    /** Related artists can come back as list rows rather than cards. */
    @Test fun showAllReadsArtistRows() {
        val parsed = InnertubeParser.parseShelfItemsPage(
            parse(
                """
                {"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {
                  "contents": [{"musicShelfRenderer": {"contents": [{"musicResponsiveListItemRenderer": {
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "UCrelated",
                      "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ARTIST"}}}},
                    "flexColumns": [
                      {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Piyush Mishra"}]}}},
                      {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "72.1M monthly audience"}]}}}
                    ]
                  }}]}}]
                }}}}]}}}
                """,
            ),
        )
        assertEquals("Piyush Mishra", parsed.items.single().title)
        assertEquals("UCrelated", parsed.items.single().browseId)
    }
}
