package com.opentune.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.opentune.data.NerdStats

/**
 * Reports the decode and output stages to [NerdStats.pipeline] for the audio
 * pipeline page: which codec the player opened, the PCM coming out of it and
 * the AudioTrack it went to, with that track's buffer.
 */
@OptIn(UnstableApi::class)
internal object PipelineTelemetry : AnalyticsListener {
    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        NerdStats.onPipeline { it.copy(decoderName = decoderName) }
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
        // Raw PCM says its depth here; a compressed stream decodes to 16-bit unless the decoder says otherwise.
        val out = if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW) encodingLabel(format.pcmEncoding) else null
        NerdStats.onPipeline { it.copy(decoderOutput = out ?: it.decoderOutput) }
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        val bytesPerFrame = bytesPerSample(audioTrackConfig.encoding) * Integer.bitCount(audioTrackConfig.channelConfig).coerceAtLeast(1)
        val frames = if (bytesPerFrame > 0) audioTrackConfig.bufferSize / bytesPerFrame else null
        NerdStats.onPipeline {
            it.copy(
                decoderOutput = it.decoderOutput ?: encodingLabel(audioTrackConfig.encoding),
                outputSampleRateHz = audioTrackConfig.sampleRate,
                outputEncoding = encodingLabel(audioTrackConfig.encoding),
                outputChannels = Integer.bitCount(audioTrackConfig.channelConfig).takeIf { c -> c > 0 },
                bufferFrames = frames,
                bufferMs = frames?.let { f -> (f * 1000L / audioTrackConfig.sampleRate.coerceAtLeast(1)).toInt() },
                offload = audioTrackConfig.offload,
            )
        }
    }

    override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        NerdStats.onPipeline { it.copy(decoderName = null, decoderOutput = null) }
    }

    private fun encodingLabel(encoding: Int): String? = when (encoding) {
        C.ENCODING_PCM_8BIT -> "8-bit PCM"
        C.ENCODING_PCM_16BIT -> "16-bit PCM"
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit PCM"
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32-bit PCM"
        C.ENCODING_PCM_FLOAT -> "Float32"
        C.ENCODING_INVALID, Format.NO_VALUE -> null
        else -> "Passthrough"
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }
}
