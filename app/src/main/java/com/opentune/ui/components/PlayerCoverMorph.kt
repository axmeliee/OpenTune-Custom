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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The now-playing cover growing out of the mini player into the full player as
 * it opens, and shrinking back into it on close. The mini player reports where
 * its cover sits ([miniLanding]); the player reports where its own cover lands
 * ([playerLanding]); [startOpen] and [startClose] run the flight over the top
 * while the player fades in or out behind it. Always on unless motion is reduced.
 */
object PlayerMorph {
    internal var miniCover by mutableStateOf<Rect?>(null)
    internal var playerCover by mutableStateOf<Rect?>(null)
    /** How much the player's cover is scaled about its centre (smaller while paused). */
    internal var playerCoverScale = 1f

    var expanding by mutableStateOf(false)
        private set
    var collapsing by mutableStateOf(false)
        private set
    private var openFrom: Rect? = null
    private var closeFrom: Rect? = null
    private var round = false

    /** On the mini player's cover, so a flight knows where it starts and lands. */
    fun Modifier.miniLanding(): Modifier = onGloballyPositioned { miniCover = it.boundsInRoot() }

    /** On the full player's cover, so a flight knows where it lands and leaves from. */
    fun Modifier.playerLanding(): Modifier = onGloballyPositioned { playerCover = it.boundsInRoot() }

    /** Begins the grow from the mini cover; [round] when it's the circular one. */
    fun startOpen(round: Boolean) {
        openFrom = miniCover ?: return
        this.round = round
        expanding = true
    }

    /** Begins the shrink back into the mini cover, captured before the player leaves. */
    fun startClose() {
        if (playerCover == null || miniCover == null) return
        closeFrom = playerCover
        collapsing = true
    }

    internal fun openFrom() = openFrom
    internal fun closeFrom() = closeFrom
    internal fun round() = round
    internal fun endOpen() { expanding = false; openFrom = null }
    internal fun endClose() { collapsing = false; closeFrom = null }
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

/** The flight, over everything: grows the cover on open, shrinks it on close. */
@Composable
fun CoverMorph(url: String?, smallUrl: String? = null) {
    val openT = remember { Animatable(0f) }
    LaunchedEffect(PlayerMorph.expanding) {
        if (!PlayerMorph.expanding) return@LaunchedEffect
        try {
            var tries = 0
            while (PlayerMorph.playerCover == null && tries++ < 25) kotlinx.coroutines.delay(16)
            if (PlayerMorph.playerCover == null) return@LaunchedEffect
            openT.snapTo(0f)
            openT.animateTo(1f, tween(460, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        } finally {
            PlayerMorph.endOpen()
        }
    }
    val closeT = remember { Animatable(0f) }
    LaunchedEffect(PlayerMorph.collapsing) {
        if (!PlayerMorph.collapsing) return@LaunchedEffect
        try {
            closeT.snapTo(0f)
            closeT.animateTo(1f, tween(360, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)))
        } finally {
            PlayerMorph.endClose()
        }
    }
    // Land on the player cover as it's drawn, scaled about its centre.
    fun scaled(laid: Rect): Rect {
        val k = PlayerMorph.playerCoverScale
        return Rect(
            laid.center.x - laid.width * k / 2f, laid.center.y - laid.height * k / 2f,
            laid.center.x + laid.width * k / 2f, laid.center.y + laid.height * k / 2f,
        )
    }
    val density = LocalDensity.current
    when {
        PlayerMorph.expanding -> {
            val from = PlayerMorph.openFrom() ?: return
            val to = PlayerMorph.playerCover?.let(::scaled) ?: return
            val startCorner = if (PlayerMorph.round()) with(density) { from.width.toDp().value / 2f } else 12f
            MorphBox(url, smallUrl, from, to, openT.value, startCornerDp = startCorner, endCornerDp = 28f)
        }
        PlayerMorph.collapsing -> {
            val from = PlayerMorph.closeFrom() ?: return
            val to = PlayerMorph.miniCover ?: return
            MorphBox(url, smallUrl, from, to, closeT.value, startCornerDp = 28f, endCornerDp = 12f)
        }
    }
}
