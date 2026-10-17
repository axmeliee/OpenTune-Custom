package com.opentune.ui.wrapped

import com.opentune.data.history.PlayRecord
import com.opentune.data.history.Wrapped
import com.opentune.data.model.Song
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedMusicTest {
    private fun song(id: String) = Song(id, "Song $id", "Artist", null, "3:00")

    @Test fun playsTheTopSongsInOrderThenTheOneOnRepeatEachOnce() {
        val list = WrappedTune.playlist(listOf(song("a"), song("b"), song("c")), song("d")) { true }
        assertEquals(listOf("a", "b", "c", "d"), list.map { it.videoId })
        val again = WrappedTune.playlist(listOf(song("a"), song("b")), song("a")) { true }
        assertEquals(listOf("a", "b"), again.map { it.videoId })
    }

    @Test fun leavesOutWhatIsNotASong() {
        val list = WrappedTune.playlist(listOf(song("radio:1"), song("a"), song("ep")), null) { it != "radio:1" && it != "ep" }
        assertEquals(listOf("a"), list.map { it.videoId })
    }

    @Test fun startsALittleBeforeTheFirstChorus() {
        assertEquals(58_000L, WrappedTune.startMs(200_000, listOf(120_000, 60_000)))
        // A chorus right at the top starts the song from the beginning, never before it.
        assertEquals(0L, WrappedTune.startMs(200_000, listOf(1_000)))
    }

    @Test fun skipsAChorusTooNearTheEnd() {
        assertEquals(60_000L, WrappedTune.startMs(200_000, listOf(190_000)))
        assertEquals(98_000L, WrappedTune.startMs(200_000, listOf(100_000, 190_000)))
    }

    @Test fun withoutAChorusStartsAboutAThirdIn() {
        assertEquals(60_000L, WrappedTune.startMs(200_000, emptyList()))
    }

    @Test fun withNeitherLengthNorChorusWaitsToKnow() {
        assertNull(WrappedTune.startMs(0, emptyList()))
        // A chorus alone is enough: its length can't be checked, but it's where the song is.
        assertEquals(43_000L, WrappedTune.startMs(0, listOf(45_000)))
    }

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

    @Test fun movesOnToTheNextSongAndRoundAgain() {
        assertEquals(1, WrappedTune.next(0, 3))
        assertEquals(0, WrappedTune.next(2, 3))
        assertEquals(0, WrappedTune.next(0, 1))
        assertEquals(0, WrappedTune.next(0, 0))
    }

    @Test fun theTopSongAndOnRepeatPagesCueTheirSongs() {
        val utc = TimeZone.getTimeZone("UTC")
        fun at(day: Int, hour: Int) = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.MARCH, day, hour, 0, 0) }.timeInMillis
        fun play(id: String, day: Int, hour: Int) = PlayRecord(id, "Song $id", "Artist", null, "3:00", null, at(day, hour), 180_000)
        val records = (
            List(3) { play("a", 1 + it, 20) } +
                List(2) { play("b", 9, 10 + it) }
            ).sortedByDescending { it.playedAt }
        val s = Wrapped.summarize(records, at(1, 0), at(31, 0), utc)
        assertEquals("a", cueFor(Slide.TOP_SONG, s))
        assertEquals("b", cueFor(Slide.REPEAT, s))
        assertNull(cueFor(Slide.MINUTES, s))
        assertNull(cueFor(null, s))
    }
}
