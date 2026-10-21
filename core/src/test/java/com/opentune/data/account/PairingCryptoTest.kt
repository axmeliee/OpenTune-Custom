package com.opentune.data.account

import org.junit.Assert.*
import org.junit.Test

class PairingCryptoTest {
    @Test fun authenticatedPacketsRejectTamperingAndWrongDirection() {
        val key = PairingCrypto.secret()
        val packet = PairingCrypto.seal(key, "test session".toByteArray(), "session")
        assertEquals("test session", String(PairingCrypto.open(key, packet, "session")))
        assertTrue(runCatching { PairingCrypto.open(key, packet, "ack") }.isFailure)
        packet[15] = (packet[15].toInt() xor 1).toByte()
        assertTrue(runCatching { PairingCrypto.open(key, packet, "session") }.isFailure)
    }
    @Test fun qrExpiresAndOnlyAllowsLocalLiteralAddresses() {
        val offer = PairingOffer.create("192.168.1.42", 40000, 1000)
        assertEquals(offer.host, PairingOffer.parse(offer.payload, 1001).host)
        assertTrue(runCatching { PairingOffer.parse(offer.payload, 121001) }.isFailure)
        listOf("127.0.0.1", "8.8.8.8", "192.168.1.001", "localhost", "10.0.0.1.example.com", "169.254.169.254", "172.32.0.1").forEach {
            assertFalse(it, PairingCrypto.privateHost(it))
        }
        listOf("10.0.0.1", "172.16.0.1", "192.168.1.1").forEach { assertTrue(PairingCrypto.privateHost(it)) }
    }
}
