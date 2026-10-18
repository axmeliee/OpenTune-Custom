package com.opentune.data.lyrics

import com.opentune.data.Http
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Better Lyrics' lyrics service: word-by-word TTML for songs someone has
 * already looked up. Anything it hasn't seen needs the extension's own API
 * key, which isn't ours to use, so those come back empty and the other
 * sources answer.
 */
internal object BetterLyrics {
    private const val API = "https://lyrics-api.boidu.dev/getLyrics"
    private const val USER_AGENT = "OpenTune (+https://github.com/justshivv/Opentune)"
    /** A recording more than this much longer or shorter than the track is another version. */
    private const val TOLERANCE_MS = 4_000L
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("s", cleaned.title)
            .addQueryParameter("a", cleaned.artist)
            .apply { if (durationMs > 0) addQueryParameter("d", (durationMs / 1000).toString()) }
            .build()
        val body = runInterruptible(Dispatchers.IO) {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use {
                if (!it.isSuccessful) null else it.body?.string()
            }
        } ?: return null
        return parse(body, durationMs)
    }

    internal fun parse(body: String, durationMs: Long): Lyrics? {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val ttml = (obj["ttml"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        // The recording's own length, from <body dur>: another version's timing would be off from the first line.
        val length = Regex("""<body\b[^>]*\bdur="([^"]+)"""").find(ttml)?.groupValues?.get(1)?.let(Ttml::time)
        if (durationMs > 0 && length != null && length > 0 && abs(length - durationMs) > TOLERANCE_MS) return null
        val lines = Ttml.parse(ttml)
        if (lines.isEmpty()) return null
        return Lyrics.Synced(lines, "Better Lyrics", recordingMs = length?.takeIf { it > 0 } ?: 0)
    }
}
