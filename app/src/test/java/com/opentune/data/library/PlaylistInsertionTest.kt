package com.opentune.data.library

import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.SettingsState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaylistInsertionTest {
    private lateinit var id: String
    private val a = Song("a", "First", "Artist", null)
    private val b = Song("b", "Second", "Artist", null)
    private val c = Song("c", "Third", "Artist", null)

    @Before fun setUp() {
        AppSettings.updateLibrary { it.copy(addNewSongsFirst = false) }
        LibraryStore.importJson(Json.parseToJsonElement("{}"))
        id = LibraryStore.createPlaylist("Test", a)
    }

    @After fun resetPreference() {
        AppSettings.updateLibrary { it.copy(addNewSongsFirst = false) }
    }

    @Test fun defaultAppendsAndSwitchingBackRestoresAppending() {
        LibraryStore.addToPlaylist(id, b)
        assertOrder("a", "b")
        AppSettings.updateLibrary { it.copy(addNewSongsFirst = true) }
        LibraryStore.addToPlaylist(id, c)
        assertOrder("c", "a", "b")
        AppSettings.updateLibrary { it.copy(addNewSongsFirst = false) }
        LibraryStore.addToPlaylist(id, Song("d", "Fourth", "Artist", null))
        assertOrder("c", "a", "b", "d")
    }

    @Test fun batchPrependsInOrderAndSkipsExistingAndRepeatedSongs() {
        AppSettings.updateLibrary { it.copy(addNewSongsFirst = true) }
        LibraryStore.addAllToPlaylist(id, listOf(b, a, b, c))
        assertOrder("b", "c", "a")
        LibraryStore.addToPlaylist(id, a)
        assertOrder("b", "c", "a")
        LibraryStore.addAllToPlaylist(id, emptyList())
        assertOrder("b", "c", "a")
    }

    @Test fun enablingThePreferenceDoesNotReorderExistingSongs() {
        LibraryStore.addAllToPlaylist(id, listOf(b, c))
        AppSettings.updateLibrary { it.copy(addNewSongsFirst = true) }
        assertOrder("a", "b", "c")
    }

    @Test fun syncedPlaylistStillAppendsWithoutChangingItsBatchOrDuplicates() {
        LibraryStore.link(id, "PLcustom-test", null)
        AppSettings.updateLibrary { it.copy(addNewSongsFirst = true) }
        LibraryStore.addAllToPlaylist(id, listOf(b, a, b, c))
        assertOrder("a", "b", "c")
    }

    @Test fun playlistAwaitingItsOnlineCopyStillAppends() {
        id = LibraryStore.createPlaylist("Waiting", a, com.opentune.data.model.PlaylistPrivacy.PRIVATE)
        AppSettings.updateLibrary { it.copy(addNewSongsFirst = true) }
        LibraryStore.addToPlaylist(id, b)
        assertOrder("a", "b")
    }

    @Test fun savedPreferenceSurvivesSerializationAndOldSettingsKeepDefault() {
        assertFalse(Json.decodeFromString<SettingsState>("{}").library.addNewSongsFirst)
        val state = SettingsState(library = com.opentune.data.settings.LibrarySettings(addNewSongsFirst = true))
        assertTrue(Json.decodeFromString<SettingsState>(Json.encodeToString(state)).library.addNewSongsFirst)
    }

    private fun assertOrder(vararg ids: String) {
        assertEquals(ids.toList(), LibraryStore.playlist(id)!!.songs.map { it.videoId })
    }
}
