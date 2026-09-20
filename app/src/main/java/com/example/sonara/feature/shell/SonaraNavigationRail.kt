package com.example.sonara.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import com.example.sonara.core.ui.theme.SonaraTheme

/**
 * SonaraNavigationRail — Tablet / Wide Screen adaptive navigation rail.
 */
@Composable
fun SonaraNavigationRail(
    currentRoute: String,
    onNavigate: (NavigationDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val materials = SonaraTheme.materials
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Column(
        modifier = modifier
            .width(88.dp)
            .fillMaxHeight()
            .background(materials.navigation)
            .statusBarsPadding()
            .padding(vertical = dimensions.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sonara",
            style = typography.screenTitle,
            color = colors.accent
        )

        Spacer(modifier = Modifier.height(dimensions.space2Xl))

        NavigationDestination.items.forEach { item ->
            val isSelected = currentRoute == item.route
            val contentColor = if (isSelected) colors.accent else colors.secondaryText
            val localizedTitle = stringResource(item.titleResId)

            Column(
                modifier = Modifier
                    .padding(vertical = dimensions.spaceMd)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 28.dp),
                        role = Role.Tab,
                        onClick = { onNavigate(item) }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = localizedTitle,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = localizedTitle,
                    style = typography.navigationLabel,
                    color = contentColor
                )
            }
        }
    }
}
