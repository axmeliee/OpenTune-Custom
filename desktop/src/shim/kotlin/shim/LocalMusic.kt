package com.opentune.data.local

/** The phone's music files are read through MediaStore; on the desktop there are none, only the id prefix. */
object LocalMusic {
    const val PREFIX = "local:"

    fun isLocal(videoId: String) = videoId.startsWith(PREFIX)
}
