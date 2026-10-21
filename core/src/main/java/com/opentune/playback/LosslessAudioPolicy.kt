package com.opentune.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** Based on BitChord AudioOutputPolicy / FLAC measurements (GPL-3.0, see notices). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object LosslessAudioPolicy {
    fun unsafeFlacDecoder(name: String): Boolean = name.lowercase(java.util.Locale.ROOT).let {
        it == "c2.sec.flac.decoder" || (it.startsWith("omx.sec.") && it.contains("flac"))
    }
    data class Info(val rate: Int, val channels: Int, val bits: Int, val durationMs: Long = 0)
    fun mp4StreamInfo(data: ByteArray): Info? {
        for (i in 4 until data.size - 42) {
            if (data[i] == 'd'.code.toByte() && data[i+1] == 'f'.code.toByte() && data[i+2] == 'L'.code.toByte() && data[i+3] == 'a'.code.toByte()) {
                streamInfo(data.copyOfRange(i + 8, data.size))?.let { return it }
            }
        }
        return null
    }
    fun streamInfo(format: Format): Info? = if (format.sampleMimeType == MimeTypes.AUDIO_FLAC)
        format.initializationData.firstNotNullOfOrNull(::streamInfo) else null
    fun streamInfo(data: ByteArray): Info? {
        fun b(i: Int) = data[i].toInt() and 255
        fun header(i: Int) = data.size >= i + 4 && b(i) and 127 == 0 && b(i + 1) == 0 && b(i + 2) == 0 && b(i + 3) == 34
        val body = when {
            data.size >= 4 && String(data, 0, 4, Charsets.US_ASCII) == "fLaC" -> if (header(4)) 8 else 4
            header(0) -> 4
            data.size == 34 -> 0
            else -> return null
        }
        if (data.size < body + 34) return null
        val rate = (b(body + 10) shl 12) or (b(body + 11) shl 4) or (b(body + 12) shr 4)
        val channels = ((b(body + 12) shr 1) and 7) + 1
        val bits = (((b(body + 12) and 1) shl 4) or (b(body + 13) shr 4)) + 1
        var samples = (b(body + 13) and 15).toLong()
        for (i in 14..17) samples = (samples shl 8) or b(body + i).toLong()
        return Info(rate, channels, bits, if (rate > 0) samples * 1000 / rate else 0).takeIf { rate in 8000..384000 && bits in 4..32 }
    }
}
