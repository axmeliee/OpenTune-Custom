package com.opentune.data.library

import com.opentune.data.history.PlayRecord
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.Song
import java.util.Locale
import kotlinx.serialization.Serializable

/** A song marked "Not interested": kept out of suggestions, still playable. */
@Serializable
data class HiddenSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val hiddenAt: Long = 0,
)

/**
 * An artist marked "Don't play": every song credited to them is kept out of
 * suggestions. The name is what catches most rows, because most rows carry
 * no artist id; the id, when the song had one, also catches a row that
 * spells the name differently.
 */
@Serializable
data class HiddenArtist(
    val name: String,
    val browseId: String? = null,
    val hiddenAt: Long = 0,
) {
    /** Identity in the list: the same name hidden twice is one entry. */
    val key: String get() = HiddenList.normalize(name)
}

/**
 * What the user asked not to be suggested. Applied only where the app picks
 * songs on its own: autoplay and radio, smart shuffle, Daily Mixes and the
 * song shelves on Home. Playlists, Liked songs, downloads and albums are the
 * user's own choices and are never filtered, and a hidden song tapped
 * directly still plays.
 */
data class HiddenList(
    val songs: List<HiddenSong> = emptyList(),
    val artists: List<HiddenArtist> = emptyList(),
) {
    private val songIds: Set<String> by lazy { songs.mapTo(HashSet()) { it.videoId } }
    private val artistIds: Set<String> by lazy { artists.mapNotNullTo(HashSet()) { it.browseId } }
    private val artistKeys: Set<String> by lazy { artists.mapTo(HashSet()) { it.key } }

    val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty()

    fun hidesSong(videoId: String?): Boolean = videoId != null && videoId in songIds

    /**
     * Whether any artist on a credit line is hidden. A line names several
     * artists ("A, B & C", "A feat. B"); one hidden name among them is
     * enough. The whole line is tried too, so a hidden "Simon & Garfunkel"
     * still matches its own songs.
     */
    fun hidesArtist(artist: String?, artistId: String? = null): Boolean {
        if (artists.isEmpty()) return false
        if (artistId != null && artistId in artistIds) return true
        if (artist.isNullOrBlank()) return false
        if (normalize(artist) in artistKeys) return true
        return artistNames(artist).any { normalize(it) in artistKeys }
    }

    fun hides(videoId: String?, artist: String?, artistId: String? = null): Boolean =
        hidesSong(videoId) || hidesArtist(artist, artistId)

    fun hides(song: Song): Boolean = hides(song.videoId, song.artist, song.artistId)

    fun songs(list: List<Song>): List<Song> = if (isEmpty) list else list.filterNot(::hides)

    fun records(list: List<PlayRecord>): List<PlayRecord> =
        if (isEmpty) list else list.filterNot { hides(it.videoId, it.artist) }

    /**
     * Home's shelves without hidden songs. Only song cards are checked: an
     * album or playlist card is something to open, not something that plays
     * on its own. A shelf left empty goes.
     */
    fun shelves(list: List<HomeShelf>): List<HomeShelf> {
        if (isEmpty) return list
        return list.mapNotNull { shelf ->
            val kept = shelf.items.filterNot { item ->
                item.videoId != null && item.browseId == null &&
                    hides(item.videoId, InnertubeParser.artistFromSubtitle(item.subtitle))
            }
            when {
                kept.size == shelf.items.size -> shelf
                kept.isEmpty() -> null
                else -> shelf.copy(items = kept)
            }
        }
    }

    companion object {
        val EMPTY = HiddenList()

        private val LIST = Regex("""\s*(?:,|·|\s\(?feat\.?\s|\s\(?ft\.\s|\s\(?featuring\s)\s*""", RegexOption.IGNORE_CASE)
        private val AMPERSAND = Regex("""\s*&\s*""")
        private val LOOSE = Regex("""\s+(?:x|and|with)\s+""", RegexOption.IGNORE_CASE)

        /**
         * Every name a credit line could mean, broadest first. A line is cut
         * at commas and "feat." first, then at "&", then at "x", "and" and
         * "with". Each level is kept, because each separator is also part of
         * some artist's own name: "Simon & Garfunkel", "Lil Nas X",
         * "Florence and the Machine".
         */
        fun artistNames(line: String): List<String> {
            val listed = line.split(LIST).map { it.trim().trimEnd(')') }.filter(String::isNotBlank)
            val joined = listed.flatMap { it.split(AMPERSAND) }
            val loose = joined.flatMap { it.split(LOOSE) }
            return (listed + joined + loose).map(String::trim).filter(String::isNotEmpty).distinct()
        }

        /**
         * The artist a "Don't play" picks from a song's credit line: the
         * first one named, as the rest of the app reads it (see
         * [com.opentune.data.reco.Recommendations.mainArtist]).
         */
        fun mainArtist(line: String): String =
            line.substringBefore(",").substringBefore(" & ").substringBefore(" feat").substringBefore(" ft.").substringBefore(" x ").trim()

        /** Case, spacing and a leading "The" don't make a different artist. */
        fun normalize(name: String): String =
            name.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").removePrefix("the ")
    }
}
