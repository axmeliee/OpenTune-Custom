package com.opentune.data.innertube

import com.opentune.data.model.PlaylistPrivacy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOwnerTest {
    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    /** An own playlist's page, cut down to the header YouTube Music wraps in its edit form. */
    private fun ownPage(privacy: String) = parse(
        """
        {"contents": {"twoColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
          {"musicEditablePlaylistDetailHeaderRenderer": {
            "header": {"musicResponsiveHeaderRenderer": {"title": {"runs": [{"text": "Mine"}]}}},
            "editHeader": {"musicPlaylistEditHeaderRenderer": {"title": {"runs": [{"text": "Mine"}]}, "privacy": "$privacy"}}
          }}
        ]}}}}]}}}
        """,
    )

    @Test fun anOwnPlaylistSaysWhoCanSeeIt() {
        assertEquals(PlaylistPrivacy.UNLISTED, InnertubeParser.parsePlaylistPrivacy(ownPage("UNLISTED")))
        assertEquals(PlaylistPrivacy.PUBLIC, InnertubeParser.parsePlaylistPrivacy(ownPage("PUBLIC")))
        assertEquals(true, InnertubeParser.parsePlaylistOwned(ownPage("PRIVATE")))
    }

    @Test fun aValueItDoesNotKnowIsNotGuessedAt() {
        assertNull(InnertubeParser.parsePlaylistPrivacy(ownPage("SOMETHING_NEW")))
        assertNull(InnertubeParser.parsePlaylistPrivacy(parse("""{"header": {"musicResponsiveHeaderRenderer": {}}}""")))
    }

    @Test fun onlyRealPlaylistsCanBeEdited() {
        assertTrue(InnertubeParser.isEditablePlaylist("VLPLabc"))
        assertFalse(InnertubeParser.isEditablePlaylist("VLLM"))
        assertFalse(InnertubeParser.isEditablePlaylist("VLRDCLAK5uy_abc"))
        assertFalse(InnertubeParser.isEditablePlaylist("VLOLAK5uy_abc"))
        assertFalse(InnertubeParser.isEditablePlaylist("MPREb_abc"))
        assertFalse(InnertubeParser.isEditablePlaylist("PLabc"))
    }
}
