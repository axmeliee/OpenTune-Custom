package com.opentune.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A tapped song's cover flying into the now-playing card. The root
 * watches where taps land ([watchTaps]), the card says where its cover is
 * ([landing], [card]); when a song starts just after a tap outside the
 * card, the cover lifts from the tap and arcs down into the card's.
 */
object FlyingCover {
    internal var target by mutableStateOf<Rect?>(null)
    internal var cardBounds: Rect? = null
    private var tapAt = Offset.Unspecified
    private var tapTime = -1L

    /** Notes each tap on the screen without taking it. */
    fun Modifier.watchTaps(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            recordTap(down.position)
        }
    }

    internal fun recordTap(position: Offset) {
        tapAt = position
        tapTime = SystemClock.uptimeMillis()
    }

    /** On the now-playing card's cover: where flights land. */
    fun Modifier.landing(): Modifier = onGloballyPositioned { target = it.boundsInRoot() }

    /** On the whole card, so taps on it (skip, play) don't send a cover flying. */
    fun Modifier.card(): Modifier = onGloballyPositioned { cardBounds = it.boundsInRoot() }

    /** Where a flight for a song starting now begins, or null if no tap just led to it. */
    internal fun takeStart(): Offset? {
        val recent = tapTime >= 0L && SystemClock.uptimeMillis() - tapTime < TAP_WINDOW_MS
        val at = tapAt
        tapTime = -1L
        if (!recent || at == Offset.Unspecified) return null
        if (cardBounds?.inflate(12f)?.contains(at) == true) return null
        return at
    }

    private const val TAP_WINDOW_MS = 1_500L

    // ---- Opening the player from the mini player ----

    /** Where the full player's cover is laid out, while it's on screen. */
    internal var playerCover by mutableStateOf<Rect?>(null)
    /** How much the player's cover is scaled about its centre (smaller while paused). */
    internal var playerCoverScale = 1f
    /** While true the mini player's cover is growing into the player's; the player's own waits, hidden, under it. */
    var expanding by mutableStateOf(false)
        private set
    private var expandFrom: Rect? = null
    private var expandRound = false

    /** On the full player's cover: where an opening lands. */
    fun Modifier.playerLanding(): Modifier = onGloballyPositioned { playerCover = it.boundsInRoot() }

    /** Starts the mini player's cover growing into the player that's about to open. */
    fun startExpand(round: Boolean) {
        val from = target ?: return
        expandFrom = from
        expandRound = round
        expanding = true
    }

    internal fun expandStart() = expandFrom
    internal fun expandRound() = expandRound

    internal fun endExpand() {
        expanding = false
        expandFrom = null
    }
}

/**
 * The mini player's cover growing into the full player's, over everything,
 * while the player fades in behind it ([FlyingCover.startExpand]).
 */
@Composable
fun CoverExpand(url: String?, smallUrl: String? = null) {
    val t = remember { Animatable(0f) }
    val expanding = FlyingCover.expanding
    LaunchedEffect(expanding) {
        if (!expanding) return@LaunchedEffect
        try {
            // Also clear the hidden-cover flag when cancelled while waiting for layout.
            var tries = 0
            while (FlyingCover.playerCover == null && tries++ < 25) kotlinx.coroutines.delay(16)
            if (FlyingCover.playerCover == null) return@LaunchedEffect
            t.snapTo(0f)
            t.animateTo(1f, tween(460, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        } finally {
            FlyingCover.endExpand()
        }
    }
    if (!expanding) return
    val from = FlyingCover.expandStart() ?: return
    val laid = FlyingCover.playerCover ?: return
    // Land on the cover as it's drawn, scaled about its centre.
    val k = FlyingCover.playerCoverScale
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
    val startCorner = if (FlyingCover.expandRound()) from.width / 2f else with(density) { 12.dp.toPx() }
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

/**
 * The flight itself, over everything: started when [songKey] changes with
 * [url] as its cover. Draws nothing between flights.
 */
@Composable
fun CoverFlight(songKey: String?, url: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    var flight by remember { mutableStateOf<Triple<String?, Offset, Rect>?>(null) }
    val t = remember { Animatable(0f) }
    LaunchedEffect(songKey, enabled) {
        flight = null
        if (!enabled || songKey == null) return@LaunchedEffect
        val start = FlyingCover.takeStart() ?: return@LaunchedEffect
        // The card may still be arriving for a first song; wait a moment for it.
        var end = FlyingCover.target
        var tries = 0
        while (end == null && tries++ < 20) { kotlinx.coroutines.delay(16); end = FlyingCover.target }
        end ?: return@LaunchedEffect
        flight = Triple(url, start, end)
        try {
            t.snapTo(0f)
            t.animateTo(1f, tween(720, easing = CubicBezierEasing(0.3f, 0f, 0.15f, 1f)))
        } finally {
            // A skip can cancel this effect before another tap starts a flight.
            flight = null
        }
    }
    val f = flight ?: return
    val density = LocalDensity.current
    val startSize = with(density) { 72.dp.toPx() }
    val p = t.value
    val (_, from, to) = f
    val size = startSize + (to.width - startSize) * p
    // Along a curve that rises a little before it drops into the card.
    val x = from.x + (to.center.x - from.x) * p
    val y = from.y + (to.center.y - from.y) * p - with(density) { 90.dp.toPx() } * sin(Math.PI.toFloat() * p) * 0.6f
    val lift = 1f + 0.12f * sin(Math.PI.toFloat() * p)
    Box(
        modifier
            .offset { IntOffset((x - size / 2f).roundToInt(), (y - size / 2f).roundToInt()) }
            .size(with(density) { size.toDp() })
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
                rotationZ = -8f * sin(Math.PI.toFloat() * p)
                alpha = if (p > 0.92f) (1f - p) / 0.08f else 1f
            }
            .shadow(with(density) { (16f * (1f - p)).toDp() }, RoundedCornerShape(14.dp)),
    ) {
        Artwork(f.first, Modifier.matchParentSize(), RoundedCornerShape((14 - 2 * p).dp))
    }
}

/**
 * The app's window cropped toward a pill at the top centre, [p] of the way:
 * at 1 it's [width] by [height] with fully round ends, in the layer's own
 * (unscaled) pixels.
 */
fun pillCrop(p: Float, width: Float, height: Float): androidx.compose.ui.graphics.Shape =
    androidx.compose.foundation.shape.GenericShape { size, _ ->
        val w = size.width + (minOf(width, size.width) - size.width) * p
        val h = size.height + (height - size.height) * p
        val r = h / 2f * p
        val left = (size.width - w) / 2f
        addRoundRect(androidx.compose.ui.geometry.RoundRect(left, 0f, left + w, h, androidx.compose.ui.geometry.CornerRadius(r, r)))
    }
