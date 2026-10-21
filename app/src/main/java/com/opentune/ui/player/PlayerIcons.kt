package com.opentune.ui.player

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
