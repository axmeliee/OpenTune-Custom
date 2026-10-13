package com.opentune.data.lossless

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SpotiflacVerificationApiTest {
    private val base = LosslessRegistry.DEFAULT_VERIFY_BASE
    private val state = "0123456789abcdef0123456789abcdef"
    private val session = """{"session_id":"id","session_secret":"secret","expires_at":"2099-01-01T00:00:00Z"}"""

    @Test fun usesCommunityIssuerAndTheProviderIssuedChallenge() {
        assertEquals("https://verify.example", LosslessRegistry.verificationBase(JSONObject("""{"community":{"verify":"https://verify.example/"}}""")))
        assertEquals(base, LosslessRegistry.verificationBase(JSONObject("""{"community":{"verify":"http://unsafe.example"}}""")))
        val page = "$base/challenge?id=issued"
        val result = SpotiflacVerificationApi.challengeUrl(JSONObject().put("challenge_url", page), base, state)!!
        assertEquals("issued", result.toHttpUrl().queryParameter("id"))
        assertEquals("spotiflac://session-grant?state=$state", result.toHttpUrl().queryParameter("cb"))
        assertNull(SpotiflacVerificationApi.challengeUrl(JSONObject("""{"challenge_id":"obsolete"}"""), base, state))
        for (url in listOf("https://evil.example/challenge?id=issued", "http://verify.spotbye.qzz.io/challenge", "$base/unrelated")) {
            assertNull(SpotiflacVerificationApi.challengeUrl(JSONObject().put("challenge_url", url), base, state))
        }
        assertTrue(SpotiflacVerificationApi.isChallengePage("$result#help", result))
        assertFalse(SpotiflacVerificationApi.isChallengePage("$base/challenge?id=other", result))
    }

    @Test fun acceptsOnlyTheExactCallbackAndAttemptState() {
        val callback = "spotiflac://session-grant?state=$state&grant=issued_token"
        assertEquals("issued_token", SpotiflacVerificationApi.grantFromCallback(Uri.parse(callback), state))
        for (uri in listOf(callback.replace(state, "wrong"), "$callback&state=$state", "$callback&grant=other",
            callback.replace("session-grant?", "session-grant.evil?"), callback.replace("session-grant?", "session-grant/path?"),
            callback.replace("issued_token", ""), callback.replace("issued_token", "line%0Abreak"),
            callback.replace("spotiflac:", "https:"), "$callback#fragment")) {
            assertNull(uri, SpotiflacVerificationApi.grantFromCallback(Uri.parse(uri), state))
        }
    }

    @Test fun oldIssuerSessionsRequireReverificationWithoutLosingInstallIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("spotiflac", 0)
        prefs.edit().clear().putString("install_id", "keep-me").putString("session", session).commit()
        SpotiflacSession.init(context)
        assertNull(SpotiflacSession.current())
        assertFalse(SpotiflacSession.connected.value)
        assertTrue(SpotiflacSession.needsVerification.value)
        assertEquals("keep-me", SpotiflacSession.installId())
        SpotiflacSession.init(context)
        assertTrue(SpotiflacSession.needsVerification.value)
        val revision = SpotiflacSession.revision.value
        assertTrue(SpotiflacSession.save(session, base))
        assertTrue(SpotiflacSession.connected.value)
        assertFalse(SpotiflacSession.needsVerification.value)
        assertTrue(SpotiflacSession.revision.value > revision)
        SpotiflacSession.init(context)
        assertNotNull(SpotiflacSession.current())
        SpotiflacSession.clear()
        assertEquals("keep-me", SpotiflacSession.installId())
    }

    @Test fun staleRejectedRequestCannotClearARenewedSession() {
        SpotiflacSession.init(RuntimeEnvironment.getApplication())
        assertTrue(SpotiflacSession.save(session, base))
        val old = SpotiflacSession.current()!!
        val renewed = JSONObject(session).put("session_secret", "new-secret").toString()
        assertTrue(SpotiflacSession.save(renewed, base))
        SpotiflacSession.invalidate(old)
        assertTrue(SpotiflacSession.connected.value)
        SpotiflacSession.invalidate(SpotiflacSession.current())
        assertFalse(SpotiflacSession.connected.value)
        assertTrue(SpotiflacSession.needsVerification.value)
    }
}

