package com.example.sonara.feature.playlist.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    isOpen: Boolean,
    track: Track?,
    playlists: List<PlaylistSummary>,
    selectedPlaylistId: String?,
    onSelectPlaylist: (String) -> Unit,
    onConfirmAdd: () -> Unit,
    onCreateNewPlaylist: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen || track == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimensions.spaceLg)
                .padding(bottom = dimensions.space2Xl),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Add to Playlist",
                    style = typography.sectionTitle,
                    color = colors.primaryText
                )
                Text(
                    text = "${track.title} • ${track.artist}",
                    style = typography.caption,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (playlists.isEmpty()) {
                // Case A: No playlists exist
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = dimensions.spaceLg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(dimensions.spaceLg)
                ) {
                    Text(
                        text = "No playlists have been created yet.",
                        style = typography.body,
                        color = colors.secondaryText
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd, Alignment.CenterHorizontally)
                    ) {
                        SonaraButton(
                            text = "Cancel",
                            onClick = onDismiss,
                            variant = SonaraButtonVariant.Secondary
                        )
                        SonaraButton(
                            text = "Create Playlist",
                            onClick = onCreateNewPlaylist,
                            variant = SonaraButtonVariant.Primary
                        )
                    }
                }
            } else {
                // Case B: Playlists exist
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onCreateNewPlaylist)
                        .padding(vertical = dimensions.spaceSm, horizontal = dimensions.spaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = PhosphorIcons.Plus,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = "New Playlist",
                        style = typography.trackTitle,
                        color = colors.accent,
                        modifier = Modifier.weight(1f)
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        val isSelected = playlist.id == selectedPlaylistId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) colors.surfaceVariant else colors.surface)
                                .clickable { onSelectPlaylist(playlist.id) }
                                .padding(vertical = dimensions.spaceSm, horizontal = dimensions.spaceSm),
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
                                if (!playlist.coverImage.isNullOrBlank()) {
                                    AsyncImage(
                                        model = playlist.coverImage,
                                        contentDescription = null,
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

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = typography.trackTitle,
                                    color = colors.primaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${playlist.size} tracks",
                                    style = typography.caption,
                                    color = colors.secondaryText
                                )
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectPlaylist(playlist.id) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colors.accent,
                                    unselectedColor = colors.divider
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(dimensions.spaceSm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd, Alignment.End)
                ) {
                    SonaraButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        variant = SonaraButtonVariant.Secondary
                    )
                    SonaraButton(
                        text = "Add to this playlist",
                        onClick = onConfirmAdd,
                        enabled = selectedPlaylistId != null,
                        variant = SonaraButtonVariant.Primary
                    )
                }
            }
        }
    }
}
