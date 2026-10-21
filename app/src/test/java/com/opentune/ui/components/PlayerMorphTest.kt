package com.opentune.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pull that lands after the player starts opening must not leave a second cover hanging over the screen. */
class PlayerMorphTest {
    private val mini = Rect(16f, 1660f, 114f, 1758f)
    private val player = Rect(53f, 216f, 868f, 1031f)

    @After fun reset() {
        if (PlayerMorph.expanding) PlayerMorph.endOpen()
        if (PlayerMorph.collapsing) PlayerMorph.endClose()
        PlayerMorph.endPull()
        PlayerMorph.dragClose(0f)
        PlayerMorph.miniCover = null
        PlayerMorph.playerCover = null
        PlayerMorph.lastPlayerCover = null
    }

    @Test fun aLatePullDuringTheOpenIsIgnored() {
        PlayerMorph.miniCover = mini
        PlayerMorph.lastPlayerCover = player
        PlayerMorph.pullOpen(600f, round = false, screenHeight = 2000f)
        assertTrue(PlayerMorph.pulling > 0f)

        PlayerMorph.startOpen(round = false)
        assertTrue(PlayerMorph.expanding)
        assertEquals(0f, PlayerMorph.pulling)

        // The finger's last move, handled after the open began.
        PlayerMorph.pullOpen(1100f, round = false, screenHeight = 2000f)
        assertEquals(0f, PlayerMorph.pulling)

        PlayerMorph.endOpen()
        assertEquals(0f, PlayerMorph.pulling)
    }

    @Test fun openingWithNoMiniCoverStillLetsGoOfThePull() {
        PlayerMorph.miniCover = mini
        PlayerMorph.pullOpen(400f, round = false, screenHeight = 2000f)
        PlayerMorph.miniCover = null

        PlayerMorph.startOpen(round = false)
        assertFalse(PlayerMorph.expanding)
        assertEquals(0f, PlayerMorph.pulling)
    }

    @Test fun closingWithNowhereToLandShowsThePlayersCoverAgain() {
        PlayerMorph.miniCover = mini
        PlayerMorph.playerCover = player
        PlayerMorph.dragClose(300f)
        assertTrue(PlayerMorph.coverAway)

        PlayerMorph.miniCover = null
        PlayerMorph.startClose()
        assertFalse(PlayerMorph.collapsing)
        assertFalse(PlayerMorph.coverAway)
    }
}
