package com.example.sonara.feature.player.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import com.example.sonara.domain.model.LyricWord
import com.example.sonara.domain.model.Track
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sonara.core.ui.motion.LocalReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraCard
import com.example.sonara.core.ui.components.SonaraEmptyState
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.components.SonaraLoadingIndicator
import com.example.sonara.core.ui.theme.SonaraPalette
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.feature.lyrics.LyricsUiState
import com.example.sonara.feature.player.PlayerUiState

// ── Web Transition Baseline Spec ──
// Web Entrance: translateY(60%) -> translateY(0) + opacity (0 -> 1) in 450ms (cubic-bezier(0.2, 0.8, 0.2, 1))
// Web Exit:     translateY(0) -> translateY(60%) + opacity (1 -> 0) in 400ms (cubic-bezier(0.2, 0.8, 0.2, 1))
private val WebTransitionEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f)
private val WebEnterSlide = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 450, easing = WebTransitionEasing)
private val WebExitSlide  = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 400, easing = WebTransitionEasing)
private val WebEnterFade  = tween<Float>(durationMillis = 450, easing = WebTransitionEasing)
private val WebExitFade   = tween<Float>(durationMillis = 400, easing = WebTransitionEasing)

enum class ExpandedPlayerViewMode {
    PLAYER,
    LYRICS
}

private data class TrackTransitionSnapshot(
    val trackId: String?,
    val title: String,
    val artist: String,
    val artworkUrl: String?
)

private fun Track.toTransitionSnapshot(): TrackTransitionSnapshot =
    TrackTransitionSnapshot(trackId = id, title = title, artist = artist, artworkUrl = artworkUrl)

/** Outcome of a released swipe: commit forward (next), commit backward (previous), or snap back. */
private enum class SkipIntent { NEXT, PREVIOUS, CANCEL }

/**
 * Resolved graphicsLayer values for the two artwork/metadata layers at a given transition progress.
 *  - `top*`   describe the OUTGOING/resting song A (drawn on top).
 *  - `under*` describe the INCOMING song B (drawn beneath A, physically "underneath").
 */
private data class TransitionLayers(
    val topTranslationX: Float,
    val topTranslationY: Float,
    val topAlpha: Float,
    val underTranslationX: Float,
    val underTranslationY: Float,
    val underAlpha: Float
)

/**
 * "Fall Away / Opposite Diagonal Entry" — the Expanded Player's gesture-driven song-switch physics.
 *
 * This is a LOCAL player interaction, deliberately NOT part of
 * [com.example.sonara.core.ui.motion.SonaraMotion] (that navigation vocabulary is frozen to two
 * primitives and forbids adding a third). It models a single, continuously gesture-controlled
 * transition:
 *  - The outgoing song A follows the finger horizontally, sinks downward, and fades out ("falls away").
 *    Swipe left: falls toward bottom-left. Swipe right: falls toward bottom-right.
 *  - The incoming song B enters along the exact opposite diagonal toward resting position (0, 0).
 *    Swipe left: enters from bottom-right (X > 0, Y > 0) -> (0, 0).
 *    Swipe right: enters from bottom-left (X < 0, Y > 0) -> (0, 0).
 *    B is revealed and moves DURING the drag, not after A is gone.
 *
 * All functions are pure math (no Compose types) so the physics is unit-testable on the JVM, mirroring
 * the existing `SwipeGestureHelper` convention.
 */
private object FallAwayRiseUp {

    /**
     * Commit decision from displacement OR release velocity, mirrored for both directions. A fast flick
     * commits with little distance; a slow deliberate drag commits by passing the distance threshold.
     * This only classifies intent — it never produces motion, so it cannot overshoot.
     */
    fun decideCommit(
        offsetX: Float,
        velocityX: Float,
        distanceThresholdPx: Float,
        velocityThresholdPx: Float,
        minFlingDistancePx: Float = 20f
    ): SkipIntent {
        val leftByDistance = offsetX <= -distanceThresholdPx
        val leftByVelocity = offsetX < -minFlingDistancePx && velocityX <= -velocityThresholdPx
        val rightByDistance = offsetX >= distanceThresholdPx
        val rightByVelocity = offsetX > minFlingDistancePx && velocityX >= velocityThresholdPx
        return when {
            leftByDistance || leftByVelocity -> SkipIntent.NEXT
            rightByDistance || rightByVelocity -> SkipIntent.PREVIOUS
            else -> SkipIntent.CANCEL
        }
    }

    /** Normalised transition progress in [0,1] from the signed horizontal finger displacement. */
    fun progressForOffset(offsetX: Float, spanPx: Float): Float {
        if (spanPx <= 0f) return 0f
        return (kotlin.math.abs(offsetX) / spanPx).coerceIn(0f, 1f)
    }

    /** Smoothstep ease so the vertical rise/fall decelerates into rest without overshoot. */
    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Resolve both layers for the current [offsetX]/[progress].
     *
     * Reduced motion collapses to an opacity-only replacement (no translation at all): A fades out and
     * B fades in over the same progress, matching the app's reduced-motion contract of "near-immediate
     * replacement, no large translations".
     */
    fun layers(
        offsetX: Float,
        progress: Float,
        maxFallPx: Float,
        maxRisePx: Float,
        maxEntryXPx: Float,
        reduceMotion: Boolean
    ): TransitionLayers {
        val p = progress.coerceIn(0f, 1f)
        if (reduceMotion) {
            return TransitionLayers(
                topTranslationX = 0f,
                topTranslationY = 0f,
                topAlpha = (1f - p).coerceIn(0f, 1f),
                underTranslationX = 0f,
                underTranslationY = 0f,
                underAlpha = p
            )
        }
        // A: fully faded slightly before commit completes, so it is gone (not lingering) at rest.
        val topAlpha = (1f - p / 0.9f).coerceIn(0f, 1f)
        val topTranslationY = p * maxFallPx

        // B: enters along the opposite diagonal toward its resting position (0, 0).
        // If offsetX < 0 (swipe left/next): B enters from bottom-right (+X, +Y) -> (0, 0).
        // If offsetX > 0 (swipe right/previous): B enters from bottom-left (-X, +Y) -> (0, 0).
        val horizontalSign = when {
            offsetX < 0f -> 1f
            offsetX > 0f -> -1f
            else -> 0f
        }
        val remaining = 1f - smooth(p)
        val underTranslationX = horizontalSign * remaining * maxEntryXPx
        val underTranslationY = remaining * maxRisePx
        val underAlpha = smooth(p / 0.75f)
        return TransitionLayers(
            topTranslationX = offsetX,
            topTranslationY = topTranslationY,
            topAlpha = topAlpha,
            underTranslationX = underTranslationX,
            underTranslationY = underTranslationY,
            underAlpha = underAlpha
        )
    }

