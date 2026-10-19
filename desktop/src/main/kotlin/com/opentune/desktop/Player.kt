package com.opentune.desktop

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.Song
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The queue and the song playing, through JavaFX's media player. It reads
 * songs from [Relay]. When the queue runs out it carries on with YouTube
 * Music's up-next for the last song, as the phone does.
 */
object Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _volume = MutableStateFlow(Library.volume())
    val volume: StateFlow<Double> = _volume.asStateFlow()

    val current: Song? get() = _queue.value.getOrNull(_index.value)

    private var media: MediaPlayer? = null

    private val fx by lazy {
        // JavaFX's toolkit, started once, with no window of its own.
        val ready = java.util.concurrent.CountDownLatch(1)
        runCatching { Platform.startup { ready.countDown() } }.onFailure { ready.countDown() }
        Platform.setImplicitExit(false)
        ready.await()
    }

    private fun onFx(block: () -> Unit) {
        fx
        if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
    }

    fun playAll(songs: List<Song>, start: Int) {
        if (songs.isEmpty()) return
        _queue.value = songs
        open(start.coerceIn(songs.indices))
    }

    fun playAt(i: Int) {
        if (i in _queue.value.indices) open(i)
    }

    fun toggle() = onFx {
        val m = media ?: return@onFx current?.let { open(_index.value) } ?: Unit
        if (m.status == MediaPlayer.Status.PLAYING) m.pause() else m.play()
    }

    fun next() {
        val i = _index.value + 1
        if (i in _queue.value.indices) open(i) else extendThen { open(i) }
    }

    /** Back to the start of the song a few seconds in; before that, the song before. */
    fun previous() = onFx {
        val m = media
        if (m != null && m.currentTime.toSeconds() > 4) m.seek(Duration.ZERO)
        else if (_index.value > 0) open(_index.value - 1)
    }

    fun seekTo(ms: Long) = onFx { media?.seek(Duration.millis(ms.toDouble())) }

    fun setVolume(v: Double) {
        _volume.value = v.coerceIn(0.0, 1.0)
        Library.setVolume(_volume.value)
        onFx { media?.volume = _volume.value }
    }

    fun positionMs(): Long = media?.currentTime?.toMillis()?.toLong() ?: 0L
    fun durationMs(): Long = media?.totalDuration?.toMillis()?.takeIf { it.isFinite() }?.toLong() ?: 0L

    private fun open(i: Int) {
        val song = _queue.value.getOrNull(i) ?: return
        _index.value = i
        _error.value = null
        _loading.value = true
        Library.played(song)
        onFx {
            media?.dispose()
            val m = MediaPlayer(Media(Relay.addressOf(song.videoId)))
            media = m
            m.volume = _volume.value
            m.setOnReady { _loading.value = false }
            m.setOnPlaying { _playing.value = true; _loading.value = false }
            m.setOnPaused { _playing.value = false }
            m.setOnStopped { _playing.value = false }
            m.setOnEndOfMedia { next() }
            m.setOnError {
                System.err.println("Player: ${song.videoId}: ${m.error?.message}")
                _loading.value = false
                _playing.value = false
                _error.value = "Couldn't play ${song.title}"
            }
            m.play()
        }
    }

    /** Adds YouTube Music's up-next for the last song, then runs [then]. */
    private fun extendThen(then: () -> Unit) {
        val last = _queue.value.lastOrNull() ?: return
        scope.launch {
            val more = runCatching { InnertubeParser.parseWatchQueue(Innertube.next(last.videoId)) }.getOrDefault(emptyList())
            val have = _queue.value.mapTo(HashSet()) { it.videoId }
            val fresh = more.filter { it.videoId !in have }
            if (fresh.isEmpty()) {
                _playing.value = false
                return@launch
            }
            _queue.value = _queue.value + fresh
            then()
        }
    }
}
