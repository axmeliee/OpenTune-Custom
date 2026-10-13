package com.opentune.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTest {
    @Test fun buildsAUpiLinkWithTheAmountFilledIn() {
        assertEquals(
            "upi://pay?pa=whoosthis@axl&pn=OpenTune&am=99.00&cu=INR&tn=Support%20OpenTune",
            Support.upiLink(99.0),
        )
        assertEquals("upi://pay?pa=whoosthis@axl&pn=OpenTune&am=12.50&cu=INR&tn=Support%20OpenTune", Support.upiLink(12.5))
    }

    @Test fun asksForAStarOnlyAfterSomeDaysAndNotTooOften() {
        assertFalse("too soon", Support.shouldAsk(done = false, days = 2, asked = 0, nextAt = 0, now = 1_000))
        assertTrue(Support.shouldAsk(done = false, days = 5, asked = 0, nextAt = 0, now = 1_000))
        assertFalse("after Later, waits", Support.shouldAsk(done = false, days = 9, asked = 1, nextAt = 5_000, now = 1_000))
        assertFalse("starred or never again", Support.shouldAsk(done = true, days = 30, asked = 1, nextAt = 0, now = 1_000))
        assertFalse("three asks at most", Support.shouldAsk(done = false, days = 90, asked = 3, nextAt = 0, now = 1_000))
    }
}