    /**
     * Settle duration (ms) for completing the remaining travel after release. High release velocity
     * shortens it toward `distance / velocity` (physical continuity — the flick keeps its speed); a
     * slow release falls back to [baseMs]. Always clamped to [minMs]..[maxMs]; never negative, and the
     * caller animates to an exact target with a decelerating tween so there is no overshoot.
     */
    fun settleDurationMs(
        remainingPx: Float,
        velocityAbsPxPerSec: Float,
        minMs: Int,
        maxMs: Int,
        baseMs: Int
    ): Int {
        if (remainingPx <= 0f) return minMs
        val byVelocity = if (velocityAbsPxPerSec > 1f) {
            (remainingPx / velocityAbsPxPerSec * 1000f).toInt()
        } else {
            baseMs
        }
        return byVelocity.coerceIn(minMs, maxMs)
    }
}

// ── "Fall Away / Rise Up" tunables (dial these on-device; see §21/§22 verification) ──
private const val FA_SPAN_FRACTION = 0.85f            // finger travel mapped to full progress (× artwork width)
private const val FA_COMMIT_DISTANCE_FRACTION = 0.32f // slow-drag commit threshold (× artwork width)
private const val FA_VELOCITY_THRESHOLD_DP = 520f     // flick commit threshold (dp/s)
private const val FA_MAX_FALL_DP = 130f               // A downward travel at full progress
private const val FA_MAX_RISE_DP = 88f                // B upward travel from beneath toward rest
private const val FA_MAX_ENTRY_X_DP = 110f            // B opposite horizontal travel toward rest
private const val FA_METADATA_TRAVEL_FRACTION = 0.45f // metadata travels less than artwork, same identity/alpha
private const val FA_DIRECTION_DEADZONE_DP = 6f       // min displacement before a direction (and neighbour) is fixed
private const val FA_PREVIOUS_RESTART_THRESHOLD_MS = 3000L
private const val FA_COMMIT_MIN_MS = 90
private const val FA_COMMIT_MAX_MS = 260
private const val FA_COMMIT_BASE_MS = 220
private const val FA_CANCEL_MS = 200
private const val FA_BUTTON_MS = 300                  // neutral-start transition for transport buttons / a11y
private const val FA_SWAP_OUT_MS = 110               // graceful fade for external / no-neighbour identity changes
private const val FA_SWAP_IN_MS = 150
private const val FA_EDGE_FALLBACK_DP = 16f          // used when system-gesture insets report zero

/**
 * A single artwork tile at the player's resting geometry — the identical rounded card, border, shadow,
 * and fallback glyph the Expanded Player has always drawn. Rendered once per visible transition layer
 * (top = outgoing A, under = incoming B); the caller supplies a [graphicsLayer] modifier carrying that
 * layer's fall/rise. Artwork crossfade is off on purpose: the "Fall Away / Rise Up" motion — not a
 * dissolve, scale, blur, or 3-D trick — is what carries the change. Artwork is the primary anchor.
 */
