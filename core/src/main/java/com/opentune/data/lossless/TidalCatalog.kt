package com.opentune.data.lossless

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Resolve an exact recording without making the verified provider depend on song.link. */
internal object TidalCatalog {
    internal suspend fun withLinkedFallback(linked: kotlinx.coroutines.Deferred<String?>, search: suspend () -> String?): String? {
        if (linked.isCompleted) linked.await()?.let { return it }
        return search() ?: linked.await()
    }

    suspend fun find(identity: CatalogIdentity, registry: JSONObject?, report: (String) -> Unit): String? {
        for (base in LosslessRegistry.endpoints(registry, "tidal")) {
            try {
                val url = "$base/search/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", "${identity.title} ${identity.artist}").build()
                match(JSONObject(LosslessHttp.text(url.toString(), mapOf("X-API-Key" to LosslessRegistry.API_KEY))), identity)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                report("SpotiFLAC: a catalog endpoint was unavailable")
            }
        }
        return null
    }

    internal fun match(root: JSONObject, identity: CatalogIdentity): String? {
        val data = root.optJSONObject("data") ?: root
        val items = data.optJSONArray("items") ?: data.optJSONObject("tracks")?.optJSONArray("items") ?: return null
        for (i in 0 until minOf(items.length(), 50)) {
            val track = items.optJSONObject(i) ?: continue
            val artists = track.optJSONArray("artists")?.let { values ->
                (0 until values.length()).mapNotNull { values.optJSONObject(it)?.optString("name") }
            }.orEmpty() + listOfNotNull(track.optJSONObject("artist")?.optString("name"))
            val version = track.optString("version").takeUnless { it.isBlank() || it == "null" }
            val title = track.optString("title") + (version?.let { " ($it)" } ?: "")
            if (identity.matches(title, artists, track.optJSONObject("album")?.optString("title"), track.optLong("duration") * 1000)) {
                return track.optString("id").takeIf { Regex("[0-9]+").matches(it) }
            }
        }
        return null
    }
}
