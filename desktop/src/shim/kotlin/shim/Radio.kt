package com.opentune.data.radio

/** The desktop has no radio; the shared track-id check only needs to know a station's id when it sees one. */
object Radio {
    private const val PREFIX = "radio:"

    fun isRadio(id: String) = id.startsWith(PREFIX)
}
