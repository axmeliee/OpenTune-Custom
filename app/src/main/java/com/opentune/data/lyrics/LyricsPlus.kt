package com.opentune.data.lyrics

import com.opentune.data.Http
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * LyricsPlus, the open lyrics backend behind the YouLyPlus extension, as
 * Echo Music uses it: community-run servers that look a song up by title,
 * artist and length and answer with lines timed word by word where the
 * catalogue has them. No key or account.
 *
 * The server that answered last is asked first, alone for a moment; if it
 * is slow or has nothing, the others are asked together and the first
 * usable answer wins.
 */
object LyricsPlus {
    private val SERVERS = listOf(
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )
    private const val USER_AGENT = "OpenTune (+https://github.com/justshivv/Opentune)"
    /** How long the last good server has before the rest are asked too. */
    private const val HEAD_START_MS = 2_500L
    private const val BUDGET_MS = 9_000L
    /** An answer further than this from the track's length is another recording. */
    private const val TOLERANCE_MS = 8_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val lastGood = AtomicReference<String?>(null)

    suspend fun lookup(title: String, artist: String, durationMs: Long, album: String? = null): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val first = lastGood.get()
        if (first != null) {
            withTimeoutOrNull(HEAD_START_MS) { runCatching { ask(first, cleaned, durationMs, album) }.getOrNull() }
                ?.let { return it }
        }
        return race(SERVERS.filter { it != first }, cleaned, durationMs, album)
    }

    /** Asks every server at once; the first one with lyrics wins and the rest are dropped. */
    private suspend fun race(servers: List<String>, cleaned: TrackNameCleaner.Cleaned, durationMs: Long, album: String?): Lyrics? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val winner = CompletableDeferred<Lyrics?>()
        var left = servers.size
        val lock = Any()
        servers.forEach { server ->
            scope.launch {
                val found = runCatching { ask(server, cleaned, durationMs, album) }.getOrNull()
                synchronized(lock) {
                    left--
                    if (found != null) winner.complete(found) else if (left == 0) winner.complete(null)
                }
            }
        }
        return try {
            withTimeoutOrNull(BUDGET_MS) { winner.await() }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun ask(server: String, cleaned: TrackNameCleaner.Cleaned, durationMs: Long, album: String?): Lyrics? {
        val url = "$server/v2/lyrics/get".toHttpUrl().newBuilder()
            .addQueryParameter("title", cleaned.title)
            .addQueryParameter("artist", cleaned.artist)
            .apply {
                if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000).toString())
                if (!album.isNullOrBlank()) addQueryParameter("album", album)
            }
            .build()
        val body = runInterruptible(Dispatchers.IO) {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use {
                if (!it.isSuccessful) null else it.body?.string()
            }
        } ?: return null
        return parse(body, durationMs)?.also { lastGood.set(server) }
    }

    internal fun parse(body: String, durationMs: Long): Lyrics? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val meta = root["metadata"] as? JsonObject
        val total = (meta?.get("totalDuration") as? JsonPrimitive)?.contentOrNull?.let(Ttml::time)
        if (durationMs > 0 && total != null && total > 0 && abs(total - durationMs) > TOLERANCE_MS) return null
        val items = (root["lyrics"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val wordType = (root["type"] as? JsonPrimitive)?.contentOrNull.equals("Word", ignoreCase = true)
        val lines = items.mapIndexedNotNull { i, item ->
            val start = item.long("time") ?: return@mapIndexedNotNull null
            val next = items.getOrNull(i + 1)?.long("time")
            val end = item.long("duration")?.let { start + it } ?: next ?: (start + 4_000)
            // Background vocals are left out: they'd sweep under the lead line.
            val words = (item["syllabus"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { (it["isBackground"] as? JsonPrimitive)?.booleanOrNull != true }
                .mapNotNull { s ->
                    val text = (s["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val b = s.long("time") ?: return@mapNotNull null
                    LyricWord(text, b, b + (s.long("duration") ?: 0))
                }
                .filter { it.text.isNotBlank() }
            val text = (if (words.isNotEmpty()) words.joinToString("") { it.text } else (item["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                .replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            if (wordType && words.size >= 2) LyricLine(start, end, text, words, wordSynced = true)
            else LyricLine(start, end, text, LrcParser.spreadWords(text, start, end), wordSynced = false)
        }
        return lines.takeIf { it.isNotEmpty() }?.let { Lyrics.Synced(it, "LyricsPlus") }
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
