package com.opentune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlin.math.abs

/*
 * The lyric playhead, adapted from BitChord's LyricClock
 * (github.com/kushagrasinghx/BitChord, GPL-3.0). Both apps are GPL-3.0.
 */

/**
 * The lyric playhead: a clock that runs on its own frames and is *steered* by
 * the player's readings rather than set by them. Three rules, each the answer
 * to a way lyric sync has gone wrong before:
 *
 *  1. **A reading is dated by when it was taken, not when it was seen.** A
 *     reading taken before a stall but read on a busy frame looks old; snapping
 *     to it moves the words back, a ratchet freezes them, easing back rolls them
 *     back a line. Every reading is compared with where the song was *at the
 *     moment it was taken* ([sampledAtMs]).
 *  2. **Nothing but an announced jump moves the words backwards.** A seek or
 *     skip ([discontinuity] changing) may roll them back; a reading that
 *     disagrees without one is held until the next confirms it, and a confirmed
 *     gap behind is absorbed by running slower, never by reversing.
 *  3. **Readings steer, they don't set.** The newest reading, carried forward at
 *     the learned playback rate, is where the song is; the words close the gap by
 *     running up to [MAX_SLEW] faster or [MAX_SLOW] slower. The rate is learned
 *     from the readings, so a song at 1.25x stays in step without being told.
 *
 * Plain arithmetic with no Compose in it, so it can be tested frame by frame;
 * [rememberLyricsClock] is the composable that drives it. All times are
 * milliseconds on a monotonic frame clock.
 */
internal class LyricClock(startMs: Long) {
    /** Where the lyrics are drawn, in song milliseconds. */
    var displayedMs: Double = startMs.toDouble()
        private set

    private var lastNowMs = Double.NaN

    private var lastReportMs = Long.MIN_VALUE
    private var lastSampledAtMs = Double.NaN

    /** When playback was last seen stopped; a reading from before then was taken standing still. */
    private var heldAtMs = Double.NEGATIVE_INFINITY

    private var lastDiscontinuity: Long? = null
    private var jumpPending = false

    private var anchorAtMs = Double.NaN
    private var anchorMs = 0.0

    /** The playback rate, learned from how far the song moves between readings. */
    private var rate = 1.0

    private val historyAt = DoubleArray(HISTORY)
    private val historyMs = DoubleArray(HISTORY)
    private var historySize = 0
    private var rateSettled = false

    private var suspectAtMs = Double.NaN
    private var suspectMs = 0L
    private var rejections = 0

    private var glideStartMs = Double.NaN
    private var glideOffsetMs = 0.0

    /** Forget the run so far: frames stopped arriving, and the next [frame] starts a new one. */
    fun restart() {
        lastNowMs = Double.NaN
        jumpPending = false
        glideStartMs = Double.NaN
        clearSuspect()
    }

    /** Playback is not moving: settle on [positionMs] outright, as of [nowMs]. */
    fun hold(positionMs: Long, nowMs: Double) {
        restart()
        displayedMs = positionMs.toDouble()
        heldAtMs = nowMs
    }

    /**
     * Advance to the frame at [nowMs] and return where the lyrics should be drawn.
     * [reportedMs] is the player's latest reading and [sampledAtMs] when it was
     * taken (or NaN where nobody said). [discontinuity] is any value that changes
     * when the playhead jumps on purpose; only its changing matters.
     */
    fun frame(nowMs: Double, reportedMs: Long, sampledAtMs: Double, discontinuity: Long): Long {
        val firstOfRun = lastNowMs.isNaN()
        if (discontinuity != lastDiscontinuity) {
            if (lastDiscontinuity != null && !firstOfRun) jumpPending = true
            lastDiscontinuity = discontinuity
        }
        val isNew = reportedMs != lastReportMs ||
            (!sampledAtMs.isNaN() && sampledAtMs != lastSampledAtMs)
        lastReportMs = reportedMs
        lastSampledAtMs = sampledAtMs

        if (firstOfRun) {
            lastNowMs = nowMs
            val takenAt = when {
                sampledAtMs.isNaN() || sampledAtMs <= heldAtMs -> nowMs
                else -> minOf(sampledAtMs, nowMs)
            }
            anchor(takenAt, reportedMs)
            displayedMs = targetAt(nowMs)
            return displayedMs.toLong()
        }

        val dt = (nowMs - lastNowMs).coerceAtLeast(0.0)
        lastNowMs = nowMs
        advance(nowMs, dt)

        if (isNew) {
            val takenAt = if (sampledAtMs.isNaN()) nowMs else minOf(sampledAtMs, nowMs)
            if (takenAt >= anchorAtMs || jumpPending) take(nowMs, takenAt, reportedMs)
        }
        return displayedMs.toLong()
    }

