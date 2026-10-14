package com.opentune.data.library

import com.opentune.data.Collection
import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySnapshotTest {
    @Test fun savedCollectionPreservesOrderDuplicatesAndArtwork() {
        val song = Song("a", "Title", "Artist", "https://images.test/w1200-h1200", "3:12", albumId = "album", isExplicit = true)
        val collection = Collection("VLplaylist", "Playlist", "Artist", song.thumbnailUrl,
            listOf(song.copy(setVideoId = "first"), song.copy(setVideoId = "second")))
        val restored = Json.decodeFromString(Collection.serializer(), Json.encodeToString(Collection.serializer(), collection))
        assertEquals(collection, restored)
        assertEquals(listOf("first", "second"), restored.songs.map { it.setVideoId })
    }
}
