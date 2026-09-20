package com.example.sonara.feature.playlist.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.Track

@Composable
fun RemoveSongConfirmationDialog(
    track: Track?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (track == null) return

    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .padding(dimensions.spaceXl),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
        ) {
            Text(
                text = "Remove from Playlist?",
                style = typography.sectionTitle,
                color = colors.primaryText
            )

            Text(
                text = "Remove \"${track.title}\" from this playlist?",
                style = typography.body,
                color = colors.secondaryText
            )

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
                    text = "Remove",
                    onClick = onConfirm,
                    variant = SonaraButtonVariant.Primary
                )
            }
        }
    }
}
