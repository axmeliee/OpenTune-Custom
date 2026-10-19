package com.opentune.playback

import com.opentune.data.DebugLog as Log
import com.opentune.data.ExploreFeed
import com.opentune.data.isYouTubeId
import com.opentune.data.library.HiddenList
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.reco.Recommendations
import com.opentune.data.settings.AppSettings
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** The player's shuffle button: off, the list shuffled, or shuffled with similar songs mixed in. */
enum class ShuffleMode { OFF, ON, SMART }

/**
 * The mode the shuffle button moves to from this one: off, shuffle, then
 * smart shuffle when [smartAvailable] (a queue from a list), then off.
 */
fun ShuffleMode.next(smartAvailable: Boolean): ShuffleMode = when (this) {
    ShuffleMode.OFF -> ShuffleMode.ON
    ShuffleMode.ON -> if (smartAvailable) ShuffleMode.SMART else ShuffleMode.OFF
    ShuffleMode.SMART -> ShuffleMode.OFF
}

/**
 * Smart shuffle: a shuffled playlist or album with a few similar songs mixed
 * in, about one after every four of the list's own. The songs come from the
 * same engine autoplay uses (see [Recommendations]) and skip whatever the
 * user hid.
 *
 * The queue rules are pure functions so they can be tested without a player;
 * [PlayerConnection] applies them. The state here — which list the queue came
 * from and whether smart shuffle is on for it — lives in the process rather
 * than in a screen, so the player shows it right after the app is reopened
 * while the music kept playing.
 */
object SmartShuffle {
    private const val TAG = "SmartShuffle"

    /** List songs per added song. */
    const val EVERY = 4

    /** Each seed's radio brings about twenty songs; more seeds give more variety. */
    private const val PICKS_PER_SEED = 15
    private const val MAX_SEEDS = 4

    // ---- Queue rules -------------------------------------------------------------

    /**
     * The mixed play order: [list] shuffled by [seed], with one of
     * [recommendations] after every [every] list songs until the picks run
     * out. The list is never cut short, and its songs are never filtered:
     * they are the user's own. Picks the user hid, picks already in the list,
     * and repeats are dropped (see [picks]). Added songs come back marked
     * [Song.smartShuffle]; list songs come back unmarked.
     *
     * The same inputs give the same order, so the queue can be shuffled at
     * once and the picks slotted in when they arrive, without moving a song
     * the user already sees.
     */
    fun mix(list: List<Song>, recommendations: List<Song>, hidden: HiddenList, seed: Long, every: Int = EVERY): List<Song> =
        interleave(list.map { it.copy(smartShuffle = false) }.shuffled(Random(seed)), picks(list, recommendations, hidden), every)

    /** The recommendations worth adding to [list], once each and marked. */
    fun picks(list: List<Song>, recommendations: List<Song>, hidden: HiddenList): List<Song> {
        val have = list.mapTo(HashSet()) { it.videoId }
        return recommendations
            .filter { it.videoId !in have && !hidden.hides(it) }
            .distinctBy { it.videoId }
            .map { it.copy(smartShuffle = true) }
    }

    /** [songs] in order with one of [picks] after every [every]. */
    fun interleave(songs: List<Song>, picks: List<Song>, every: Int = EVERY): List<Song> {
        require(every > 0)
        val out = ArrayList<Song>(songs.size + picks.size)
        var next = 0
        songs.forEachIndexed { i, song ->
            out += song
            if ((i + 1) % every == 0 && next < picks.size) out += picks[next++]
        }
        return out
    }

    /**
     * Where the added songs of [mixed] go in a queue whose songs after the
     * current one are [upcoming] (by id): each pair is a position counted
     * from just after the current song, in the order to insert them. Null
     * when [upcoming] isn't [mixed] without its added songs, which means the
     * queue changed while the picks were being found.
     */
    fun insertions(upcoming: List<String>, mixed: List<Song>): List<Pair<Int, Song>>? {
        val out = mutableListOf<Pair<Int, Song>>()
        var read = 0
        mixed.forEachIndexed { at, song ->
            if (song.smartShuffle) {
                out += at to song
            } else {
                if (read >= upcoming.size || upcoming[read] != song.videoId) return null
                read++
            }
        }
        return if (read == upcoming.size) out else null
    }

