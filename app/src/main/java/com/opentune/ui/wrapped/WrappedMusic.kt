package com.opentune.ui.wrapped

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.opentune.data.DebugLog as Log
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.lyrics.SongParts
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import com.opentune.data.settings.AppSettings
import com.opentune.playback.SongSources
import com.opentune.playback.isUpgradedUri
import com.opentune.playback.toMediaItem
import com.opentune.playback.trackUri
import com.opentune.playback.videoIdOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Your own top songs, played behind the Wrapped story.
 *
 * A short-lived player of its own rather than the app's main one, so the
 * queue, the history and the notification are left as they were. It reads
 * songs through the same [SongSources] the main player does: a download from
 * its file, anything else from the song cache or a freshly resolved stream.
 * Each song starts a little before its first chorus when the lyrics mark one
 * and come quickly, else about a third of the way in, and fades in.
 *
 * Nothing here reaches the story as an error: a song that won't play is
 * skipped, and when none will the story runs silent.
 */
@OptIn(UnstableApi::class)
internal class WrappedMusic(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var songs: List<Song> = emptyList()
    /** The song wanted, as an index into [songs]. */
    private var at = 0
    /** The song in the player, or -1. */
    private var loaded = -1
    private var audible = false
    /** How far faded in, 0 to 1; the volume is [WrappedTune.level] of it. */
    private var x = 0f
    /** The one thing under way: a fade, a song starting, or both. Each new one replaces it. */
    private var job: Job? = null
    /** Songs that failed in a row; once every one has, the music gives up. */
    private var failures = 0
    private var closed = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) moveOn()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) failures = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Couldn't play ${songs.getOrNull(loaded)?.videoId}", error)
            loaded = -1
            if (++failures < songs.size) {
                moveOn()
            } else {
                job?.cancel()
                release()
            }
        }
    }

    /** The songs to play, in order; a new list starts again from its first. */
    fun setSongs(list: List<Song>) {
        if (list == songs || closed) return
        songs = list
        at = 0
        loaded = -1
        failures = 0
        act {
            fadeTo(0f, SWITCH_MS)
            if (audible) start(at) else player?.stop()
        }
    }

    /** Whether the music should be heard: it fades in, or fades out and pauses where it is. */
    fun setAudible(on: Boolean) {
        if (closed) return
        audible = on
        act {
            if (on) {
                start(at)
            } else {
                fadeTo(0f, OUT_MS)
                player?.pause()
            }
        }
    }

    /** The story reached a page about [videoId]: move to that song, if it's one of ours and not already on. */
    fun cue(videoId: String?) {
        val i = songs.indexOfFirst { it.videoId == videoId }
        if (closed || i < 0 || i == at) return
        at = i
        if (audible) act {
            fadeTo(0f, SWITCH_MS)
            start(i)
        }
    }

    /** Fades out and lets go of the player; [then] runs once it's quiet. */
    fun close(then: () -> Unit) {
        if (closed) return
        audible = false
        act {
            try {
                fadeTo(0f, LEAVE_MS)
            } finally {
                release()
                then()
                scope.cancel()
            }
        }
        closed = true
    }

    /** Starts [block] in place of whatever was under way. */
    private fun act(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private fun moveOn() {
        if (songs.isEmpty()) return
        at = WrappedTune.next(at, songs.size)
        loaded = -1
        if (audible) act { start(at) }
    }

    /** Plays song [i], loading it first unless it's the one already in, and fades it in once it sounds. */
    private suspend fun start(i: Int) {
        val song = songs.getOrNull(i) ?: return
        val p = player ?: build().also { player = it }
        val idle = p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED
        if (loaded != i || idle) {
            x = 0f
            p.volume = 0f
            val from = startFor(song)
            p.setMediaItem(song.toMediaItem(), from ?: 0L)
            p.prepare()
            loaded = i
            if (from == null) {
                // The length is only known once the song opens; then about a third in.
                while (p.playbackState != Player.STATE_READY) {
                    if (p.playbackState == Player.STATE_IDLE) return
                    delay(POLL_MS)
                }
                p.duration.takeIf { it != C.TIME_UNSET }?.let { WrappedTune.startMs(it, emptyList()) }?.let { p.seekTo(it) }
            }
        }
        p.play()
        // Silent until it really plays: a fade spent buffering would be lost.
        while (!p.isPlaying) delay(POLL_MS)
        fadeTo(1f, IN_MS)
    }

    /** Where in [song] to begin; null when its length isn't known yet. */
    private suspend fun startFor(song: Song): Long? {
        val durationMs = song.durationMillis()
        warm(song.videoId)
        // Lyrics a download keeps, or ones already looked up, come at once;
        // anything slower isn't waited for. Without the length a lookup can
        // match another cut of the song, timed differently, so none is made.
        val lyrics = if (durationMs <= 0) null else try {
            withTimeoutOrNull(LYRICS_WAIT_MS) { LyricsRepository.lyricsFor(song.videoId, song.title, song.artist, durationMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No lyrics for ${song.videoId}: ${e.message}")
            null
        }
        // Lyrics are timed against the song shifted by its offset; the player wants the song's own time.
        val offset = AppSettings.lyricsOffsetFor(song.videoId)
        val choruses = (lyrics as? Lyrics.Synced)?.let { SongParts.choruses(it).map { ms -> ms - offset } }.orEmpty()
        return WrappedTune.startMs(durationMs, choruses)
    }

    /** Resolves a streamed song's URL while the lyrics are looked for, so the player finds it waiting. */
    private fun warm(videoId: String) {
        val uri = trackUri(videoId)
        if (videoIdOf(uri) == null || isUpgradedUri(uri)) return
        scope.launch(Dispatchers.IO) {
            runCatching { StreamResolver.resolve(videoId) }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "Warm-up for $videoId failed: ${e.message}")
            }
        }
    }

    private suspend fun fadeTo(target: Float, fullMs: Long) {
        val p = player ?: run { x = target; return }
        var last = SystemClock.elapsedRealtime()
        while (x != target) {
            delay(FRAME_MS)
            val now = SystemClock.elapsedRealtime()
            x = WrappedTune.step(x, target, now - last, fullMs)
            last = now
            p.volume = WrappedTune.level(x)
        }
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(SongSources(context).routing))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            // Takes the floor like any music: another app pauses, and a call pauses this.
            /* handleAudioFocus= */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { it.addListener(listener) }

    private fun release() {
        player?.run {
            removeListener(listener)
            stop()
            release()
        }
        player = null
        loaded = -1
        x = 0f
    }

    private companion object {
        const val TAG = "WrappedMusic"
        const val FRAME_MS = 16L
        const val POLL_MS = 50L
        /** From silence to full when a song comes in. */
        const val IN_MS = 2_500L
        /** Full to silence when muted or the app goes to the back. */
        const val OUT_MS = 600L
        /** Full to silence before the next song comes in. */
        const val SWITCH_MS = 700L
        /** Full to silence on leaving Wrapped. */
        const val LEAVE_MS = 800L
        const val LYRICS_WAIT_MS = 1_200L
    }
}

