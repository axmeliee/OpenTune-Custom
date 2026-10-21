package com.opentune.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.MusicRepository
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The watch's connection to the player service, and the songs a search found. */
class WatchPlayer @JvmOverloads constructor(
    application: Application,
    /** False only in tests, which have no player service to reach. */
    connectNow: Boolean = true,
) : AndroidViewModel(application) {
    val player = PlayerConnection(application)
    val currentSong = player.currentSong
    val isPlaying = player.isPlaying
    val isBuffering = player.isBuffering
    val hasNext = player.hasNext
    val queue = player.queue
    val currentIndex = player.currentIndex

    sealed interface Found {
        data object Idle : Found
        data object Searching : Found
        data class Songs(val query: String, val songs: List<Song>) : Found
        data class Failed(val query: String) : Found
    }

    private val _found = MutableStateFlow<Found>(Found.Idle)
    val found = _found.asStateFlow()

    init {
        if (connectNow) viewModelScope.launch { player.connect() }
    }

    fun search(query: String) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        _found.value = Found.Searching
        viewModelScope.launch {
            _found.value = runCatching { MusicRepository.search(q, SearchFilter.SONGS) }
                .map { rows -> Found.Songs(q, songsIn(rows)) }
                .getOrElse { Found.Failed(q) }
        }
    }

    fun playAll(songs: List<Song>, index: Int) = player.playAll(songs, index)
    fun togglePlayPause() = player.togglePlayPause()
    fun skipNext() = player.skipNext()
    fun skipPrevious() = player.skipPrevious()
    fun playQueueItem(index: Int) = player.playQueueItem(index)

    override fun onCleared() {
        player.disconnect()
        super.onCleared()
    }

    companion object {
        /** The songs in a search's rows, in order, without repeats. */
        fun songsIn(rows: List<SearchResult>): List<Song> = rows.mapNotNull {
            when (it) {
                is SearchResult.TopTrack -> it.song
                is SearchResult.Track -> it.song
                is SearchResult.Browse -> null
            }
        }.distinctBy { it.videoId }
    }
}
