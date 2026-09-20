package com.example.sonara.feature.shell

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons

sealed class NavigationDestination(
    val route: String,
    @StringRes val titleResId: Int,
    val icon: ImageVector
) {
    data object Home : NavigationDestination("home", R.string.nav_home, PhosphorIcons.House)
    data object Search : NavigationDestination("search", R.string.nav_search, PhosphorIcons.MagnifyingGlass)
    data object Library : NavigationDestination("library", R.string.nav_library, PhosphorIcons.Books)
    data object Playlists : NavigationDestination("playlists", R.string.nav_playlists, PhosphorIcons.Playlist)
    data object Settings : NavigationDestination("settings", R.string.nav_settings, PhosphorIcons.SlidersHorizontal)

    companion object {
        val items by lazy { listOf(Home, Search, Library, Playlists) }
    }
}
