package com.opentune.data.together

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import com.opentune.data.DebugLog as Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * AAC frames packed for a message: each a two-byte big-endian length, then
 * the frame. About a second of 24 kbps speech is some 3 KB.
 */
object VoiceFrames {
    fun pack(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        frames.forEach { f ->
            require(f.size <= 0xFFFF)
            out.write(f.size ushr 8); out.write(f.size and 0xFF); out.write(f)
        }
        return out.toByteArray()
    }

    /** The frames in [data], or null if it isn't a packing of them. */
    fun unpack(data: ByteArray): List<ByteArray>? {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i < data.size) {
            if (i + 2 > data.size) return null
            val n = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            if (n == 0 || i + n > data.size) return null
            frames += data.copyOfRange(i, i + n)
            i += n
        }
        return frames
    }
}

private const val RATE = 16_000
private const val BITRATE = 24_000
private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
/** Samples in one AAC-LC frame. */
private const val FRAME_SAMPLES = 1024
private const val TAG = "Voice"

/** ms of audio in [frames] AAC frames. */
internal fun framesMs(frames: Int): Long = frames * FRAME_SAMPLES * 1000L / RATE

/**
 * Records the microphone as AAC, handing it on about once a second while it
 * records ([onPiece]), and stopping by itself at [maxMs]. The music ducks
 * while it records.
 */