    /** Folds one new reading in — or holds it back, if it disagrees with the song so far. */
    private fun take(nowMs: Double, takenAt: Double, reportedMs: Long) {
        if (jumpPending) {
            jumpPending = false
            clearSuspect()
            anchor(takenAt, reportedMs)
            jumpTo(nowMs)
            return
        }
        val elapsed = takenAt - anchorAtMs
        if (abs(reportedMs - (anchorMs + elapsed * rate)) <= tolerance(elapsed)) {
            clearSuspect()
            anchor(takenAt, reportedMs, continues = true)
            return
        }
        val sinceSuspect = takenAt - suspectAtMs
        val agrees = !suspectAtMs.isNaN() && sinceSuspect >= 0 &&
            abs(reportedMs - (suspectMs + sinceSuspect * rate)) <= tolerance(sinceSuspect)
        rejections++
        if (agrees) {
            val firstAt = suspectAtMs
            val first = suspectMs
            clearSuspect()
            anchor(firstAt, first)
            anchor(takenAt, reportedMs, continues = true)
        } else if (rejections > MAX_REJECTIONS) {
            clearSuspect()
            anchor(takenAt, reportedMs)
        } else {
            suspectAtMs = takenAt
            suspectMs = reportedMs
        }
    }

    private fun tolerance(elapsedMs: Double): Double =
        OUTLIER_MS + abs(elapsedMs) * if (rateSettled) RATE_SLACK else UNSETTLED_RATE_SLACK

    private fun anchor(atMs: Double, positionMs: Long, continues: Boolean = false) {
        anchorAtMs = atMs
        anchorMs = positionMs.toDouble()
        if (!continues) historySize = 0
        if (historySize == HISTORY) {
            historyAt.copyInto(historyAt, 0, 1, HISTORY)
            historyMs.copyInto(historyMs, 0, 1, HISTORY)
            historySize--
        }
        historyAt[historySize] = atMs
        historyMs[historySize] = anchorMs
        historySize++
        learnRate()
    }

    private fun learnRate() {
        val newest = historySize - 1
        if (newest < 1) return
        var oldest = 0
        while (oldest < newest && historyAt[newest] - historyAt[oldest] > RATE_WINDOW_MS) oldest++
        val span = historyAt[newest] - historyAt[oldest]
        if (span >= MIN_RATE_SPAN_MS) {
            rate = ((historyMs[newest] - historyMs[oldest]) / span).coerceIn(MIN_RATE, MAX_RATE)
            rateSettled = true
            return
        }
        val pairSpan = historyAt[newest] - historyAt[newest - 1]
        if (!rateSettled && pairSpan >= MIN_PAIR_SPAN_MS) {
            val measured = ((historyMs[newest] - historyMs[newest - 1]) / pairSpan)
                .coerceIn(MIN_RATE, MAX_RATE)
            rate += (measured - rate) * PAIR_LEARNING
        }
    }

    private fun clearSuspect() {
        suspectAtMs = Double.NaN
        rejections = 0
    }

