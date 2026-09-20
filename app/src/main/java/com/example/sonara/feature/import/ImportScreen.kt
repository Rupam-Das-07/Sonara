package com.example.sonara.feature.import

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.components.SonaraCard
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.ImportMatchItem
import com.example.sonara.domain.model.Track

@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onNavigateToPlaylist: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    // Storage Access Framework picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onFileSelected(uri)
        } else {
            // Cancelled selection
            if (state.step is ImportStep.AwaitingFile) {
                viewModel.cancelImport()
                onDismiss()
            }
        }
    }

    // The SAF file picker is launched ONLY from an explicit user action: the "Import" button in
    // the AwaitingFile landing UI below, and the ParseFailed "Retry" action. It must never be
    // launched as a side effect of entering a state. A previous LaunchedEffect(state.step) that
    // auto-launched the picker whenever step == AwaitingFile made the picker open by itself the
    // moment the Import screen appeared -- startImport() sets step = AwaitingFile to show the
    // landing screen, which immediately satisfied that condition. The ~1s the user saw before the
    // picker appeared was just the system DocumentsUI activity's own start-up latency.

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        when (val step = state.step) {
            is ImportStep.Idle, is ImportStep.AwaitingFile -> {
                // Standby file picker launcher UI
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(dimensions.spaceLg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.import_select_file),
                        style = typography.sectionTitle,
                        color = colors.primaryText
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceSm))
                    Text(
                        text = stringResource(R.string.import_select_file_desc),
                        style = typography.caption,
                        color = colors.secondaryText
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceLg))
                    SonaraButton(
                        text = stringResource(R.string.import_button),
                        onClick = {
                            filePickerLauncher.launch(arrayOf("text/*", "application/json", "*/*"))
                        },
                        variant = SonaraButtonVariant.Primary
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceSm))
                    SonaraButton(
                        text = stringResource(R.string.import_cancel),
                        onClick = {
                            viewModel.cancelImport()
                            onDismiss()
                        },
                        variant = SonaraButtonVariant.Text
                    )
                }
            }

            is ImportStep.Parsing -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(dimensions.spaceLg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = colors.accent
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceMd))
                    Text(
                        text = stringResource(R.string.import_parsing),
                        style = typography.sectionTitle,
                        color = colors.primaryText
                    )
                }
            }

            is ImportStep.Parsed, is ImportStep.Matching -> {
                val matching = step as? ImportStep.Matching
                val processed = matching?.processedTracks ?: 0
                val total = matching?.totalTracks ?: 1
                val progressFraction = (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(dimensions.spaceLg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.import_matching, processed, total),
                        style = typography.sectionTitle,
                        color = colors.primaryText
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceMd))
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = colors.accent,
                        trackColor = colors.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceLg))
                    SonaraButton(
                        text = stringResource(R.string.import_cancel),
                        onClick = {
                            viewModel.cancelImport()
                            onDismiss()
                        },
                        variant = SonaraButtonVariant.Text
                    )
                }
            }

            is ImportStep.Review -> {
                ReviewContent(
                    review = step,
                    onToggleSelect = { viewModel.toggleItemSelection(it) },
                    onSelectAlternative = { order, alt -> viewModel.selectAlternative(order, alt) },
                    onUpdateName = { viewModel.updatePlaylistName(it) },
                    onConfirm = { viewModel.confirmImport() },
                    onCancel = {
                        viewModel.cancelImport()
                        onDismiss()
                    }
                )
            }

            is ImportStep.Persisting -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(dimensions.spaceLg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = colors.accent
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceMd))
                    Text(
                        text = stringResource(R.string.import_persisting),
                        style = typography.sectionTitle,
                        color = colors.primaryText
                    )
                }
            }

            is ImportStep.Done -> {
                val result = step.result
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(dimensions.spaceLg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceMd))
                    Text(
                        text = stringResource(R.string.import_done_title),
                        style = typography.screenTitle,
                        color = colors.primaryText
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceSm))
                    Text(
                        text = stringResource(R.string.import_done_msg, result.totalTracksAdded, result.playlistName),
                        style = typography.body,
                        color = colors.secondaryText
                    )
                    if (result.collapsedDuplicates > 0) {
                        Spacer(modifier = Modifier.height(dimensions.spaceXs))
                        Text(
                            text = stringResource(R.string.import_duplicates_merged, result.collapsedDuplicates),
                            style = typography.caption,
                            color = colors.secondaryText
                        )
                    }
                    Spacer(modifier = Modifier.height(dimensions.spaceLg))
                    SonaraButton(
                        text = stringResource(R.string.import_view_playlist),
                        onClick = {
                            viewModel.cancelImport()
                            onNavigateToPlaylist(result.playlistId)
                        },
                        variant = SonaraButtonVariant.Primary
                    )
                }
            }

            is ImportStep.ParseFailed -> {
                ErrorView(
                    error = step.error,
                    onRetry = {
                        filePickerLauncher.launch(arrayOf("text/*", "application/json", "*/*"))
                    },
                    onCancel = {
                        viewModel.cancelImport()
                        onDismiss()
                    }
                )
            }

            is ImportStep.MatchFailed -> {
                ErrorView(
                    error = step.error,
                    onRetry = { viewModel.retryMatching() },
                    onCancel = {
                        viewModel.cancelImport()
                        onDismiss()
                    }
                )
            }

            is ImportStep.PersistFailed -> {
                ErrorView(
                    error = step.error,
                    onRetry = { viewModel.retryPersistFromReview() },
                    onCancel = {
                        viewModel.cancelImport()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun ReviewContent(
    review: ImportStep.Review,
    onToggleSelect: (Int) -> Unit,
    onSelectAlternative: (Int, Track) -> Unit,
    onUpdateName: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val readyCount = review.confidentItems.count { it.isSelected } + review.reviewItems.count { it.isSelected }
    val toReviewCount = review.reviewItems.size
    val skippedCount = review.skippedItems.size

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimensions.spaceMd, vertical = dimensions.spaceSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.import_cancel),
                    tint = colors.primaryText
                )
            }
            Text(
                text = stringResource(R.string.import_review_title),
                style = typography.sectionTitle,
                color = colors.primaryText,
                modifier = Modifier.weight(1f)
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = dimensions.spaceLg)
        ) {
            // Editable Playlist Name Field
            item {
                Spacer(modifier = Modifier.height(dimensions.spaceSm))
                OutlinedTextField(
                    value = review.playlistName,
                    onValueChange = onUpdateName,
                    label = { Text(stringResource(R.string.import_playlist_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.divider,
                        focusedTextColor = colors.primaryText,
                        unfocusedTextColor = colors.primaryText
                    )
                )
                Spacer(modifier = Modifier.height(dimensions.spaceSm))
                // Counts Subtitle
                Text(
                    text = stringResource(R.string.import_ready_count, readyCount, toReviewCount, skippedCount),
                    style = typography.caption,
                    color = colors.secondaryText
                )
                Spacer(modifier = Modifier.height(dimensions.spaceMd))
            }

            // Bucket 1: Confident Matches
            if (review.confidentItems.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.import_confident_header, review.confidentItems.size),
                        style = typography.sectionTitle,
                        color = colors.primaryText,
                        modifier = Modifier.padding(vertical = dimensions.spaceSm)
                    )
                }
                items(review.confidentItems, key = { "conf_${it.sourceOrder}" }) { item ->
                    ReviewTrackRow(
                        item = item,
                        onToggle = { onToggleSelect(item.sourceOrder) },
                        onSelectAlternative = { alt -> onSelectAlternative(item.sourceOrder, alt) }
                    )
                }
            }

            // Bucket 2: Needs Review (Ambiguous Matches)
            if (review.reviewItems.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(dimensions.spaceMd))
                    Text(
                        text = stringResource(R.string.import_review_header, review.reviewItems.size),
                        style = typography.sectionTitle,
                        color = colors.accent,
                        modifier = Modifier.padding(vertical = dimensions.spaceSm)
                    )
                }
                items(review.reviewItems, key = { "rev_${it.sourceOrder}" }) { item ->
                    ReviewTrackRow(
                        item = item,
                        onToggle = { onToggleSelect(item.sourceOrder) },
                        onSelectAlternative = { alt -> onSelectAlternative(item.sourceOrder, alt) }
                    )
                }
            }

            // Bucket 3: Won't Be Added (Unmatched / Skipped)
            if (review.skippedItems.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(dimensions.spaceMd))
                    Text(
                        text = stringResource(R.string.import_skipped_header, review.skippedItems.size),
                        style = typography.sectionTitle,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(vertical = dimensions.spaceSm)
                    )
                }
                items(review.skippedItems, key = { "skip_${it.sourceOrder}" }) { item ->
                    SkippedTrackRow(item = item)
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // Bottom Action Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(dimensions.spaceMd)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SonaraButton(
                    text = stringResource(R.string.import_cancel),
                    onClick = onCancel,
                    variant = SonaraButtonVariant.Text
                )
                SonaraButton(
                    text = "${stringResource(R.string.import_confirm_button)} ($readyCount)",
                    onClick = onConfirm,
                    enabled = readyCount > 0,
                    variant = SonaraButtonVariant.Primary
                )
            }
        }
    }
}

