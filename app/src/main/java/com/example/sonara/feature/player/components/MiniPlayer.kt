package com.example.sonara.feature.player.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.components.SonaraLoadingIndicator
import com.example.sonara.core.ui.theme.SonaraPalette
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.feature.player.PlayerUiState

// ── Width Breakpoints ──
private val COMPACT_MAX_DP = 360.dp   // < 360dp -> Compact Phone
private val NORMAL_MAX_DP  = 412.dp   // 360-411dp -> Normal Phone (Primary Target)
private val LARGE_MAX_DP   = 600.dp   // 412-599dp -> Large Phone
// >= 600dp -> Tablet / Expanded

// Entrance animation specs matching Web Desktop (translateY(24px) + scale(0.98) -> natural)
private val EnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EnterSlide  = tween<IntOffset>(durationMillis = 320, easing = EnterEasing)
private val ExitSlide   = tween<IntOffset>(durationMillis = 200, easing = EnterEasing)
private val EnterFade   = tween<Float>(durationMillis = 300, easing = EnterEasing)
private val ExitFade    = tween<Float>(durationMillis = 200, easing = EnterEasing)

/**
 * Sonara Android MiniPlayer -- Final Three Approved Refinements.
 *
 * 1. Stronger Center Transport Grouping:
 *    Shuffle  --[flankSpacing]--  [ Previous -[coreSpacing]- PLAY -[coreSpacing]- Next ]  --[flankSpacing]--  Repeat
 * 2. Optical Artwork + Metadata Alignment:
 *    Start padding 12dp + Arrangement.Center for optical baseline alignment with 46dp artwork.
 * 3. Subtle Artwork Elevation:
 *    Restrained 8dp shadow, 0.75dp border sheen, and crisp separation from capsule surface.
 *
 * Authority: Consumes PlayerUiState only -- zero direct ExoPlayer access.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onClickBody: () -> Unit,
    audioOutputState: com.example.sonara.domain.model.AudioOutputState = com.example.sonara.domain.model.AudioOutputState(),
    onOpenAudioOutputSelector: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isVisible = state.isConnected && state.trackTitle != "No Track Selected"

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(animationSpec = EnterSlide) { it / 2 } +
                fadeIn(animationSpec = EnterFade) +
                scaleIn(initialScale = 0.97f, animationSpec = EnterFade),
        exit  = slideOutVertically(animationSpec = ExitSlide) { it / 3 } +
                fadeOut(animationSpec = ExitFade) +
                scaleOut(targetScale = 0.97f, animationSpec = ExitFade),
        modifier = modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val screenWidth = maxWidth

            // Responsive layout configuration with distinct core vs flank spacing
            val (horizontalMargin, paddingH, artworkSize, coreSpacing, flankSpacing) = when {
                screenWidth < COMPACT_MAX_DP -> Quint(8.dp,  10.dp, 38.dp, 6.dp,  12.dp)
                screenWidth < NORMAL_MAX_DP  -> Quint(10.dp, 14.dp, 42.dp, 8.dp,  18.dp)
                screenWidth < LARGE_MAX_DP   -> Quint(12.dp, 16.dp, 44.dp, 10.dp, 20.dp)
                else                         -> Quint(16.dp, 18.dp, 48.dp, 12.dp, 24.dp)
            }

            MiniPlayerCard(
                state                     = state,
                margin                    = horizontalMargin,
                paddingH                  = paddingH,
                artworkSize               = artworkSize,
                coreSpacing               = coreSpacing,
                flankSpacing              = flankSpacing,
                onPlay                    = onPlay,
                onPause                   = onPause,
                onNext                    = onNext,
                onPrevious                = onPrevious,
                onSeek                    = onSeek,
                onClickBody               = onClickBody,
                audioOutputState          = audioOutputState,
                onOpenAudioOutputSelector = onOpenAudioOutputSelector
            )
        }
    }
}

private data class Quint<A, B, C, D, E>(
    val first: A, val second: B, val third: C, val fourth: D, val fifth: E
)

@Composable
private fun MiniPlayerCard(
    state: PlayerUiState,
    margin: Dp,
    paddingH: Dp,
    artworkSize: Dp,
    coreSpacing: Dp,
    flankSpacing: Dp,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onClickBody: () -> Unit,
    audioOutputState: com.example.sonara.domain.model.AudioOutputState = com.example.sonara.domain.model.AudioOutputState(),
    onOpenAudioOutputSelector: () -> Unit = {}
) {
    val colors = SonaraTheme.colors
    val materials = SonaraTheme.materials

    // Local shuffle and repeat states
    var isShuffled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(0) } // 0 = off, 1 = repeat all, 2 = repeat one

    // Smooth liquid progress animation synchronized with actual playback position (FROZEN)
    val liquidProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = if (state.isPlaying) {
            tween(durationMillis = 500, easing = LinearEasing)
        } else {
            snap()
        },
        label = "LiquidProgressAnimation"
    )

    // 28dp pill capsule shape with tight, tailored vertical bounds
    val capsuleShape = RoundedCornerShape(28.dp)

    val isDark = colors.isDark

    val capsuleBackground = materials.player
    val capsuleBorder = if (isDark) Color(0xFF264043).copy(alpha = 0.60f) else SonaraPalette.BoneBorder.copy(alpha = 0.85f)
    val shadowSpot = if (isDark) Color.Black.copy(alpha = 0.45f) else Color(0xFF2E1C15).copy(alpha = 0.16f)
    val shadowAmbient = if (isDark) Color.Black.copy(alpha = 0.25f) else Color(0xFF2E1C15).copy(alpha = 0.08f)
    val timestampColor = if (isDark) Color(0xFFA8A297) else SonaraPalette.DarkSecondaryText
    val titleColor = if (isDark) Color(0xFFF6F2EA) else SonaraPalette.DarkPrimaryText
    val subtitleColor = if (isDark) Color(0xFFA8A297) else SonaraPalette.DarkSecondaryText
    val transportIconColor = if (isDark) Color.White.copy(alpha = 0.90f) else SonaraPalette.DarkPrimaryText.copy(alpha = 0.90f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = margin, vertical = 2.dp)
            // Voluminous ambient shadow
            .shadow(
                elevation    = 16.dp,
                shape        = capsuleShape,
                clip         = false,
                spotColor    = shadowSpot,
                ambientColor = shadowAmbient
            )
            .clip(capsuleShape)
            // Deep Petrol (Dark) / Elevated Bone (Light) translucent surface
            .background(capsuleBackground)
            // Subtle specular border sheen
            .border(1.dp, capsuleBorder, capsuleShape)
    ) {
        // Ambient radial light wash (top-left subtle illumination)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            SonaraPalette.OxideAccent.copy(alpha = if (isDark) 0.08f else 0.04f),
                            Color.Transparent
                        ),
                        radius = 650f,
                        center = Offset(80f, 80f)
                    )
                )
        )

        // Main 3-Tier Content Stack with tightened vertical padding & rhythm
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = paddingH, end = paddingH, top = 7.dp, bottom = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // ===============================================================
            // TIER 1 -- Liquid-Fill Segmented Progress Bar (FROZEN)
            // ===============================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Elapsed timestamp (FROZEN: 13.5sp SemiBold, 36dp width)
                Text(
                    text       = formatTime(state.currentPositionMs / 1000L),
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = timestampColor,
                    textAlign  = TextAlign.End,
                    modifier   = Modifier.width(36.dp)
                )

                // Segmented dash progress rail with continuous liquid fill (FROZEN)
                LiquidSegmentedProgressRail(
                    progress = liquidProgress,
                    onSeek   = onSeek,
                    isDark   = isDark,
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                )

                // Duration timestamp (FROZEN: 13.5sp SemiBold, 36dp width)
                Text(
                    text       = formatTime(state.durationMs / 1000L),
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = timestampColor,
                    textAlign  = TextAlign.Start,
                    modifier   = Modifier.width(36.dp)
                )
            }

            // ===============================================================
            // TIER 2 -- Artwork + Metadata Row (Optically Aligned & Subtle Elevation)
            // Tapping opens Expanded Player; Audio Output button on right
            // ===============================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clickable artwork + metadata area
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null,
                            onClick           = onClickBody
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Square Rounded Artwork Thumbnail with Subtle Elevation
                    Box(
                        modifier = Modifier
                            .size(artworkSize)
                            .shadow(
                                elevation    = 6.dp,
                                shape        = RoundedCornerShape(8.dp),
                                clip         = false,
                                spotColor    = if (isDark) Color.Black.copy(alpha = 0.55f) else Color(0xFF2E1C15).copy(alpha = 0.20f),
                                ambientColor = if (isDark) Color.Black.copy(alpha = 0.25f) else Color(0xFF2E1C15).copy(alpha = 0.10f)
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF132B2D) else SonaraPalette.BoneSecondarySurface)
                            .border(0.75.dp, if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!state.artworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model              = ImageRequest.Builder(LocalContext.current)
                                    .data(state.artworkUrl)
                                    .crossfade(250)
                                    .build(),
                                contentDescription = "${state.trackTitle} artwork",
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text       = "♪",
                                fontSize   = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color      = SonaraPalette.OxideAccent
                            )
                        }
                    }

                    // Metadata Column: Optically Centered with Artwork with Smooth Crossfade
                    AnimatedContent(
                        targetState = state.trackTitle to (state.artistName to state.albumTitle),
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(150))
                        },
                        label = "MiniPlayerMetadataCrossfade",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp, end = 4.dp)
                    ) { (title, artistAlbum) ->
                        val (artist, album) = artistAlbum
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Track Title (15sp Bold, crisp white in Dark, dark in Light)
                            Text(
                                text          = title,
                                fontSize      = 15.sp,
                                fontWeight    = FontWeight.Bold,
                                color         = titleColor,
                                maxLines      = 1,
                                overflow      = TextOverflow.Ellipsis,
                                letterSpacing = (-0.2).sp
                            )

                            // Artist • Album subtitle (12sp, muted bone / dark secondary)
                            val subtitleText = buildString {
                                append(artist)
                                if (album.isNotBlank()) {
                                    append(" • ")
                                    append(album)
                                }
                            }
                            Text(
                                text       = subtitleText,
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color      = subtitleColor,
                                maxLines   = 1,
                                overflow   = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Audio Output Route Icon Button (Dedicated, accessible, non-intrusive)
                val activeOutputName = getLocalizedDeviceName(audioOutputState.activeDevice)
                val outputDesc = androidx.compose.ui.res.stringResource(
                    com.example.sonara.R.string.audio_output_current_output,
                    activeOutputName
                )
                SonaraIconButton(
                    onClick = onOpenAudioOutputSelector,
                    contentDescription = outputDesc,
                    variant = SonaraIconButtonVariant.Ghost,
                    size = 32.dp
                ) {
                    val isExternal = audioOutputState.activeDevice.type != com.example.sonara.domain.model.AudioDeviceType.PHONE_SPEAKER
                    androidx.compose.material3.Icon(
                        imageVector = getDeviceIcon(audioOutputState.activeDevice.type),
                        contentDescription = outputDesc,
                        tint = if (isExternal) SonaraPalette.OxideAccent else transportIconColor.copy(alpha = 0.65f),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // ===============================================================
            // TIER 3 -- Stronger Center Transport Grouping (Exactly Centered)
            // Shuffle  --[flankSpacing]--  [ Previous -[coreSpacing]- PLAY -[coreSpacing]- Next ]  --[flankSpacing]--  Repeat
            // ===============================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(flankSpacing)
                ) {
                    // Left Secondary Flank: Shuffle Icon
                    SonaraIconButton(
                        onClick            = { isShuffled = !isShuffled },
                        contentDescription = if (isShuffled) "Disable shuffle" else "Enable shuffle",
                        variant            = SonaraIconButtonVariant.Ghost,
                        size               = 36.dp
                    ) {
                        Icon(
                            imageVector        = PhosphorIcons.Shuffle,
                            contentDescription = if (isShuffled) "Disable shuffle" else "Enable shuffle",
                            tint               = if (isShuffled) SonaraPalette.OxideAccent else transportIconColor.copy(alpha = 0.80f),
                            modifier           = Modifier.size(21.dp)
                        )
                    }

                    // ── Central Cohesive Transport Core: Previous + Play/Pause (42dp) + Next ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(coreSpacing)
                    ) {
                        // Previous Track Icon
                        SonaraIconButton(
                            onClick            = onPrevious,
                            contentDescription = "Previous track",
                            variant            = SonaraIconButtonVariant.Ghost,
                            size               = 34.dp
                        ) {
                            Text(
                                text     = "⏮",
                                fontSize = 18.sp,
                                color    = transportIconColor
                            )
                        }

                        // Play/Pause -- Dominant Circular Oxide Accent Button (42dp) (FROZEN)
                        if (state.isBuffering) {
                            Box(
                                modifier         = Modifier.size(42.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                SonaraLoadingIndicator(size = 26.dp)
                            }
                        } else if (state.isPlaying) {
                            SonaraIconButton(
                                onClick            = onPause,
                                contentDescription = "Pause",
                                variant            = SonaraIconButtonVariant.Accent,
                                size               = 42.dp
                            ) {
                                PauseIcon(modifier = Modifier.size(16.dp))
                            }
                        } else {
                            SonaraIconButton(
                                onClick            = onPlay,
                                contentDescription = "Play",
                                variant            = SonaraIconButtonVariant.Accent,
                                size               = 42.dp
                            ) {
                                PlayIcon(modifier = Modifier.size(16.dp))
                            }
                        }

                        // Next Track Icon
                        SonaraIconButton(
                            onClick            = onNext,
                            contentDescription = "Next track",
                            variant            = SonaraIconButtonVariant.Ghost,
                            size               = 34.dp
                        ) {
                            Text(
                                text     = "⏭",
                                fontSize = 18.sp,
                                color    = transportIconColor
                            )
                        }
                    }

                    // Right Secondary Flank: Repeat Icon
                    val repeatIcon = if (repeatMode == 2) PhosphorIcons.RepeatOnce else PhosphorIcons.Repeat
                    SonaraIconButton(
                        onClick = {
                            repeatMode = (repeatMode + 1) % 3
                        },
                        contentDescription = when (repeatMode) {
                            1 -> "Repeat all"
                            2 -> "Repeat one"
                            else -> "Repeat off"
                        },
                        variant = SonaraIconButtonVariant.Ghost,
                        size    = 36.dp
                    ) {
                        Icon(
                            imageVector        = repeatIcon,
                            contentDescription = when (repeatMode) {
                                1 -> "Repeat all"
                                2 -> "Repeat one"
                                else -> "Repeat off"
                            },
                            tint               = if (repeatMode > 0) SonaraPalette.OxideAccent else transportIconColor.copy(alpha = 0.80f),
                            modifier           = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Liquid Segmented Progress Rail -- continuous liquid energy fill across
 * prominent segmented tick marks (3.2dp tick width, 13dp tick height). (FROZEN)
 */
