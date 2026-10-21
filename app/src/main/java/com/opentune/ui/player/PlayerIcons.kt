package com.opentune.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The player's own icons where Material has nothing that fits, drawn on its
 * 24-unit grid at the same weight: lyrics as a speech bubble holding a quote,
 * and a pair of earbuds for Bluetooth output.
 */
internal object PlayerIcons {
    /** A rounded speech bubble, outlined, with two quote marks inside. */
    val Lyrics: ImageVector by lazy {
        ImageVector.Builder("Lyrics", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6f, 3.5f)
                horizontalLineTo(18f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, 2.5f)
                verticalLineTo(14.5f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, 2.5f)
                horizontalLineTo(10.5f)
                lineTo(6.5f, 20.5f)
                verticalLineTo(17f)
                horizontalLineTo(6f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, -2.5f)
                verticalLineTo(6f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, -2.5f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                quoteMark(8.6f, 8.6f)
                quoteMark(12.8f, 8.6f)
            }
        }.build()
    }

    /** Two earbuds side by side, stems down, each with its speaker opening. */
    val Earbuds: ImageVector by lazy {
        ImageVector.Builder("Earbuds", 24.dp, 24.dp, 24f, 24f).apply {
            // The heads, the openings cut out of them.
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                circle(7.4f, 7.6f, 3.4f)
                circle(6.6f, 7.6f, 1.15f)
                circle(16.6f, 7.6f, 3.4f)
                circle(17.4f, 7.6f, 1.15f)
            }
            // The stems, down from the inner side of each head.
            path(fill = SolidColor(Color.Black)) {
                stem(8.7f, 8.4f)
                stem(13.5f, 8.4f)
            }
        }.build()
    }

    /**
     * Headphones, drawn plainly: a tall arch with square ends for the band,
     * and two bars either side of it for the sound, the inner ones taller.
     */
    val Headphones: ImageVector by lazy {
        ImageVector.Builder("Headphones", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            ) {
                moveTo(7.4f, 18.6f)
                verticalLineTo(10.8f)
                arcToRelative(4.6f, 4.6f, 0f, false, true, 9.2f, 0f)
                verticalLineTo(18.6f)
            }
            path(fill = SolidColor(Color.Black)) {
                // Inner bars, then the shorter outer ones.
                rect(4.35f, 11.5f, 5.85f, 17.75f)
                rect(18.15f, 11.5f, 19.65f, 17.75f)
                rect(2f, 12.35f, 3.5f, 17.05f)
                rect(20.5f, 12.35f, 22f, 17.05f)
            }
        }.build()
    }

    private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
        moveTo(l, t)
        horizontalLineTo(r)
        verticalLineTo(b)
        horizontalLineTo(l)
        close()
    }

    /** A filled comma-like quote mark, 2.6 wide, its top left at ([x], [y]). */
    private fun PathBuilder.quoteMark(x: Float, y: Float) {
        moveTo(x, y)
        horizontalLineToRelative(2.6f)
        verticalLineToRelative(2.4f)
        curveToRelative(0f, 1.5f, -0.7f, 2.5f, -2.1f, 2.9f)
        lineToRelative(-0.35f, -0.85f)
        curveToRelative(0.7f, -0.3f, 1.05f, -0.8f, 1.1f, -1.45f)
        horizontalLineTo(x)
        close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    /** A stem 1.8 wide with round ends, from ([x], [top]) down to y 20.3. */
    private fun PathBuilder.stem(x: Float, top: Float) {
        val w = 1.8f
        val r = w / 2f
        moveTo(x, top + r)
        arcToRelative(r, r, 0f, false, true, w, 0f)
        verticalLineTo(20.3f - r)
        arcToRelative(r, r, 0f, false, true, -w, 0f)
        close()
    }
}

/** The icon for where the sound is going: earbuds for Bluetooth, headphones for a wire or USB. */
internal val OutputRoute.icon: ImageVector
    get() = when (this) {
        OutputRoute.CAST -> Icons.Rounded.Cast
        OutputRoute.BLUETOOTH -> PlayerIcons.Earbuds
        OutputRoute.HEADPHONES, OutputRoute.USB -> PlayerIcons.Headphones
        OutputRoute.PHONE -> Icons.Rounded.Smartphone
    }
