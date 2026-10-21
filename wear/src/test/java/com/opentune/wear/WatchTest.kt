package com.opentune.wear

import android.graphics.Bitmap
import android.media.AudioManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The watch app's home on a round watch, and the small rules behind it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class WatchTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun homeOffersSearchLikedAndRecent() {
        val player = WatchPlayer(ApplicationProvider.getApplicationContext(), connectNow = false)
        compose.setContent { WatchApp(player) }
        compose.waitForIdle()
        compose.onNodeWithText("Search").assertExists()
        compose.onNodeWithText("Liked songs").assertExists()
        compose.onNodeWithText("Recent").assertExists()
        save("wear-home")
    }

    @Test fun theCrownTurnsVolumeOneStepAtATime() {
        assertEquals(AudioManager.ADJUST_RAISE, WatchVolume.step(40f))
        assertEquals(AudioManager.ADJUST_LOWER, WatchVolume.step(-40f))
        assertNull(WatchVolume.step(3f))
    }

    @Test fun searchKeepsSongsInOrderOnce() {
        fun song(id: String) = Song(id, "Title $id", "Artist", null)
        val rows = listOf(SearchResult.TopTrack(song("a")), SearchResult.Track(song("b")), SearchResult.Track(song("a")))
        assertEquals(listOf("a", "b"), WatchPlayer.songsIn(rows).map { it.videoId })
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
