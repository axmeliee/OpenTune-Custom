package com.opentune.data.lossless

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SpotiflacGatedTest {
    private val now = Instant.parse("2026-10-09T00:00:00Z")

    // Community protocol vectors independently calculated with Python hmac.digest,
    // following spotbye/SpotiFLAC backend/community_session.go (raw rolling key).
    @Test fun signingMatchesCommunityProtocolRatherThanExtensionGateway() {
        val headers = SpotiflacGated.signHeaders("test-session", "test-secret", "/api/dl", """{"id":"123","quality":"LOSSLESS"}""".toByteArray(), now, "00112233445566778899aabb")
        assertEquals("sGyo4Tg9XurK9ziM6zehlS6J50pVPS63bq3Wpz_s2zM", headers["X-Sig-Signature"])
        assertNotEquals("The extension gateway signature is rejected by the community API",
            "MbML0aLmcnZRIzEVgNk4CDiz3B5ABAUzlgu6pxfL41A", headers["X-Sig-Signature"])
        assertEquals("173ab0c363fbccd5fad246271673b71f819828eeed4772b4ea340ad61d050e3b", headers["X-Sig-Body-Sha256"])
        assertEquals("2026-10-09T00:00:00.000Z", headers["X-Sig-Timestamp"])
        val next = SpotiflacGated.signHeaders("test-session", "test-secret", "/api/dl", byteArrayOf(), now.plusSeconds(300), "different")
        assertNotEquals(headers["X-Sig-Signature"], next["X-Sig-Signature"])
    }

    @Test fun communitySigningUsesTheTimestampWindowAcrossSongChanges() {
        val body = """{"id":"456","quality":"LOSSLESS"}""".toByteArray()
        val nonce = "112233445566778899aabbcc"
        val before = SpotiflacGated.signHeaders("test-session", "test-secret", "/api/dl", body,
            Instant.parse("2026-10-09T00:04:59.999Z"), nonce)
        val after = SpotiflacGated.signHeaders("test-session", "test-secret", "/api/dl", body,
            Instant.parse("2026-10-09T00:05:00.000Z"), nonce)
        assertEquals("DHiUxXPnXSsrv7t8HVFugjH8EhlTlzBvO4RFg6hvlxw", before["X-Sig-Signature"])
        assertEquals("pigWTdSZ7aN8QfBm_emHhnmUAOzyA-3yJkV8m2FMXn0", after["X-Sig-Signature"])
    }

    @Test fun invalidOrExpiredSessionsAreNotUsed() {
        assertNull(SpotiflacSession.parse("{}", now))
        assertNull(SpotiflacSession.parse("""{"session_id":"id","session_secret":"secret","expires_at":"2026-10-08T00:00:00Z"}""", now))
        assertNull(SpotiflacSession.parse("""{"session_id":"","session_secret":"secret","expires_at":"2026-10-10T00:00:00Z"}""", now))
        assertNotNull(SpotiflacSession.parse("""{"session_id":"id","session_secret":"secret","expires_at":"2026-10-10T00:00:00Z"}""", now))
    }
}

