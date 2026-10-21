package com.opentune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The now-playing cover growing out of the mini player into the full player as
 * it opens, and shrinking back into it on close. The mini player reports where
 * its cover sits ([miniLanding]); the player reports where its own cover lands
 * ([playerLanding]); [startOpen] and [startClose] run the flight over the top
 * while the player fades in or out behind it. A swipe drives the same flight by
 * hand: up on the mini player ([pullOpen]) or down on the player ([dragClose]),
 * the cover following the finger, and the flight finishes from wherever the
 * finger let go. Always on unless motion is reduced.
 */
object PlayerMorph {
    internal var miniCover by mutableStateOf<Rect?>(null)
    internal var playerCover by mutableStateOf<Rect?>(null)
    /** Where the player's cover sat the last time it was open, for a swipe up before it's there. */
    internal var lastPlayerCover: Rect? = null
    /** How much the player's cover is scaled about its centre (smaller while paused). */
    internal var playerCoverScale = 1f

    var expanding by mutableStateOf(false)
        private set
    var collapsing by mutableStateOf(false)
        private set
    /** 0..1 while a finger pulls the mini player up; 0 otherwise. */
    var pulling by mutableFloatStateOf(0f)
        private set
    /** 0..1 while a finger drags the open player down; 0 otherwise. */
    var dragging by mutableFloatStateOf(0f)
        private set
    /** True while the cover flies between the big cover and the small one in the player's lyrics and queue header. */
    var flying by mutableStateOf(false)
    /** Whether the player's own cover should hide, the flying one standing in for it. */
    val coverAway: Boolean get() = expanding || collapsing || dragging > 0f || flying

    private var openFrom: Rect? = null
    private var closeFrom: Rect? = null
    private var openAt = 0f
    private var closeAt = 0f
    private var round = false
    /** Where the cover was when the drag down began, before the player moved with it. */
    private var dragRest: Rect? = null

    /** On the mini player's cover, so a flight knows where it starts and lands. */
    fun Modifier.miniLanding(): Modifier = onGloballyPositioned { miniCover = it.boundsInRoot() }

    /** On the full player's cover, so a flight knows where it lands and leaves from. */
    fun Modifier.playerLanding(): Modifier = onGloballyPositioned { r ->
        val b = r.boundsInRoot()
        playerCover = b
        if (dragging == 0f && !collapsing) lastPlayerCover = b
    }

    /** Begins the grow from the mini cover; [round] when it's the circular one. */
    fun startOpen(round: Boolean) {
        openFrom = miniCover ?: return
        this.round = round
        openAt = pulling
        pulling = 0f
        expanding = true
    }

    /** Begins the shrink back into the mini cover, captured before the player leaves. */
    fun startClose() {
        val from = dragRest ?: playerCover
        if (from == null || miniCover == null) return
        closeFrom = from
        closeAt = dragging
        collapsing = true
    }

    /**
     * The mini player pulled up by [px] so far: the cover grows toward where
     * the player's cover will be, as far as the finger has gone. [round] for
     * the folded, circular mini player.
     */
    fun pullOpen(px: Float, round: Boolean, screenHeight: Float) {
        val from = miniCover ?: return
        this.round = round
        val to = lastPlayerCover
        val travel = if (to != null) (from.top - to.top).coerceAtLeast(1f) else screenHeight * 0.6f
        pulling = (px / travel).coerceIn(0f, 1f)
    }

    /** The pull let go short of opening: the cover goes back to the mini player. */
    fun endPull() { pulling = 0f }

