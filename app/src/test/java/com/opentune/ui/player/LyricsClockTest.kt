package com.opentune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsClockTest {
    private fun run(start: Double, frames: Int, raw: (Int) -> Long, dt: Double = 16.0, playing: Boolean = true): List<Double> {
        var shown = start
        return (1..frames).map { i -> LyricsClock.step(shown, raw(i), dt, playing).also { shown = it } }
    }

    @Test fun followsAPlayerThatRunsEvenly() {
        val shown = run(1_000.0, 60, raw = { 1_000L + it * 16 })
        assertEquals(1_000.0 + 60 * 16, shown.last(), 2.0)
    }

    @Test fun neverStepsBackWhenThePlayerCorrectsItselfBackwards() {
        // The player jumps back 150 ms at frame 10, then runs on from there.
        val shown = run(5_000.0, 40, raw = { i -> if (i < 10) 5_000L + i * 16 else 5_000L + i * 16 - 150 })
        shown.zipWithNext().forEach { (a, b) -> assertTrue("went back from $a to $b", b >= a) }
        // And it ends close to the player again.
        assertEquals(5_000.0 + 40 * 16 - 150, shown.last(), 20.0)
    }

    @Test fun catchesUpWithAPlayerThatJumpsALittleAhead() {
        val shown = run(2_000.0, 60, raw = { i -> 2_000L + i * 16 + if (i >= 5) 200 else 0 })
        assertEquals(2_000.0 + 60 * 16 + 200, shown.last(), 5.0)
    }

    @Test fun aSeekGoesStraightThere() {
        assertEquals(60_000.0, LyricsClock.step(10_000.0, 60_000, 16.0, playing = true), 0.0)
        assertEquals(1_000.0, LyricsClock.step(10_000.0, 1_000, 16.0, playing = true), 0.0)
    }

    @Test fun pausedSettlesOnThePlayersPosition() {
        val shown = run(3_050.0, 30, raw = { 3_000L }, playing = false)
        assertEquals(3_000.0, shown.last(), 1.0)
    }

    @Test fun aLongFrameDoesNotLeapAhead() {
        // A stalled frame of a second shouldn't throw the clock past the player.
        val next = LyricsClock.step(4_000.0, 4_016, 1_000.0, playing = true)
        assertTrue(next <= 4_000.0 + 100 + 2)
    }

    @Test fun followsAFasterPlaybackSpeedWithoutFallingBehind() {
        // At 1.5x the player moves 24 ms a frame; the clock keeps pace instead of trailing it.
        var shown = 1_000.0
        repeat(60) { i -> shown = LyricsClock.step(shown, 1_000L + (i + 1) * 24, 16.0, playing = true, rate = 1.5) }
        assertEquals(1_000.0 + 60 * 24, shown, 2.0)
    }
}
