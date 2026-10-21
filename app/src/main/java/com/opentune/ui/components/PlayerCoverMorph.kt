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
 * it opens. The mini player reports where its cover sits ([miniLanding]); the
 * player reports where its own cover lands ([playerLanding]); on [startOpen]
 * the cover lifts from the small one and grows into the large one over the top,
 * while the player fades in behind it, then hands over. Always on unless motion
 * is reduced.
 */
object PlayerMorph {
    internal var miniCover by mutableStateOf<Rect?>(null)
    internal var playerCover by mutableStateOf<Rect?>(null)
    /** How much the player's cover is scaled about its centre (smaller while paused). */
    internal var playerCoverScale = 1f
    var expanding by mutableStateOf(false)
        private set
    private var from: Rect? = null
    private var round = false

    /** On the mini player's cover, so a flight knows where it starts. */
    fun Modifier.miniLanding(): Modifier = onGloballyPositioned { miniCover = it.boundsInRoot() }

    /** On the full player's cover, so a flight knows where it lands. */
    fun Modifier.playerLanding(): Modifier = onGloballyPositioned { playerCover = it.boundsInRoot() }

    /** Begins the grow from the mini cover; [round] when it's the circular one. */
    fun startOpen(round: Boolean) {
        from = miniCover ?: return
        this.round = round
        expanding = true
    }

    internal fun from() = from
    internal fun round() = round
    internal fun end() { expanding = false; from = null }
}

/**
 * The mini player's cover growing into the full player's, over everything,
 * while the player fades in behind it.
 */
@Composable
fun CoverMorph(url: String?, smallUrl: String? = null) {
    val t = remember { Animatable(0f) }
    val expanding = PlayerMorph.expanding
    LaunchedEffect(expanding) {
        if (!expanding) return@LaunchedEffect
        try {
            // Wait briefly for the player's cover to be laid out, then grow onto it.
            var tries = 0
            while (PlayerMorph.playerCover == null && tries++ < 25) kotlinx.coroutines.delay(16)
            if (PlayerMorph.playerCover == null) return@LaunchedEffect
            t.snapTo(0f)
            t.animateTo(1f, tween(460, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        } finally {
            PlayerMorph.end()
        }
    }
    if (!expanding) return
    val from = PlayerMorph.from() ?: return
    val laid = PlayerMorph.playerCover ?: return
    // Land on the cover as it's drawn, scaled about its centre.
    val k = PlayerMorph.playerCoverScale
    val to = Rect(
        laid.center.x - laid.width * k / 2f, laid.center.y - laid.height * k / 2f,
        laid.center.x + laid.width * k / 2f, laid.center.y + laid.height * k / 2f,
    )
    val p = t.value
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    val left = lerp(from.left, to.left)
    val top = lerp(from.top, to.top)
    val w = lerp(from.width, to.width)
    val h = lerp(from.height, to.height)
    val density = LocalDensity.current
    val startCorner = if (PlayerMorph.round()) from.width / 2f else with(density) { 12.dp.toPx() }
    val corner = with(density) { (startCorner + (28.dp.toPx() - startCorner) * p).toDp() }
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
