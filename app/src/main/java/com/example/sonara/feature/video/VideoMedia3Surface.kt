package com.example.sonara.feature.video

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player

fun VideoPlaybackRoute.shouldRenderMedia3Surface(): Boolean = this == VideoPlaybackRoute.MEDIA3

fun VideoPlaybackRoute.shouldRenderResolutionState(): Boolean = this == VideoPlaybackRoute.RESOLVING

/** Attaches the existing service-owned Media3 player to one lifecycle-owned Android surface. */
@Composable
fun VideoMedia3Surface(
    player: Player?,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val surfaceView = androidx.compose.runtime.remember(context) {
        SurfaceView(context).apply {
            setZOrderMediaOverlay(true)
        }
    }

    DisposableEffect(player, surfaceView) {
        player?.setVideoSurfaceView(surfaceView)
        onDispose {
            player?.clearVideoSurfaceView(surfaceView)
        }
    }

    AndroidView(
        factory = { surfaceView },
        modifier = modifier,
        update = { view ->
            view.visibility = if (isVisible) android.view.View.VISIBLE else android.view.View.GONE
            player?.setVideoSurfaceView(view)
        }
    )
}

@Composable
fun VideoResolutionSurface(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
