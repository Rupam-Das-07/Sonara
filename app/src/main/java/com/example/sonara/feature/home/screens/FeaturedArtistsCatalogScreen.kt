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
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.feature.home.HomeModule
import com.example.sonara.feature.home.components.ArtistTile
import com.example.sonara.feature.home.components.FeaturedArtistsSkeleton

/**
 * FeaturedArtistsCatalogScreen — Dedicated catalogue screen presenting all featured artists.
 *
 * Visual Architecture:
 * 1. Top bar with back navigation (← Featured Artists)
 * 2. Responsive adaptive grid of circular artist tiles (minSize = 76.dp)
 * 3. Reuses [ArtistTile] to maintain visual parity with the Home shelf
 * 4. Honest Loading (skeletons), Ready (all items), and Empty states
 *
 * Entered only when artists.size > 8. Mirrors [CuratedPlaylistsScreen] exactly.
 */
@Composable
fun FeaturedArtistsCatalogScreen(
    artistsModule: HomeModule<List<FeaturedArtist>>,
    onBack: () -> Unit,
    onArtistClick: (FeaturedArtist) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Contextual navigation header
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
                text = stringResource(R.string.home_featured_artists),
                style = typography.sectionTitle,
                color = colors.primaryText,
                modifier = Modifier.padding(start = dimensions.spaceSm)
            )
        }

        when (artistsModule) {
            HomeModule.Hidden -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No artists available.",
                        style = typography.secondaryBody,
                        color = colors.secondaryText
                    )
                }
            }

            HomeModule.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = dimensions.spaceLg)
                ) {
                    FeaturedArtistsSkeleton()
                }
            }

            is HomeModule.Ready -> {
                val artists = artistsModule.value
                if (artists.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No artists available.",
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
                            bottom = dimensions.space2Xl
                        ),
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm),
                        verticalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                    ) {
                        items(artists, key = { it.id }) { artist ->
                            Box(contentAlignment = Alignment.Center) {
                                ArtistTile(
                                    artist = artist,
                                    onClick = { onArtistClick(artist) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
