package com.opentune.data.lyrics

import com.opentune.data.Http
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Community lyrics matched to a YouTube video. The read-only request format
 * follows SimpMusic's public client and EchoMusic's integration:
 * https://github.com/EchoMusicApp/Echo-Music/tree/main/simpmusic
 * The client User-Agent is required by this endpoint; it is scoped to these
 * requests, leaving the shared HTTP client's identity unchanged.
 */
object SimpMusic {
    private const val BASE = "https://api-lyrics.simpmusic.org/v1/"
    private const val SOURCE = "SimpMusic"
    private const val TOLERANCE_MS = 8_000L
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val json = Json { ignoreUnknownKeys = true }
    // The repository waits twelve seconds for all sources. A stalled lookup
    // should finish within that budget, including reading the response body.
    private val client by lazy { Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }

    fun lookup(videoId: String, durationMs: Long): Lyrics? {
        if (!VIDEO_ID.matches(videoId)) return null
        return lookup(videoId, durationMs, client)
    }

    internal fun lookup(videoId: String, durationMs: Long, httpClient: OkHttpClient): Lyrics? {
        val request = request(videoId) ?: return null
        return httpClient.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 204) return null
            if (!response.isSuccessful) throw IOException("SimpMusic answered ${response.code}")
            response.body?.string()?.let { parse(it, videoId, durationMs) }
        }
    }

    internal fun request(videoId: String): Request? {
        if (!VIDEO_ID.matches(videoId)) return null
        val url = BASE.toHttpUrl().newBuilder().addPathSegment(videoId).build()
        return Request.Builder().url(url)
            .header("User-Agent", "SimpMusicLyrics/1.0")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()
    }

    internal fun parse(body: String, videoId: String, durationMs: Long): Lyrics? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        // EchoMusic's API model uses type=success; the upstream client also
        // supports success=true. An explicit failure always wins.
        val success = (root["success"] as? JsonPrimitive)?.booleanOrNull
        val type = root.str("type")
        if (success == false || (type != null && type != "success")) return null
        if (success != true && type != "success") return null
        val candidates = (root["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { it.str("videoId").let { id -> id == null || id == videoId } }
            .filter { entry ->
                val seconds = entry.num("durationSeconds") ?: entry.num("duration")
                seconds == null || (seconds.isFinite() && seconds >= 0 &&
                    (durationMs <= 0 || seconds == 0.0 || abs(seconds * 1000 - durationMs) <= TOLERANCE_MS))
            }
            .sortedBy { entry ->
                val seconds = entry.num("durationSeconds") ?: entry.num("duration")
                if (durationMs <= 0) 0.0 else seconds?.takeIf { it > 0 }?.let { abs(it * 1000 - durationMs) } ?: Double.MAX_VALUE
            }
        var best: Lyrics? = null
        for (entry in candidates) {
            val rich = entry.str("richSyncLyrics")?.let { richLines(it, durationMs) }.orEmpty()
            val lineSynced = entry.str("syncedLyrics")?.let { LrcParser.parse(it, durationMs) }.orEmpty()
            for (lines in listOf(rich, lineSynced)) {
                if (lines.isEmpty()) continue
                val found = Lyrics.Synced(lines, SOURCE)
                if (rank(found) > rank(best)) best = found
            }
            val plain = (entry.str("plainLyric") ?: entry.str("plainLyrics"))?.trim()?.takeIf { it.isNotEmpty() }
            if (best == null && plain != null) best = Lyrics.Plain(plain, SOURCE)
        }
        return best
    }

    /** Enhanced LRC or Musixmatch-style rich sync: absolute seconds and word offsets in seconds. */
    private fun richLines(value: String, durationMs: Long): List<LyricLine> {
        val items = runCatching { json.parseToJsonElement(value) as? JsonArray }.getOrNull()
            ?: return LrcParser.parse(value, durationMs)
        return items.filterIsInstance<JsonObject>().mapNotNull line@ { item ->
            val start = item.num("ts")?.ms() ?: return@line null
            val end = item.num("te")?.ms()?.takeIf { it > start } ?: return@line null
            val tokens = (item["l"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val timed = tokens.mapNotNull word@ { token ->
                val text = token.str("c") ?: return@word null
                val offset = token.num("o")?.ms() ?: return@word null
                if (offset >= end - start) return@word null
                LyricWord(text, start + offset, end)
            }
            val text = (item.str("x")?.takeIf { it.isNotBlank() }
                ?: tokens.joinToString("") { it.str("c").orEmpty() }).trim()
            if (text.isEmpty()) return@line null
            val validTiming = timed.isNotEmpty() && timed.size == tokens.size &&
                timed.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }
            val words = if (validTiming) timed.mapIndexed { i, word ->
                word.copy(endMs = timed.getOrNull(i + 1)?.startMs ?: end)
            }.filter { it.text.isNotBlank() } else emptyList()
            LyricLine(start, end, text, words.ifEmpty { LrcParser.spreadWords(text, start, end) }, wordSynced = words.isNotEmpty())
        }.sortedBy { it.startMs }
    }

    private fun rank(lyrics: Lyrics?): Int = when (lyrics) {
        null -> 0
        is Lyrics.Plain -> 1
        is Lyrics.Synced -> if (lyrics.lines.any { it.wordSynced }) 3 else 2
    }

    private fun Double.ms(): Long? = takeIf { it.isFinite() && it in 0.0..86_400.0 }?.let { (it * 1000).toLong() }
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
