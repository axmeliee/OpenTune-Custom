package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opentune.data.Pills
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h300dp-xxhdpi", application = android.app.Application::class)
class PillHostTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun host(dark: Boolean) {
        compose.setContent {
            MaterialTheme(if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF101014) else Color(0xFFF4F1EC))) {
                    PillHost(reduceMotion = false)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String): Bitmap {
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        // Saved for a look only when the folder has been made by hand.
        File("build/pill-shots").takeIf { it.isDirectory }?.let { dir ->
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bmp
    }

    @Test fun aDoneNoteGrowsFromADotAndLeaves() {
        compose.mainClock.autoAdvance = false
        host(dark = true)
        Pills.done("Added to Gym")
        compose.mainClock.advanceTimeBy(120)
        shot("dot")
        compose.mainClock.advanceTimeBy(900)
        val open = shot("done-dark")
        compose.onNodeWithText("Added to Gym").assertExists()
        // The pill's face is drawn near the top middle.
        val d = compose.activity.resources.displayMetrics.density
        val mid = open.getPixel(open.width / 2, (30 * d).toInt())
        assertTrue("no pill drawn", mid != open.getPixel(open.width / 2, open.height - 10))
        compose.mainClock.advanceTimeBy(6000)
        compose.onNodeWithText("Added to Gym").assertDoesNotExist()
    }

    @Test fun undoRunsAndDismisses() {
        host(dark = false)
        var undone = 0
        Pills.withAction("\"Song\" won't be suggested", "Undo") { undone++ }
        compose.mainClock.advanceTimeBy(900)
        shot("undo-light")
        compose.onNodeWithText("Undo").performClick()
        assertEquals(1, undone)
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test fun aNewerNoteReplacesTheOneShowing() {
        host(dark = true)
        Pills.error("Couldn't find a music video for this song")
        compose.mainClock.advanceTimeBy(300)
        Pills.done("Added to Road trip")
        compose.mainClock.advanceTimeBy(2200)
        shot("error-then-done")
        compose.onNodeWithText("Added to Road trip").assertExists()
        compose.onNodeWithText("Couldn't find a music video for this song").assertDoesNotExist()
    }
}