@Composable
private fun LiquidSegmentedProgressRail(
    progress: Float,
    onSeek: (Float) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = Color(0xFFB85D38) // Crisp Oxide Accent
    val inactiveColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFF1B1F1E).copy(alpha = 0.15f)

    val currentOnSeek by rememberUpdatedState(onSeek)
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val effectiveProgress = if (isDragging) dragProgress else progress.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    if (width > 0f) {
                        val fraction = (down.position.x / width).coerceIn(0f, 1f)
                        isDragging = true
                        dragProgress = fraction
                        currentOnSeek(fraction)
                        down.consume()
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            isDragging = false
                            break
                        }

                        val width = size.width.toFloat()
                        if (width > 0f) {
                            val fraction = (change.position.x / width).coerceIn(0f, 1f)
                            dragProgress = fraction
                            currentOnSeek(fraction)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val segmentWidth = 3.2.dp.toPx()
        val segmentGap   = 3.2.dp.toPx()
        val segmentHeight = 13.dp.toPx()
        val step = segmentWidth + segmentGap

        val totalSegments = ((size.width + segmentGap) / step).toInt().coerceAtLeast(1)
        val yTop = (size.height - segmentHeight) / 2f
        val cornerRadius = CornerRadius(segmentWidth / 2f, segmentWidth / 2f)

        val fillX = (size.width * effectiveProgress.coerceIn(0f, 1f))

        // 1. Draw all inactive segments
        for (i in 0 until totalSegments) {
            val xLeft = i * step
            if (xLeft + segmentWidth > size.width) break
            drawRoundRect(
                color        = inactiveColor,
                topLeft      = Offset(xLeft, yTop),
                size         = Size(segmentWidth, segmentHeight),
                cornerRadius = cornerRadius
            )
        }

        // 2. Continuous sub-pixel liquid fill clipped exactly to fill boundary
        if (fillX > 0f) {
            clipRect(left = 0f, top = 0f, right = fillX, bottom = size.height) {
                for (i in 0 until totalSegments) {
                    val xLeft = i * step
                    if (xLeft > fillX) break
                    drawRoundRect(
                        color        = activeColor,
                        topLeft      = Offset(xLeft, yTop),
                        size         = Size(segmentWidth, segmentHeight),
                        cornerRadius = cornerRadius
                    )
                }
            }
        }
    }
}

