package com.opentune.data.library

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.account.AccountStore
import com.opentune.data.innertube.Innertube
import com.opentune.data.model.LikeStatus
import com.opentune.data.model.Song
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A track as stored on the device: enough to show it and play it again. */
@Serializable
data class SongRef(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    /** YouTube Music's "E" mark; null for songs saved before it was kept. */
    val explicit: Boolean? = null,
) {
    fun toSong() = Song(videoId, title, artist, thumbnailUrl, durationText, albumName = albumName, isExplicit = explicit)

    companion object {
        fun of(song: Song) = SongRef(song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName, song.isExplicit)
    }
}

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<SongRef> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/** An album or playlist from YouTube Music saved to the library with its heart. */
@Serializable
data class SavedList(
    val browseId: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

@Serializable
private data class LibraryDocument(
    val liked: List<SongRef> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    /** Songs marked "Dislike": kept out of autoplay. */
    val disliked: Set<String> = emptySet(),
    /** Albums and playlists saved with their heart, newest first. */
    val savedLists: List<SavedList> = emptyList(),
    /** "Not interested": kept out of suggestions, newest first. */
    val hiddenSongs: List<HiddenSong> = emptyList(),
    /** "Don't play [artist]": their songs kept out of suggestions, newest first. */
    val hiddenArtists: List<HiddenArtist> = emptyList(),
)

/**
 * Liked songs and playlists made in the app, kept on the device. Liking also
 * rates the track on YouTube Music when signed in, so likes follow the
 * account too.
 */
object LibraryStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val doc = MutableStateFlow(LibraryDocument())

    private val _liked = MutableStateFlow<List<SongRef>>(emptyList())
    /** Newest like first. */
    val liked: StateFlow<List<SongRef>> = _liked.asStateFlow()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    private val _savedLists = MutableStateFlow<List<SavedList>>(emptyList())
    /** Albums and playlists saved with their heart, newest first. */
    val savedLists: StateFlow<List<SavedList>> = _savedLists.asStateFlow()

    private val _hidden = MutableStateFlow(HiddenList.EMPTY)
    /** Songs and artists kept out of autoplay, radio, smart shuffle and Home's picks. */
    val hidden: StateFlow<HiddenList> = _hidden.asStateFlow()

    /** Keeps [song] out of suggestions; undone with [unhideSong]. */
    fun hideSong(song: Song): HiddenSong {
        val entry = HiddenSong(song.videoId, song.title, song.artist, song.thumbnailUrl, System.currentTimeMillis())
        update { d -> d.copy(hiddenSongs = listOf(entry) + d.hiddenSongs.filterNot { it.videoId == song.videoId }) }
        return entry
    }

    fun unhideSong(videoId: String) = update { d -> d.copy(hiddenSongs = d.hiddenSongs.filterNot { it.videoId == videoId }) }

    /**
     * Keeps every song by [song]'s first credited artist out of suggestions;
     * null when the song names no artist. Undone with [unhideArtist].
     */
    fun hideArtist(song: Song): HiddenArtist? {
        val name = HiddenList.mainArtist(song.artist).takeIf { it.isNotBlank() } ?: return null
        val entry = HiddenArtist(name, song.artistId, System.currentTimeMillis())
        update { d -> d.copy(hiddenArtists = listOf(entry) + d.hiddenArtists.filterNot { it.key == entry.key }) }
        return entry
    }

    fun unhideArtist(key: String) = update { d -> d.copy(hiddenArtists = d.hiddenArtists.filterNot { it.key == key }) }

    fun isSaved(browseId: String): Boolean = _savedLists.value.any { it.browseId == browseId }

    /** Saves [list] to the library, or with [saved] false takes it out. */
    fun setSaved(list: SavedList, saved: Boolean) = update { d ->
        val rest = d.savedLists.filterNot { it.browseId == list.browseId }
        d.copy(savedLists = if (saved) listOf(list.copy(savedAt = System.currentTimeMillis())) + rest else rest)
    }

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        val f = File(context.filesDir, "library.json")
        publish(runCatching { json.decodeFromString(LibraryDocument.serializer(), f.readText()) }.getOrDefault(LibraryDocument()))
        scope.launch {
            doc.drop(1).debounce(800).collect { d ->
                runCatching {
                    val tmp = File(f.parentFile, "library.json.tmp")
                    tmp.writeText(json.encodeToString(LibraryDocument.serializer(), d))
                    tmp.renameTo(f)
                }.onFailure { Log.w("LibraryStore", "Couldn't save the library", it) }
            }
        }
    }

    private fun publish(d: LibraryDocument) {
        doc.value = d
        _liked.value = d.liked
        _playlists.value = d.playlists
        _savedLists.value = d.savedLists
        _hidden.value = HiddenList(d.hiddenSongs, d.hiddenArtists)
        LibraryArtwork.keep(d.liked.map { it.thumbnailUrl } + d.playlists.flatMap { p -> p.songs.map { it.thumbnailUrl } })
    }

    private fun update(t: (LibraryDocument) -> LibraryDocument) = publish(t(doc.value))

    fun isLiked(videoId: String): Boolean = _liked.value.any { it.videoId == videoId }

    fun isDisliked(videoId: String): Boolean = videoId in doc.value.disliked

    /**
     * Marks [song] as one not to hear again: it's taken out of Liked, kept out
     * of autoplay from now on, and rated down on YouTube Music when signed in.
     */
    fun dislike(song: Song) {
        update { d -> d.copy(liked = d.liked.filterNot { it.videoId == song.videoId }, disliked = d.disliked + song.videoId) }
        if (AccountStore.signedIn.value && !song.videoId.startsWith("local:")) {
            scope.launch {
                runCatching { Innertube.rate(song.videoId, LikeStatus.DISLIKE) }
                    .onFailure { Log.w("LibraryStore", "Couldn't rate ${song.videoId} down on YouTube Music", it) }
            }
        }
    }

    fun setLiked(song: Song, liked: Boolean) {
        update { d ->
            val rest = d.liked.filterNot { it.videoId == song.videoId }
            d.copy(liked = if (liked) listOf(SongRef.of(song)) + rest else rest, disliked = if (liked) d.disliked - song.videoId else d.disliked)
        }
        if (AccountStore.signedIn.value && !song.videoId.startsWith("local:")) {
            scope.launch {
                runCatching { Innertube.rate(song.videoId, if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT) }
                    .onFailure { Log.w("LibraryStore", "Couldn't rate ${song.videoId} on YouTube Music", it) }
            }
        }
    }

    fun createPlaylist(name: String, first: Song? = null): String {
        val id = UUID.randomUUID().toString()
        val p = LocalPlaylist(id, name.trim().ifEmpty { "New playlist" }, listOfNotNull(first?.let(SongRef::of)))
        update { it.copy(playlists = listOf(p) + it.playlists) }
        return id
    }

    /** Adds [songs] in order, skipping any already there; one write for the lot. */
    fun addAllToPlaylist(id: String, songs: List<Song>) = editPlaylist(id) { p ->
        val have = p.songs.mapTo(HashSet()) { it.videoId }
        p.copy(songs = p.songs + songs.filter { have.add(it.videoId) }.map(SongRef::of))
    }

    fun addToPlaylist(id: String, song: Song) = editPlaylist(id) { p ->
        if (p.songs.any { it.videoId == song.videoId }) p else p.copy(songs = p.songs + SongRef.of(song))
    }

    fun removeFromPlaylist(id: String, index: Int) = editPlaylist(id) { p ->
        p.copy(songs = p.songs.filterIndexed { i, _ -> i != index })
    }

    fun movePlaylistItem(id: String, from: Int, to: Int) = editPlaylist(id) { p ->
        if (from !in p.songs.indices || to !in p.songs.indices) p
        else p.copy(songs = p.songs.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun deletePlaylist(id: String) = update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }

    fun playlist(id: String): LocalPlaylist? = _playlists.value.firstOrNull { it.id == id }

    private fun editPlaylist(id: String, t: (LocalPlaylist) -> LocalPlaylist) = update { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) t(it) else it })
    }

    fun exportJson(): JsonElement = json.encodeToJsonElement(LibraryDocument.serializer(), doc.value)

    fun importJson(element: JsonElement) = publish(json.decodeFromJsonElement(LibraryDocument.serializer(), element))
}