@Composable
private fun ArtworkCard(
    snapshot: TrackTransitionSnapshot,
    isPlaying: Boolean,
    isDark: Boolean,
    breatheGlow: Float,
    artworkSize: Dp,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(artworkSize)
            .shadow(
                elevation = if (isPlaying) 28.dp else 12.dp,
                shape = RoundedCornerShape(16.dp),
                clip = false,
                spotColor = if (isPlaying) SonaraPalette.OxideAccent.copy(alpha = breatheGlow * 1.5f) else Color.Black.copy(alpha = 0.40f),
                ambientColor = if (isPlaying) SonaraPalette.OxideAccent.copy(alpha = breatheGlow) else Color.Black.copy(alpha = 0.20f)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant)
            .border(
                width = 1.dp,
                color = if (isDark) Color(0xFF264043).copy(alpha = 0.60f) else SonaraPalette.BoneBorder.copy(alpha = 0.85f),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        if (!snapshot.artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(snapshot.artworkUrl)
                    .crossfade(false)
                    .build(),
                contentDescription = snapshot.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "♪",
                    fontSize = 48.sp,
                    color = colors.secondaryText.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Title + artist for one song identity, at the Expanded Player's usual metadata typography. Rendered
 * once per visible transition layer so the metadata always belongs to the SAME song as the artwork it
 * accompanies (never A-artwork + B-title); the caller supplies the per-layer [graphicsLayer].
 */
@Composable
private fun TrackMetadata(
    snapshot: TrackTransitionSnapshot,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    Column(modifier = modifier) {
        Text(
            text = snapshot.title,
            style = typography.display.copy(
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold
            ),
            color = colors.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = snapshot.artist,
            style = typography.artistMetadata.copy(
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium
            ),
            color = colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Sonara Android Expanded Player.
 *
 * Direct native Compose implementation of the Web Expanded Player (ExpandedPlayer.jsx / ExpandedPlayer.css).
 * Source of truth: Visual hierarchy, artwork prominence, breathing glow, progress rail, transport controls,
 * dedicated smooth volume slider, synchronized lyrics with Romanization, and Petrol/Bone atmospheric backdrop.
 */
@Composable
fun ExpandedPlayerScreen(
    isExpanded: Boolean,
    playerState: PlayerUiState,
    lyricsState: LyricsUiState,
    onCollapse: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    peekNextTrack: () -> Track? = { null },
    peekPreviousTrack: () -> Track? = { null },
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddPlaylist: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleRomanization: () -> Unit,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onToggleDownload: () -> Unit = {},
    audioOutputState: com.example.sonara.domain.model.AudioOutputState = com.example.sonara.domain.model.AudioOutputState(),
    onOpenAudioOutputSelector: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Intercept system back button
    BackHandler(enabled = isExpanded) {
        onCollapse()
    }

    AnimatedVisibility(
        visible = isExpanded,
        enter = slideInVertically(animationSpec = WebEnterSlide) { fullHeight -> (fullHeight * 0.60f).toInt() } +
                fadeIn(animationSpec = WebEnterFade),
        exit = slideOutVertically(animationSpec = WebExitSlide) { fullHeight -> (fullHeight * 0.60f).toInt() } +
               fadeOut(animationSpec = WebExitFade),
        modifier = modifier.fillMaxSize()
    ) {
        ExpandedPlayerContent(
            playerState = playerState,
            lyricsState = lyricsState,
            onCollapse = onCollapse,
            onPlay = onPlay,
            onPause = onPause,
            onNext = onNext,
            onPrevious = onPrevious,
            peekNextTrack = peekNextTrack,
            peekPreviousTrack = peekPreviousTrack,
            onSeek = onSeek,
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat,
            onToggleFavorite = onToggleFavorite,
            onAddPlaylist = onAddPlaylist,
            onVolumeChange = onVolumeChange,
            onToggleMute = onToggleMute,
            onToggleRomanization = onToggleRomanization,
            isDownloaded = isDownloaded,
            isDownloading = isDownloading,
            onToggleDownload = onToggleDownload,
            audioOutputState = audioOutputState,
            onOpenAudioOutputSelector = onOpenAudioOutputSelector
        )
    }
}

@Composable
private fun ExpandedPlayerContent(
    playerState: PlayerUiState,
    lyricsState: LyricsUiState,
    onCollapse: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    peekNextTrack: () -> Track? = { null },
    peekPreviousTrack: () -> Track? = { null },
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddPlaylist: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleRomanization: () -> Unit,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onToggleDownload: () -> Unit = {},
    audioOutputState: com.example.sonara.domain.model.AudioOutputState = com.example.sonara.domain.model.AudioOutputState(),
    onOpenAudioOutputSelector: () -> Unit = {}
) {
    val context = LocalContext.current
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val isDark = colors.isDark

    var viewMode by remember { mutableStateOf(ExpandedPlayerViewMode.PLAYER) }

    // Reset to PLAYER view on track change
    LaunchedEffect(playerState.trackId) {
        viewMode = ExpandedPlayerViewMode.PLAYER
    }

    // Breathing glow animation when playing
    val infiniteTransition = rememberInfiniteTransition(label = "ArtworkBreathe")
    val breatheGlow by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BreatheGlowAlpha"
    )

    val surfaceColor = if (isDark) Color(0xFF071A1C) else SonaraPalette.BoneCanvas
    val auroraColor = if (isDark) Color(0xFF0D282B) else SonaraPalette.BoneElevatedSurface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceColor)
    ) {
        // ── Atmospheric Background Layer ──
        Box(modifier = Modifier.fillMaxSize()) {
            // Blurred Artwork Underlay with Native Crossfade
            if (!playerState.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(playerState.artworkUrl)
                        .crossfade(250)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(0.dp)),
                    alpha = if (isDark) 0.22f else 0.12f
                )
            }

            // Midnight Aurora Gradient Wash
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                auroraColor.copy(alpha = if (isDark) 0.85f else 0.90f),
                                auroraColor.copy(alpha = if (isDark) 0.40f else 0.50f),
                                surfaceColor.copy(alpha = if (isDark) 0.92f else 0.96f),
                                surfaceColor
                            )
                        )
                    )
            )

            // Subtle Top-Left Light Wash
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                SonaraPalette.OxideAccent.copy(alpha = if (isDark) 0.10f else 0.05f),
                                Color.Transparent
                            ),
                            radius = 900f
                        )
                    )
            )
        }

        // ── Main UI Layout (with status bar & nav bar insets) ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── TOP BAR (Minimize + NOW PLAYING + View / Romanize Actions) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Minimize Button
                SonaraIconButton(
                    onClick = onCollapse,
                    contentDescription = "Minimize",
                    variant = SonaraIconButtonVariant.Ghost,
                    size = 42.dp
                ) {
                    Icon(
                        imageVector = PhosphorIcons.CornersIn,
                        contentDescription = "Minimize",
                        tint = colors.primaryText,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 1. INCREASED FONT SIZE: Header Label "NOW PLAYING"
                Text(
                    text = "NOW PLAYING",
                    style = typography.caption.copy(
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    ),
                    color = colors.secondaryText.copy(alpha = 0.90f)
                )

                // Right Actions (Romanize toggle + Lyrics/Player toggle)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (viewMode == ExpandedPlayerViewMode.LYRICS) {
                        val isRomanized = when (lyricsState) {
                            is LyricsUiState.Synced -> lyricsState.isRomanized
                            is LyricsUiState.Plain -> lyricsState.isRomanized
                            else -> false
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isRomanized) SonaraPalette.OxideAccent else colors.surfaceVariant)
                                .clickable(onClick = onToggleRomanization),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "aA",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRomanized) Color.White else colors.primaryText
                            )
                        }
                    }

                    // View Mode Switcher Button
                    SonaraIconButton(
                        onClick = {
                            viewMode = if (viewMode == ExpandedPlayerViewMode.PLAYER) {
                                ExpandedPlayerViewMode.LYRICS
                            } else {
                                ExpandedPlayerViewMode.PLAYER
                            }
                        },
                        contentDescription = if (viewMode == ExpandedPlayerViewMode.PLAYER) "Show Lyrics" else "Show Player",
                        variant = SonaraIconButtonVariant.Ghost,
                        size = 42.dp
                    ) {
                        Icon(
                            imageVector = if (viewMode == ExpandedPlayerViewMode.PLAYER) PhosphorIcons.TextAa else PhosphorIcons.MusicNote,
                            contentDescription = if (viewMode == ExpandedPlayerViewMode.PLAYER) "Show Lyrics" else "Show Player",
                            tint = colors.primaryText,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // ── BODY CONTENT AREA (Animated View Switching) ──
            AnimatedContent(
                targetState = viewMode,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(250))
                },
                label = "ExpandedPlayerViewModeSwitch",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { mode ->
                when (mode) {
                    ExpandedPlayerViewMode.PLAYER -> {
                        PlayerViewContent(
                            playerState = playerState,
                            isPlaying = playerState.isPlaying,
                            isDark = isDark,
                            breatheGlow = breatheGlow,
                            onPlay = onPlay,
                            onPause = onPause,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            peekNextTrack = peekNextTrack,
                            peekPreviousTrack = peekPreviousTrack,
                            onSeek = onSeek,
                            onToggleShuffle = onToggleShuffle,
                            onToggleRepeat = onToggleRepeat,
                            onToggleFavorite = onToggleFavorite,
                            onAddPlaylist = onAddPlaylist,
                            onVolumeChange = onVolumeChange,
                            onToggleMute = onToggleMute,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            onToggleDownload = onToggleDownload,
                            audioOutputState = audioOutputState,
                            onOpenAudioOutputSelector = onOpenAudioOutputSelector
                        )
                    }
                    ExpandedPlayerViewMode.LYRICS -> {
                        LyricsViewContent(
                            state = lyricsState,
                            playerState = playerState,
                            onSeek = onSeek
                        )
                    }
                }
            }
        }
    }
}