// ── Crisp High-Visibility Custom Vector Icons (FROZEN) ──

@Composable
private fun PauseIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val barWidth = 4.dp.toPx()
        val barHeight = 14.dp.toPx()
        val gap = 4.5.dp.toPx()
        val totalW = barWidth * 2 + gap
        val xStart = (size.width - totalW) / 2f
        val yStart = (size.height - barHeight) / 2f
        val r = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx())

        // Left bar
        drawRoundRect(color = color, topLeft = Offset(xStart, yStart), size = Size(barWidth, barHeight), cornerRadius = r)
        // Right bar
        drawRoundRect(color = color, topLeft = Offset(xStart + barWidth + gap, yStart), size = Size(barWidth, barHeight), cornerRadius = r)
    }
}

@Composable
private fun PlayIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val h = 15.dp.toPx()
        val w = 13.dp.toPx()
        val xStart = (size.width - w) / 2f + 1.2.dp.toPx()
        val yStart = (size.height - h) / 2f

        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(xStart, yStart)
            lineTo(xStart + w, yStart + h / 2f)
            lineTo(xStart, yStart + h)
            close()
        }
        drawPath(path = path, color = color)
    }
}

/** Format seconds to mm:ss string, matching Web Desktop PlaybackProgress formatTime() (FROZEN) */
private fun formatTime(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0:00"
    val m = totalSeconds / 60L
    val s = totalSeconds % 60L
    val sStr = if (s < 10L) "0$s" else "$s"
    return "$m:$sStr"
}
