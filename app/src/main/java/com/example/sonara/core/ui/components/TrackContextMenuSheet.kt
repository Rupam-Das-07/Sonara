package com.example.sonara.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sonara.R
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.Track

/**
 * Contextual bottom sheet for track actions:
 * - Play Next (inserts track right after the current playing track in PlaybackQueueEngine)
 * - Play Now (immediately switches playback to this track)
 * - Add to Playlist (opens existing playlist picker)
 * - Create Playlist with this Song (opens creation dialog, atomically creates + adds)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextMenuSheet(
    track: Track,
    onPlayNext: () -> Unit,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToPlaylist: (() -> Unit)? = null,
    onCreatePlaylistWithSong: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimensions.spaceLg)
                .padding(bottom = dimensions.space2Xl),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
        ) {
            // Track Preview Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = dimensions.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!track.artworkUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = "${track.title} artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = PhosphorIcons.MusicNote,
                            contentDescription = null,
                            tint = colors.secondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = track.title,
                        style = typography.trackTitle,
                        color = colors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = if (track.album.isNotBlank()) "${track.artist} • ${track.album}" else track.artist
                    Text(
                        text = subtitle,
                        style = typography.artistMetadata,
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            SonaraDivider(modifier = Modifier.padding(vertical = dimensions.spaceXs))

            // Option 1: Play Next
            ContextMenuActionItem(
                icon = PhosphorIcons.ListPlus,
                title = stringResource(R.string.action_play_next),
                subtitle = stringResource(R.string.action_play_next_desc),
                onClick = {
                    onPlayNext()
                    onDismiss()
                }
            )

            // Option 2: Play Now
            ContextMenuActionItem(
                icon = PhosphorIcons.Play,
                title = stringResource(R.string.action_play_now),
                onClick = {
                    onPlayNow()
                    onDismiss()
                }
            )

            // Option 3: Add to Playlist
            if (onAddToPlaylist != null) {
                ContextMenuActionItem(
                    icon = PhosphorIcons.Playlist,
                    title = stringResource(R.string.action_add_to_playlist),
                    onClick = {
                        onAddToPlaylist()
                        onDismiss()
                    }
                )
            }

            // Option 4: Create Playlist with this Song
            if (onCreatePlaylistWithSong != null) {
                ContextMenuActionItem(
                    icon = PhosphorIcons.Plus,
                    title = stringResource(R.string.action_create_playlist_with_song),
                    onClick = {
                        onCreatePlaylistWithSong()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun ContextMenuActionItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = dimensions.spaceSm, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primaryText,
            modifier = Modifier.size(22.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = typography.body,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = typography.caption,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
