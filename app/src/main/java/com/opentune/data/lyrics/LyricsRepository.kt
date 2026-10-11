package com.opentune.data.lyrics

import com.opentune.data.isYouTubeId
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.innertube.Innertube
import com.opentune.data.local.LocalMusic
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.opentune.data.download.Downloads
import com.opentune.data.settings.LyricsSource
import com.opentune.data.settings.AppSettings
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Lyrics from LRCLIB (lrclib.net): free, keyless, and mostly line-synced, with
 * word sync for some tracks; from LyricsPlus (see [LyricsPlus]), often word
 * by word; from KuGou (see [KuGou]) and NetEase (see
 * [NetEase]), line-synced; and from YouTube Music, unsynced.
 *
 * Every enabled source is asked at once and the answers are read in the
 * order set in Settings › Lyrics sources. Plain text never stops the search:
 * the first source in that order with synced lyrics wins, and plain text
 * shows only when none has them. Within
 * LRCLIB: an exact match on title, artist and duration; then a search on
 * title and artist, preferring synced results whose duration is closest;
 * then a free-text search on the cleaned title. YouTube Music's lyrics are
 * unsynced and never used for local files.
 */
object LyricsRepository {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "OpenTune (https://github.com/justshivv/Opentune)"
    private const val TAG = "Lyrics"

    /** A search hit further than this from the track's duration is a different recording. */
    private const val DURATION_TOLERANCE_S = 8.0
    /** Within this, a hit is the same recording. */
    private const val CLOSE_S = 2.0

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Lyrics?>()

    /** Lyrics saved with a download, so they show offline. */
    private fun offlineFile(videoId: String): File? = Downloads.lyricsFileFor(videoId)

