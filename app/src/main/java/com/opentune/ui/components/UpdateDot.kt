package com.opentune.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.UpdateCheck

/** Whether a newer release is waiting to be installed, the one [UpdateDot] marks. */
@Composable
fun updatePending(): Boolean {
    val pending by UpdateCheck.pending.collectAsState()
    return pending != null
}

/** The small red dot that marks the way to a waiting update. */
@Composable
fun UpdateDot(modifier: Modifier = Modifier, size: Dp = 9.dp) {
    Box(
        modifier
            .size(size)
            .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
            .background(Color(0xFFFF453A), CircleShape),
    )
}

/** [content] with an [UpdateDot] on its top right corner while an update waits. */
@Composable
fun WithUpdateDot(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val show = updatePending()
    Box(modifier) {
        content()
        AnimatedVisibility(show, Modifier.align(Alignment.TopEnd), enter = scaleIn(), exit = scaleOut()) {
            UpdateDot(size = 12.dp)
        }
    }
}
