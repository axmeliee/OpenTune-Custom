package com.opentune.ui.share

import android.graphics.Bitmap
import com.opentune.ui.player.coverArt
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class LyricCardTest {
    @Test fun drawsAPostAndAStory() {
        val lines = listOf("I've been running through the night", "chasing every light I see", "and I won't stop until the morning")
        for (story in listOf(false, true)) {
            val card = LyricCard.draw(coverArt(), lines, "Blinding Lights", "The Weeknd", story)
            assertEquals(LyricCard.WIDTH, card.width)
            // A post is 4:5; a story is 9:16.
            assertEquals(if (story) LyricCard.STORY_HEIGHT else LyricCard.HEIGHT, card.height)
            File("build/screenshots/share/lyrics-${if (story) "story" else "post"}.png").apply { parentFile!!.mkdirs() }.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Six long lines still fit, smaller.
        val long = List(LyricCard.MAX_LINES) { "A much longer line of the song that goes on and on for a while" }
        LyricCard.draw(null, long, "Song", "Artist").compress(Bitmap.CompressFormat.PNG, 100, File("build/screenshots/share/lyrics-long.png").outputStream())
    }
}
