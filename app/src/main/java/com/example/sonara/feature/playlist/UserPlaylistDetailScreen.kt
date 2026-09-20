package com.example.sonara.feature.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.components.TrackRow
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track

@Composable
fun UserPlaylistDetailScreen(
    playlist: PlaylistDetail,
    isReorderMode: Boolean,
    reorderedTracks: List<Track>,
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onToggleLike: (Track, Boolean) -> Unit,
    isLiked: (String) -> Boolean,
    onStartReorder: () -> Unit,
    onMoveTrack: (Int, Int) -> Unit,
    onCommitReorder: () -> Unit,
    onCancelReorder: () -> Unit,
    onRename: (PlaylistSummary) -> Unit,
    onDelete: (PlaylistSummary) -> Unit,
    onRequestRemoveTrack: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = {
        if (isReorderMode) onCancelReorder() else onBack()
    })

    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val tracksToDisplay = if (isReorderMode) reorderedTracks else playlist.tracks
    val summary = PlaylistSummary(
        id = playlist.id,
        name = playlist.name,
        size = playlist.tracks.size,
        coverImage = playlist.coverImage
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = dimensions.space2Xl),
        verticalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        // Top Navigation Bar
        item(key = "user-playlist-header-nav") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = dimensions.spaceLg,
                        end = dimensions.spaceLg,
                        top = dimensions.spaceSm
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SonaraIconButton(
                        onClick = { if (isReorderMode) onCancelReorder() else onBack() },
                        contentDescription = "Back",
                        variant = SonaraIconButtonVariant.Ghost,
                        size = dimensions.minTouchTarget
                    ) {
                        Icon(
                            imageVector = PhosphorIcons.ArrowLeft,
                            contentDescription = null,
                            tint = colors.primaryText,
                            modifier = Modifier.size(dimensions.iconMedium)
                        )
                    }
                    Text(
                        text = if (isReorderMode) "Reorder Songs" else "Playlist",
                        style = typography.sectionTitle,
                        color = colors.primaryText,
                        modifier = Modifier.padding(start = dimensions.spaceSm)
                    )
                }

                if (isReorderMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
                        SonaraButton(
                            text = "Done",
                            onClick = onCommitReorder,
                            variant = SonaraButtonVariant.Primary
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(dimensions.spaceXs)) {
                        if (playlist.tracks.size > 1) {
                            SonaraIconButton(
                                onClick = onStartReorder,
                                contentDescription = "Reorder tracks",
                                variant = SonaraIconButtonVariant.Ghost,
                                size = dimensions.minTouchTarget
                            ) {
                                Icon(
                                    imageVector = PhosphorIcons.DotsSixVertical,
                                    contentDescription = null,
                                    tint = colors.secondaryText,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        SonaraIconButton(
                            onClick = { onRename(summary) },
                            contentDescription = "Rename playlist",
                            variant = SonaraIconButtonVariant.Ghost,
                            size = dimensions.minTouchTarget
                        ) {
                            Icon(
                                imageVector = PhosphorIcons.PencilSimple,
                                contentDescription = null,
                                tint = colors.secondaryText,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        SonaraIconButton(
                            onClick = { onDelete(summary) },
                            contentDescription = "Delete playlist",
                            variant = SonaraIconButtonVariant.Ghost,
                            size = dimensions.minTouchTarget
                        ) {
                            Icon(
                                imageVector = PhosphorIcons.Trash,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Header Banner Artwork
        item(key = "user-playlist-banner") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!playlist.coverImage.isNullOrBlank()) {
                    AsyncImage(
                        model = playlist.coverImage,
                        contentDescription = "${playlist.name} banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = PhosphorIcons.Playlist,
                        contentDescription = null,
                        tint = colors.secondaryText,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.35f to Color.Transparent,
                                    0.75f to colors.background.copy(alpha = 0.65f),
                                    1.0f to colors.background
                                )
                            )
                        )
                )
            }
        }

        // Metadata & Actions
        item(key = "user-playlist-metadata") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensions.spaceLg),
                verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
            ) {
                Text(
                    text = playlist.name,
                    style = typography.display,
                    color = colors.primaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "${playlist.tracks.size} tracks",
                    style = typography.caption,
                    color = colors.secondaryText
                )

                if (playlist.tracks.isNotEmpty() && !isReorderMode) {
                    Spacer(modifier = Modifier.height(dimensions.spaceXs))
                    SonaraButton(
                        text = "Play All",
                        onClick = { onPlayTrack(playlist.tracks.first()) },
                        variant = SonaraButtonVariant.Primary,
                        leadingIcon = {
                            Icon(
                                imageVector = PhosphorIcons.Play,
                                contentDescription = null,
                                tint = colors.onAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }

        // Tracks section
        if (tracksToDisplay.isEmpty()) {
            item(key = "user-playlist-empty") {
                Text(
                    text = "No songs in this playlist yet. Add songs using the playlist button in the player or search results.",
                    style = typography.secondaryBody,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(horizontal = dimensions.spaceLg, vertical = dimensions.spaceMd)
                )
            }
        } else {
            itemsIndexed(tracksToDisplay, key = { _, track -> track.id }) { index, track ->
                Box(modifier = Modifier.padding(horizontal = dimensions.spaceLg)) {
                    if (isReorderMode) {
                        ReorderTrackRow(
                            track = track,
                            index = index,
                            totalCount = tracksToDisplay.size,
                            onMoveUp = { onMoveTrack(index, index - 1) },
                            onMoveDown = { onMoveTrack(index, index + 1) }
                        )
                    } else {
                        TrackRow(
                            track = track,
                            isLiked = isLiked(track.id),
                            onPlay = { onPlayTrack(track) },
                            onToggleLike = { liked -> onToggleLike(track, liked) },
                            trailingContent = {
                                SonaraIconButton(
                                    onClick = { onRequestRemoveTrack(track) },
                                    contentDescription = "Remove from playlist",
                                    variant = SonaraIconButtonVariant.Ghost,
                                    size = dimensions.minTouchTarget
                                ) {
                                    Icon(
                                        imageVector = PhosphorIcons.MinusCircle,
                                        contentDescription = null,
                                        tint = colors.secondaryText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReorderTrackRow(
    track: Track,
    index: Int,
    totalCount: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = dimensions.spaceMd, vertical = dimensions.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        Icon(
            imageVector = PhosphorIcons.DotsSixVertical,
            contentDescription = null,
            tint = colors.secondaryText,
            modifier = Modifier.size(20.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = typography.trackTitle,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                style = typography.caption,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(dimensions.spaceXs)) {
            SonaraIconButton(
                onClick = onMoveUp,
                enabled = index > 0,
                contentDescription = "Move up",
                variant = SonaraIconButtonVariant.Ghost,
                size = dimensions.minTouchTarget
            ) {
                Text(
                    text = "▲",
                    fontSize = 14.sp,
                    color = if (index > 0) colors.primaryText else colors.disabledText
                )
            }

            SonaraIconButton(
                onClick = onMoveDown,
                enabled = index < totalCount - 1,
                contentDescription = "Move down",
                variant = SonaraIconButtonVariant.Ghost,
                size = dimensions.minTouchTarget
            ) {
                Text(
                    text = "▼",
                    fontSize = 14.sp,
                    color = if (index < totalCount - 1) colors.primaryText else colors.disabledText
                )
            }
        }
    }
}
