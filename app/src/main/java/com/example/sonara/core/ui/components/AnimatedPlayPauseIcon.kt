package com.example.sonara.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Backward-compatible delegating wrapper around [PlayPauseMorph].
 */
@Composable
fun AnimatedPlayPauseIcon(
    isPlaying: Boolean,
    color: Color = Color.White,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp
) {
    PlayPauseMorph(
        isPlaying = isPlaying,
        modifier = modifier,
        size = size,
        color = color
    )
}
