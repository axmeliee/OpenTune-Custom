package com.opentune.playback

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import com.opentune.playback.LiveUpdateRules.Snapshot
import com.opentune.playback.LiveUpdateRules.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The notification [LiveUpdate] posts meets Android 16's rules for a Live Update. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class LiveUpdateNotificationTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val now = 1_000_000L

    private fun build(state: State, durationMs: Long = 200_000): Notification {
        val content = LiveUpdateRules.content(
            Snapshot(state, "Blinding Lights", "The Weeknd", durationMs, positionMs = 50_000),
            now,
            coverColor = 0xFF1E88E5.toInt(),
        )!!
        return LiveUpdate.notification(context, content, cover = null, open = null, dismissed = null)
    }

    /**
     * Android's own Notification.hasPromotableCharacteristics() as released
     * (Android 16 QPR1 and later). Robolectric's Android 16 is a pre-release
     * build whose version still wanted the notification colorized, so the
     * rules are checked one by one here instead.
     */
    @Suppress("DEPRECATION")
    private fun assertPromotable(n: Notification) {
        assertTrue("requests promotion", n.extras.getBoolean("android.requestPromotedOngoing"))
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse("has a title", n.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrEmpty())
        val template = n.extras.getString(Notification.EXTRA_TEMPLATE)
        assertTrue("promotable style: $template", template == null || template == Notification.ProgressStyle::class.java.name)
        assertTrue("not a group summary", n.flags and Notification.FLAG_GROUP_SUMMARY == 0)
        assertTrue("no custom views", n.contentView == null && n.bigContentView == null && n.headsUpContentView == null)
        assertFalse("not colorized", n.extras.getBoolean(Notification.EXTRA_COLORIZED))
    }

    @Test
    fun playingIsPromotableWithACountdownAndABar() {
        val n = build(State.PLAYING)
        assertPromotable(n)
        assertEquals(Notification.ProgressStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("Blinding Lights", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("The Weeknd", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(now + 150_000, n.`when`)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertNull(n.shortCriticalText)
        assertEquals(250, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(LiveUpdateRules.PROGRESS_MAX, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(LiveUpdateRules.barColor(0xFF1E88E5.toInt()), n.color)
    }

    @Test
    fun pausedIsPromotableAndSaysPaused() {
        val n = build(State.PAUSED)
        assertPromotable(n)
        assertEquals("Paused", n.shortCriticalText)
        assertFalse(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    @Test
    fun radioWithoutABarIsStillPromotable() {
        val n = build(State.PLAYING, durationMs = 0)
        assertPromotable(n)
        assertNull(n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("Blinding", n.shortCriticalText)
    }
}
