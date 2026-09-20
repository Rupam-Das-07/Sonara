package com.example.sonara.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sonara.R
import com.example.sonara.core.ui.components.LucideIcons
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraDivider
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.theme.SonaraTheme

/**
 * Top App Bar containing Sonara branding, section title, Back button (in Settings), Settings entry,
 * and quick Theme Toggle placed on the right side of the settings icon.
 * Opaque Surface ensuring clean native Android top-app-bar/content separation.
 */
@Composable
fun SonaraTopBar(
    currentTitle: String,
    onToggleTheme: () -> Unit = {},
    isSettings: Boolean = false,
    onOpenSettings: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val materials = SonaraTheme.materials
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Surface(
        color = materials.topBar,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensions.spaceLg, vertical = dimensions.spaceSm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSettings) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                    ) {
                        SonaraIconButton(
                            onClick = onBack,
                            contentDescription = "Back",
                            variant = SonaraIconButtonVariant.Ghost
                        ) {
                            Icon(
                                imageVector = PhosphorIcons.ArrowLeft,
                                contentDescription = "Back",
                                tint = colors.primaryText,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.nav_settings),
                            style = typography.display,
                            color = colors.primaryText
                        )
                    }
                } else {
                    Text(
                        text = "Sonara",
                        style = typography.display,
                        color = colors.accent
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                ) {
                    if (!isSettings) {
                        SonaraIconButton(
                            onClick = onOpenSettings,
                            contentDescription = stringResource(R.string.nav_settings),
                            variant = SonaraIconButtonVariant.Ghost
                        ) {
                            Icon(
                                imageVector = PhosphorIcons.SlidersHorizontal,
                                contentDescription = stringResource(R.string.nav_settings),
                                tint = colors.secondaryText,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Theme Toggle Icon Button
                    val isDark = colors.isDark
                    val themeIcon = if (isDark) LucideIcons.Sun else LucideIcons.Moon
                    val themeDescription = if (isDark) "Switch to Light Mode" else "Switch to Dark Mode"

                    SonaraIconButton(
                        onClick = onToggleTheme,
                        contentDescription = themeDescription,
                        variant = SonaraIconButtonVariant.Ghost
                    ) {
                        Icon(
                            imageVector = themeIcon,
                            contentDescription = themeDescription,
                            tint = colors.secondaryText,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            if (!isSettings && currentTitle.isNotEmpty() && currentTitle != "Home") {
                Text(
                    text = currentTitle,
                    style = typography.secondaryBody,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(horizontal = dimensions.spaceLg, vertical = dimensions.spaceXs)
                )
            }

            SonaraDivider()
        }
    }
}
