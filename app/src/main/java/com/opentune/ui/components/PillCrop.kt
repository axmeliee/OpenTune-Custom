package com.opentune.ui.components

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