@Composable
private fun ReviewTrackRow(
    item: ReviewItemState,
    onToggle: () -> Unit,
    onSelectAlternative: (Track) -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = dimensions.spaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accent,
                uncheckedColor = colors.secondaryText
            )
        )

        // Artwork
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!item.selectedTrack.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.selectedTrack.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(modifier = Modifier.width(dimensions.spaceSm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.selectedTrack.title,
                style = typography.body.copy(fontWeight = FontWeight.Medium),
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.selectedTrack.artist,
                style = typography.caption,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // If ambiguous and has alternatives, show option to choose alternative
            if (item.isAmbiguous && item.alternatives.isNotEmpty()) {
                Box {
                    Text(
                        text = stringResource(R.string.import_change_selection),
                        style = typography.caption.copy(color = colors.accent, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .clickable { menuExpanded = true }
                            .padding(top = 2.dp)
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(colors.surface)
                    ) {
                        item.alternatives.forEach { alt ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(alt.title, style = typography.body, color = colors.primaryText)
                                        Text(alt.artist, style = typography.caption, color = colors.secondaryText)
                                    }
                                },
                                onClick = {
                                    onSelectAlternative(alt)
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkippedTrackRow(item: ImportMatchItem) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val reasonText = when (item.reason) {
        "local_file" -> stringResource(R.string.import_reason_local_file)
        "episode" -> stringResource(R.string.import_reason_episode)
        "empty_query" -> stringResource(R.string.import_reason_empty_query)
        "no_candidates" -> stringResource(R.string.import_reason_no_candidates)
        else -> stringResource(R.string.import_reason_below_threshold)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimensions.spaceXs)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.resolvedTrack?.title ?: "Track #${item.sourceOrder + 1}",
                style = typography.body.copy(fontWeight = FontWeight.Normal),
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = reasonText,
                style = typography.caption,
                color = colors.error.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ErrorView(
    error: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimensions.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = colors.error,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(dimensions.spaceMd))
        Text(
            text = error,
            style = typography.body,
            color = colors.primaryText,
            modifier = Modifier.padding(horizontal = dimensions.spaceMd)
        )
        Spacer(modifier = Modifier.height(dimensions.spaceLg))
        Row(horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)) {
            SonaraButton(
                text = stringResource(R.string.import_cancel),
                onClick = onCancel,
                variant = SonaraButtonVariant.Text
            )
            SonaraButton(
                text = stringResource(R.string.import_retry),
                onClick = onRetry,
                variant = SonaraButtonVariant.Primary
            )
        }
    }
}