    private fun advance(nowMs: Double, dt: Double) {
        val target = targetAt(nowMs)
        val step = dt * rate
        val error = target - (displayedMs + step)
        when {
            !glideStartMs.isNaN() -> {
                val progress = (nowMs - glideStartMs) / GLIDE_MS
                if (progress >= 1.0) {
                    displayedMs = target
                    glideStartMs = Double.NaN
                } else {
                    displayedMs = target + glideOffsetMs * (1.0 - smoothstep(progress))
                }
            }
            error <= -LOST_MS || error >= LOST_MS -> displayedMs = target
            error >= GLIDE_FROM_MS -> {
                startGlide(nowMs - dt, displayedMs - (target - step))
                displayedMs = target + glideOffsetMs * (1.0 - smoothstep(dt / GLIDE_MS))
            }
            else -> {
                val closing = error * (dt / (dt + CORRECTION_MS))
                displayedMs += step + closing.coerceIn(-MAX_SLOW * step, MAX_SLEW * step)
            }
        }
    }

    private fun jumpTo(nowMs: Double) {
        val offset = displayedMs - targetAt(nowMs)
        if (abs(offset) > GLIDE_MAX_MS) {
            displayedMs = targetAt(nowMs)
            glideStartMs = Double.NaN
        } else {
            startGlide(nowMs, offset)
        }
    }

    private fun startGlide(startMs: Double, offsetMs: Double) {
        glideStartMs = startMs
        glideOffsetMs = offsetMs
    }

    private fun targetAt(atMs: Double): Double = anchorMs + (atMs - anchorAtMs) * rate
}

private fun smoothstep(fraction: Double): Double {
    val t = fraction.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

private const val OUTLIER_MS = 250.0
private const val RATE_SLACK = 0.2
private const val UNSETTLED_RATE_SLACK = 0.75
private const val MAX_REJECTIONS = 2
private const val MIN_RATE_SPAN_MS = 900.0
private const val RATE_WINDOW_MS = 4_000.0
private const val HISTORY = 16
private const val MIN_PAIR_SPAN_MS = 300.0
private const val PAIR_LEARNING = 0.5
private const val MIN_RATE = 0.25
private const val MAX_RATE = 4.0
private const val CORRECTION_MS = 600.0
private const val MAX_SLEW = 0.25
private const val MAX_SLOW = 0.5
private const val GLIDE_FROM_MS = 300.0
private const val GLIDE_MS = 260.0
private const val GLIDE_MAX_MS = 2_000.0
private const val LOST_MS = 3_000.0

/**
 * [raw] (the player's position) as a smooth, steered clock for the lyrics. The
 * position is read fresh each frame, so every reading is taken now; [playing]
 * is the intent to play (true through a buffer stall, so a stall coasts rather
 * than snapping the words back), [discontinuity] changes on a seek or skip so
 * those jumps are taken cleanly, and [trackKey] restarts the clock for a new
 * song.
 */
@Composable
internal fun rememberLyricsClock(
    raw: () -> Long,
    playing: () -> Boolean,
    discontinuity: () -> Long,
    trackKey: Any?,
): () -> Long {
    val read by rememberUpdatedState(raw)
    val isPlaying by rememberUpdatedState(playing)
    val jump by rememberUpdatedState(discontinuity)
    val shown = remember { mutableLongStateOf(read()) }
    LaunchedEffect(trackKey) {
        val clock = LyricClock(read())
        shown.longValue = read()
        var lastRaw = read()
        var movedAtNs = -1L
        while (true) {
            val nowNs = withFrameNanos { it }
            val nowMs = nowNs / 1_000_000.0
            val r = read()
            if (r != lastRaw) { lastRaw = r; movedAtNs = nowNs }
            // The position advances every frame while the song is really playing, so a
            // frozen one is a pause or a buffer stall, not playback — the frame loop
            // then idles, which also lets Compose settle in tests.
            val advancing = movedAtNs >= 0 && (nowNs - movedAtNs) / 1_000_000 < STILL_MS
            when {
                advancing -> shown.longValue = clock.frame(nowMs, r, nowMs, jump())
                // Paused: the words settle exactly on the player; a seek while paused
                // still moves them, because hold runs for every reading.
                !isPlaying() -> { clock.hold(r, nowMs); shown.longValue = r; delay(100) }
                // The position is frozen though playback means to run — a buffer stall.
                // Hold the words where they are (never back) and wait for it to move.
                else -> { clock.restart(); delay(100) }
            }
        }
    }
    return { shown.longValue }
}

/** A position unchanged for this long is a pause or a stall, not playback. */
private const val STILL_MS = 100L
