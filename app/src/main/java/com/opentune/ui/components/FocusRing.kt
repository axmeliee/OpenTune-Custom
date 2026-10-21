package com.opentune.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** The colour of the ring round whatever a remote or keyboard has focused. */
val LocalFocusRingColor = staticCompositionLocalOf { Color.White }

/**
 * For clickables that show their press another way (a scale, a glow): no
 * ripple, only a ring in [shape] while a TV remote or keyboard has them
 * focused. A finger never focuses them, so on a phone it's never seen.
 */
class FocusRing(private val shape: Shape) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, shape)

    override fun equals(other: Any?) = other is FocusRing && other.shape == shape
    override fun hashCode() = shape.hashCode()

    private class Node(
        private val interactions: InteractionSource,
        private val shape: Shape,
    ) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
        private var focused = false

        override fun onAttach() {
            coroutineScope.launch {
                interactions.interactions.collect {
                    val now = when (it) {
                        is FocusInteraction.Focus -> true
                        is FocusInteraction.Unfocus -> false
                        else -> return@collect
                    }
                    if (now != focused) {
                        focused = now
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (!focused) return
            val color = currentValueOf(LocalFocusRingColor)
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, color.copy(alpha = 0.14f))
            // Twice as wide as it shows: the clickable is usually clipped to the same shape.
            drawOutline(outline, color, style = Stroke(RING.toPx() * 2))
        }
    }

    private companion object {
        val RING = 2.5.dp
    }
}
