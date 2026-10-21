package com.opentune.desktop

import com.opentune.data.model.Song
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Liked songs, your playlists, what was played lately and the volume, kept
 * in one small file in the user's own folder: ~/.opentune/desktop.json.
 */
object Library {
    @Serializable
    data class Saved(val id: String, val title: String, val artist: String, val cover: String? = null, val length: String? = null) {
        fun toSong() = Song(id, title, artist, cover, length)
    }

    @Serializable
    data class Playlist(val id: String, val name: String, val songs: List<Saved> = emptyList()) {
        /** Up to four different covers, for the playlist's tile. */
        val covers: List<String> get() = songs.mapNotNull { it.cover }.distinct().take(4)
    }

    @Serializable
    private data class State(
        val liked: List<Saved> = emptyList(),
        val recent: List<Saved> = emptyList(),
        val volume: Double = 0.8,
        val playlists: List<Playlist> = emptyList(),
    )

    private const val RECENT = 50
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var file = File(System.getProperty("user.home"), ".opentune/desktop.json")

    private val state = MutableStateFlow(load())
    private val _liked = MutableStateFlow(state.value.liked.map { it.toSong() })
    val liked: StateFlow<List<Song>> = _liked.asStateFlow()
    private val _recent = MutableStateFlow(state.value.recent.map { it.toSong() })
    val recent: StateFlow<List<Song>> = _recent.asStateFlow()
    private val _playlists = MutableStateFlow(state.value.playlists)
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** For tests: keeps the library in [at] instead. */
    internal fun useFile(at: File) {
        file = at
        state.value = load()
        publish()
    }

    fun isLiked(id: String) = _liked.value.any { it.videoId == id }

    fun setLiked(song: Song, liked: Boolean) = update { s ->
        val rest = s.liked.filterNot { it.id == song.videoId }
        s.copy(liked = if (liked) listOf(song.saved()) + rest else rest)
    }

    fun played(song: Song) = update { s ->
        s.copy(recent = (listOf(song.saved()) + s.recent.filterNot { it.id == song.videoId }).take(RECENT))
    }

    /** A new, empty playlist; its id is returned. */
    fun createPlaylist(name: String): String {
        val id = "pl-" + java.util.UUID.randomUUID().toString().take(8)
        update { it.copy(playlists = it.playlists + Playlist(id, name.trim().ifEmpty { "New playlist" })) }
        return id
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun deletePlaylist(id: String) = update { s -> s.copy(playlists = s.playlists.filterNot { it.id == id }) }

    /** Adds [song] at the end, unless it's already there. */
    fun addToPlaylist(id: String, song: Song) = editPlaylist(id) { p ->
        if (p.songs.any { it.id == song.videoId }) p else p.copy(songs = p.songs + song.saved())
    }

    fun removeFromPlaylist(id: String, videoId: String) = editPlaylist(id) { p -> p.copy(songs = p.songs.filterNot { it.id == videoId }) }

    private fun editPlaylist(id: String, change: (Playlist) -> Playlist) = update { s ->
        s.copy(playlists = s.playlists.map { if (it.id == id) change(it) else it })
    }

    fun volume(): Double = state.value.volume
    fun setVolume(v: Double) = update { it.copy(volume = v) }

    private fun Song.saved() = Saved(videoId, title, artist, thumbnailUrl, durationText)

    @Synchronized
    private fun update(change: (State) -> State) {
        state.value = change(state.value)
        publish()
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(State.serializer(), state.value))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private fun publish() {
        _liked.value = state.value.liked.map { it.toSong() }
        _recent.value = state.value.recent.map { it.toSong() }
        _playlists.value = state.value.playlists
    }

    private fun load(): State = runCatching { json.decodeFromString(State.serializer(), file.readText()) }.getOrDefault(State())
}