    /**
     * Positions in [upcoming] (the songs after the current one) of added
     * songs to take out: every one not [kept], for turning smart shuffle
     * off; or, with [hidden], only those the user has since hidden. Highest
     * first, so removing them in order doesn't shift the rest.
     */
    fun toRemove(upcoming: List<Song>, kept: Set<String>, hidden: HiddenList? = null): List<Int> =
        upcoming.indices.filter { i ->
            val song = upcoming[i]
            song.smartShuffle && song.videoId !in kept && (hidden == null || hidden.hides(song))
        }.reversed()

    // ---- Where the queue came from ---------------------------------------------------

    /**
     * The list a queue was started from. [playlistId] is set when that list
     * is one of the app's own playlists, which "Keep in playlist" adds to.
     * [canMix] is false for a list with no YouTube Music songs (files on the
     * phone, a server's songs): the engines have nothing to start from.
     */
    data class Origin(val token: Long, val songIds: Set<String>, val playlistId: String?, val canMix: Boolean = true)

    private val _origin = MutableStateFlow<Origin?>(null)
    val origin: StateFlow<Origin?> = _origin.asStateFlow()

    private val _on = MutableStateFlow(false)
    /** Whether smart shuffle is on for [origin]'s queue. */
    val on: StateFlow<Boolean> = _on.asStateFlow()

    /** Added songs the user kept in the playlist; turning smart shuffle off leaves them queued. */
    private val kept = mutableSetOf<String>()
    fun kept(): Set<String> = kept.toSet()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short messages for the app's snackbar. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var tokens = 0L

    /** A queue from [songs] begins. A local playlist's rows carry its id as [Song.playbackSourceId]. */
    fun startList(songs: List<Song>): Origin {
        val playlistId = songs.firstOrNull()?.playbackSourceId?.takeIf { id -> LibraryStore.playlist(id) != null }
        val o = Origin(++tokens, songs.mapTo(HashSet()) { it.videoId }, playlistId, songs.any { isYouTubeId(it.videoId) })
        _origin.value = o
        _on.value = false
        kept.clear()
        return o
    }

    /** A queue that isn't a list began: a song on its own, a station, a radio. */
    fun clear() {
        _origin.value = null
        _on.value = false
        kept.clear()
    }

    fun setOn(on: Boolean) {
        _on.value = on && _origin.value != null
    }

    fun keep(videoId: String) {
        kept += videoId
    }

    internal fun notice(text: String) {
        _notices.tryEmit(text)
    }

    // ---- Finding the songs ------------------------------------------------------

    /**
     * Songs similar to [list], from a few of its songs picked by [seed], taken
     * in turn from each so no one seed fills the mix. Disliked songs are left
     * out here; hidden ones are dropped by [Recommendations] and again by
     * [picks].
     */
    suspend fun recommendationsFor(list: List<Song>, seed: Long): List<Song> = coroutineScope {
        val wanted = list.size / EVERY + 1
        val seeds = list.filter { isYouTubeId(it.videoId) }.distinctBy { it.videoId }
            .shuffled(Random(seed xor 0x5EED))
            .take((wanted + PICKS_PER_SEED - 1) / PICKS_PER_SEED)
            .take(MAX_SEEDS)
        val engine = AppSettings.playback.value.recommender
        seeds.map { s ->
            async {
                try {
                    Recommendations.after(s, engine).songs
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't find songs like ${s.title}", e)
                    emptyList()
                }
            }
        }.awaitAll()
            .let { ExploreFeed.weave(it) }
            .filterNot { LibraryStore.isDisliked(it.videoId) }
    }
}
