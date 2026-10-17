package com.opentune.ui.share

import android.content.Context
import android.content.Intent
import com.opentune.data.model.Song

/** Sharing an album or playlist: a link for YouTube Music's own, the songs themselves for one made in the app. */
object ListShare {
    /**
     * Where [browseId] opens on YouTube Music, and in OpenTune for anyone who
     * has it: playlists as playlist?list=, albums and the rest as browse/.
     */
    fun linkFor(browseId: String): String = when {
        browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("OLAK") || browseId.startsWith("RD") ->
            playlistLink(browseId)
        else -> "https://music.youtube.com/browse/$browseId"
    }

    /** A YouTube Music playlist's address, from its id with or without the `VL` it's browsed under. */
    fun playlistLink(playlistId: String): String = "https://music.youtube.com/playlist?list=${playlistId.removePrefix("VL")}"

    fun shareList(context: Context, title: String, browseId: String) =
        send(context, "$title\n${linkFor(browseId)}", title)

    /** A playlist on YouTube Music, by its id: anyone it's shared with can open it there and save it. */
    fun sharePlaylist(context: Context, title: String, playlistId: String) =
        send(context, "$title\n${playlistLink(playlistId)}", title)

    /**
     * A playlist made in the app has no address of its own, so it goes as
     * its songs, each with its link, up to [MAX_SONGS] of them.
     */
    internal fun localText(name: String, songs: List<Song>): String = buildString {
        append(name).append(" · ").append(songs.size).append(if (songs.size == 1) " song" else " songs").append('\n')
        songs.take(MAX_SONGS).forEachIndexed { i, s ->
            append('\n').append(i + 1).append(". ").append(s.title)
            if (s.artist.isNotBlank()) append(" – ").append(s.artist)
            append("\nhttps://music.youtube.com/watch?v=").append(s.videoId)
        }
        if (songs.size > MAX_SONGS) append("\n\n…and ").append(songs.size - MAX_SONGS).append(" more")
        append("\n\nShared from OpenTune")
    }

    fun shareLocal(context: Context, name: String, songs: List<Song>) = send(context, localText(name, songs), name)

    private fun send(context: Context, text: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
        runCatching { context.startActivity(Intent.createChooser(intent, "Share $title").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private const val MAX_SONGS = 50
}
