package com.opentune.data.together

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomKeyTest {
    private val tag = RoomCode.generate().tag

    @Test fun aWrappedKeyOpensOnlyForItsGuest() {
        val host = Schnorr.newPrivateKey()
        val guest = Schnorr.newPrivateKey()
        val stranger = Schnorr.newPrivateKey()
        val key = RoomKey.random(tag)
        val wrapped = key.wrapFor(host, Schnorr.publicKey(guest))!!
        val opened = RoomKey.unwrap(wrapped, guest, Schnorr.publicKey(host), tag)!!
        assertArrayEquals(key.bytes, opened.bytes)
        // Same message, both keys.
        assertEquals("hello", opened.open(key.seal("hello")))
        // Someone else can't open it, even knowing both public keys.
        assertNull(RoomKey.unwrap(wrapped, stranger, Schnorr.publicKey(host), tag))
    }

    @Test fun bothSidesAgreeOnTheSharedSecret() {
        repeat(10) {
            val a = Schnorr.newPrivateKey()
            val b = Schnorr.newPrivateKey()
            assertArrayEquals(Schnorr.sharedSecret(a, Schnorr.publicKey(b)), Schnorr.sharedSecret(b, Schnorr.publicKey(a)))
        }
    }

    @Test fun voiceFramesPackAndUnpack() {
        val frames = listOf(ByteArray(3) { 1 }, ByteArray(300) { 2 }, ByteArray(1) { 3 })
        val back = VoiceFrames.unpack(VoiceFrames.pack(frames))!!
        assertEquals(frames.size, back.size)
        frames.indices.forEach { assertArrayEquals(frames[it], back[it]) }
        assertNull(VoiceFrames.unpack(byteArrayOf(0, 9, 1)))
    }
}
