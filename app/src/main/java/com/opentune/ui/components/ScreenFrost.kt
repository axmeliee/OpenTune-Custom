package com.opentune.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** How far the screen behind a popup has got to being frosted. */
internal enum class Frost { PENDING, READY, FAILED }

/**
 * The app's screen as it was when a popup opened, blurred and veiled, drawn
 * behind the popup in its own window. Some phones say they blur behind a
 * window and then don't; this doesn't ask them. The screen is copied once
 * with [PixelCopy] (safe with hardware bitmaps), shrunk, and blurred while
 * drawn, so it costs one copy, not a blur every frame. [shown] (0..1) fades
 * it in and out with the popup. Must be called inside a dialog.
 *
 * Returns [Frost.FAILED] where the copy couldn't be made: the caller then
 * falls back to the window's own blur and dim ([FrostBehind]).
 */
@Composable
internal fun ScreenFrost(shown: () -> Float, veil: Float = 0.42f): Frost {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    var shot by remember { mutableStateOf<ImageBitmap?>(null) }
    var state by remember { mutableStateOf(if (activity == null || dialogWindow == null) Frost.FAILED else Frost.PENDING) }
    // This draws its own veil, so the window's dim stays off from the first frame (no flash of grey).
    DisposableEffect(dialogWindow, state) {
        if (state != Frost.FAILED) {
            dialogWindow?.setDimAmount(0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        onDispose { }
    }
    LaunchedEffect(activity) {
        val window = activity?.window ?: return@LaunchedEffect
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) { state = Frost.FAILED; return@LaunchedEffect }
        // Copied straight into a small bitmap (PixelCopy scales to fit), so a
        // popup opening never holds a full-screen copy in memory as it animates.
        val w = (decor.width / SHRINK).coerceAtLeast(1)
        val h = (decor.height / SHRINK).coerceAtLeast(1)
        val small = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: run { state = Frost.FAILED; return@LaunchedEffect }
        val copied = suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(window, small, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
        if (!copied) { small.recycle(); state = Frost.FAILED; return@LaunchedEffect }
        // Blurred once, off the main thread: every frame after only draws it.
        withContext(Dispatchers.Default) { boxBlur(small, BLUR_RADIUS, BLUR_PASSES) }
        shot = small.asImageBitmap()
        state = Frost.READY
    }
    // Where this window and the app's sit on the screen, so the copy lines up with what it copied.
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    val image = shot
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { c ->
                val mine = IntArray(2).also(view::getLocationOnScreen)
                val app = IntArray(2).also { activity?.window?.decorView?.getLocationOnScreen(it) }
                val inWindow = c.positionInWindow()
                offset = IntOffset(app[0] - mine[0] - inWindow.x.toInt(), app[1] - mine[1] - inWindow.y.toInt())
            }
            .graphicsLayer { alpha = shown().coerceIn(0f, 1f) },
    ) {
        val decor = activity?.window?.decorView
        if (image != null && decor != null) {
            drawImage(
                image,
                dstOffset = offset,
                dstSize = IntSize(decor.width, decor.height),
                filterQuality = FilterQuality.High,
            )
        }
        drawRect(Color.Black.copy(alpha = veil))
    }
    return state
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Box-blurs [bitmap] in place, [passes] times over a box [radius] pixels
 * either side, rows then columns. Three passes come out close to a gaussian.
 * On a copy a tenth of the screen's size this takes a few milliseconds.
 */
internal fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 2 || h < 2 || radius < 1) return
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(maxOf(w, h))
    repeat(passes) {
        for (y in 0 until h) blurLine(px, y * w, 1, w, radius, tmp)
        for (x in 0 until w) blurLine(px, x, w, h, radius, tmp)
    }
    bitmap.setPixels(px, 0, w, 0, 0, w, h)
}

/** One row or column of [px], [n] long from [start] in steps of [step], box-blurred with edges held. */
private fun blurLine(px: IntArray, start: Int, step: Int, n: Int, radius: Int, tmp: IntArray) {
    var a = 0; var r = 0; var g = 0; var b = 0
    fun at(i: Int) = px[start + i.coerceIn(0, n - 1) * step]
    for (i in -radius..radius) {
        val c = at(i)
        a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
    }
    val size = 2 * radius + 1
    for (i in 0 until n) {
        tmp[i] = ((a / size) shl 24) or ((r / size) shl 16) or ((g / size) shl 8) or (b / size)
        val out = at(i - radius)
        val inn = at(i + radius + 1)
        a += (inn ushr 24) - (out ushr 24)
        r += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
        g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
        b += (inn and 0xFF) - (out and 0xFF)
    }
    for (i in 0 until n) px[start + i * step] = tmp[i]
}

/** The copy is this many times smaller a side than the screen. */
private const val SHRINK = 8
private const val BLUR_RADIUS = 4
private const val BLUR_PASSES = 3