    private fun readOffline(videoId: String): Lyrics? =
        offlineFile(videoId)?.takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString(Lyrics.serializer(), f.readText()) }.getOrNull()
        }

    /** Fetches and keeps the lyrics for a downloaded song; called by the download job. */
    suspend fun saveOffline(videoId: String, title: String, artist: String, durationMs: Long) {
        val f = offlineFile(videoId) ?: return
        val found = lyricsFor(videoId, title, artist, durationMs) ?: return
        withContext(Dispatchers.IO) { runCatching { f.writeText(json.encodeToString(Lyrics.serializer(), found)) } }
    }

    suspend fun lyricsFor(videoId: String, title: String, artist: String, durationMs: Long): Lyrics? {
        withContext(Dispatchers.IO) { readOffline(videoId) }?.let { return it }
        val settings = AppSettings.lyrics.value
        val chosen = settings.chosen[videoId]
        val key = "$videoId|${settings.ordered.filter { it.enabled }.joinToString(",") { it.source.name }}|${settings.preferWordSynced}|$chosen"
        if (cache.containsKey(key)) return cache[key]
        // A source picked for this song from the player goes first, on its own.
        if (chosen != null) {
            withContext(Dispatchers.IO) { ask(chosen, videoId, title, artist, durationMs) }.getOrNull()?.let { picked ->
                if (durationMs > 0) cache[key] = picked
                return picked
            }
        }
        val enabled = settings.ordered.filter { it.enabled }.map { it.source }
        val target = if (settings.preferWordSynced) WORD_SYNCED else LINE_SYNCED
        // Every source is asked at once, as Echo Music does, and the answers
        // are read in the user's order: a slow source no longer holds up the
        // ones after it, and the order still decides between them.
        val pending = enabled.map { source -> source to lookups.async { ask(source, videoId, title, artist, durationMs) } }
        val deadline = System.nanoTime() + BUDGET_MS * 1_000_000
        var failed = false
        var best: Lyrics? = null
        try {
            for ((source, answer) in pending) {
                val wait = (deadline - System.nanoTime()) / 1_000_000
                // Past the budget, only answers already in are read.
                val outcome = if (answer.isCompleted || wait > 0) withTimeoutOrNull(wait.coerceAtLeast(1)) { answer.await() } else null
                if (outcome == null || outcome.isFailure) {
                    if (source in COUNTED) failed = true
                    outcome?.exceptionOrNull()?.let { Log.w(TAG, "${source.label} lookup failed for $videoId", it) }
                    continue
                }
                val found = outcome.getOrNull() ?: continue
                if (rank(found) > rank(best)) best = found
                // The first source with synced lyrics wins (word by word, when
                // that's wanted). Plain text only holds the place: a later
                // source may well have the same song in time.
                if (rank(found) >= target) break
            }
        } finally {
            pending.forEach { it.second.cancel() }
        }
        val result = best
        // Only remember answers, and only ones matched on the song's length:
        // without it any version's lyrics can match, timed for another cut.
        // Plain text found while a synced source was down isn't kept either,
        // so the next play asks again.
        val settled = !(result is Lyrics.Plain && failed)
        if (result != null && durationMs > 0 && settled) cache[key] = result
        return result
    }

    /**
     * Every source's answer for one song, as each comes in, for the player's
     * "Lyrics source" sheet. Sources switched off in Settings are asked too:
     * this is where to see whether one would help.
     */
    fun everySource(videoId: String, title: String, artist: String, durationMs: Long): Flow<Pair<LyricsSource, Result<Lyrics?>>> = channelFlow {
        LyricsSource.entries.forEach { source ->
            launch(Dispatchers.IO) { send(source to ask(source, videoId, title, artist, durationMs)) }
        }
    }

    internal fun rank(lyrics: Lyrics?): Int = when (lyrics) {
        null -> 0
        is Lyrics.Plain -> 1
        is Lyrics.Synced -> if (lyrics.lines.any { it.wordSynced }) WORD_SYNCED else LINE_SYNCED
    }

    internal const val LINE_SYNCED = 2
    internal const val WORD_SYNCED = 3

    /** Lookups outlive the caller's wait: a source that's still out is dropped, not waited for. */
    private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How long the sources get, all together, before the best answer in hand is shown. */
    private const val BUDGET_MS = 12_000L

    /** Sources whose failure means a plain-text answer may only be standing in. */
    private val COUNTED = setOf(LyricsSource.LRCLIB, LyricsSource.LYRICSPLUS, LyricsSource.KUGOU, LyricsSource.NETEASE)

    private suspend fun ask(source: LyricsSource, videoId: String, title: String, artist: String, durationMs: Long): Result<Lyrics?> = runCatching {
        when (source) {
            LyricsSource.LRCLIB -> lookup(title, artist, durationMs)
            LyricsSource.LYRICSPLUS -> LyricsPlus.lookup(title, artist, durationMs)
            LyricsSource.KUGOU -> runInterruptible { KuGou.lookup(title, artist, durationMs) }
            LyricsSource.NETEASE -> runInterruptible { NetEase.lookup(title, artist, durationMs) }
            LyricsSource.UNISON -> if (!isYouTubeId(videoId)) null else runInterruptible { Unison.lookup(videoId, title, artist, durationMs) }
            LyricsSource.YOUTUBE_CAPTIONS -> if (!isYouTubeId(videoId)) null else YouTubeCaptions.lookup(videoId)
            LyricsSource.YOUTUBE_MUSIC -> if (!isYouTubeId(videoId)) null else youTubeMusic(videoId)
        }
    }

    private suspend fun lookup(title: String, artist: String, durationMs: Long): Lyrics? = coroutineScope {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val durationS = durationMs / 1000.0

        // Each step returns at once with synced lyrics; plain text is kept
        // in case no later step finds them in time.
        var plain: Lyrics? = null
        fun take(found: Lyrics?): Lyrics? {
            if (found is Lyrics.Synced) return found
            if (plain == null) plain = found
            return null
        }

        // The exact match and the search by fields go out together, so a slow
        // or busy exact lookup doesn't hold up the search behind it.
        val exact = if (durationMs > 0) async(Dispatchers.IO) {
            runCatching {
                request(
                    "get",
                    "track_name" to cleaned.title,
                    "artist_name" to cleaned.artist,
                    "duration" to durationS.toLong().toString(),
                ) as? JsonObject
            }
        } else null
        val byFields = async(Dispatchers.IO) { runCatching { request("search", "track_name" to cleaned.title, "artist_name" to cleaned.artist) } }
        val e = exact?.await()
        take(e?.getOrNull()?.let { toLyrics(it, durationMs) })?.let { return@coroutineScope it }
        val f = byFields.await()
        take(pick(f.getOrNull(), durationS)?.let { toLyrics(it, durationMs) })?.let { return@coroutineScope it }
        // Both turned away rather than empty: LRCLIB is down, which the caller counts.
        if ((e == null || e.isFailure) && f.isFailure) throw f.exceptionOrNull()!!

        val byQuery = request("search", "q" to "${cleaned.artist} ${cleaned.title}")
        take(pick(byQuery, durationS)?.let { toLyrics(it, durationMs) })?.let { return@coroutineScope it }

        // The title alone, for an artist LRCLIB spells another way (accents, a
        // transliteration, a "feat." in the artist field). Only a hit by the
        // same artist counts, so a cover's words never sync to this song.
        val byTitle = request("search", "track_name" to cleaned.title)
        take(pick(sameArtist(byTitle, cleaned.artist), durationS)?.let { toLyrics(it, durationMs) })?.let { return@coroutineScope it }
        plain
    }

    /** The results whose artist matches [artist] loosely: one name inside the other, ignoring case, accents and punctuation. */
    internal fun sameArtist(results: JsonElement?, artist: String): JsonArray {
        val want = looseName(artist)
        if (want.length < 2) return JsonArray(emptyList())
        return JsonArray((results as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { o ->
            val got = looseName(o.str("artistName").orEmpty())
            got.length >= 2 && (got.contains(want) || want.contains(got))
        })
    }

    private fun looseName(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * YouTube Music's own lyrics: unsynced text from LyricFind and others.
     * The watch queue links a Lyrics tab by browse id (`MPLYt…`); browsing
     * it returns the text in a description shelf.
     */
    private suspend fun youTubeMusic(videoId: String): Lyrics? {
        val browseId = findString(Innertube.next(videoId)) { key, value -> key == "browseId" && value.startsWith("MPLYt") }
            ?: return null
        val page = Innertube.browse(browseId)
        val shelf = findObject(page, "musicDescriptionShelfRenderer") ?: return null
        val text = (shelf["description"] as? JsonObject)?.runsText()?.takeIf { it.isNotBlank() } ?: return null
        return Lyrics.Plain(text.trim(), "YouTube Music")
    }

    private fun JsonObject.runsText(): String =
        (this["runs"] as? JsonArray).orEmpty().joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }

    private fun findString(node: JsonElement, match: (String, String) -> Boolean): String? = when (node) {
        is JsonObject -> node.entries.firstNotNullOfOrNull { (k, v) ->
            val str = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (str != null && match(k, str)) str else findString(v, match)
        }
        is JsonArray -> node.firstNotNullOfOrNull { findString(it, match) }
        else -> null
    }

    private fun findObject(node: JsonElement, key: String): JsonObject? = when (node) {
        is JsonObject -> (node[key] as? JsonObject) ?: node.values.firstNotNullOfOrNull { findObject(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { findObject(it, key) }
        else -> null
    }

    /**
     * The best search hit for a track [durationS] long: the synced result
     * closest in length within [CLOSE_S], else the closest synced one within
     * [DURATION_TOLERANCE_S], else the closest plain one. A recording a few
     * seconds longer (an intro, a radio edit) puts every line off by that
     * much, so length matters more than the order LRCLIB lists them in.
     */
    internal fun pick(results: JsonElement?, durationS: Double): JsonObject? {
        val all = (results as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        fun gap(o: JsonObject) = if (durationS <= 0) 0.0 else abs((o.num("duration") ?: durationS) - durationS)
        val near = all.filter { durationS <= 0 || gap(it) <= DURATION_TOLERANCE_S }.sortedBy(::gap)
        val synced = near.filter { !it.str("syncedLyrics").isNullOrBlank() }
        return synced.firstOrNull { gap(it) <= CLOSE_S }
            ?: synced.firstOrNull()
            ?: near.firstOrNull { !it.str("plainLyrics").isNullOrBlank() }
    }

    private fun toLyrics(obj: JsonObject, durationMs: Long): Lyrics? {
        obj.str("syncedLyrics")?.takeIf { it.isNotBlank() }?.let { lrc ->
            val lines = LrcParser.parse(lrc, durationMs)
            if (lines.isNotEmpty()) return Lyrics.Synced(lines, "LRCLIB")
        }
        return obj.str("plainLyrics")?.takeIf { it.isNotBlank() }?.let { Lyrics.Plain(it.trim(), "LRCLIB") }
    }

    private fun request(path: String, vararg params: Pair<String, String>): JsonElement? {
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        // LRCLIB is often busy (503) or rate-limits (429) for a moment; a
        // short wait and another try usually gets through.
        for (attempt in 0..RETRIES) {
            val call = Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            val busy = call.execute().use { response ->
                if (response.code == 404) return null
                if (response.code == 429 || response.code in 500..599) {
                    if (attempt == RETRIES) throw java.io.IOException("LRCLIB answered ${response.code}")
                    true
                } else {
                    if (!response.isSuccessful) throw java.io.IOException("LRCLIB answered ${response.code}")
                    return response.body?.string()?.let(json::parseToJsonElement)
                }
            }
            if (busy) Thread.sleep(RETRY_WAIT_MS * (attempt + 1))
        }
        return null
    }

    private const val RETRIES = 2
    private const val RETRY_WAIT_MS = 900L

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
