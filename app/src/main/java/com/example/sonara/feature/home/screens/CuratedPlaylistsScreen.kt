package com.example.sonara.feature.home.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.feature.home.HomeModule
import com.example.sonara.feature.home.components.CuratedPlaylistsSkeleton
import com.example.sonara.feature.home.components.PlaylistGridTile

/**
 * CuratedPlaylistsScreen — Dedicated catalogue screen presenting all curated playlists
 * dynamically fetched from the backend.
 *
 * Visual Architecture:
 * 1. Top bar with back navigation
 * 2. Responsive 4-column / adaptive grid of circular playlist tiles
 * 3. Reuses [PlaylistGridTile] to maintain visual parity with the Home shelf
 * 4. Honest Loading (skeletons), Ready (all items), and Empty states
 */
@Composable
fun CuratedPlaylistsScreen(
    playlistsModule: HomeModule<List<PlaylistSummary>>,
    onBack: () -> Unit,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Top navigation bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dimensions.spaceLg,
                    end = dimensions.spaceLg,
                    top = dimensions.spaceSm,
                    bottom = dimensions.spaceSm
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SonaraIconButton(
                onClick = onBack,
                contentDescription = "Back to Home",
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
                text = stringResource(R.string.home_playlist_catalog),
                style = typography.sectionTitle,
                color = colors.primaryText,
                modifier = Modifier.padding(start = dimensions.spaceSm)
            )
        }

        // Catalogue content grid
        when (playlistsModule) {
            HomeModule.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = dimensions.spaceLg)
                ) {
                    CuratedPlaylistsSkeleton()
                }
            }

            is HomeModule.Ready -> {
                val playlists = playlistsModule.value
                if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(dimensions.spaceLg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No curated playlists available.",
                            style = typography.secondaryBody,
                            color = colors.secondaryText
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 76.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = dimensions.spaceLg,
                            end = dimensions.spaceLg,
                            top = dimensions.spaceSm,
                            bottom = dimensions.space2Xl
                        ),
                        verticalArrangement = Arrangement.spacedBy(dimensions.spaceLg),
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                PlaylistGridTile(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist) }
                                )
                            }
                        }
                    }
                }
            }

            HomeModule.Hidden -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(dimensions.spaceLg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Unable to load curated playlists.",
                        style = typography.secondaryBody,
                        color = colors.secondaryText
                    )
                }
            }
        }
    }
}
