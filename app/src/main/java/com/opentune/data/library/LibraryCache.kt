package com.opentune.data.library

import android.content.Context
import com.opentune.data.Collection
import com.opentune.data.account.AccountStore
import com.opentune.data.model.ShelfItem
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Private, account-scoped snapshots; music files are still managed by Downloads. */
object LibraryCache {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var root: File? = null
    private val _playlists = MutableStateFlow<List<ShelfItem>>(emptyList())
    val playlists = _playlists.asStateFlow()
    fun owner(): String = AccountStore.account.value?.email?.takeIf { it.isNotBlank() }
        ?.let(::hash) ?: "guest"

    fun init(context: Context) {
        root = File(context.filesDir, "library-cache").apply { mkdirs() }
        scope.launch {
            AccountStore.account.collectLatest {
                _playlists.value = emptyList()
                val key = owner()
                val saved = runCatching {
                    json.decodeFromString(ListSerializer(ShelfItem.serializer()), file(key, "index").readText())
                }.getOrDefault(emptyList())
                if (owner() == key) _playlists.value = saved
            }
        }
    }

    fun collection(id: String, owner: String = owner()): Collection? = runCatching {
        json.decodeFromString(Collection.serializer(), file(owner, id).readText())
    }.getOrNull()

    fun fresh(id: String, owner: String): Boolean = runCatching {
        val f = file(owner, id)
        f.isFile && System.currentTimeMillis() - f.lastModified() < 6 * 60 * 60 * 1000L
    }.getOrDefault(false)

    @Synchronized
    fun save(collection: Collection, owner: String) {
        if (this.owner() != owner) return
        write(file(owner, collection.browseId), json.encodeToString(Collection.serializer(), collection))
        LibraryArtwork.keep(listOf(collection.thumbnailUrl) + collection.songs.map { it.thumbnailUrl })
    }

    @Synchronized
    fun savePlaylists(items: List<ShelfItem>, owner: String) {
        if (this.owner() != owner) return
        write(file(owner, "index"), json.encodeToString(ListSerializer(ShelfItem.serializer()), items))
        if (this.owner() == owner) _playlists.value = items
        LibraryArtwork.keep(items.map { it.thumbnailUrl })
    }

    private fun file(owner: String, id: String) = File(root ?: error("Library cache not initialized"), "$owner-${hash(id)}.json")
    private fun write(file: File, value: String) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(value)
            check(tmp.renameTo(file))
        }
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
