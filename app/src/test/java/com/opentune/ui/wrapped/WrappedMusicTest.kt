package com.opentune.ui.wrapped

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedMusicTest {
    @Test fun fadeRisesEvenlyFromSilenceToFull() {
        assertEquals(0f, WrappedTune.level(0f), 0f)
        assertEquals(1f, WrappedTune.level(1f), 0f)
        assertEquals(0.25f, WrappedTune.level(0.5f), 1e-6f)
        assertEquals(1f, WrappedTune.level(3f), 0f)
        assertEquals(0f, WrappedTune.level(-1f), 0f)
        var previous = -1f
        for (i in 0..20) {
            val v = WrappedTune.level(i / 20f)
            assertTrue(v > previous)
            previous = v
        }
    }

    @Test fun fadeStepsTowardItsTargetAndStopsThere() {
        assertEquals(0.5f, WrappedTune.step(0f, 1f, 1_000, 2_000), 1e-6f)
        assertEquals(1f, WrappedTune.step(0.9f, 1f, 1_000, 2_000), 0f)
        assertEquals(0.5f, WrappedTune.step(1f, 0f, 300, 600), 1e-6f)
        assertEquals(0f, WrappedTune.step(0.2f, 0f, 600, 600), 0f)
        // A fade cut short part-way carries on from where it got to.
        assertEquals(0.3f, WrappedTune.step(0.4f, 0f, 100, 1_000), 1e-6f)
        assertEquals(1f, WrappedTune.step(0f, 1f, 16, 0), 0f)
    }

    /** Forty seconds of the calm music, past every chord change and back to the first. */
    private val music: FloatArray by lazy {
        val rate = 44_100
        val out = FloatArray(rate * 40 * 2)
        CalmSynth(rate).render(out, rate * 40)
        out
    }

    @Test fun theCalmMusicStaysInRangeAndIsNeverNaN() {
        assertTrue(music.all { !it.isNaN() && it in -1f..1f })
    }

    @Test fun theCalmMusicIsSoftButHeard() {
        val rms = sqrt(music.sumOf { (it * it).toDouble() } / music.size)
        assertTrue("too quiet: $rms", rms > 0.02)
        assertTrue("too loud: $rms", rms < 0.3)
        assertTrue("peaks: ${music.maxOf { abs(it) }}", music.maxOf { abs(it) } < 0.9f)
    }

    @Test fun theCalmMusicHasNoClicks() {
        // Neighbouring samples of each channel never jump: a click is a sudden step.
        var worst = 0f
        for (i in 2 until music.size) worst = maxOf(worst, abs(music[i] - music[i - 2]))
        assertTrue("jump of $worst", worst < 0.08f)
    }

    @Test fun theCalmMusicMovesOnFromChordToChord() {
        // Each chord's stretch sounds different from the one before it.
        val rate = 44_100
        fun level(fromSec: Double) = (0 until rate).sumOf { i -> abs(music[2 * ((fromSec * rate).toInt() + i)]).toDouble() }
        val a = level(2.0)
        val b = level(11.0)
        assertTrue(abs(a - b) / maxOf(a, b) > 0.01)
    }

    @Test fun theSameStartPlaysTheSameMusic() {
        val one = FloatArray(4410 * 2).also { CalmSynth(44_100).render(it, 4410) }
        val two = FloatArray(4410 * 2).also { CalmSynth(44_100).render(it, 4410) }
        assertTrue(one.contentEquals(two))
    }
}
