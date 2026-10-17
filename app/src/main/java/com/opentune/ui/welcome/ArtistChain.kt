package com.opentune.ui.welcome

import com.opentune.data.ExploreFeed
import com.opentune.data.MusicRepository
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * How the welcome's artist grid grows: each artist picked brings in a few
 * more like them, placed right after it, and picking one of those brings in
 * more again, until Done.
 */
internal object ArtistChain {
    /** How many new artists one pick brings in. */
    const val PER_PICK = 6

    /** Fewer than this off the artist page, and the radio is asked as well. */
    private const val ENOUGH = 3

    /**
     * Artists like the one picked: the "Fans might also like" row off their
     * page; failing that, the artists most heard in a radio off their top
     * song, with photos from their own pages.
     */
    suspend fun load(browseId: String): List<ExploreFeed.Artist> {
        val page = MusicRepository.artist(browseId)
        val onPage = similar(page, browseId)
        if (onPage.size >= ENOUGH) return onPage
        val lead = page.songs.firstOrNull() ?: return onPage
        val heard = fromRadio(MusicRepository.watchQueue(lead.videoId), browseId)
            .filter { (id, _) -> onPage.none { it.browseId == id } }
            .take(PER_PICK)
        val withPhotos = coroutineScope {
            heard.map { (id, name) ->
                async {
                    val photo = runCatching { MusicRepository.artist(id).thumbnailUrl }.getOrNull()
                    ExploreFeed.Artist(id, name, photo)
                }
            }.awaitAll()
        }
        return onPage + withPhotos
    }

    /**
     * Other artists on [page]: rows of nothing but artists first (that's
     * "Fans might also like"), then artist cards from any other row.
     */
    fun similar(page: ArtistPage, self: String): List<ExploreFeed.Artist> {
        fun ShelfItem.isArtist() = videoId == null && browseId?.startsWith("UC") == true
        val (rows, rest) = page.sections.partition { s -> s.items.isNotEmpty() && s.items.all { it.isArtist() } }
        return (rows + rest).flatMap { it.items }
            .filter { it.isArtist() && it.browseId != self }
            .distinctBy { it.browseId }
            .map { ExploreFeed.Artist(it.browseId!!, it.title, it.thumbnailUrl) }
    }

    /** Artists credited in a radio other than [self], as id and name, the most heard first. */
    fun fromRadio(songs: List<Song>, self: String): List<Pair<String, String>> =
        songs.filter { it.artistId != null && it.artistId != self }
            .groupBy { it.artistId!! }
            .entries
            .sortedByDescending { it.value.size }
            .map { (id, credited) -> id to credited.first().artist.split(", ", " & ").first().trim() }

    /**
     * [grid] with up to [max] of [found] that aren't on it yet, placed right
     * after [anchor] (at the end if [anchor] isn't there).
     */
    fun grow(grid: List<ExploreFeed.Artist>, anchor: String, found: List<ExploreFeed.Artist>, max: Int = PER_PICK): List<ExploreFeed.Artist> {
        val have = grid.mapTo(HashSet()) { it.browseId }
        val fresh = found.filter { have.add(it.browseId) }.take(max)
        if (fresh.isEmpty()) return grid
        val at = grid.indexOfFirst { it.browseId == anchor }.let { if (it < 0) grid.size else it + 1 }
        return grid.take(at) + fresh + grid.drop(at)
    }
}
