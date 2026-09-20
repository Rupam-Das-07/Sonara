package com.example.sonara.feature.playlist

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.components.SonaraCard
import com.example.sonara.core.ui.components.SonaraEmptyState
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.PlaylistOrigin
import com.example.sonara.domain.model.PlaylistSummary

@Composable
fun PlaylistsScreen(
    state: PlaylistUiState,
    onSelectPlaylist: (PlaylistSummary) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRenamePlaylist: (PlaylistSummary) -> Unit,
    onDeletePlaylist: (PlaylistSummary) -> Unit,
    onImportPlaylist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensions.spaceLg)
    ) {
        Spacer(modifier = Modifier.height(dimensions.spaceSm))

        // Top Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.playlists_title),
                style = typography.sectionTitle,
                color = colors.primaryText
            )

            Row(horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
                SonaraButton(
                    text = stringResource(R.string.import_button),
                    onClick = onImportPlaylist,
                    variant = SonaraButtonVariant.Secondary
                )
                SonaraButton(
                    text = "New Playlist",
                    onClick = onCreatePlaylist,
                    variant = SonaraButtonVariant.Secondary,
                    leadingIcon = {
                        Icon(
                            imageVector = PhosphorIcons.Plus,
                            contentDescription = null,
                            tint = colors.primaryText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(dimensions.spaceMd))

        val userPlaylists = remember(state.playlists) {
            state.playlists.filter { it.origin == PlaylistOrigin.USER }
        }
        val spotifyPlaylists = remember(state.playlists) {
            state.playlists.filter { it.origin == PlaylistOrigin.SPOTIFY }
        }

        if (userPlaylists.isEmpty() && spotifyPlaylists.isEmpty()) {
            // CASE 1: No USER playlists and no SPOTIFY playlists — preserve existing overall empty state
            SonaraCard {
                SonaraEmptyState(
                    title = "No Playlists Yet",
                    message = "Create custom playlists to organize your music.",
                    actionLabel = "Create Playlist",
                    onAction = onCreatePlaylist
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
            ) {
                // Section 1: YOUR PLAYLISTS (frozen first order)
                item(key = "header_your_playlists") {
                    PlaylistSectionHeader(title = "YOUR PLAYLISTS")
                }

                if (userPlaylists.isEmpty()) {
                    // CASE 3: Compact/subtle message when native playlists are empty but Spotify imports exist
                    item(key = "empty_your_playlists") {
                        Text(
                            text = "No user playlists yet. Tap \"New Playlist\" to create one.",
                            style = typography.secondaryBody,
                            color = colors.secondaryText,
                            modifier = Modifier.padding(vertical = dimensions.spaceSm)
                        )
                    }
                } else {
                    // CASES 2 & 4: User playlists
                    items(userPlaylists, key = { it.id }) { playlist ->
                        PlaylistRowItem(
                            playlist = playlist,
                            onClick = { onSelectPlaylist(playlist) },
                            onRename = { onRenamePlaylist(playlist) },
                            onDelete = { onDeletePlaylist(playlist) }
                        )
                    }
                }

                // Section 2: IMPORTED FROM SPOTIFY (only shown when Spotify imports exist: CASES 3 & 4)
                if (spotifyPlaylists.isNotEmpty()) {
                    item(key = "header_spotify_playlists") {
                        Spacer(modifier = Modifier.height(dimensions.spaceMd))
                        PlaylistSectionHeader(title = "IMPORTED FROM SPOTIFY")
                    }
                    items(spotifyPlaylists, key = { it.id }) { playlist ->
                        PlaylistRowItem(
                            playlist = playlist,
                            onClick = { onSelectPlaylist(playlist) },
                            onRename = { onRenamePlaylist(playlist) },
                            onDelete = { onDeletePlaylist(playlist) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = SonaraTheme.typography.cardTitle.copy(
            letterSpacing = 0.5.sp
        ),
        color = SonaraTheme.colors.primaryText,
        modifier = modifier.padding(vertical = SonaraTheme.dimensions.spaceXs)
    )
}

@Composable
private fun PlaylistRowItem(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions
    var isMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = dimensions.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        // Thumbnail Artwork
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!playlist.coverImage.isNullOrBlank()) {
                AsyncImage(
                    model = playlist.coverImage,
                    contentDescription = "${playlist.name} artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = PhosphorIcons.Playlist,
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Title and Track Count
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = playlist.name,
                style = typography.trackTitle,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.playlist_tracks_count, playlist.size),
                style = typography.caption,
                color = colors.secondaryText
            )
        }

        // Overflow Menu (Rename, Delete)
        Box {
            SonaraIconButton(
                onClick = { isMenuOpen = true },
                contentDescription = "Playlist options",
                variant = SonaraIconButtonVariant.Ghost,
                size = dimensions.minTouchTarget
            ) {
                Icon(
                    imageVector = PhosphorIcons.DotsThreeVertical,
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                modifier = Modifier.background(colors.surface)
            ) {
                DropdownMenuItem(
                    text = { Text("Rename", style = typography.body, color = colors.primaryText) },
                    leadingIcon = {
                        Icon(
                            imageVector = PhosphorIcons.PencilSimple,
                            contentDescription = null,
                            tint = colors.secondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        isMenuOpen = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", style = typography.body, color = colors.accent) },
                    leadingIcon = {
                        Icon(
                            imageVector = PhosphorIcons.Trash,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        isMenuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}
