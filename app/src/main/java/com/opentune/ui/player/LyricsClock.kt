package com.opentune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * The time lyrics are drawn at. The player's position is extrapolated between
 * updates from the session and corrected when one arrives, so it steps,
 * sometimes back by a tenth of a second or two. A word that has just finished
 * would then count as unsung again and play its motion a second time. This
 * clock runs on the frame time instead, leans toward the player's position a
 * little each frame, and while playing never runs backwards; only a real seek
 * (a jump of more than [SEEK_MS]) moves it straight to the new place.
 */
internal object LyricsClock {
    /** A gap bigger than this is a seek, a skip or a stall: go straight there. */
    const val SEEK_MS = 400.0

    /** How much of the remaining gap to the player each frame closes. */
    private const val PULL = 0.1

    /**
     * The next time to show, [dtMs] after [shown], given the player's position
     * [raw] and whether it's moving ([playing]), at [rate] times real time
     * (the playback speed), so a faster song isn't drawn a little behind.
     */
    fun step(shown: Double, raw: Long, dtMs: Double, playing: Boolean, rate: Double = 1.0): Double {
        val gap = raw - shown
        if (abs(gap) > SEEK_MS) return raw.toDouble()
        if (!playing) return shown + gap * 0.35
        // Run with the frames, then close part of what's left between that and the
        // player. Never step back while playing: a player that's a little behind
        // slows the clock down rather than reversing it.
        val predicted = shown + dtMs.coerceIn(0.0, 100.0) * rate.coerceIn(0.25, 4.0)
        val next = predicted + (raw - predicted) * PULL
        return maxOf(next, shown)
    }
}

/**
 * [raw] (the player's position, read each frame) as a smooth, forward-only
 * clock for lyrics. Playing means the position moved in the last
 * [STILL_MS]; a paused player's position doesn't change.
 */
@Composable
internal fun rememberLyricsClock(raw: () -> Long): () -> Long {
    val read by rememberUpdatedState(raw)
    val shown = remember { mutableLongStateOf(raw()) }
    LaunchedEffect(Unit) {
        var value = read().toDouble()
        var last = withFrameNanos { it }
        var seen = read()
        var movedAt = last
        // The playback speed, as the position's own progress over a couple of seconds shows it.
        var rate = 1.0
        var sinceRaw = seen
        var sinceAt = last
        while (true) {
            val now = withFrameNanos { it }
            val r = read()
            if (r != seen) { seen = r; movedAt = now }
            val playing = (now - movedAt) / 1_000_000 < STILL_MS
            val span = (now - sinceAt) / 1_000_000.0
            if (!playing || abs(r - value) > LyricsClock.SEEK_MS) {
                sinceRaw = r; sinceAt = now
            } else if (span >= RATE_WINDOW_MS) {
                val measured = (r - sinceRaw) / span
                if (measured in 0.2..4.5) rate = (rate + measured) / 2
                sinceRaw = r; sinceAt = now
            }
            value = LyricsClock.step(value, r, (now - last) / 1_000_000.0, playing, rate)
            last = now
            shown.longValue = value.toLong()
            // Paused and caught up: no need to wake every frame.
            if (!playing && abs(value - r) < 1.0) kotlinx.coroutines.delay(100)
        }
    }
    return { shown.longValue }
}

/**
 * Short, so a pause is seen before the clock has run on far past the
 * player (it then settles back, unlighting the end of a word). The position
 * changes every frame while playing, so a few frames still is a pause.
 */
private const val STILL_MS = 100L
private const val RATE_WINDOW_MS = 2_000.0