    /** The open player dragged down by [px]: the cover shrinks toward the mini player with the finger. */
    fun dragClose(px: Float) {
        if (px <= 0f) {
            if (!collapsing) { dragging = 0f; dragRest = null }
            return
        }
        val rest = dragRest ?: playerCover?.let(::scaled)?.also { dragRest = it } ?: return
        val to = miniCover ?: return
        dragging = (px / (to.center.y - rest.center.y).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /** The player's cover as drawn: its laid-out box scaled about its centre. */
    internal fun scaled(laid: Rect): Rect {
        val k = playerCoverScale
        return Rect(
            laid.center.x - laid.width * k / 2f, laid.center.y - laid.height * k / 2f,
            laid.center.x + laid.width * k / 2f, laid.center.y + laid.height * k / 2f,
        )
    }

    internal fun openFrom() = openFrom
    internal fun closeFrom() = closeFrom
    internal fun openAt() = openAt
    internal fun closeAt() = closeAt
    internal fun dragRest() = dragRest
    internal fun round() = round
    internal fun endOpen() { expanding = false; openFrom = null; openAt = 0f }
    internal fun endClose() { collapsing = false; closeFrom = null; closeAt = 0f; dragging = 0f; dragRest = null }
}

/** Interpolates the cover between [from] and [to] (p 0..1) and draws it over everything. */
@Composable
private fun MorphBox(url: String?, smallUrl: String?, from: Rect, to: Rect, p: Float, startCornerDp: Float, endCornerDp: Float) {
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    val left = lerp(from.left, to.left)
    val top = lerp(from.top, to.top)
    val w = lerp(from.width, to.width)
    val h = lerp(from.height, to.height)
    val density = LocalDensity.current
    val corner = with(density) { (startCornerDp + (endCornerDp - startCornerDp) * p).dp }
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .shadow(with(density) { (28f * p).toDp() }, RoundedCornerShape(corner)),
    ) {
        // The mini player's own copy underneath, already loaded, so it never shows empty.
        if (smallUrl != null) Artwork(smallUrl, Modifier.matchParentSize(), RoundedCornerShape(corner))
        Artwork(url, Modifier.matchParentSize(), RoundedCornerShape(corner))
    }
}

/** The flight, over everything: grows the cover on open, shrinks it on close, and follows a swipe either way. */
@Composable
fun CoverMorph(url: String?, smallUrl: String? = null) {
    val openT = remember { Animatable(0f) }
    LaunchedEffect(PlayerMorph.expanding) {
        if (!PlayerMorph.expanding) return@LaunchedEffect
        try {
            // From wherever a pull up left it.
            openT.snapTo(PlayerMorph.openAt())
            var tries = 0
            while (PlayerMorph.playerCover == null && tries++ < 25) kotlinx.coroutines.delay(16)
            if (PlayerMorph.playerCover == null) return@LaunchedEffect
            val left = 1f - openT.value
            openT.animateTo(1f, tween((460 * left).roundToInt().coerceAtLeast(180), easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        } finally {
            PlayerMorph.endOpen()
        }
    }
    val closeT = remember { Animatable(0f) }
    LaunchedEffect(PlayerMorph.collapsing) {
        if (!PlayerMorph.collapsing) return@LaunchedEffect
        try {
            // From wherever a drag down left it.
            closeT.snapTo(PlayerMorph.closeAt())
            val left = 1f - closeT.value
            closeT.animateTo(1f, tween((360 * left).roundToInt().coerceAtLeast(160), easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)))
        } finally {
            PlayerMorph.endClose()
        }
    }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    // Where the player's cover will sit before it has ever been laid out: a
    // square across the screen below the top bar, as the classic layout has it.
    val guess = with(density) {
        val w = config.screenWidthDp.dp.toPx()
        val side = w - 48.dp.toPx()
        val top = 150.dp.toPx()
        Rect(24.dp.toPx(), top, 24.dp.toPx() + side, top + side)
    }
    fun roundStart(from: Rect) = if (PlayerMorph.round()) with(density) { from.width.toDp().value / 2f } else 12f
    when {
        PlayerMorph.expanding -> {
            val from = PlayerMorph.openFrom() ?: return
            val to = PlayerMorph.playerCover?.let(PlayerMorph::scaled) ?: return
            MorphBox(url, smallUrl, from, to, openT.value, startCornerDp = roundStart(from), endCornerDp = 28f)
        }
        PlayerMorph.collapsing -> {
            val from = PlayerMorph.closeFrom() ?: return
            val to = PlayerMorph.miniCover ?: return
            MorphBox(url, smallUrl, from, to, closeT.value, startCornerDp = 28f, endCornerDp = 12f)
        }
        PlayerMorph.pulling > 0f -> {
            val from = PlayerMorph.miniCover ?: return
            val to = PlayerMorph.lastPlayerCover?.let(PlayerMorph::scaled) ?: guess
            MorphBox(url, smallUrl, from, to, PlayerMorph.pulling, startCornerDp = roundStart(from), endCornerDp = 28f)
        }
        PlayerMorph.dragging > 0f -> {
            val from = PlayerMorph.dragRest() ?: return
            val to = PlayerMorph.miniCover ?: return
            MorphBox(url, smallUrl, from, to, PlayerMorph.dragging, startCornerDp = 28f, endCornerDp = 12f)
        }
    }
}
