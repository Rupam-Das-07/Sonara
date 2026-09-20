package com.example.sonara.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.sonara.R
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.components.SonaraEmptyState
import com.example.sonara.core.ui.motion.LocalReduceMotion
import com.example.sonara.core.ui.motion.SonaraMotion
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.Track
import kotlin.math.roundToInt

private const val TAB_TRANSITION_ENTER_MS = 200
private const val TAB_TRANSITION_EXIT_MS = 180
private const val TAB_SLIDE_DISTANCE_FRACTION = 0.12f

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onSelectTab: (LibraryTab) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onToggleLike: (Track, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = SonaraTheme.dimensions
    val reduceMotion = LocalReduceMotion.current

    // Hoist list states so switching tabs preserves scroll position
    val likedSongsListState = rememberLazyListState()
    val historyListState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensions.spaceLg)
    ) {
        Spacer(modifier = Modifier.height(dimensions.spaceSm))

        // Tab Row: Liked Songs | History
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimensions.spaceXs)
        ) {
            SonaraButton(
                text = "${stringResource(R.string.tab_liked_songs)} (${state.likedSongs.size})",
                onClick = { onSelectTab(LibraryTab.LIKED_SONGS) },
                variant = if (state.selectedTab == LibraryTab.LIKED_SONGS) SonaraButtonVariant.Primary else SonaraButtonVariant.Secondary,
                modifier = Modifier.weight(1f)
            )
            SonaraButton(
                text = "${stringResource(R.string.tab_playback_history)} (${state.history.size})",
                onClick = { onSelectTab(LibraryTab.HISTORY) },
                variant = if (state.selectedTab == LibraryTab.HISTORY) SonaraButtonVariant.Primary else SonaraButtonVariant.Secondary,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(dimensions.spaceMd))

        AnimatedContent(
            targetState = state.selectedTab,
            transitionSpec = {
                if (reduceMotion) {
                    ContentTransform(
                        targetContentEnter = fadeIn(
                            animationSpec = tween(durationMillis = SonaraMotion.REDUCED_MS)
                        ),
                        initialContentExit = fadeOut(
                            animationSpec = tween(durationMillis = SonaraMotion.REDUCED_MS)
                        ),
                        targetContentZIndex = 0f,
                        sizeTransform = null
                    )
                } else {
                    val forward = targetState.ordinal > initialState.ordinal
                    val enterSlide = slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = TAB_TRANSITION_ENTER_MS,
                            easing = FastOutSlowInEasing
                        )
                    ) { fullWidth ->
                        val distance = (fullWidth * TAB_SLIDE_DISTANCE_FRACTION).roundToInt()
                        if (forward) distance else -distance
                    }
                    val enterFade = fadeIn(
                        animationSpec = tween(
                            durationMillis = TAB_TRANSITION_ENTER_MS,
                            easing = LinearOutSlowInEasing
                        )
                    )
                    val exitSlide = slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis = TAB_TRANSITION_ENTER_MS,
                            easing = FastOutSlowInEasing
                        )
                    ) { fullWidth ->
                        val distance = (fullWidth * TAB_SLIDE_DISTANCE_FRACTION).roundToInt()
                        if (forward) -distance else distance
                    }
                    val exitFade = fadeOut(
                        animationSpec = tween(
                            durationMillis = TAB_TRANSITION_EXIT_MS,
                            easing = FastOutLinearInEasing
                        )
                    )

                    ContentTransform(
                        targetContentEnter = enterSlide + enterFade,
                        initialContentExit = exitSlide + exitFade,
                        targetContentZIndex = 0f,
                        sizeTransform = null
                    )
                }
            },
            label = "LibraryTabTransition",
            modifier = Modifier.fillMaxSize()
        ) { tab ->
            when (tab) {
                LibraryTab.LIKED_SONGS -> {
                    if (state.likedSongs.isEmpty()) {
                        SonaraEmptyState(
                            title = stringResource(R.string.library_liked_empty_title),
                            message = stringResource(R.string.library_liked_empty_message)
                        )
                    } else {
                        LazyColumn(
                            state = likedSongsListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                        ) {
                            items(state.likedSongs, key = { it.id }) { track ->
                                TrackRowItem(
                                    track = track,
                                    isLiked = true,
                                    onPlay = { onPlayTrack(track) },
                                    onToggleLike = { onToggleLike(track, true) }
                                )
                            }
                        }
                    }
                }
                LibraryTab.HISTORY -> {
                    if (state.history.isEmpty()) {
                        SonaraEmptyState(
                            title = stringResource(R.string.library_history_empty_title),
                            message = stringResource(R.string.library_history_empty_message)
                        )
                    } else {
                        LazyColumn(
                            state = historyListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                        ) {
                            items(state.history, key = { it.id }) { item ->
                                TrackRowItem(
                                    track = item.track,
                                    isLiked = state.likedSongs.any { it.id == item.track.id },
                                    onPlay = { onPlayTrack(item.track) },
                                    onToggleLike = { isLiked -> onToggleLike(item.track, isLiked) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    isLiked: Boolean,
    onPlay: () -> Unit,
    onToggleLike: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    com.example.sonara.core.ui.components.TrackRow(
        track = track,
        isLiked = isLiked,
        onPlay = onPlay,
        onToggleLike = onToggleLike,
        modifier = modifier
    )
}
