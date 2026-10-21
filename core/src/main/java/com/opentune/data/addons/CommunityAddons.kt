package com.opentune.data.addons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Public owner-curated catalog. URLs are hidden in UI, not confidential network credentials. */
object CommunityAddons {
    private const val CONFIG = "https://justshivv.github.io/Opentune/usage-config.json"
    private val mutex = Mutex()
    private var lastCheck = 0L
    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastCheck < 15 * 60_000 && lastCheck != 0L) return@withLock true
        withContext(Dispatchers.IO) {
            try {
                val config = JSONObject(AddonHttp.bytes(CONFIG, 8192, false).toString(Charsets.UTF_8))
                val base = AddonProtocol.url(config.getString("apiBase"))
                // Only the configured OpenTune Worker supplies the catalog, never an arbitrary relay.
                require(base.host == "opentune-usage.create-a-new-site-with-sites.workers.dev")
                val document = JSONObject(AddonHttp.bytes(base.newBuilder().encodedPath("/v1/addons").build().toString(), 65536, false).toString(Charsets.UTF_8))
                val sources = document.getJSONArray("sources")
                require(sources.length() <= 20)
                val incoming = mutableListOf<AudioAddon>()
                for (i in 0 until sources.length()) {
                    val item = sources.getJSONObject(i)
                    val id = item.getString("id"); require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                    val name = item.getString("name").take(80)
                    val url = AddonProtocol.normalize(item.getString("url"))
                    val existing = AddonStore.addons.value.firstOrNull { it.key == "admin:$id" && it.base == url }
                    val manifest = existing?.manifest ?: try { AddonClient.forBase(url).manifest() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { AddonManifest("admin:$id", name, "") }
                    incoming += AudioAddon("admin:$id", url, manifest.copy(name = name), admin = true)
                }
                require(incoming.map { it.key }.distinct().size == incoming.size)
                AddonStore.syncCommunity(incoming)
                lastCheck = now
                true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false } // Offline never clears the last working catalog.
        }
    }
}
