package com.opentune.playback
import org.junit.Assert.*
import org.junit.Test
class LosslessAudioPolicyTest {
    @Test fun excludesSamsungTimestampBugWithoutExcludingPlatformDecoder() {
        assertTrue(LosslessAudioPolicy.unsafeFlacDecoder("c2.sec.flac.decoder"))
        assertTrue(LosslessAudioPolicy.unsafeFlacDecoder("OMX.SEC.flac.dec"))
        assertFalse(LosslessAudioPolicy.unsafeFlacDecoder("c2.android.flac.decoder"))
        assertFalse(LosslessAudioPolicy.unsafeFlacDecoder("OMX.google.flac.decoder"))
    }
    @Test fun measuresActualStreamInfoAcrossRawAndMp4InitializationShapes() {
        val body = ByteArray(34)
        val packed = (96000L shl 44) or (1L shl 41) or (23L shl 36) or 960000L
        for (i in 0..7) body[10+i] = (packed ushr ((7-i)*8)).toByte()
        val header = byteArrayOf(0,0,0,34)
        for (data in listOf(body, header+body, "fLaC".toByteArray()+header+body)) {
            assertEquals(LosslessAudioPolicy.Info(96000,2,24,10000), LosslessAudioPolicy.streamInfo(data))
        }
        assertNull(LosslessAudioPolicy.streamInfo(ByteArray(5)))
    }
    @Test fun qualityComparisonNeverTreatsUnknownAsHigherQuality() {
        val score = com.opentune.data.addons.AddonResolver::qualityScore
        assertTrue(score(24,96000) > score(16,44100))
        assertTrue(score(16,44100) > score(null,192000))
        assertEquals(score(24,96000),score(24,96000))
    }
}
