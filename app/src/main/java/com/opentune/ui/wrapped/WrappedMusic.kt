package com.opentune.ui.wrapped

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.opentune.data.DebugLog as Log

/**
 * Calm music behind the Wrapped story, made as it plays by [CalmSynth]: no
 * song, no stream, so the app's own player, its queue and its history are
 * left as they were, and it never fails to load.
 *
 * It plays on a thread of its own into a plain audio output. It fades in when
 * wanted and out when not, then pauses where it is, so it picks up the same
 * chord when it comes back. It takes the audio like any music: another app
 * starting, or a call, quiets it, and it comes back when they're done.
 */
internal class WrappedMusic(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    @Volatile private var wanted = false
    @Volatile private var held = false
    @Volatile private var closing = false
    private var thread: Thread? = null
    private var onQuiet: (() -> Unit)? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            // Lost for good or for a while (a call, another app): quiet; back when it's returned.
            held = change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            wake()
        }
        .build()

    /** Whether the music should be heard: it fades in, or fades out and pauses where it is. */
    fun setAudible(on: Boolean) {
        if (closing) return
        wanted = on
        if (on) {
            held = audio?.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (thread == null) thread = Thread(::run, "WrappedMusic").apply { start() }
        }
        wake()
    }

    /** Fades out and lets go of the audio; [then] runs on the main thread once it's quiet. */
    fun close(then: () -> Unit) {
        if (closing) return
        closing = true
        wanted = false
        if (thread == null) {
            then()
            return
        }
        onQuiet = then
        wake()
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun run() {
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "No audio output for Wrapped's music", e)
            finish(null)
            return
        }
        val synth = CalmSynth(RATE)
        val samples = FloatArray(FRAMES * 2)
        val pcm = ShortArray(FRAMES * 2)
        var x = 0f
        var playing = false
        val blockMs = FRAMES * 1000L / RATE
        while (true) {
            val target = if (wanted && !held && !closing) 1f else 0f
            if (target == 0f && x == 0f) {
                if (playing) { track.pause(); playing = false }
                if (closing) break
                synchronized(lock) {
                    if (!(wanted && !held) && !closing) lock.wait()
                }
                continue
            }
            if (!playing) { track.play(); playing = true }
            val from = x
            x = WrappedTune.step(x, target, blockMs, if (target > x) IN_MS else if (closing) LEAVE_MS else OUT_MS)
            synth.render(samples, FRAMES)
            // The volume moves smoothly across the block, so a fade never steps.
            for (i in 0 until FRAMES) {
                val g = WrappedTune.level(from + (x - from) * i / FRAMES)
                pcm[2 * i] = (samples[2 * i] * g * Short.MAX_VALUE).toInt().toShort()
                pcm[2 * i + 1] = (samples[2 * i + 1] * g * Short.MAX_VALUE).toInt().toShort()
            }
            if (track.write(pcm, 0, pcm.size) < 0) break
        }
        finish(track)
    }

    private fun finish(track: AudioTrack?) {
        runCatching { track?.stop() }
        track?.release()
        audio?.abandonAudioFocusRequest(focus)
        thread = null
        val then = onQuiet
        onQuiet = null
        if (then != null) main.post(then)
    }

    private companion object {
        const val TAG = "WrappedMusic"
        const val RATE = 44_100
        const val FRAMES = 1024
        /** From silence to full when the story opens. */
        const val IN_MS = 3_000L
        /** Full to silence when muted or the app goes to the back. */
        const val OUT_MS = 700L
        /** Full to silence on leaving Wrapped. */
        const val LEAVE_MS = 900L
    }
}

/** The fades behind Wrapped's music, kept apart from the player so they can be tested. */
internal object WrappedTune {
    /** The volume [x] of the way through a fade: squared, so it rises evenly to the ear instead of jumping at the start. */
    fun level(x: Float): Float = x.coerceIn(0f, 1f).let { it * it }

    /** [x] moved toward [target] by [elapsedMs] of a fade that takes [fullMs] from silence to full. */
    fun step(x: Float, target: Float, elapsedMs: Long, fullMs: Long): Float {
        if (fullMs <= 0) return target
        val by = elapsedMs.coerceAtLeast(0).toFloat() / fullMs
        return if (x < target) minOf(target, x + by) else maxOf(target, x - by)
    }
}