/** The choices behind Wrapped's music, kept apart from the player so they can be tested. */
internal object WrappedTune {
    /** How far into a song to begin when its chorus isn't known. */
    const val PART_WAY = 0.3
    /** Begin this long before the chorus, so the fade-in arrives with it. */
    const val CHORUS_LEAD_MS = 2_000L
    /** A chorus closer than this to the end leaves too little to hear. */
    const val MIN_LEFT_MS = 30_000L

    /**
     * The top songs in order, then the one played most in a day: each once,
     * and only ones that are songs ([playable] turns away stations and episodes).
     */
    fun playlist(top: List<Song>, onRepeat: Song?, playable: (String) -> Boolean): List<Song> =
        (top + listOfNotNull(onRepeat)).filter { playable(it.videoId) }.distinctBy { it.videoId }

    /**
     * Where to begin a song [durationMs] long (0 when unknown): a little before
     * the first of its [choruses] that leaves enough to hear, else about a
     * third in. Null when neither is known.
     */
    fun startMs(durationMs: Long, choruses: List<Long>): Long? {
        val known = durationMs > 0
        val chorus = choruses.sorted().firstOrNull { it >= 0 && (!known || it <= durationMs - MIN_LEFT_MS) }
        if (chorus != null) return (chorus - CHORUS_LEAD_MS).coerceAtLeast(0)
        return if (known) (durationMs * PART_WAY).toLong() else null
    }

    /** The volume [x] of the way through a fade: squared, so it rises evenly to the ear instead of jumping at the start. */
    fun level(x: Float): Float = x.coerceIn(0f, 1f).let { it * it }

    /** [x] moved toward [target] by [elapsedMs] of a fade that takes [fullMs] from silence to full. */
    fun step(x: Float, target: Float, elapsedMs: Long, fullMs: Long): Float {
        if (fullMs <= 0) return target
        val by = elapsedMs.coerceAtLeast(0).toFloat() / fullMs
        return if (x < target) minOf(target, x + by) else maxOf(target, x - by)
    }

    /** The song after [index], back to the first after the last. */
    fun next(index: Int, size: Int): Int = if (size <= 0) 0 else (index + 1) % size
}
