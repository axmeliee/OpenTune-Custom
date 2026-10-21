package com.opentune.data.lyrics

import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpMusicTest {
    private val id = "4NRXx6U8ABQ"

    private fun track(duration: Int? = 200, lrc: String? = null, rich: String? = null, plain: String? = null, videoId: String = id): JsonObject =
        buildJsonObject {
            put("videoId", videoId)
            duration?.let { put("durationSeconds", it) }
            lrc?.let { put("syncedLyrics", it) }
            rich?.let { put("richSyncLyrics", it) }
            plain?.let { put("plainLyric", it) }
        }

    private fun answer(vararg tracks: JsonObject, success: Boolean? = null): String = buildJsonObject {
        if (success == null) put("type", "success") else put("success", success)
        put("data", JsonArray(tracks.toList()))
    }.toString()

    @Test fun usesThePublicClientHeadersOnlyOnTheLyricsRequest() {
        val request = SimpMusic.request(id)!!
        assertEquals("https://api-lyrics.simpmusic.org/v1/$id", request.url.toString())
        assertEquals("GET", request.method)
        assertEquals("SimpMusicLyrics/1.0", request.header("User-Agent"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals("application/json", request.header("Content-Type"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("X-HMAC"))
        listOf("", "local:42", "subsonic:42", "radio:https://example.com", "../anything", "$id?query=1").forEach {
            assertNull(SimpMusic.request(it))
        }
    }

    @Test fun decodesLineSyncAndPlainTextFromBothResponseEnvelopes() {
        val lrc = "[00:01.00] First line\n[00:04.50] Second line"
        val synced = SimpMusic.parse(answer(track(lrc = lrc)), id, 200_000) as Lyrics.Synced
        assertEquals("SimpMusic", synced.source)
        assertEquals(listOf("First line", "Second line"), synced.lines.map { it.text })
        assertEquals(4_500L, synced.lines[1].startMs)
        assertFalse(synced.lines[0].wordSynced)
        assertEquals(Lyrics.Plain("Just the words", "SimpMusic"), SimpMusic.parse(answer(track(plain = " Just the words "), success = true), id, 200_000))
        assertEquals(Lyrics.Plain("Plural field", "SimpMusic"), SimpMusic.parse("""{"type":"success","data":[{"plainLyrics":"Plural field"}]}""", id, 0))
    }

    @Test fun readsRichJsonAbsoluteSecondsAndRelativeWordOffsets() {
        val rich = """[{"ts":4.25,"te":7.5,"x":"Hello there","l":[{"c":"Hello ","o":0.0},{"c":"there","o":1.5}]}]"""
        val synced = SimpMusic.parse(answer(track(rich = rich, lrc = "[00:01] Wrong fallback")), id, 200_000) as Lyrics.Synced
        val line = synced.lines.single()
        assertTrue(line.wordSynced)
        assertEquals("Hello there", line.text)
        assertEquals(4_250L, line.startMs)
        assertEquals(7_500L, line.endMs)
        assertEquals(listOf(LyricWord("Hello ", 4_250, 5_750), LyricWord("there", 5_750, 7_500)), line.words)
    }

    @Test fun readsEnhancedLrcAndFallsBackWhenRichSyncIsUnusable() {
        val rich = "[00:01.00]<00:01.00>Hello <00:02.00>there"
        val synced = SimpMusic.parse(answer(track(rich = rich)), id, 200_000) as Lyrics.Synced
        assertTrue(synced.lines.single().wordSynced)
        for (bad in listOf("not JSON", "[\"changed format\"]", "[]", "[{\"ts\":-1,\"te\":2,\"x\":\"bad\"}]")) {
            val fallback = SimpMusic.parse(answer(track(rich = bad, lrc = "[00:01.00] Good line")), id, 200_000) as Lyrics.Synced
            assertEquals("Good line", fallback.lines.single().text)
        }
    }

    @Test fun malformedWordTimingDoesNotClaimWordSync() {
        val rich = """[{"ts":1,"te":4,"x":"Keep this line","l":[{"c":"Keep ","o":2},{"c":"this ","o":1},{"c":"line","o":3}]}]"""
        val synced = SimpMusic.parse(answer(track(rich = rich)), id, 200_000) as Lyrics.Synced
        assertEquals("Keep this line", synced.lines.single().text)
        assertFalse(synced.lines.single().wordSynced)
    }

    @Test fun prefersUsableSyncAndRejectsDifferentRecordings() {
        val wrong = track(duration = 215, lrc = "[00:01] Wrong cut")
        val empty = track(duration = 200)
        val near = track(duration = 201, lrc = "[00:01] Near cut")
        val exact = track(duration = 200, lrc = "[00:01] Exact cut")
        val plain = track(plain = "Plain stand-in")
        assertNull(SimpMusic.parse(answer(wrong), id, 200_000))
        assertNull(SimpMusic.parse(answer(track(lrc = "[00:01] Wrong video", videoId = "dQw4w9WgXcQ")), id, 200_000))
        val found = SimpMusic.parse(answer(wrong, empty, near, plain, exact), id, 200_000) as Lyrics.Synced
        assertEquals("Exact cut", found.lines.single().text)
        val rich = track(duration = 201, rich = "[00:01]<00:01>Hello <00:02>there")
        assertTrue((SimpMusic.parse(answer(exact, rich), id, 200_000) as Lyrics.Synced).lines.single().wordSynced)
        assertTrue(SimpMusic.parse(answer(wrong), id, 0) is Lyrics.Synced)
        assertTrue(SimpMusic.parse(answer(track(duration = null, lrc = "[00:01] Same video")), id, 200_000) is Lyrics.Synced)
    }

    @Test fun ignoresEmptyMalformedAndErrorResponses() {
        for (body in listOf("", "<html>unavailable</html>", "[]", "{}", answer(), answer(track(plain = "")), answer(track(plain = "Do not show"), success = false),
            """{"type":"error","data":[{"plainLyric":"Do not show"}]}""",
            """{"type":"success","success":false,"data":[{"plainLyric":"Do not show"}]}""")) {
            assertNull(body, SimpMusic.parse(body, id, 200_000))
        }
    }

    @Test fun missingLyricsAndServiceFailureAreDifferentOutcomes() {
        fun client(code: Int, body: String) = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("SimpMusicLyrics/1.0", chain.request().header("User-Agent"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertNull(SimpMusic.lookup(id, 200_000, client(404, "{}")))
        assertNull(SimpMusic.lookup(id, 200_000, client(200, answer())))
        assertEquals(Lyrics.Plain("Words", "SimpMusic"), SimpMusic.lookup(id, 200_000, client(200, answer(track(plain = "Words")))))
        for (code in listOf(403, 429, 503)) {
            val error = runCatching { SimpMusic.lookup(id, 200_000, client(code, "{}")) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals("SimpMusic answered $code", error?.message)
        }
    }
}
