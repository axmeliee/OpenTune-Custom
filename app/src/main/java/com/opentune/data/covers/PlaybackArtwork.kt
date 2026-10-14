package com.opentune.data.covers

import com.opentune.data.download.Downloads
import com.opentune.data.model.PLAYER_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings

/** One high-resolution image per playing track, even if metadata arrives again later. */
internal class PlaybackArtwork(
    private val preferred: (Song) -> String? = { song ->
        Downloads.artFor(song.videoId)?.toURI()?.toString()
            ?: if (AppSettings.ui.value.albumCovers) AlbumCovers.cached(song) else null
    },
) {
    private var id: String? = null
    private var url: String? = null
    fun choose(song: Song): String? {
        if (id != song.videoId) {
            id = song.videoId
            url = (preferred(song) ?: song.thumbnailUrl).artworkAt(PLAYER_ART_PX)
        }
        return url
    }
    fun clear() { id = null; url = null }
}