class VoiceRecorder(
    private val context: Context,
    private val maxMs: Long,
    private val onPiece: (cfg: ByteArray?, frames: List<ByteArray>, msSoFar: Long, last: Boolean) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null
    private val focus = Focus(context)

    val isRecording get() = running

    @SuppressLint("MissingPermission") // asked for by the screen before it starts one
    fun start(): Boolean {
        if (running) return false
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
        val codec = runCatching {
            MediaCodec.createEncoderByType(MIME).apply {
                configure(
                    MediaFormat.createAudioFormat(MIME, RATE, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 4)
                    },
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                start()
            }
        }.getOrNull() ?: run { record.release(); return false }
        running = true
        focus.take()
        record.startRecording()
        worker = thread(name = "voice-record") {
            try {
                encode(record, codec)
            } catch (e: Exception) {
                Log.w(TAG, "Recording failed", e)
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { codec.stop() }
                codec.release()
                focus.drop()
                running = false
            }
        }
        return true
    }

    /** Ends the recording; the last piece goes out from the recording thread. */
    fun stop() {
        running = false
    }

    private fun encode(record: AudioRecord, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(FRAME_SAMPLES * 2)
        var cfg: ByteArray? = null
        var cfgSent = false
        var pending = mutableListOf<ByteArray>()
        var total = 0
        var samplesIn = 0L
        var eosQueued = false
        var done = false
        val framesPerPiece = RATE / FRAME_SAMPLES // about a second
        while (!done) {
            if (!eosQueued) {
                val stopNow = !running || framesMs((samplesIn / FRAME_SAMPLES).toInt()) >= maxMs
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    if (stopNow) {
                        codec.queueInputBuffer(inIndex, 0, 0, samplesIn * 1_000_000 / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    } else {
                        val n = record.read(pcm, 0, minOf(pcm.size, buf.capacity()))
                        if (n > 0) {
                            buf.clear(); buf.put(pcm, 0, n)
                            codec.queueInputBuffer(inIndex, 0, n, samplesIn * 1_000_000 / RATE, 0)
                            samplesIn += n / 2
                        } else codec.queueInputBuffer(inIndex, 0, 0, samplesIn * 1_000_000 / RATE, 0)
                    }
                }
            }
            var outIndex = codec.dequeueOutputBuffer(info, 10_000)
            while (outIndex >= 0) {
                val out = codec.getOutputBuffer(outIndex)!!
                val bytes = ByteArray(info.size).also { out.position(info.offset); out.get(it) }
                when {
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> cfg = bytes
                    bytes.isNotEmpty() -> { pending += bytes; total++ }
                }
                codec.releaseOutputBuffer(outIndex, false)
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (eos) done = true
                if ((pending.size >= framesPerPiece || eos) && cfg != null) {
                    onPiece(if (cfgSent) null else cfg, pending, framesMs(total), eos)
                    cfgSent = true
                    pending = mutableListOf()
                }
                if (eos) break
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }
        // A recording too short to make a single frame still ends, with nothing in it.
        if (!cfgSent && cfg != null) onPiece(cfg, pending, framesMs(total), true)
    }
}

/**
 * Plays AAC voice clips, one after another: live talk as its pieces arrive
 * ([talk]), and saved notes from the chat ([play]). The music ducks under it.
 */
class VoicePlayer(context: Context) {
    private class Job(val id: String, val cfg: ByteArray, val pieces: LinkedBlockingQueue<List<ByteArray>> = LinkedBlockingQueue(), @Volatile var ended: Boolean = false)

    private val focus = Focus(context)
    private val jobs = LinkedBlockingQueue<Job>()
    private val byId = java.util.concurrent.ConcurrentHashMap<String, Job>()
    @Volatile private var stopCurrent = false
    @Volatile var playing: String? = null
        private set
    /** Called with the clip now playing, or null when it's quiet again. */
    var onPlaying: (String?) -> Unit = {}

    init {
        thread(name = "voice-play", isDaemon = true) {
            while (true) {
                val job = jobs.take()
                playing = job.id
                onPlaying(job.id)
                stopCurrent = false
                runCatching { decode(job) }.onFailure { Log.w(TAG, "Playback failed", it) }
                byId.remove(job.id)
                if (jobs.isEmpty()) { playing = null; onPlaying(null); focus.drop() }
            }
        }
    }

    /** More of live clip [id]; the first call queues it to be heard. */
    fun talk(id: String, cfg: ByteArray, frames: List<ByteArray>, done: Boolean) {
        val job = byId.getOrPut(id) { Job(id, cfg).also { jobs.put(it) } }
        job.pieces.put(frames)
        if (done) job.ended = true
    }

    /** A whole clip, from the chat. Playing it again while it plays stops it. */
    fun play(id: String, cfg: ByteArray, frames: List<ByteArray>) {
        if (playing == id) { stopCurrent = true; return }
        val job = Job(id, cfg).apply { pieces.put(frames); ended = true }
        byId[id] = job
        jobs.put(job)
    }

    fun stop() { stopCurrent = true; jobs.clear() }

    private fun decode(job: Job) {
        focus.take()
        val format = MediaFormat.createAudioFormat(MIME, RATE, 1).apply { setByteBuffer("csd-0", ByteBuffer.wrap(job.cfg)) }
        val codec = MediaCodec.createDecoderByType(MIME).apply { configure(format, null, null, 0); start() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        val frames = ArrayDeque<ByteArray>()
        var inputDone = false
        var waitedMs = 0L
        try {
            while (!stopCurrent) {
                if (frames.isEmpty() && !inputDone) {
                    val piece = job.pieces.poll(100, TimeUnit.MILLISECONDS)
                    if (piece != null) { frames.addAll(piece); waitedMs = 0 } else {
                        waitedMs += 100
                        // A piece that never comes: give up on the rest after a few seconds.
                        if (job.ended && job.pieces.isEmpty() || waitedMs > 4_000) inputDone = true
                    }
                }
                if (frames.isNotEmpty() || inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        if (frames.isEmpty()) {
                            codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val f = frames.removeFirst()
                            codec.getInputBuffer(inIndex)!!.apply { clear(); put(f) }
                            codec.queueInputBuffer(inIndex, 0, f.size, pts, 0)
                            pts += FRAME_SAMPLES * 1_000_000L / RATE
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)!!
                    val pcm = ByteArray(info.size).also { out.position(info.offset); out.get(it) }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (pcm.isNotEmpty()) track.write(pcm, 0, pcm.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }
}

/** Ducks other audio (the music) while a voice records or plays. */
private class Focus(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .build()
    @Volatile private var held = false

    fun take() {
        if (held) return
        held = am?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun drop() {
        if (!held) return
        held = false
        am?.abandonAudioFocusRequest(request)
    }
}
