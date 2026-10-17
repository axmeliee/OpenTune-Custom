package com.opentune.ui.player

import com.opentune.ui.components.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun haloBarsRestWhenPaused() {
        repeat(72) { assertEquals(0.08f, haloLevel(it, 12f, playing = false), 0f) }
        val levels = (0 until 72).map { haloLevel(it, 3.3f, playing = true) }
        assertTrue(levels.all { it in 0f..1f })
        assertTrue("they vary", levels.max() - levels.min() > 0.3f)
    }

    @Test fun hapticStrengthMapsToWords() {
        assertEquals("Off", Haptics.label(0f))
        assertEquals("Light", Haptics.label(0.3f))
        assertEquals("Medium", Haptics.label(0.6f))
        assertEquals("Strong", Haptics.label(1f))
    }
}
