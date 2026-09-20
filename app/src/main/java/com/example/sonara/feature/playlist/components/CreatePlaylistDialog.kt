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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.theme.SonaraTheme

@Composable
fun CreatePlaylistDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    if (!isOpen) return

    var name by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun submit() {
        val trimmed = name.trim()
        when {
            trimmed.isBlank() -> {
                errorMessage = "Playlist name cannot be empty"
            }
            trimmed.length > 60 -> {
                errorMessage = "Name cannot exceed 60 characters"
            }
            else -> {
                errorMessage = null
                onConfirm(trimmed)
            }
        }
    }

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
                text = "Create Playlist",
                style = typography.sectionTitle,
                color = colors.primaryText
            )

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (errorMessage != null) errorMessage = null
                },
                placeholder = {
                    Text("e.g. Late Night Acoustic", style = typography.body, color = colors.disabledText)
                },
                singleLine = true,
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                    focusedTextColor = colors.primaryText,
                    unfocusedTextColor = colors.primaryText,
                    cursorColor = colors.accent,
                    errorBorderColor = colors.accent,
                    focusedContainerColor = colors.surfaceVariant,
                    unfocusedContainerColor = colors.surfaceVariant
                ),
                shape = RoundedCornerShape(8.dp)
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    style = typography.caption,
                    color = colors.accent,
                    modifier = Modifier.padding(start = 4.dp)
                )
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
                    text = "Create",
                    onClick = { submit() },
                    variant = SonaraButtonVariant.Primary
                )
            }
        }
    }
}
