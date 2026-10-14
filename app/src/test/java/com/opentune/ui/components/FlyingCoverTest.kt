package com.opentune.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FlyingCoverTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun changingSongsDuringFlightRemovesTheInterruptedCover() {
        val song = mutableStateOf<String?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    CoverFlight(song.value, null, true, Modifier.testTag("flight"))
                }
            }
        }
        compose.runOnIdle {
            FlyingCover.target = Rect(0f, 700f, 50f, 750f)
            FlyingCover.cardBounds = null
        }
        compose.runOnIdle {
            FlyingCover.recordTap(androidx.compose.ui.geometry.Offset(100f, 100f))
            song.value = "one"
        }
        compose.mainClock.advanceTimeBy(180)
        compose.onNodeWithTag("flight", useUnmergedTree = true).assertExists()
        // A queue advance has no second tap to begin a new flight.
        compose.runOnIdle { song.value = "two" }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("flight", useUnmergedTree = true).assertDoesNotExist()
    }
}
