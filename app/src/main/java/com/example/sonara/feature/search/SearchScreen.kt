package com.example.sonara.feature.search

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraCard
import com.example.sonara.core.ui.components.SonaraDivider
import com.example.sonara.core.ui.components.SonaraEmptyState
import com.example.sonara.core.ui.components.SonaraErrorBanner
import com.example.sonara.core.ui.components.SonaraLoadingIndicator
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.library.TrackRowItem
import com.example.sonara.feature.search.components.ClearSearchHistoryConfirmationDialog
import com.example.sonara.feature.search.components.SearchHistoryItemRow
import com.example.sonara.feature.search.components.SearchSuggestionItemRow

/**
 * Native Search Screen supporting dual-mode discovery (Mode 1: Songs & Mode 2: Videos),
 * live search-as-you-type autocomplete suggestions, search history, progressive loading, and playback.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onQuerySubmit: (String) -> Unit,
    onSuggestionSelected: (SearchSuggestion) -> Unit = { suggestion ->
        onQueryChange(suggestion.query)
        onQuerySubmit(suggestion.query)
    },
    onSelectMode: (SearchMode) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onToggleLike: (Track, Boolean) -> Unit,
    isLiked: (String) -> Boolean,
    onDeleteHistoryEntry: (Long) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions
    val focusManager = LocalFocusManager.current

    var isClearHistoryDialogOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensions.spaceLg),
        verticalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        Spacer(modifier = Modifier.height(dimensions.spaceSm))

        // Search Input Bar
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    style = typography.body,
                    color = colors.secondaryText
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = PhosphorIcons.MagnifyingGlass,
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            onQueryChange("")
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = PhosphorIcons.X,
                            contentDescription = stringResource(R.string.action_clear),
                            tint = colors.secondaryText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    onQuerySubmit(query)
                }
            ),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = colors.primaryText,
                unfocusedTextColor = colors.primaryText
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(dimensions.dividerThickness, colors.divider, RoundedCornerShape(24.dp))
        )

        // Main Search Content: 3 distinct mutual states
        if (query.isBlank()) {
            if (state.searchHistory.isNotEmpty()) {
                // Search History Section (Past Searches)
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.search_recent_header),
                            style = typography.caption.copy(color = colors.accent, fontWeight = FontWeight.Bold)
                        )
                        TextButton(
                            onClick = { isClearHistoryDialogOpen = true }
                        ) {
                            Text(
                                text = stringResource(R.string.action_clear_all),
                                style = typography.caption.copy(color = colors.secondaryText)
                            )
                        }
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(dimensions.spaceXs),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.searchHistory, key = { it.id }) { historyEntry ->
                            SearchHistoryItemRow(
                                entry = historyEntry,
                                onClick = {
                                    focusManager.clearFocus()
                                    onQueryChange(historyEntry.query)
                                    onQuerySubmit(historyEntry.query)
                                },
                                onDelete = {
                                    onDeleteHistoryEntry(historyEntry.id)
                                }
                            )
                            SonaraDivider()
                        }
                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            } else {
                SonaraEmptyState(
                    title = stringResource(R.string.search_discover_title),
                    message = stringResource(R.string.search_discover_message)
                )
            }
        } else if (!state.isSearchSubmitted) {
            // Typing State: Suggestions Only, rendered directly on native background (no card/container)
            if (state.relatedSuggestions.isNotEmpty()) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(dimensions.spaceXs),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.search_suggestions_header),
                            style = typography.caption.copy(color = colors.accent, fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(bottom = dimensions.spaceXs)
                        )
                    }
                    itemsIndexed(state.relatedSuggestions) { index, suggestion ->
                        SearchSuggestionItemRow(
                            suggestion = suggestion,
                            onClick = {
                                focusManager.clearFocus()
                                onSuggestionSelected(suggestion)
                            }
                        )
                        if (index < state.relatedSuggestions.lastIndex) {
                            SonaraDivider()
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            } else if (state.isSuggestionsLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = dimensions.spaceXl),
                    contentAlignment = Alignment.TopCenter
                ) {
                    SonaraLoadingIndicator()
                }
            }
        } else {
            // Submitted Search State: Tab Switcher + Active Mode Results
            SearchModeTabSwitcher(
                activeMode = state.activeMode,
                onSelectMode = onSelectMode,
                modifier = Modifier
                    .width(200.dp)
                    .align(Alignment.Start)
            )

            when (val modeState = state.currentModeState) {
                is ModeSearchState.Initial -> {
                    SonaraEmptyState(
                        title = stringResource(R.string.search_discover_title),
                        message = stringResource(R.string.search_discover_message)
                    )
                }
                is ModeSearchState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = dimensions.spaceXl),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        SonaraLoadingIndicator()
                    }
                }
                is ModeSearchState.Error -> {
                    SonaraErrorBanner(
                        message = modeState.message,
                        onRetry = {
                            onQuerySubmit(query)
                        }
                    )
                }
                is ModeSearchState.Empty -> {
                    val emptyTitle = if (state.activeMode == SearchMode.SONGS) {
                        stringResource(R.string.search_no_songs_title)
                    } else {
                        stringResource(R.string.search_no_videos_title)
                    }
                    val emptyMessage = if (state.activeMode == SearchMode.SONGS) {
                        stringResource(R.string.search_no_songs_message, modeState.query)
                    } else {
                        stringResource(R.string.search_no_videos_message, modeState.query)
                    }
                    SonaraCard {
                        SonaraEmptyState(
                            title = emptyTitle,
                            message = emptyMessage
                        )
                    }
                }
                is ModeSearchState.Success -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(modeState.tracks, key = { it.id }) { track ->
                            TrackRowItem(
                                track = track,
                                isLiked = isLiked(track.id),
                                onPlay = { onPlayTrack(track) },
                                onToggleLike = { liked -> onToggleLike(track, liked) }
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }

    if (isClearHistoryDialogOpen) {
        ClearSearchHistoryConfirmationDialog(
            onConfirm = {
                onClearHistory()
                isClearHistoryDialogOpen = false
            },
            onDismiss = {
                isClearHistoryDialogOpen = false
            }
        )
    }
}

/**
 * Editorial segmented pill control for switching search mode (Songs vs Videos).
 */
@Composable
private fun SearchModeTabSwitcher(
    activeMode: SearchMode,
    onSelectMode: (SearchMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val modes = listOf(SearchMode.SONGS, SearchMode.VIDEOS)
    val selectedIndex = if (activeMode == SearchMode.SONGS) 0 else 1

    BoxWithConstraints(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(dimensions.dividerThickness, colors.divider, RoundedCornerShape(18.dp))
            .padding(2.dp)
    ) {
        val tabWidth = maxWidth / 2
        val animatedOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "SearchModeTabOffset"
        )

        // Indicator pill
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.accent)
        )

        // Labels
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEach { mode ->
                val isSelected = mode == activeMode
                val label = when (mode) {
                    SearchMode.SONGS -> stringResource(R.string.search_mode_songs)
                    SearchMode.VIDEOS -> stringResource(R.string.search_mode_videos)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelectMode(mode) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = typography.caption.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (isSelected) colors.background else colors.secondaryText
                    )
                }
            }
        }
    }
}
