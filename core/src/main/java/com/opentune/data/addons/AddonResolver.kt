package com.opentune.data.addons

import com.opentune.data.lossless.CatalogIdentity
import com.opentune.data.lossless.LosslessSource
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object AddonResolver {
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun failed(addon: String, song: String) { failures["$addon:$song"] = System.nanoTime() / 1_000_000 + 120_000 }
    fun retry() { failures.clear() }

    /** First verified match wins, in the listener's order. Playback never waits on this search. */
    suspend fun resolve(song: Song, durationMs: Long, download: Boolean = false,
        report: (String) -> Unit = {},
        clientFor: (String) -> AddonClient = AddonClient::forBase,
        probe: suspend (String, Int, Boolean) -> ByteArray = AddonHttp::bytes): AddonStream? = withContext(Dispatchers.IO) {
        if (durationMs <= 0) return@withContext null
        val identity = CatalogIdentity(song.title, song.artist, null, durationMs)
        for (addon in AddonStore.addons.value) {
            if (!addon.enabled) continue
            if ((failures["${addon.key}:${song.videoId}"] ?: 0) > System.nanoTime() / 1_000_000) { report("${addon.manifest.name}: recent playback failure; trying the next add-on"); continue }
            if (!AddonStore.usable(addon)) { report("${addon.manifest.name}: needs a supported lossless output"); continue }
            if (download && !addon.manifest.downloads) { report("${addon.manifest.name}: streaming only"); continue }
            val result = withTimeoutOrNull(10_000) {
                try {
                    val client = clientFor(addon.base)
                    val manifest = client.manifest()
                    AddonStore.refreshed(addon.key, manifest)
                    fun permitted(): Boolean = AddonStore.addons.value.firstOrNull { it.key == addon.key }?.let {
                        AddonStore.usable(it) && (!download || it.manifest.downloads)
                    } == true
                    if (!permitted()) return@withTimeoutOrNull null
                    val candidates = client.search("${song.title} ${song.artist}", manifest, "LOSSLESS")
                        .filter { identity.matches(it.title, listOf(it.artist), null, it.durationMs) }
                    if (candidates.isEmpty()) { report("${manifest.name}: no matching recording"); return@withTimeoutOrNull null }
                    for (track in candidates.take(3)) {
                        if (!permitted()) return@withTimeoutOrNull null
                        val stream = try { client.stream(track.id, manifest, "LOSSLESS") }
                        catch (e: AddonHttp.Failure) {
                            if (e.status != 404 || track.streamUrl == null) throw e
                            AddonProtocol.stream(org.json.JSONObject().put("url", track.streamUrl).put("format", track.format).toString())
                        }
                        if (!stream.lossless) { report("${manifest.name}: returned compressed audio for a lossless request"); continue }
                        if (download && stream.transport != null) {
                            report("${manifest.name}: segmented stream; trying the next source for a downloadable file"); continue
                        }
                        if (!permitted()) return@withTimeoutOrNull null
                        val checked = if (stream.transport == null && stream.codec == "flac") {
                            val info = LosslessSource.flacInfo(probe(stream.url, 65536, true))
                            if (info == null || !LosslessSource.durationMatches(durationMs, info.durationMs)) {
                                report("${manifest.name}: FLAC header or duration did not match"); continue
                            }
                            stream.copy(bits = info.bits, sampleRate = info.sampleRate)
                        } else {
                            val prefix = probe(stream.url, 65536, true)
                            if (stream.transport != null) {
                                val text = prefix.toString(Charsets.UTF_8)
                                val valid = if (stream.transport == "hls") text.trimStart().startsWith("#EXTM3U") && !Regex("#EXT-X-(?:SESSION-)?KEY:.*METHOD=(?!NONE)").containsMatchIn(text)
                                    else text.contains("<MPD") && !text.contains("ContentProtection", true)
                                if (!valid) { report("${manifest.name}: invalid or protected media manifest"); continue }
                            }
                            stream
                        }
                        report("${manifest.name}: ${if (stream.transport == null && stream.codec == "flac") "verified FLAC" else "lossless requested; decoder confirms quality during playback"}")
                        return@withTimeoutOrNull checked.copy(addonId = addon.key, provider = manifest.name)
                    }
                    null
                } catch (e: CancellationException) { throw e }
                catch (e: AddonHttp.Failure) { report("${addon.manifest.name}: HTTP ${e.status}${if (e.status == 429) " · rate limited" else ""}"); null }
                catch (_: Exception) { report("${addon.manifest.name}: unavailable or invalid response"); null }
            }
            if (result != null) return@withContext result
        }
        null
    }
}
