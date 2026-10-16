package com.opentune.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Swiping song rows in an ordinary vertical list; the arithmetic is in [SwipeQueueTest]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SwipeToQueueRowTest {
    @get:Rule val compose = createComposeRule()

    private val songs = List(30) { Song("id$it", "Song $it", "Artist", null, "3:00") }
    private val next = mutableListOf<String>()
    private val queued = mutableListOf<String>()
    private lateinit var list: LazyListState

    @Before fun setUp() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        AppSettings.updatePlayback { it.copy(swipeToQueue = true) }
    }

    private fun show(direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                list = rememberLazyListState()
                LazyColumn(Modifier.width(360.dp).height(480.dp).testTag("list"), list) {
                    items(songs.size) { i ->
                        SongListItem(
                            songs[i],
                            onClick = {},
                            modifier = Modifier.testTag("row$i"),
                            onPlayNext = { next += songs[i].videoId },
                            onAddToQueue = { queued += songs[i].videoId },
                        )
                    }
                }
            }
        }
    }

    @Test fun swipingRightPlaysNext() {
        show()
        compose.onNodeWithTag("row0").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f) }
        compose.waitForIdle()
        assertEquals(listOf("id0"), next)
        assertEquals(emptyList<String>(), queued)
    }

    @Test fun swipingLeftAddsToTheQueue() {
        show()
        compose.onNodeWithTag("row1").performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f) }
        compose.waitForIdle()
        assertEquals(listOf("id1"), queued)
        assertEquals(emptyList<String>(), next)
    }

    @Test fun aShortSlowDragDoesNothing() {
        show()
        compose.onNodeWithTag("row0").performTouchInput {
            swipe(Offset(left + 10f, centerY), Offset(left + 10f + width * 0.15f, centerY), durationMillis = 1_500)
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), next + queued)
    }

    @Test fun rightToLeftMirrorsTheSwipe() {
        show(LayoutDirection.Rtl)
        compose.onNodeWithTag("row0").performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f) }
        compose.waitForIdle()
        assertEquals(listOf("id0"), next)
        assertEquals(emptyList<String>(), queued)
    }

    @Test fun theListStillScrolls() {
        show()
        // Up the list, with a little sideways wobble on the way.
        compose.onNodeWithTag("list").performTouchInput {
            swipe(Offset(centerX, bottom - 20f), Offset(centerX + 40f, top + 20f), durationMillis = 300)
        }
        compose.waitForIdle()
        assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
        assertEquals(emptyList<String>(), next + queued)
    }

    @Test fun theSwitchTurnsItOff() {
        AppSettings.updatePlayback { it.copy(swipeToQueue = false) }
        show()
        compose.onNodeWithTag("row0").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f) }
        compose.onNodeWithTag("row1").performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f) }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), next + queued)
    }

    @Test fun aSwipeSaysWhatItDid() {
        show()
        val notes = mutableListOf<String>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            QueueNotices.messages.collect { notes += it }
        }
        compose.onNodeWithTag("row2").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f) }
        compose.waitForIdle()
        job.cancel()
        assertEquals(listOf("\"Song 2\" plays next"), notes)
    }

    @Test fun talkBackOffersBothActionsOnTheRowItFocuses() {
        show()
        val row = compose.onNode(hasText("Song 3"))
        val actions = row.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Play next", "Add to queue"), actions.map { it.label })
        compose.runOnIdle { actions.first { it.label == "Add to queue" }.action() }
        assertEquals(listOf("id3"), queued)
    }
}
