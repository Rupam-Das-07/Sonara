package com.example.sonara.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.components.SonaraDivider
import com.example.sonara.core.ui.theme.SonaraTheme

/**
 * Bottom Navigation Bar with Phosphor Icons and localized labels.
 */
@Composable
fun SonaraBottomNavBar(
    currentRoute: String,
    onNavigate: (NavigationDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val materials = SonaraTheme.materials
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(materials.navigation)
            .padding(bottom = bottomInset)
    ) {
        SonaraDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = dimensions.spaceXs),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationDestination.items.forEach { item ->
                val isSelected = currentRoute == item.route
                val contentColor = if (isSelected) colors.accent else colors.secondaryText
                val localizedTitle = stringResource(item.titleResId)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 24.dp),
                            role = Role.Tab,
                            onClick = { onNavigate(item) }
                        )
                        .padding(vertical = dimensions.spaceXs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = localizedTitle,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = localizedTitle,
                        style = typography.caption,
                        color = contentColor
                    )
                }
            }
        }
    }
}
