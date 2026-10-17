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

    private val utc = java.time.ZoneOffset.UTC
    private fun at(date: String) = java.time.LocalDate.parse(date).atTime(12, 0).toInstant(utc).toEpochMilli()
    private fun ask(days: Int = 10, done: Boolean = false, askedOn: String? = null, now: String) = Support.shouldAsk(
        done = done,
        days = days,
        askedHalf = askedOn?.let { Support.halfMonth(at(it), utc) } ?: -1,
        askedAt = askedOn?.let { at(it) } ?: 0,
        now = at(now),
        zone = utc,
    )

    @Test fun asksOnlyAfterSomeDaysOfUse() {
        assertFalse("too soon", ask(days = 2, now = "2026-10-03"))
        assertTrue(ask(days = 5, now = "2026-10-03"))
    }

    @Test fun asksOnceInEachHalfOfTheMonth() {
        assertFalse("already asked this half", ask(askedOn = "2026-10-02", now = "2026-10-14"))
        assertTrue("second half", ask(askedOn = "2026-10-02", now = "2026-10-16"))
        assertFalse("asked in the second half", ask(askedOn = "2026-10-20", now = "2026-10-31"))
        assertTrue("next month", ask(askedOn = "2026-10-20", now = "2026-11-03"))
        assertTrue("a new year", ask(askedOn = "2026-12-20", now = "2027-01-02"))
    }

    @Test fun leavesAWeekBetweenAsksAcrossTheMiddleOfTheMonth() {
        assertFalse(ask(askedOn = "2026-10-15", now = "2026-10-17"))
        assertTrue(ask(askedOn = "2026-10-15", now = "2026-10-22"))
    }

    @Test fun neverAgainOnceStarredOrDeclined() {
        assertFalse(ask(done = true, days = 90, askedOn = "2026-01-01", now = "2026-10-20"))
    }

    @Test fun halvesOfTheMonthSplitAfterThe15th() {
        assertEquals(Support.halfMonth(at("2026-10-01"), utc), Support.halfMonth(at("2026-10-15"), utc))
        assertEquals(Support.halfMonth(at("2026-10-15"), utc) + 1, Support.halfMonth(at("2026-10-16"), utc))
        assertEquals(Support.halfMonth(at("2026-10-31"), utc) + 1, Support.halfMonth(at("2026-11-01"), utc))
    }
}
