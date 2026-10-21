package com.opentune.desktop

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.isYouTubeId
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.subsonic.Subsonic
import javafx.application.Platform
import javafx.scene.media.EqualizerBand
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The queue and the song playing, through JavaFX's media player. It reads
 * YouTube songs from [Relay], and songs on your own server straight from it. When the queue runs out it carries on with YouTube
 * Music's up-next for the last song, as the phone does, when autoplay is
 * on. The phone's speed, balance and 15-band equalizer settings are applied
 * through JavaFX's own equalizer, and the sleep timer winds the volume down.
 */
object Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** When the sleep timer stops playback: at a time, or when the song ends. */
    sealed interface Sleep {
        data class At(val epochMs: Long, val minutes: Int) : Sleep
        data object EndOfSong : Sleep
    }

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

    /** The song's id and its exact length once the player knows it, for lyrics to match on. */
    private val _durationOf = MutableStateFlow("" to 0L)
    val durationOf: StateFlow<Pair<String, Long>> = _durationOf.asStateFlow()

    val current: Song? get() = _queue.value.getOrNull(_index.value)

    private val _sleep = MutableStateFlow<Sleep?>(null)
    val sleep: StateFlow<Sleep?> = _sleep.asStateFlow()
    private var sleepJob: Job? = null
    /** 1 normally; less while the sleep timer winds down. */
    @Volatile private var fade = 1.0

    private var media: MediaPlayer? = null

    init {
        // A change in Settings reaches the song already playing.
        scope.launch {
            combine(AppSettings.sound, AppSettings.equalizer) { _, _ -> }.collect {
                // JavaFX starts with the first song, not for this.
                if (media != null) onFx { media?.let(::applyEffects) }
            }
        }
    }

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
        when {
            i in _queue.value.indices -> open(i)
            AppSettings.playback.value.autoplay -> extendThen { open(i) }
            else -> onFx { media?.stop() }
        }
    }

    /** Stops playback in [minutes], the last few turning the volume down; null turns the timer off. */
    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        setFade(1.0)
        if (minutes == null) {
            _sleep.value = null
            return
        }
        val total = minutes * 60_000L
        val at = System.currentTimeMillis() + total
        _sleep.value = Sleep.At(at, minutes)
        sleepJob = scope.launch {
            // The last third, at most three minutes, fades out, as on the phone.
            val window = minOf(180_000L, total / 3)
            while (true) {
                val left = at - System.currentTimeMillis()
                if (left <= 0) break
                setFade(if (left < window) left.toDouble() / window else 1.0)
                delay(1_000)
            }
            onFx { media?.pause() }
            setFade(1.0)
            _sleep.value = null
        }
    }

    /** Stops playback when the song playing ends. */
    fun sleepAtEndOfSong() {
        sleepJob?.cancel()
        setFade(1.0)
        _sleep.value = Sleep.EndOfSong
    }

    private fun setFade(f: Double) {
        fade = f
        onFx { media?.volume = _volume.value * fade }
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
        onFx { media?.volume = _volume.value * fade }
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
            val address = addressOf(song.videoId)
            if (address == null) {
                _loading.value = false
                _error.value = "Couldn't play ${song.title}"
                return@onFx
            }
            val m = runCatching { MediaPlayer(Media(address)) }.getOrElse {
                System.err.println("Player: ${song.videoId}: ${it.message}")
                _loading.value = false
                _error.value = "Couldn't play ${song.title}"
                return@onFx
            }
            media = m
            m.volume = _volume.value * fade
            applyEffects(m)
            m.setOnReady {
                _loading.value = false
                m.totalDuration?.toMillis()?.takeIf { it.isFinite() && it > 0 }?.let { _durationOf.value = song.videoId to it.toLong() }
            }
            m.setOnPlaying { _playing.value = true; _loading.value = false }
            m.setOnPaused { _playing.value = false }
            m.setOnStopped { _playing.value = false }
            m.setOnEndOfMedia {
                if (_sleep.value == Sleep.EndOfSong) {
                    _sleep.value = null
                    m.stop()
                } else {
                    next()
                }
            }
            m.setOnError {
                System.err.println("Player: ${song.videoId}: ${m.error?.message}")
                _loading.value = false
                _playing.value = false
                _error.value = "Couldn't play ${song.title}"
            }
            m.play()
        }
    }

    /**
     * Speed, balance and the equalizer, from the phone's settings. JavaFX
     * has no preamp, so the curve is lowered by its biggest boost instead,
     * as the phone takes that boost off the input to keep from clipping;
     * bass and treble go on the bands below 120 Hz and above 8 kHz.
     */
    private fun applyEffects(m: MediaPlayer) {
        val sound = AppSettings.sound.value
        val eq = AppSettings.equalizer.value
        m.rate = sound.speed.toDouble().coerceIn(0.25, 4.0)
        m.balance = eq.balance.toDouble().coerceIn(-1.0, 1.0)
        val equalizer = m.audioEqualizer
        equalizer.bands.clear()
        equalizer.isEnabled = eq.enabled
        if (!eq.enabled) return
        val gains = EQ_BANDS_HZ.mapIndexed { i, hz ->
            (eq.bands.getOrNull(i) ?: 0f) + eq.preampDb +
                (if (hz <= 120f) eq.bassDb else 0f) + (if (hz >= 8_000f) eq.trebleDb else 0f)
        }
        val headroom = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
        // Each band two-thirds of an octave wide, centred on its frequency.
        val width = 2.0.pow(1.0 / 3) - 2.0.pow(-1.0 / 3)
        EQ_BANDS_HZ.forEachIndexed { i, hz ->
            val gain = (gains[i] - headroom).toDouble().coerceIn(EqualizerBand.MIN_GAIN, EqualizerBand.MAX_GAIN)
            equalizer.bands += EqualizerBand(hz.toDouble(), hz * width, gain)
        }
    }

    /**
     * Where JavaFX reads [id] from: a song on your server as MP3, which JavaFX can play where it can't FLAC; anything
     * else through the relay.
     */
    private fun addressOf(id: String): String? = when {
        Subsonic.isSubsonic(id) -> Subsonic.streamUrl(id)?.replace("format=raw", "format=mp3&maxBitRate=320")
        else -> Relay.addressOf(id)
    }

    /** Adds YouTube Music's up-next for the last song, then runs [then]. */
    private fun extendThen(then: () -> Unit) {
        // Only YouTube songs have an up-next to follow.
        val last = _queue.value.lastOrNull()?.takeIf { isYouTubeId(it.videoId) } ?: run {
            _playing.value = false
            return
        }
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
