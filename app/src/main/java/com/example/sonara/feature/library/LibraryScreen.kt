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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.components.SonaraEmptyState
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.motion.LocalReduceMotion
import com.example.sonara.core.ui.motion.SonaraMotion
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.PlaybackOrigin
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
    /**
     * Phase 2B: play intent carrying this surface's provenance. Only the Liked Songs tab
     * is eligible ([PlaybackOrigin.LIKED_SONGS]); replaying from the History tab is
     * explicitly [PlaybackOrigin.OTHER], so history cannot bootstrap its own eligibility.
     */
    onPlayTrackWithOrigin: (Track, PlaybackOrigin) -> Unit = { track, _ -> onPlayTrack(track) },
    onPlayTrackWithContext: (Track, PlaybackOrigin, List<Track>) -> Unit = { track, origin, _ ->
        onPlayTrackWithOrigin(track, origin)
    },
    onToggleLike: (Track, Boolean) -> Unit,
    onRemoveDownload: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dimensions = SonaraTheme.dimensions
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val reduceMotion = LocalReduceMotion.current

    // Hoist list states so switching tabs preserves scroll position
    val likedSongsListState = rememberLazyListState()
    val downloadedListState = rememberLazyListState()
    val historyListState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensions.spaceLg)
    ) {
        Spacer(modifier = Modifier.height(dimensions.spaceSm))

        // Tab Row: Liked Songs | Downloads | History
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
                text = "${stringResource(R.string.tab_downloads)} (${state.downloads.size})",
                onClick = { onSelectTab(LibraryTab.DOWNLOADS) },
                variant = if (state.selectedTab == LibraryTab.DOWNLOADS) SonaraButtonVariant.Primary else SonaraButtonVariant.Secondary,
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
                                     onPlay = { onPlayTrackWithContext(track, PlaybackOrigin.LIKED_SONGS, state.likedSongs) },
                                    onToggleLike = { onToggleLike(track, true) }
                                )
                            }
                        }
                    }
                }
                LibraryTab.DOWNLOADS -> {
                    if (state.downloads.isEmpty() && state.activeDownloads.isEmpty()) {
                        SonaraEmptyState(
                            title = stringResource(R.string.library_downloads_empty_title),
                            message = stringResource(R.string.library_downloads_empty_message)
                        )
                    } else {
                        LazyColumn(
                            state = downloadedListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                        ) {
                            if (state.activeDownloads.isNotEmpty()) {
                                item(key = "active_downloads_header") {
                                    Text(
                                        text = "DOWNLOADING",
                                        style = typography.caption.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.accent
                                        ),
                                        modifier = Modifier.padding(horizontal = dimensions.spaceMd, vertical = dimensions.spaceXs)
                                    )
                                }
                                items(state.activeDownloads, key = { "active_${it.download.trackId}" }) { item ->
                                    val track = item.track
                                    val progress = state.activeProgressMap[item.download.trackId]
                                    ActiveDownloadCard(
                                        track = track,
                                        download = item.download,
                                        progress = progress,
                                        onCancel = { onRemoveDownload?.invoke(item.download.trackId) }
                                    )
                                }
                                if (state.downloads.isNotEmpty()) {
                                    item(key = "completed_downloads_header") {
                                        Text(
                                            text = "DOWNLOADED",
                                            style = typography.caption.copy(
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.secondaryText
                                            ),
                                            modifier = Modifier.padding(horizontal = dimensions.spaceMd, vertical = dimensions.spaceXs)
                                        )
                                    }
                                }
                            }
                            items(state.downloads, key = { it.id }) { track ->
                                TrackRowItem(
                                    track = track,
                                    isLiked = state.likedSongs.any { it.id == track.id },
                                     onPlay = { onPlayTrackWithContext(track, PlaybackOrigin.OTHER, state.downloads) },
                                    onToggleLike = { isLiked -> onToggleLike(track, isLiked) },
                                    trailingContent = if (onRemoveDownload != null) {
                                        {
                                            SonaraIconButton(
                                                onClick = { onRemoveDownload(track.id) },
                                                contentDescription = "Remove download ${track.title}",
                                                variant = SonaraIconButtonVariant.Ghost,
                                                size = dimensions.minTouchTarget
                                            ) {
                                                Icon(
                                                    imageVector = PhosphorIcons.Trash,
                                                    contentDescription = "Remove download",
                                                    tint = SonaraTheme.colors.secondaryText,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    } else null
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
                                     onPlay = { onPlayTrackWithContext(item.track, PlaybackOrigin.OTHER, state.history.map { it.track }) },
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
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    isVideo: Boolean = false
) {
    com.example.sonara.core.ui.components.TrackRow(
        track = track,
        isLiked = isLiked,
        onPlay = onPlay,
        onToggleLike = onToggleLike,
        modifier = modifier,
        onLongClick = onLongClick,
        trailingContent = trailingContent,
        isVideo = isVideo
    )
}

@Composable
private fun ActiveDownloadCard(
    track: Track?,
    download: com.example.sonara.domain.model.DownloadInfo,
    progress: com.example.sonara.domain.model.video.download.ActiveDownloadProgress?,
    onCancel: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val title = track?.title ?: download.trackId
    val artist = track?.artist ?: if (download.isVideo) "Video Download" else "Audio Download"
    val progressFraction = progress?.progressFraction ?: download.progressFraction
    val speed = progress?.speedFormatted
    val eta = progress?.etaFormatted
    val stageDesc = progress?.stageDescription ?: if (download.isVideo) "Downloading video..." else "Downloading audio..."

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, colors.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimensions.spaceMd, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$artist • $stageDesc",
                        style = typography.caption.copy(fontSize = 11.5.sp),
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                SonaraIconButton(
                    onClick = onCancel,
                    contentDescription = "Cancel download",
                    variant = SonaraIconButtonVariant.Ghost,
                    size = 32.dp
                ) {
                    Icon(
                        imageVector = PhosphorIcons.X,
                        contentDescription = "Cancel",
                        tint = colors.secondaryText,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (progressFraction > 0f) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    color = colors.accent,
                    trackColor = colors.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            } else {
                LinearProgressIndicator(
                    color = colors.accent,
                    trackColor = colors.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }

            if (speed != null || eta != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = speed.orEmpty(),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.accent
                    )
                    Text(
                        text = eta.orEmpty(),
                        style = typography.caption.copy(fontSize = 10.5.sp),
                        color = colors.secondaryText
                    )
                }
            }
        }
    }
}
