package com.opentune.playback

import com.materialkolor.hct.Hct

/**
 * What the "now playing" Live Update shows, worked out from a snapshot of
 * the player. Nothing here touches Android, so the rules run in plain JVM
 * tests; [LiveUpdate] reads the player, asks these rules and posts the answer.
 */
object LiveUpdateRules {

    enum class State {
        /** Sound is coming out. */
        PLAYING,
        /** Play was pressed and the player is buffering towards it. */
        LOADING,
        /** Paused, or held by a call or another app's sound. */
        PAUSED,
        /** Nothing loaded, stopped, failed or at the end of the queue. */
        STOPPED,
    }

    /** The player, as far as the Live Update cares. */
    data class Snapshot(
        val state: State,
        val title: String?,
        val artist: String?,
        /** Zero or less when the length isn't known (a radio station, a live stream). */
        val durationMs: Long,
        val positionMs: Long,
        val speed: Float = 1f,
        /** How long it has been paused, or 0 when it isn't. */
        val pausedForMs: Long = 0,
    )

    /** The status-bar chip. Only one of the two is ever set, as short text always wins over the time. */
    sealed interface Chip {
        /** The time left. The system counts down to [endsAtMs] by itself, so nothing is posted per second. */
        data class CountDown(val endsAtMs: Long) : Chip
        data class Text(val text: String) : Chip
    }

    data class Content(
        val title: String,
        val text: String,
        /** Out of [PROGRESS_MAX]; null when the song's length isn't known. */
        val progress: Int?,
        val chip: Chip,
        /** The progress bar's colour, from the cover; null leaves the system's accent. */
        val color: Int?,
    )

    /** The bar's resolution. Its one segment is this long, so progress reads as tenths of a percent. */
    const val PROGRESS_MAX = 1000

    /**
     * How long a paused song stays up before it's taken down: Media3's own
     * timeout, after which the media notification stops being a foreground
     * one and can be swiped away (MediaSessionService.DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS).
     */
    const val PAUSE_LINGER_MS = 10 * 60_000L

    /** Characters the chip has room for. The system shows nothing but the icon when under half the text fits. */
    const val CHIP_CHARS = 10

    /** Under this much left, a countdown would hit zero before the song changes; the title shows instead. */
    const val MIN_COUNTDOWN_MS = 1_000L

    /** The bar is redrawn in about this many steps per song, within the bounds below. */
    const val PROGRESS_STEPS = 20
    const val MIN_STEP_MS = 10_000L
    const val MAX_STEP_MS = 60_000L

    /** Countdown ends this close together are the same chip, so a re-read of the position doesn't repost. */
    const val COUNTDOWN_SLACK_MS = 1_000L

    /** The bar's tone (of 100): dark enough on a light shade, light enough on a dark one. */
    const val BAR_TONE = 60.0

    /** What to show for [s] at wall-clock [nowMs], or null to show nothing. */
    fun content(s: Snapshot, nowMs: Long, coverColor: Int? = null, pausedLabel: String = "Paused"): Content? {
        if (s.state == State.STOPPED) return null
        if (s.state == State.PAUSED && s.pausedForMs >= PAUSE_LINGER_MS) return null
        // A Live Update must have a title, and a song without one has nothing to say.
        val title = s.title?.trim().orEmpty().ifEmpty { return null }
        val known = s.durationMs > 0
        val progress = if (known) (s.positionMs.coerceIn(0, s.durationMs) * PROGRESS_MAX / s.durationMs).toInt() else null
        val leftMs = if (known) ((s.durationMs - s.positionMs).coerceAtLeast(0) / s.speed.coerceAtLeast(0.1f)).toLong() else 0L
        val chip = when {
            s.state == State.PAUSED -> Chip.Text(pausedLabel)
            s.state == State.PLAYING && known && leftMs >= MIN_COUNTDOWN_MS -> Chip.CountDown(nowMs + leftMs)
            else -> Chip.Text(shortTitle(title))
        }
        return Content(title, s.artist?.trim().orEmpty(), progress, chip, barColor(coverColor))
    }

