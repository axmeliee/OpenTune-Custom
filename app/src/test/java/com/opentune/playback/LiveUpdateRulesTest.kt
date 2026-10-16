package com.opentune.playback

import com.materialkolor.hct.Hct
import com.opentune.playback.LiveUpdateRules.Access
import com.opentune.playback.LiveUpdateRules.Chip
import com.opentune.playback.LiveUpdateRules.Snapshot
import com.opentune.playback.LiveUpdateRules.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateRulesTest {

    private val now = 1_000_000L

    private fun snap(
        state: State = State.PLAYING,
        title: String? = "Blinding Lights",
        artist: String? = "The Weeknd",
        durationMs: Long = 200_000,
        positionMs: Long = 50_000,
        speed: Float = 1f,
        pausedForMs: Long = 0,
    ) = Snapshot(state, title, artist, durationMs, positionMs, speed, pausedForMs)

    @Test
    fun playingShowsTitleArtistProgressAndCountdown() {
        val c = LiveUpdateRules.content(snap(), now)!!
        assertEquals("Blinding Lights", c.title)
        assertEquals("The Weeknd", c.text)
        assertEquals(250, c.progress)
        assertEquals(Chip.CountDown(now + 150_000), c.chip)
    }

    @Test
    fun countdownFollowsPlaybackSpeed() {
        val c = LiveUpdateRules.content(snap(speed = 2f), now)!!
        assertEquals(Chip.CountDown(now + 75_000), c.chip)
    }

    @Test
    fun pausedSaysPausedAndKeepsItsPlace() {
        val c = LiveUpdateRules.content(snap(state = State.PAUSED, pausedForMs = 60_000), now)!!
        assertEquals(Chip.Text("Paused"), c.chip)
        assertEquals(250, c.progress)
    }

    @Test
    fun longPauseStopAndMissingTitleShowNothing() {
        assertNull(LiveUpdateRules.content(snap(state = State.PAUSED, pausedForMs = LiveUpdateRules.PAUSE_LINGER_MS), now))
        assertNull(LiveUpdateRules.content(snap(state = State.STOPPED), now))
        assertNull(LiveUpdateRules.content(snap(title = "  "), now))
        assertNull(LiveUpdateRules.content(snap(title = null), now))
    }

    @Test
    fun unknownLengthHasNoBarAndTheTitleInTheChip() {
        val c = LiveUpdateRules.content(snap(title = "Radio Paradise", durationMs = 0), now)!!
        assertNull(c.progress)
        assertEquals(Chip.Text("Radio"), c.chip)
    }

    @Test
    fun loadingAndTheLastSecondShowTheTitleNotAStuckCountdown() {
        assertEquals(Chip.Text("Blinding"), LiveUpdateRules.content(snap(state = State.LOADING), now)!!.chip)
        assertEquals(Chip.Text("Blinding"), LiveUpdateRules.content(snap(positionMs = 199_500), now)!!.chip)
    }

    @Test
    fun progressStaysInRange() {
        assertEquals(1000, LiveUpdateRules.content(snap(positionMs = 250_000), now)!!.progress)
        assertEquals(0, LiveUpdateRules.content(snap(positionMs = -5), now)!!.progress)
    }

    @Test
    fun shortTitleDropsExtrasThenWordsThenLetters() {
        assertEquals("Song", LiveUpdateRules.shortTitle("Song (feat. Someone)"))
        assertEquals("Yesterday", LiveUpdateRules.shortTitle("Yesterday - Remastered 2009"))
        assertEquals("Hello", LiveUpdateRules.shortTitle("  Hello [Live]  "))
        assertEquals("Bohemian", LiveUpdateRules.shortTitle("Bohemian Rhapsody"))
        assertEquals("A Day in", LiveUpdateRules.shortTitle("A Day in the Life"))
        assertEquals("Supercali…", LiveUpdateRules.shortTitle("Supercalifragilistic"))
        assertEquals("(Intro)", LiveUpdateRules.shortTitle("(Intro)"))
        assertTrue(LiveUpdateRules.shortTitle("Supercalifragilistic").length <= LiveUpdateRules.CHIP_CHARS)
    }

    @Test
    fun shortTitleDoesNotSplitAnEmoji() {
        val cut = LiveUpdateRules.shortTitle("abcdefgh🎵xyz")
        assertFalse(Character.isHighSurrogate(cut[cut.length - 2]))
    }

    @Test
    fun barMovesInStepsOnlyWhileSeen() {
        val s = snap()
        val c = LiveUpdateRules.content(s, now)
        assertEquals(10_000L, LiveUpdateRules.nextRefreshMs(s, c, screenOn = true))
        assertNull(LiveUpdateRules.nextRefreshMs(s, c, screenOn = false))
        assertNull(LiveUpdateRules.nextRefreshMs(s, null, screenOn = true))
    }

    @Test
    fun pausedLooksAgainWhenThePauseRunsOut() {
        val s = snap(state = State.PAUSED, pausedForMs = 60_000)
        val c = LiveUpdateRules.content(s, now)
        assertEquals(LiveUpdateRules.PAUSE_LINGER_MS - 60_000, LiveUpdateRules.nextRefreshMs(s, c, screenOn = false))
    }

    @Test
    fun noRefreshLoopWithoutABarOrWhileLoading() {
        val radio = snap(durationMs = 0)
        assertNull(LiveUpdateRules.nextRefreshMs(radio, LiveUpdateRules.content(radio, now), screenOn = true))
        val loading = snap(state = State.LOADING)
        assertNull(LiveUpdateRules.nextRefreshMs(loading, LiveUpdateRules.content(loading, now), screenOn = true))
    }

    @Test
    fun progressStepIsBounded() {
        assertEquals(LiveUpdateRules.MIN_STEP_MS, LiveUpdateRules.progressStepMs(30_000))
        assertEquals(15_000L, LiveUpdateRules.progressStepMs(300_000))
        assertEquals(LiveUpdateRules.MAX_STEP_MS, LiveUpdateRules.progressStepMs(3 * 3_600_000L))
        assertEquals(30_000L, LiveUpdateRules.progressStepMs(1_200_000, speed = 2f))
    }

    @Test
    fun samePostIgnoresCountdownJitter() {
        val a = LiveUpdateRules.content(snap(), now)!!
        assertTrue(LiveUpdateRules.samePost(a, LiveUpdateRules.content(snap(), now + 400)!!))
        assertFalse(LiveUpdateRules.samePost(a, LiveUpdateRules.content(snap(), now + 5_000)!!))
        assertFalse(LiveUpdateRules.samePost(a, LiveUpdateRules.content(snap(positionMs = 60_000), now)!!))
        assertFalse(LiveUpdateRules.samePost(null, a))
        assertFalse(LiveUpdateRules.samePost(a, LiveUpdateRules.content(snap(state = State.PAUSED), now)!!))
    }

    @Test
    fun barTakesTheCoverHueAtAReadableTone() {
        assertNull(LiveUpdateRules.barColor(null))
        val seed = 0xFF1E88E5.toInt()
        val bar = LiveUpdateRules.barColor(seed)
        assertNotNull(bar)
        val hct = Hct.fromInt(bar!!)
        assertEquals(LiveUpdateRules.BAR_TONE, hct.tone, 1.0)
        assertEquals(Hct.fromInt(seed).hue, hct.hue, 5.0)
        assertEquals(bar, LiveUpdateRules.content(snap(), now, coverColor = seed)!!.color)
    }

    @Test
    fun accessReportsTheFirstThingToFix() {
        assertEquals(Access.READY, LiveUpdateRules.access(notificationsOn = true, channelOn = true, canPromote = true, phoneDeclined = false))
        assertEquals(Access.NOTIFICATIONS_OFF, LiveUpdateRules.access(notificationsOn = false, channelOn = false, canPromote = false, phoneDeclined = true))
        assertEquals(Access.CHANNEL_OFF, LiveUpdateRules.access(notificationsOn = true, channelOn = false, canPromote = false, phoneDeclined = false))
        assertEquals(Access.LIVE_UPDATES_OFF, LiveUpdateRules.access(notificationsOn = true, channelOn = true, canPromote = false, phoneDeclined = true))
        assertEquals(Access.NOT_SHOWN, LiveUpdateRules.access(notificationsOn = true, channelOn = true, canPromote = true, phoneDeclined = true))
    }

    @Test
    fun summariesArePlainAndOnlyReadyHasNoFix() {
        assertEquals("On Android 16 and newer, the playing song shows as a Live Update", LiveUpdateRules.summary(Access.READY))
        Access.entries.filter { it != Access.READY && it != Access.NOT_SHOWN }.forEach {
            assertTrue(it.name, LiveUpdateRules.summary(it).contains("Tap to"))
        }
    }
}
