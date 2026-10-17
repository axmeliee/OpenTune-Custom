package com.opentune.ui.browse

import com.materialkolor.hct.Hct
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem

/** The release an artist page leads with, and the line above its title ("Single • 2026"). */
internal data class LatestRelease(val item: ShelfItem, val kind: String?, val year: Int?) {
    val line: String get() = listOfNotNull(kind, year?.toString()).joinToString(" • ")
}

/**
 * The artist's newest album, single or EP, off the page's own rows.
 *
 * YouTube lists each release row newest first, so only the head of each row
 * is a candidate; between rows the later year wins. A tie goes to the row
 * listed first, Albums before Singles, since nothing says which came out
 * later in the year. Null when the page has no release rows at all: the card
 * is left out rather than filled with something else.
 */
internal fun latestRelease(sections: List<HomeShelf>): LatestRelease? {
    val candidates = sections.mapNotNull { shelf ->
        val item = shelf.items.firstOrNull { it.browseId?.startsWith("MPRE") == true } ?: return@mapNotNull null
        LatestRelease(item, releaseKind(item.subtitle) ?: releaseKind(shelf.title), releaseYear(item.subtitle))
    }
    // maxByOrNull keeps the first of equals, which is the tie rule above.
    return candidates.filter { it.year != null }.maxByOrNull { it.year!! } ?: candidates.firstOrNull()
}

/** "Single", "EP" or "Album", read off a card's subtitle or its row's title. */
internal fun releaseKind(text: String): String? {
    val words = text.lowercase().split(Regex("""[^\p{L}]+""")).toSet()
    return when {
        "ep" in words || ("eps" in words && "singles" !in words) -> "EP"
        "single" in words || "singles" in words -> "Single"
        "album" in words || "albums" in words -> "Album"
        else -> null
    }
}

/** The release year in a card's subtitle, "2013" or "Single • 2026". */
internal fun releaseYear(subtitle: String): Int? =
    YEAR.findAll(subtitle).lastOrNull()?.value?.toIntOrNull()

private val YEAR = Regex("""\b(19|20)\d{2}\b""")

/**
 * The page colour for an artist whose photo's main colour is [seed]: a deep
 * shade of it under a dark theme, a pale one under a light theme. The hue is
 * kept and the strength capped, so a loud red photo gives a dark wine page
 * rather than a red one, and a grey photo stays grey.
 */
internal fun artistPageColor(seed: Int, dark: Boolean): Int {
    val hct = Hct.fromInt(seed)
    val chroma = minOf(hct.chroma, if (dark) DARK_CHROMA else LIGHT_CHROMA)
    return Hct.from(hct.hue, chroma, if (dark) DARK_TONE else LIGHT_TONE).toInt()
}

internal const val DARK_TONE = 12.0
internal const val LIGHT_TONE = 94.0
private const val DARK_CHROMA = 26.0
private const val LIGHT_CHROMA = 16.0
