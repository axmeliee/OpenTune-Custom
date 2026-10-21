package com.opentune.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.opentune.data.settings.AppSettings
import java.io.File

/**
 * Streamed audio kept on disk, keyed by video id rather than by URL (stream
 * URLs change every resolve), so replays and seeks inside played audio don't
 * touch the network. Least recently played tracks go first once the limit set
 * in Settings is reached. One instance per process, as SimpleCache requires;
 * a new limit takes effect the next time the app starts.
 */
@OptIn(UnstableApi::class)
object AudioCache {
    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(AppSettings.library.value.songCacheMb.toLong() * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        ).also { cache = it }
    }

    fun usedBytes(context: Context): Long = runCatching { get(context).cacheSpace }.getOrDefault(0L)

    /** Whether any of [videoId]'s audio is kept here, in any quality or from any source. */
    fun holds(context: Context, videoId: String): Boolean =
        runCatching { get(context).keys.any { videoId in it } }.getOrDefault(false)

    /** Drops [videoId]'s kept audio, every copy of it; it's fetched again the next time it plays. */
    fun remove(context: Context, videoId: String) {
        val c = get(context)
        c.keys.filter { videoId in it }.forEach { runCatching { c.removeResource(it) } }
    }

    fun clear(context: Context) {
        val c = get(context)
        c.keys.toList().forEach { runCatching { c.removeResource(it) } }
    }
}
