package com.opentune.data.lossless

import org.junit.Assert.*
import org.junit.Test

class LosslessSourceTest {
    @Test fun readsPrecisionRateAndDurationFromFlacStreaminfo() {
        assertEquals(LosslessSource.FlacInfo(24, 96_000, 180_000), LosslessSource.flacInfo(flac(24, 96_000, 180)))
        assertEquals(LosslessSource.FlacInfo(16, 44_100, 210_000), LosslessSource.flacInfo(flac(16, 44_100, 210)))
    }

    @Test fun rejectsHtmlTruncationMissingDurationAndNonFlac() {
        assertNull(LosslessSource.flacInfo("<html>not audio</html>".toByteArray()))
        assertNull(LosslessSource.flacInfo(flac(16, 44_100, 180).copyOf(25)))
        assertNull(LosslessSource.flacInfo(flac(16, 44_100, 0)))
        assertNull(LosslessSource.flacInfo(flac(8, 44_100, 180)))
        assertNull(LosslessSource.flacInfo(flac(16, 8_000, 180)))
        assertNull(LosslessSource.flacInfo(flac(16, 44_100, 180).apply { this[4] = 1 }))
    }

    @Test fun rejectsDifferentEditAndUnknownDuration() {
        assertTrue(LosslessSource.durationMatches(180_000, 182_000))
        assertFalse(LosslessSource.durationMatches(180_000, 185_000))
        assertFalse(LosslessSource.durationMatches(0, 180_000))
    }

    private fun flac(bits: Int, rate: Int, seconds: Int): ByteArray = ByteArray(42).apply {
        "fLaC".toByteArray().copyInto(this)
        this[4] = 0x80.toByte()
        this[7] = 34
        val packed = (rate.toLong() shl 44) or (1L shl 41) or ((bits - 1).toLong() shl 36) or (rate.toLong() * seconds)
        for (i in 0..7) this[18 + i] = (packed ushr (56 - i * 8)).toByte()
    }
}
