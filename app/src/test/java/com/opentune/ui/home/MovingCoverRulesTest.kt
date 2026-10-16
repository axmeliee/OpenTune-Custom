package com.opentune.ui.home

import com.opentune.data.model.BrowseType
import com.opentune.ui.home.MovingCoverRules.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of Home's cards may move their cover. */
class MovingCoverRulesTest {
    @Test fun onlyWithTheSwitchOnAndNothingSayingKeepStill() {
        assertTrue(MovingCoverRules.allowed(enabled = true, reduceAnimation = false, powerSave = false))
        assertFalse(MovingCoverRules.allowed(enabled = false, reduceAnimation = false, powerSave = false))
        assertFalse(MovingCoverRules.allowed(enabled = true, reduceAnimation = true, powerSave = false))
        assertFalse(MovingCoverRules.allowed(enabled = true, reduceAnimation = false, powerSave = true))
        assertFalse(MovingCoverRules.allowed(enabled = true, reduceAnimation = false, powerSave = false, covered = true))
    }

    @Test fun cardsLessThanMostlyOnScreenStayStill() {
        val spots = listOf(
            Spot("half", "a", 0.5f, 0f, 0f),
            Spot("most", "b", 0.6f, 300f, 0f),
        )
        assertEquals(setOf("most"), MovingCoverRules.pick(spots, allowed = true, scrolling = false))
    }

    @Test fun noMoreThanTwoAndOnePerShelf() {
        val spots = listOf(
            Spot("a1", "a", 1f, 100f, 0f),
            Spot("a2", "a", 1f, 100f, 200f),
            Spot("b1", "b", 1f, 400f, 0f),
            Spot("c1", "c", 1f, 700f, 0f),
        )
        assertEquals(setOf("a1", "b1"), MovingCoverRules.pick(spots, allowed = true, scrolling = false))
        assertEquals(setOf("a1"), MovingCoverRules.pick(spots, allowed = true, scrolling = false, max = 1))
    }

    @Test fun theMostVisibleGoFirstThenTopToBottomAndStartToEnd() {
        val spots = listOf(
            Spot("top-clipped", "a", 0.7f, 0f, 0f),
            Spot("lower-whole", "b", 1f, 500f, 0f),
            Spot("lowest-whole", "c", 1f, 800f, 0f),
        )
        assertEquals(listOf("lower-whole", "lowest-whole"), MovingCoverRules.pick(spots, allowed = true, scrolling = false).toList())
        // Along a shelf the first card in reading order wins: the leftmost, or the rightmost in right-to-left.
        val row = listOf(Spot("left", "a", 1f, 0f, 0f), Spot("right", "a", 1f, 0f, 400f))
        assertEquals(setOf("left"), MovingCoverRules.pick(row, allowed = true, scrolling = false))
        assertEquals(setOf("right"), MovingCoverRules.pick(row, allowed = true, scrolling = false, rtl = true))
    }

    @Test fun nothingMovesWhileScrollingOrWhenNotAllowed() {
        val spots = listOf(Spot("a", "a", 1f, 0f, 0f))
        assertEquals(emptySet<Any>(), MovingCoverRules.pick(spots, allowed = true, scrolling = true))
        assertEquals(emptySet<Any>(), MovingCoverRules.pick(spots, allowed = false, scrolling = false))
        assertEquals(setOf<Any>("a"), MovingCoverRules.pick(spots, allowed = true, scrolling = false))
    }

    @Test fun visibleShareIsTheShownPartOfTheCard() {
        assertEquals(1f, MovingCoverRules.visibleShare(100, 200, 100f, 200f), 0f)
        assertEquals(0.5f, MovingCoverRules.visibleShare(100, 200, 50f, 200f), 1e-4f)
        assertEquals(0f, MovingCoverRules.visibleShare(100, 200, 0f, 0f), 0f)
        assertEquals(0f, MovingCoverRules.visibleShare(0, 0, 0f, 0f), 0f)
    }

    @Test fun onlySongsAndAlbumsHaveMovingArt() {
        assertTrue(MovingCoverRules.hasMovingArt(isSong = true, type = null))
        assertTrue(MovingCoverRules.hasMovingArt(isSong = false, type = BrowseType.ALBUM))
        assertFalse(MovingCoverRules.hasMovingArt(isSong = false, type = BrowseType.ARTIST))
        assertFalse(MovingCoverRules.hasMovingArt(isSong = false, type = BrowseType.PLAYLIST))
        assertFalse(MovingCoverRules.hasMovingArt(isSong = false, type = null))
    }

    @Test fun onMeteredDataOnlyCachedCoversMove() {
        val spots = listOf(
            Spot("cached", "a", 1f, 0f, 0f, cached = true),
            Spot("loading", "b", 1f, 300f, 0f, cached = false),
        )
        assertEquals(setOf<Any>("cached"), MovingCoverRules.pick(spots, allowed = true, scrolling = false, metered = true))
        assertEquals(setOf<Any>("cached", "loading"), MovingCoverRules.pick(spots, allowed = true, scrolling = false, metered = false))
    }
}
