package com.opentune.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlyingCoverTest {
    private val first = Triple<String?, Offset, Rect>("one", Offset.Zero, Rect(0f, 0f, 50f, 50f))

    @Test fun changingSongsDuringFlightRemovesTheInterruptedCover() = runBlocking {
        val state = CoverFlightState()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.run(first) { awaitCancellation() }
        }
        assertEquals(first, state.current)
        job.cancelAndJoin()
        // The next song can have no tap and therefore no replacement animation.
        assertNull(state.current)
    }

    @Test fun finishingAnOldAnimationCannotClearANewerCover() = runBlocking {
        val state = CoverFlightState()
        val finish = CompletableDeferred<Unit>()
        val old = launch(start = CoroutineStart.UNDISPATCHED) { state.run(first) { finish.await() } }
        val second = first.copy(first = "two")
        val next = launch(start = CoroutineStart.UNDISPATCHED) { state.run(second) { awaitCancellation() } }
        finish.complete(Unit)
        old.join()
        assertEquals(second, state.current)
        next.cancelAndJoin()
        assertNull(state.current)
    }
}