    /**
     * When to look again without being asked, or null to wait for the
     * player's next event. Playing, the bar moves on in steps (the chip
     * counts down by itself), but only while the screen is on to see it.
     * Paused, the next look is when the pause has lasted too long.
     */
    fun nextRefreshMs(s: Snapshot, content: Content?, screenOn: Boolean): Long? = when {
        content == null -> null
        s.state == State.PAUSED -> (PAUSE_LINGER_MS - s.pausedForMs).coerceAtLeast(0)
        s.state == State.PLAYING && content.progress != null && screenOn -> progressStepMs(s.durationMs, s.speed)
        else -> null
    }

    fun progressStepMs(durationMs: Long, speed: Float = 1f): Long =
        (durationMs / speed.coerceAtLeast(0.1f) / PROGRESS_STEPS).toLong().coerceIn(MIN_STEP_MS, MAX_STEP_MS)

    /** Whether [next] would look the same as what's up already, so posting it would only cost work. */
    fun samePost(shown: Content?, next: Content): Boolean {
        if (shown == null) return false
        val a = shown.chip
        val b = next.chip
        if (a is Chip.CountDown && b is Chip.CountDown) {
            return kotlin.math.abs(a.endsAtMs - b.endsAtMs) < COUNTDOWN_SLACK_MS && shown.copy(chip = b) == next
        }
        return shown == next
    }

    /**
     * The song's name, cut to fit the chip: what's in brackets and after a
     * " - " goes first ("feat.", "Remastered"), then whole words, then letters.
     */
    fun shortTitle(title: String, max: Int = CHIP_CHARS): String {
        val full = title.trim()
        val base = full.replace(BRACKETED, "").substringBefore(" - ").trim().ifEmpty { full }
        if (base.length <= max) return base
        val space = base.take(max + 1).lastIndexOf(' ')
        if (space >= MIN_WORDS_CUT) return base.take(space).trimEnd(',', ':', ';', '-', '&', ' ')
        var end = max - 1
        if (Character.isHighSurrogate(base[end - 1])) end--
        return base.take(end).trimEnd() + "…"
    }

    /** The cover's colour at [BAR_TONE], keeping its hue and colourfulness. */
    fun barColor(seed: Int?): Int? = seed?.let { Hct.fromInt(it).withTone(BAR_TONE).toInt() }

    // ---- The Settings row ----------------------------------------------------

    /** Whether Android will let the Live Update show, worst problem first. */
    enum class Access { READY, NOTIFICATIONS_OFF, CHANNEL_OFF, LIVE_UPDATES_OFF, NOT_SHOWN }

    fun access(notificationsOn: Boolean, channelOn: Boolean, canPromote: Boolean, phoneDeclined: Boolean): Access = when {
        !notificationsOn -> Access.NOTIFICATIONS_OFF
        !channelOn -> Access.CHANNEL_OFF
        !canPromote -> Access.LIVE_UPDATES_OFF
        phoneDeclined -> Access.NOT_SHOWN
        else -> Access.READY
    }

    fun summary(access: Access): String = when (access) {
        Access.READY -> "On Android 16 and newer, the playing song shows as a Live Update"
        Access.NOTIFICATIONS_OFF -> "Notifications are off for OpenTune. Tap to turn them on."
        Access.CHANNEL_OFF -> "Android's settings hide this notification. Tap to change that."
        Access.LIVE_UPDATES_OFF -> "Live Updates are off for OpenTune in Android's settings. Tap to turn them on."
        Access.NOT_SHOWN -> "This phone didn't show it as a Live Update. A system update may add it."
    }

    private val BRACKETED = Regex("""\s*[(\[][^)\]]*[)\]]""")

    /** A word cut that leaves fewer letters than this cuts by letters instead. */
    private const val MIN_WORDS_CUT = 4
}
