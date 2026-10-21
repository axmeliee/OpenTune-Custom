package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
/** Tappable artist names look exactly like the plain line they replaced: no underline, no colour. */
class ArtistLinksLookTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun linkedNamesDrawLikePlainText() {
        val artists = "Ajay-Atul, Sonu Nigam & Neeti Mohan"
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Column(Modifier.background(Color(0xFF3A2E2A)).padding(16.dp)) {
                    Box(Modifier.width(360.dp).height(30.dp)) { ArtistLinks(artists, onArtist = { }, explicit = false, style = MaterialTheme.typography.titleMedium) }
                    Box(Modifier.width(360.dp).height(30.dp)) { ArtistLinks(artists, onArtist = null, explicit = false, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        compose.waitForIdle()
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        // Row one (links) against row two (plain): every pixel the same.
        val d = v.resources.displayMetrics.density
        val top = (16 * d).toInt(); val rowH = (30 * d).toInt(); val w = (360 * d).toInt(); val left = (16 * d).toInt()
        var diff = 0
        for (y in 0 until rowH) for (x in 0 until w) if (bmp.getPixel(left + x, top + y) != bmp.getPixel(left + x, top + rowH + y)) diff++
        assertTrue("pixels differ: $diff", diff == 0)
    }
}
