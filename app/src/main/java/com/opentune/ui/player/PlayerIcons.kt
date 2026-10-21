package com.opentune.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.ui.geometry.Offset
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

    /**
     * A pair of earbuds facing outward: each a round head with its ear tip on
     * the outer side, set apart by a crescent gap, a slanted sensor slot in
     * the head, and a straight stem meeting the head in a smooth neck.
     */
    val Earbuds: ImageVector by lazy {
        ImageVector.Builder("Earbuds", 24.dp, 24.dp, 24f, 24f).apply {
            for (mirror in listOf(false, true)) {
                fun x(v: Float) = if (mirror) 24f - v else v
                val head = Offset(x(7.1f), 9.0f)
                val tip = Offset(x(3.3f), 10.0f)
                val stem = x(7.43f)
                // The head, its edge drawn around the gap that keeps it off the tip, the slot cut out.
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                    headAround(head, HEAD_R, tip, TIP_R + GAP)
                    capsule(Offset(x(6.0f), 7.6f), Offset(x(7.0f), 9.8f), 0.62f)
                }
                path(fill = SolidColor(Color.Black)) {
                    // The ear tip.
                    moveTo(tip.x - TIP_R, tip.y)
                    arcToRelative(TIP_R, TIP_R, 0f, true, true, 2 * TIP_R, 0f)
                    arcToRelative(TIP_R, TIP_R, 0f, true, true, -2 * TIP_R, 0f)
                    close()
                    // The neck, curving in from the head to the stem.
                    moveTo(stem - 2.0f, 11.6f)
                    quadTo(stem - STEM_R, 12.3f, stem - STEM_R, 13.6f)
                    lineTo(stem + STEM_R, 13.6f)
                    quadTo(stem + STEM_R, 12.3f, stem + 2.0f, 11.6f)
                    close()
                    // The stem, straight down with a round end.
                    capsule(Offset(stem, 11.8f), Offset(stem, 17.2f), STEM_R)
                }
            }
        }.build()
    }

    private const val HEAD_R = 3.6f
    private const val TIP_R = 1.9f
    private const val GAP = 0.45f
    private const val STEM_R = 1.2f

    /**
     * A circle of radius [r] about [c], less where it would come within
     * [keep] of [away]: the long way round its own edge, then back along the
     * inside of the circle it gives way to.
     */
    private fun PathBuilder.headAround(c: Offset, r: Float, away: Offset, keep: Float) {
        val dx = away.x - c.x
        val dy = away.y - c.y
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        val a = (r * r - keep * keep + d * d) / (2 * d)
        val h = kotlin.math.sqrt((r * r - a * a).coerceAtLeast(0f))
        val ux = dx / d
        val uy = dy / d
        val px = c.x + a * ux
        val py = c.y + a * uy
        val i1 = Offset(px - h * uy, py + h * ux)
        val i2 = Offset(px + h * uy, py - h * ux)
        moveTo(i1.x, i1.y)
        arcTo(r, r, 0f, true, true, i2.x, i2.y)
        arcTo(keep, keep, 0f, false, false, i1.x, i1.y)
        close()
    }

    /** A capsule [r] wide on either side of the line from [a] to [b], round at both ends. */
    private fun PathBuilder.capsule(a: Offset, b: Offset, r: Float) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val nx = -dy / len * r
        val ny = dx / len * r
        moveTo(a.x + nx, a.y + ny)
        lineTo(b.x + nx, b.y + ny)
        arcTo(r, r, 0f, false, false, b.x - nx, b.y - ny)
        lineTo(a.x - nx, a.y - ny)
        arcTo(r, r, 0f, false, false, a.x + nx, a.y + ny)
        close()
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

}

/** The icon for where the sound is going: earbuds for Bluetooth, headphones for a wire or USB. */
internal val OutputRoute.icon: ImageVector
    get() = when (this) {
        OutputRoute.CAST -> Icons.Rounded.Cast
        OutputRoute.BLUETOOTH -> PlayerIcons.Earbuds
        OutputRoute.HEADPHONES, OutputRoute.USB -> PlayerIcons.Headphones
        OutputRoute.PHONE -> Icons.Rounded.Smartphone
    }