private data class ExpandedLayoutMetrics(
    val artworkSize: Dp,
    val artworkToMetaGap: Dp,
    val metaToProgressGap: Dp,
    val progressToTransportGap: Dp,
    val transportToVolumeGap: Dp
)

/**
 * Main Player View: Centered Composition with Dominant Artwork, Clear Controls Hierarchy & Volume Row.
 */
@Composable
private fun PlayerViewContent(
    playerState: PlayerUiState,
    isPlaying: Boolean,
    isDark: Boolean,
    breatheGlow: Float,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    peekNextTrack: () -> Track? = { null },
    peekPreviousTrack: () -> Track? = { null },
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddPlaylist: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onToggleDownload: () -> Unit = {},
    audioOutputState: com.example.sonara.domain.model.AudioOutputState = com.example.sonara.domain.model.AudioOutputState(),
    onOpenAudioOutputSelector: () -> Unit = {}
) {
    val context = LocalContext.current
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val availableHeight = maxHeight

        // Balanced scaling ensuring artwork is close to screen center with natural spacing
        val metrics = when {
            availableHeight < 560.dp -> ExpandedLayoutMetrics(
                artworkSize = 190.dp,
                artworkToMetaGap = 14.dp,
                metaToProgressGap = 10.dp,
                progressToTransportGap = 10.dp,
                transportToVolumeGap = 10.dp
            )
            availableHeight < 680.dp -> ExpandedLayoutMetrics(
                artworkSize = 235.dp,
                artworkToMetaGap = 20.dp,
                metaToProgressGap = 14.dp,
                progressToTransportGap = 12.dp,
                transportToVolumeGap = 14.dp
            )
            else -> ExpandedLayoutMetrics(
                artworkSize = 270.dp,
                artworkToMetaGap = 24.dp,
                metaToProgressGap = 16.dp,
                progressToTransportGap = 14.dp,
                transportToVolumeGap = 18.dp
            )
        }

        // ── "Fall Away / Rise Up" swipe-to-skip state (Expanded-Player-local, gesture-driven) ──
        val reduceMotion = LocalReduceMotion.current
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current

        val artworkWidthPx = with(density) { metrics.artworkSize.toPx() }
        val spanPx = artworkWidthPx * FA_SPAN_FRACTION
        val commitDistancePx = artworkWidthPx * FA_COMMIT_DISTANCE_FRACTION
        val velocityThresholdPx = with(density) { FA_VELOCITY_THRESHOLD_DP.dp.toPx() }
        val maxFallPx = with(density) { FA_MAX_FALL_DP.dp.toPx() }
        val maxRisePx = with(density) { FA_MAX_RISE_DP.dp.toPx() }
        val maxEntryXPx = with(density) { FA_MAX_ENTRY_X_DP.dp.toPx() }
        val deadzonePx = with(density) { FA_DIRECTION_DEADZONE_DP.dp.toPx() }
        val edgeFallbackPx = with(density) { FA_EDGE_FALLBACK_DP.dp.toPx() }

        // Cooperative Android system-gesture bands: never begin a skip inside the edge strips the OS
        // reserves for the Back gesture. Read live; fall back to a fixed band if they report zero.
        val systemGestureInsets = WindowInsets.systemGestures
        val leftExclusionPx = maxOf(
            systemGestureInsets.getLeft(density, layoutDirection).toFloat(),
            edgeFallbackPx
        )
        val rightExclusionPx = maxOf(
            systemGestureInsets.getRight(density, layoutDirection).toFloat(),
            edgeFallbackPx
        )

        // Signed horizontal finger displacement — the single timeline that drives BOTH layers.
        val drag = remember { Animatable(0f) }
        // Graceful-crossfade alpha for identity changes we did NOT drive with a gesture.
        val swapAlpha = remember { Animatable(1f) }
        val velocityTracker = remember { VelocityTracker() }

        // Live identity of the currently-playing track (derived from playback state).
        val livePlayerSnapshot = remember(
            playerState.trackId,
            playerState.trackTitle,
            playerState.artistName,
            playerState.artworkUrl
        ) {
            TrackTransitionSnapshot(
                trackId = playerState.trackId,
                title = playerState.trackTitle,
                artist = playerState.artistName,
                artworkUrl = playerState.artworkUrl
            )
        }

        // Two layers: `top` = resting/outgoing song A, `under` = incoming song B (physically beneath A).
        var topSnapshot by remember { mutableStateOf(livePlayerSnapshot) }
        var underSnapshot by remember { mutableStateOf<TrackTransitionSnapshot?>(null) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        // Whether the in-flight settle resolves to a committed skip (promote B) or a cancel (keep A).
        var inFlightCommit by remember { mutableStateOf(false) }

        // Keep the long-lived pointer coroutine reading the latest callbacks/state without restarting.
        val latestPlayerState by rememberUpdatedState(playerState)
        val latestPeekNext by rememberUpdatedState(peekNextTrack)
        val latestPeekPrevious by rememberUpdatedState(peekPreviousTrack)
        val latestOnNext by rememberUpdatedState(onNext)
        val latestOnPrevious by rememberUpdatedState(onPrevious)

        // Finalize any in-flight settle instantly (cancel/replace — never queue) so a new gesture starts
        // from a clean base. Promotes B only if the interrupted settle was a commit.
        fun finalizeInFlight() {
            settleJob?.cancel()
            settleJob = null
            val neighbor = underSnapshot
            if (inFlightCommit && neighbor != null) {
                topSnapshot = neighbor
            }
            inFlightCommit = false
            underSnapshot = null
            scope.launch { drag.snapTo(0f) }
        }

        // Commit a skip: fire playback, then complete the remaining travel from the finger's current
        // position (no jump-to-zero) and promote B onto the top layer once A has fully fallen away.
        fun commitSkip(forward: Boolean, releaseVelocityX: Float) {
            if (forward) latestOnNext() else latestOnPrevious()
            val neighbor = underSnapshot
            if (reduceMotion || neighbor == null) {
                // No revealed neighbour (async recommendation, or previous == restart): don't fall away.
                // Return the anchor to rest; the reconcile effect adopts the real track when it lands.
                // Under reduced motion with a known neighbour, replace immediately (no fall, no flash).
                if (reduceMotion && neighbor != null) topSnapshot = neighbor
                underSnapshot = null
                inFlightCommit = false
                settleJob?.cancel()
                settleJob = scope.launch {
                    if (reduceMotion) drag.snapTo(0f)
                    else drag.animateTo(0f, tween(FA_CANCEL_MS, easing = FastOutSlowInEasing))
                }
                return
            }
            val target = if (forward) -spanPx else spanPx
            val remaining = kotlin.math.abs(target - drag.value)
            val duration = FallAwayRiseUp.settleDurationMs(
                remainingPx = remaining,
                velocityAbsPxPerSec = kotlin.math.abs(releaseVelocityX),
                minMs = FA_COMMIT_MIN_MS,
                maxMs = FA_COMMIT_MAX_MS,
                baseMs = FA_COMMIT_BASE_MS
            )
            inFlightCommit = true
            settleJob?.cancel()
            settleJob = scope.launch {
                drag.animateTo(target, tween(duration, easing = FastOutSlowInEasing))
                topSnapshot = neighbor
                underSnapshot = null
                inFlightCommit = false
                drag.snapTo(0f)
            }
        }

        // Cancelled swipe: reverse naturally back to A. No playback change; B sinks back and fades out.
        fun settleCancel() {
            inFlightCommit = false
            settleJob?.cancel()
            settleJob = scope.launch {
                if (reduceMotion) drag.snapTo(0f)
                else drag.animateTo(0f, tween(FA_CANCEL_MS, easing = FastOutSlowInEasing))
                underSnapshot = null
            }
        }

        // Transport buttons / a11y actions: same physics with a neutral (straight-up) start.
        fun buttonSkip(forward: Boolean) {
            finalizeInFlight()
            val neighbor = if (forward) {
                latestPeekNext()?.toTransitionSnapshot()
            } else if (latestPlayerState.currentPositionMs <= FA_PREVIOUS_RESTART_THRESHOLD_MS) {
                latestPeekPrevious()?.toTransitionSnapshot()
            } else {
                null
            }
            if (forward) latestOnNext() else latestOnPrevious()
            if (reduceMotion || neighbor == null) return  // reconcile effect adopts the new track
            underSnapshot = neighbor
            inFlightCommit = true
            settleJob?.cancel()
            settleJob = scope.launch {
                drag.snapTo(0f)
                drag.animateTo(
                    if (forward) -spanPx else spanPx,
                    tween(FA_BUTTON_MS, easing = FastOutSlowInEasing)
                )
                topSnapshot = neighbor
                underSnapshot = null
                inFlightCommit = false
                drag.snapTo(0f)
            }
        }

        // Adopt track changes we did NOT drive (autoplay-next, external control, or an async
        // recommendation landing after a null-neighbour commit) — but only while idle, so an active
        // gesture/settle stays authoritative and converges via promote.
        LaunchedEffect(livePlayerSnapshot) {
            if (drag.value != 0f || settleJob?.isActive == true || underSnapshot != null) return@LaunchedEffect
            if (topSnapshot == livePlayerSnapshot) return@LaunchedEffect
            val sameTrack = topSnapshot.trackId == livePlayerSnapshot.trackId
            val fromNothing = topSnapshot.trackId.isNullOrBlank()
            if (reduceMotion || sameTrack || fromNothing) {
                topSnapshot = livePlayerSnapshot
            } else {
                swapAlpha.snapTo(1f)
                swapAlpha.animateTo(0f, tween(FA_SWAP_OUT_MS, easing = FastOutLinearInEasing))
                topSnapshot = livePlayerSnapshot
                swapAlpha.animateTo(1f, tween(FA_SWAP_IN_MS, easing = FastOutSlowInEasing))
            }
        }

        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .offset(y = (-14).dp)
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── 1. ARTWORK & SWIPE ZONE CONTAINER ──
            // Sized to fillMaxWidth() with height matching the artwork plus comfortable vertical padding.
            // Catches horizontal swipe gestures starting both INSIDE and OUTSIDE the artwork,
            // while leaving the progress rail, transport controls, and volume slider completely untouched.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.artworkSize + 16.dp)
                    .pointerInput(reduceMotion, metrics.artworkSize) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val containerWidthPx = size.width.toFloat()

                            // Cooperate with the OS Back gesture: never begin a skip inside the reserved
                            // system-gesture edge bands (widened intentional corridor everywhere else).
                            if (startX < leftExclusionPx || startX > containerWidthPx - rightExclusionPx) {
                                return@awaitEachGesture
                            }

                            // Take over instantly from any in-flight settle (cancel/replace, never queue).
                            finalizeInFlight()
                            velocityTracker.resetTracking()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            var totalAbsX = 0f
                            var totalAbsY = 0f
                            var directionResolved = false

                            val dragCompleted = drag(down.id) { change ->
                                val dx = change.positionChange().x
                                val dy = change.positionChange().y
                                totalAbsX += kotlin.math.abs(dx)
                                totalAbsY += kotlin.math.abs(dy)

                                // Let a clearly-vertical gesture through (pull-to-dismiss / scroll)
                                // until a horizontal direction has locked in.
                                if (!directionResolved && totalAbsY > totalAbsX * 1.5f && totalAbsX < deadzonePx * 3f) {
                                    return@drag
                                }

                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)

                                val projected = drag.value + dx
                                if (!directionResolved && kotlin.math.abs(projected) >= deadzonePx) {
                                    directionResolved = true
                                }
                                if (directionResolved) {
                                    // Reveal the correct neighbour BENEATH the anchor as soon as the
                                    // direction is known. Left → next, right → previous (unless the
                                    // 3000ms rule makes "previous" a restart, which has no neighbour).
                                    val desiredUnder = when {
                                        projected < 0f -> latestPeekNext()?.toTransitionSnapshot()
                                        projected > 0f &&
                                            latestPlayerState.currentPositionMs <= FA_PREVIOUS_RESTART_THRESHOLD_MS ->
                                            latestPeekPrevious()?.toTransitionSnapshot()
                                        else -> null
                                    }
                                    if (desiredUnder?.trackId != underSnapshot?.trackId) {
                                        underSnapshot = desiredUnder
                                    }
                                }

                                // Gesture progress IS the timeline — snap the anchor to the finger.
                                scope.launch {
                                    drag.snapTo((drag.value + dx).coerceIn(-spanPx, spanPx))
                                }
                            }

                            if (!dragCompleted) {
                                settleCancel()
                            } else {
                                val velocityX = velocityTracker.calculateVelocity().x
                                when (
                                    FallAwayRiseUp.decideCommit(
                                        offsetX = drag.value,
                                        velocityX = velocityX,
                                        distanceThresholdPx = commitDistancePx,
                                        velocityThresholdPx = velocityThresholdPx
                                    )
                                ) {
                                    SkipIntent.NEXT -> commitSkip(forward = true, releaseVelocityX = velocityX)
                                    SkipIntent.PREVIOUS -> commitSkip(forward = false, releaseVelocityX = velocityX)
                                    SkipIntent.CANCEL -> settleCancel()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // UNDER layer — incoming song B, physically beneath A. Rises up + fades in as A falls.
                val under = underSnapshot
                if (under != null) {
                    ArtworkCard(
                        snapshot = under,
                        isPlaying = isPlaying,
                        isDark = isDark,
                        breatheGlow = breatheGlow,
                        artworkSize = metrics.artworkSize,
                        modifier = Modifier.graphicsLayer {
                            val p = FallAwayRiseUp.progressForOffset(drag.value, spanPx)
                            val l = FallAwayRiseUp.layers(drag.value, p, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion)
                            translationX = l.underTranslationX
                            translationY = l.underTranslationY
                            alpha = l.underAlpha
                        }
                    )
                }
                // TOP layer — resting/outgoing song A, the primary anchor. Follows the finger + falls away.
                ArtworkCard(
                    snapshot = topSnapshot,
                    isPlaying = isPlaying,
                    isDark = isDark,
                    breatheGlow = breatheGlow,
                    artworkSize = metrics.artworkSize,
                    modifier = Modifier
                        .graphicsLayer {
                            val p = FallAwayRiseUp.progressForOffset(drag.value, spanPx)
                            val l = FallAwayRiseUp.layers(drag.value, p, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion)
                            translationX = l.topTranslationX
                            translationY = l.topTranslationY
                            alpha = l.topAlpha * swapAlpha.value
                        }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Next track") {
                                    buttonSkip(forward = true)
                                    true
                                },
                                CustomAccessibilityAction("Previous track") {
                                    buttonSkip(forward = false)
                                    true
                                }
                            )
                        }
                )
            }

        Spacer(modifier = Modifier.height(metrics.artworkToMetaGap))

            // ── 2. METADATA & QUICK ACTIONS ROW (Song name & Artist name increased by 1 notch) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    // Title/artist travel coherently with their OWN artwork identity — the incoming
                    // metadata belongs to B, the outgoing to A (never A-artwork + B-title). Vertical
                    // travel only (a scaled echo of the fall/rise); no horizontal finger-follow, so the
                    // text never slides sideways under the quick-action buttons.
                    val under = underSnapshot
                    if (under != null) {
                        TrackMetadata(
                            snapshot = under,
                            modifier = Modifier.graphicsLayer {
                                val p = FallAwayRiseUp.progressForOffset(drag.value, spanPx)
                                val l = FallAwayRiseUp.layers(drag.value, p, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion)
                                translationY = l.underTranslationY * FA_METADATA_TRAVEL_FRACTION
                                alpha = l.underAlpha
                            }
                        )
                    }
                    TrackMetadata(
                        snapshot = topSnapshot,
                        modifier = Modifier.graphicsLayer {
                            val p = FallAwayRiseUp.progressForOffset(drag.value, spanPx)
                            val l = FallAwayRiseUp.layers(drag.value, p, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion)
                            translationY = l.topTranslationY * FA_METADATA_TRAVEL_FRACTION
                            alpha = l.topAlpha * swapAlpha.value
                        }
                    )
                }

                // Quick Action Buttons (Favorite + Add to Playlist)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Favorite Heart Button
                    SonaraIconButton(
                        onClick = onToggleFavorite,
                        contentDescription = "Toggle Favorite",
                        variant = SonaraIconButtonVariant.Ghost,
                        size = 40.dp
                    ) {
                        Icon(
                            imageVector = if (playerState.isFavorite) PhosphorIcons.HeartFilled else PhosphorIcons.Heart,
                            contentDescription = "Toggle Favorite",
                            tint = if (playerState.isFavorite) Color(0xFFFF6B81) else colors.secondaryText,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Add to Playlist Button
                    SonaraIconButton(
                        onClick = onAddPlaylist,
                        contentDescription = "Add to Playlist",
                        variant = SonaraIconButtonVariant.Ghost,
                        size = 40.dp
                    ) {
                        Icon(
                            imageVector = PhosphorIcons.ListPlus,
                            contentDescription = "Add to Playlist",
                            tint = colors.secondaryText,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Download Button
                    SonaraIconButton(
                        onClick = onToggleDownload,
                        contentDescription = if (isDownloaded) "Downloaded" else "Download Track",
                        variant = SonaraIconButtonVariant.Ghost,
                        size = 40.dp
                    ) {
                        if (isDownloading) {
                            SonaraLoadingIndicator(
                                size = 20.dp,
                                color = colors.accent
                            )
                        } else {
                            Icon(
                                imageVector = if (isDownloaded) PhosphorIcons.CheckCircle else PhosphorIcons.DownloadSimple,
                                contentDescription = if (isDownloaded) "Downloaded" else "Download Track",
                                tint = if (isDownloaded) colors.accent else colors.secondaryText,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(metrics.metaToProgressGap))

            // ── 3. PLAYBACK PROGRESS RAIL & TIMESTAMPS AT TWO ENDS BELOW RAIL ──
            Column(modifier = Modifier.fillMaxWidth()) {
                ExpandedSegmentedProgressRail(
                    progress = playerState.progress,
                    onSeek = onSeek,
                    isDark = isDark,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 3. Starting duration (left end) & Ending duration (right end)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(playerState.currentPositionMs / 1000L),
                        style = typography.playbackTiming.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = colors.secondaryText
                    )
                    Text(
                        text = formatTime(playerState.durationMs / 1000L),
                        style = typography.playbackTiming.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = colors.secondaryText
                    )
                }
            }

            Spacer(modifier = Modifier.height(metrics.progressToTransportGap))

            // ── 4. 5-BUTTON TRANSPORT CORE ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle Toggle
                SonaraIconButton(
                    onClick = onToggleShuffle,
                    contentDescription = "Toggle Shuffle",
                    variant = SonaraIconButtonVariant.Ghost,
                    size = 44.dp
                ) {
                    Icon(
                        imageVector = PhosphorIcons.Shuffle,
                        contentDescription = "Toggle Shuffle",
                        tint = if (playerState.isShuffled) SonaraPalette.OxideAccent else colors.secondaryText.copy(alpha = 0.60f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Previous Track
                SonaraIconButton(
                    onClick = { buttonSkip(forward = false) },
                    contentDescription = "Previous Track",
                    variant = SonaraIconButtonVariant.Ghost,
                    size = 48.dp
                ) {
                    Icon(
                        imageVector = PhosphorIcons.SkipBack,
                        contentDescription = "Previous Track",
                        tint = colors.primaryText,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Dominant 64dp Play/Pause Button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = CircleShape,
                            spotColor = SonaraPalette.OxideAccent.copy(alpha = 0.45f),
                            ambientColor = SonaraPalette.OxideAccent.copy(alpha = 0.25f)
                        )
                        .clip(CircleShape)
                        .background(SonaraPalette.OxideAccent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { if (isPlaying) onPause() else onPlay() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (playerState.isBuffering) {
                        SonaraLoadingIndicator(size = 28.dp, color = Color.White)
                    } else {
                        Icon(
                            imageVector = if (isPlaying) PhosphorIcons.Pause else PhosphorIcons.Play,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Next Track
                SonaraIconButton(
                    onClick = { buttonSkip(forward = true) },
                    contentDescription = "Next Track",
                    variant = SonaraIconButtonVariant.Ghost,
                    size = 48.dp
                ) {
                    Icon(
                        imageVector = PhosphorIcons.SkipForward,
                        contentDescription = "Next Track",
                        tint = colors.primaryText,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Repeat Mode Toggle
                val repeatTint = if (playerState.repeatMode != 0) SonaraPalette.OxideAccent else colors.secondaryText.copy(alpha = 0.60f)
                val repeatIcon = if (playerState.repeatMode == 2) PhosphorIcons.RepeatOnce else PhosphorIcons.Repeat
                SonaraIconButton(
                    onClick = onToggleRepeat,
                    contentDescription = "Toggle Repeat",
                    variant = SonaraIconButtonVariant.Ghost,
                    size = 44.dp
                ) {
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Toggle Repeat",
                        tint = repeatTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(metrics.transportToVolumeGap))

            // ── 5. DEDICATED SMOOTH VOLUME CONTROL ROW ──
            ExpandedVolumeControl(
                volume = playerState.volume,
                isMuted = playerState.isMuted,
                onVolumeChange = onVolumeChange,
                onToggleMute = onToggleMute,
                isDark = isDark
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── 6. COMPACT AUDIO OUTPUT ROUTING ROW ──
            ExpandedAudioOutputRow(
                state = audioOutputState,
                onClick = onOpenAudioOutputSelector
            )
        }
    }
}

/**
 * Compact Audio Output device indicator row positioned below volume control.
 */
@Composable
private fun ExpandedAudioOutputRow(
    state: com.example.sonara.domain.model.AudioOutputState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val typography = SonaraTheme.typography
    val colors = SonaraTheme.colors
    val isDark = colors.isDark

    val activeDevice = state.activeDevice
    val deviceName = getLocalizedDeviceName(activeDevice)
    val icon = getDeviceIcon(activeDevice.type)
    val outputText = androidx.compose.ui.res.stringResource(
        com.example.sonara.R.string.audio_output_current_output,
        deviceName
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 7.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = outputText,
            tint = SonaraPalette.OxideAccent,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = outputText,
            style = typography.artistMetadata.copy(
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium
            ),
            color = if (isDark) colors.primaryText.copy(alpha = 0.92f) else colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Dedicated Interactive Volume Control.
 * Speaker Icon (tap to mute) + Smooth Thin Native-Style Slider + Live Percentage.
 */
@Composable
private fun ExpandedVolumeControl(
    volume: Float,
    isMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val typography = SonaraTheme.typography
    val colors = SonaraTheme.colors

    val displayVolume = if (isMuted) 0f else volume.coerceIn(0f, 1f)
    val percentage = (displayVolume * 100).toInt()

    val speakerIcon = when {
        isMuted || displayVolume == 0f -> PhosphorIcons.SpeakerSlash
        displayVolume < 0.5f -> PhosphorIcons.SpeakerLow
        else -> PhosphorIcons.SpeakerHigh
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Speaker / Mute Toggle Button
        SonaraIconButton(
            onClick = onToggleMute,
            contentDescription = if (isMuted) "Unmute" else "Mute",
            variant = SonaraIconButtonVariant.Ghost,
            size = 36.dp
        ) {
            Icon(
                imageVector = speakerIcon,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = if (isMuted) Color(0xFFFF6B81) else colors.secondaryText,
                modifier = Modifier.size(18.dp)
            )
        }

        // Conventional Smooth, Thin Native-Style Slider (continuous track + sleek thumb)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp),
            contentAlignment = Alignment.Center
        ) {
            SmoothVolumeSlider(
                progress = displayVolume,
                onSeek = onVolumeChange,
                isDark = isDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )
        }

        // Live Percentage Badge
        Text(
            text = "$percentage%",
            style = typography.playbackTiming.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = colors.secondaryText,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp)
        )
    }
}

/**
 * Smooth, Thin Native-Style Slider for Volume Control.
 * Clean continuous track with active fill, smooth thumb indicator, and responsive drag/seek tracking.
 */
@Composable
private fun SmoothVolumeSlider(
    progress: Float,
    onSeek: (Float) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = SonaraPalette.OxideAccent
    val inactiveColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color(0xFF1B1F1E).copy(alpha = 0.15f)

    val currentOnSeek by rememberUpdatedState(onSeek)
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val effectiveProgress = if (isDragging) dragProgress else progress.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val width = size.width.toFloat()
                    if (width > 0f) {
                        val fraction = (offset.x / width).coerceIn(0f, 1f)
                        dragProgress = fraction
                        currentOnSeek(fraction)
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val width = size.width.toFloat()
                        if (width > 0f) {
                            isDragging = true
                            val fraction = (offset.x / width).coerceIn(0f, 1f)
                            dragProgress = fraction
                            currentOnSeek(fraction)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val width = size.width.toFloat()
                        if (width > 0f) {
                            val fraction = (change.position.x / width).coerceIn(0f, 1f)
                            dragProgress = fraction
                            currentOnSeek(fraction)
                        }
                    }
                )
            }
    ) {
        val trackHeight = 3.5.dp.toPx()
        val thumbRadius = 6.dp.toPx()
        val yCenter = size.height / 2f
        val r = CornerRadius(trackHeight / 2f, trackHeight / 2f)

        val fillFraction = effectiveProgress.coerceIn(0f, 1f)
        val fillWidth = size.width * fillFraction

        // 1. Inactive background continuous rail
        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(0f, yCenter - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = r
        )

        // 2. Active fill continuous rail
        if (fillWidth > 0f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(0f, yCenter - trackHeight / 2f),
                size = Size(fillWidth, trackHeight),
                cornerRadius = r
            )
        }

        // 3. Sleek subtle thumb indicator
        val thumbX = fillWidth.coerceIn(thumbRadius, size.width - thumbRadius)
        drawCircle(
            color = Color.White,
            radius = thumbRadius,
            center = Offset(thumbX, yCenter)
        )
    }
}

/**
 * Progress Rail -- Segmented continuous liquid fill across tick marks (Sonara signature identity).
 * Supports both instant tap and smooth continuous scrubbing gestures.
 */
@Composable
private fun ExpandedSegmentedProgressRail(
    progress: Float,
    onSeek: (Float) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = SonaraPalette.OxideAccent
    val inactiveColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color(0xFF1B1F1E).copy(alpha = 0.15f)

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
        val segmentWidth = 3.5.dp.toPx()
        val segmentGap   = 3.5.dp.toPx()
        val segmentHeight = 14.dp.toPx()
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

        // 2. Continuous liquid fill clipped to boundary
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

/**
 * Lyrics View: Full Synchronized Karaoke Scrolling Lyrics.
 * Features Container-Scroll depth, click-to-seek, and localized word-by-word active tracking.
 */
@Composable
private fun LyricsViewContent(
    state: LyricsUiState,
    playerState: PlayerUiState,
    onSeek: (Float) -> Unit
) {
    val colors = SonaraTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
    ) {
        when (state) {
            is LyricsUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SonaraLoadingIndicator(size = 48.dp)
                }
            }

            is LyricsUiState.Synced -> {
                val listState = rememberLazyListState()

                // Auto-scroll each active line to vertical optical center
                LaunchedEffect(state.activeLineIndex) {
                    if (state.activeLineIndex >= 0 && state.activeLineIndex < state.lines.size) {
                        listState.animateScrollToItem(
                            index = state.activeLineIndex,
                            scrollOffset = 0
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 220.dp, bottom = 280.dp)
                    ) {
                        itemsIndexed(
                            items = state.lines,
                            key = { index, line -> "${line.timestampMs}_$index" }
                        ) { index, line ->
                            val isActive = index == state.activeLineIndex
                            val dist = if (state.activeLineIndex >= 0) kotlin.math.abs(index - state.activeLineIndex) else index
                            val lineAlpha = when (dist) {
                                0 -> 1.0f
                                1 -> 0.65f
                                2 -> 0.42f
                                3 -> 0.22f
                                else -> 0.12f
                            }

                            val displayText = if (state.isRomanized && line.romanizedText != null) {
                                line.romanizedText
                            } else {
                                line.text
                            }

                            val wordsToRender = if (state.isRomanized && line.romanizedWords.isNotEmpty()) {
                                line.romanizedWords
                            } else {
                                line.words
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isActive) colors.surfaceVariant.copy(alpha = 0.75f) else Color.Transparent
                                    )
                                    .clickable {
                                        if (playerState.durationMs > 0) {
                                            val fraction = (line.timestampMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
                                            onSeek(fraction)
                                        }
                                    }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (isActive && wordsToRender.isNotEmpty()) {
                                    // Localized Word-by-Word highlighting for active line
                                    ActiveLyricLineWords(
                                        words = wordsToRender,
                                        currentPositionMs = playerState.currentPositionMs
                                    )
                                } else {
                                    Text(
                                        text = displayText,
                                        fontSize = 23.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isActive) SonaraPalette.OxideAccent else colors.primaryText.copy(alpha = lineAlpha),
                                        lineHeight = 31.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (state.isRomanized && line.romanizedText != null && line.text != line.romanizedText) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = line.text,
                                        fontSize = 14.sp,
                                        color = if (isActive) colors.primaryText.copy(alpha = 0.60f) else colors.secondaryText.copy(alpha = (lineAlpha * 0.6f).coerceAtLeast(0.1f)),
                                        lineHeight = 20.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is LyricsUiState.Plain -> {
                val lines = state.text.split("\n")
                val romanizedLines = state.romanizedText?.split("\n")

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 40.dp, bottom = 120.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(lines) { index, lineText ->
                        val trimmedLine = lineText.trim()
                        if (trimmedLine.isNotBlank()) {
                            val romanizedLine = romanizedLines?.getOrNull(index)?.trim()
                            val displayText = if (state.isRomanized && !romanizedLine.isNullOrBlank()) {
                                romanizedLine
                            } else {
                                trimmedLine
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = displayText,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.primaryText,
                                    lineHeight = 28.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (state.isRomanized && !romanizedLine.isNullOrBlank() && romanizedLine != trimmedLine) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = trimmedLine,
                                        fontSize = 14.sp,
                                        color = colors.secondaryText.copy(alpha = 0.60f),
                                        lineHeight = 20.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            is LyricsUiState.Unavailable -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SonaraCard {
                        SonaraEmptyState(
                            title = "No Lyrics Available",
                            message = state.reason
                        )
                    }
                }
            }

            LyricsUiState.Hidden -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Lyrics not loaded",
                        color = colors.secondaryText
                    )
                }
            }
        }
    }
}

/**
 * Localized Word-by-Word highlighting composable.
 * Features continuous forward pointer tracking (no gap dropouts), invariant layout geometry,
 * and smooth animated GPU scale and color crossfades.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveLyricLineWords(
    words: List<LyricWord>,
    currentPositionMs: Long,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors

    // Continuous word index pointer: prevents highlight dropouts during breath gaps
    val activeWordIndex = remember(words, currentPositionMs) {
        when {
            words.isEmpty() -> -1
            currentPositionMs < words.first().startMs - 50L -> -1
            currentPositionMs >= words.last().endMs -> words.size
            else -> {
                val idx = words.indexOfLast { currentPositionMs >= it.startMs - 50L }
                if (idx != -1) idx else 0
            }
        }
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        words.forEachIndexed { idx, word ->
            val isWordActive = idx == activeWordIndex
            val isWordPast = idx < activeWordIndex

            // Progressive karaoke fill: active and sung words remain lit in OxideAccent, future words are dimmed
            val targetColor = when {
                isWordActive || isWordPast -> SonaraPalette.OxideAccent
                else -> colors.primaryText.copy(alpha = 0.40f)
            }
            val wordColor by animateColorAsState(
                targetValue = targetColor,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "WordColor_$idx"
            )

            // Smooth hardware-accelerated micro-scale (1.08x) for active singing word
            val targetScale = if (isWordActive) 1.08f else 1.0f
            val wordScale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "WordScale_$idx"
            )

            Text(
                text = word.text,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold, // Invariant weight prevents text reflow jitter
                color = wordColor,
                lineHeight = 31.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    scaleX = wordScale
                    scaleY = wordScale
                }
            )
        }
    }
}

private fun formatTime(totalSeconds: Long): String {
    if (totalSeconds < 0) return "0:00"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
