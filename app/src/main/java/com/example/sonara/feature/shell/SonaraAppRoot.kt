package com.example.sonara.feature.shell

import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.motion.LocalReduceMotion
import com.example.sonara.core.ui.motion.SonaraMotion
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.home.HomeScreen
import com.example.sonara.feature.home.HomeUiState
import com.example.sonara.feature.home.HomeViewModel
import com.example.sonara.feature.home.HomeViewMode
import com.example.sonara.feature.home.resolveHomeViewMode
import com.example.sonara.feature.library.LibraryScreen
import com.example.sonara.feature.library.LibraryViewModel
import com.example.sonara.feature.lyrics.LyricsViewModel
import com.example.sonara.feature.player.components.AudioOutputSelectorDialog
import com.example.sonara.feature.player.components.DownloadProgressDialog
import com.example.sonara.feature.player.components.ExpandedPlayerScreen
import com.example.sonara.feature.player.components.MiniPlayer
import com.example.sonara.feature.player.PlayerUiState
import com.example.sonara.feature.player.PlayerViewModel
import com.example.sonara.feature.import.ImportScreen
import com.example.sonara.feature.import.ImportStep
import com.example.sonara.feature.import.ImportUiState
import com.example.sonara.feature.import.ImportViewModel
import com.example.sonara.feature.playlist.PlaylistUiState
import com.example.sonara.feature.playlist.PlaylistViewModel
import com.example.sonara.feature.playlist.PlaylistsScreen
import com.example.sonara.feature.playlist.UserPlaylistDetailScreen
import com.example.sonara.feature.playlist.components.AddToPlaylistSheet
import com.example.sonara.feature.playlist.components.CreatePlaylistDialog
import com.example.sonara.feature.playlist.components.DeletePlaylistConfirmationDialog
import com.example.sonara.feature.playlist.components.RemoveSongConfirmationDialog
import com.example.sonara.feature.playlist.components.RenamePlaylistDialog
import com.example.sonara.feature.search.SearchScreen
import com.example.sonara.feature.search.SearchViewModel
import com.example.sonara.feature.settings.SettingsScreen
import com.example.sonara.feature.settings.SettingsUiState
import com.example.sonara.feature.settings.SettingsViewModel
import kotlin.math.abs
import kotlin.math.hypot

private const val THEME_MOTION_TAG = "SonaraThemeMotion"

/**
 * Duration of the wave-ripple theme reveal, in milliseconds.
 *
 * Immutable brand value: the Sonara web player runs `ToggleTheme animationType="wave-ripple"` at
 * `duration={800}`, which the web implementation scales by 1.5 to 1200 ms
 * (`WEB_THEME_TOGGLE_MOTION_SPECIFICATION.md` section 2.2). Do not shorten it -- the first ~400 ms
 * are the anticipation phase, so a shorter duration does not just speed the reveal up, it deletes
 * the part of the motion that gives it its character.
 */
private const val THEME_REVEAL_DURATION_MS = 1200

/**
 * Live read of the platform animator duration scale. Developer options ("Animator duration scale"),
 * battery saver, and the accessibility "remove animations" switch all write to this setting.
 *
 * Compose's animation clock honours it continuously -- at a scale of 0 every tween completes on its
 * first frame -- so the theme reveal must consult it at the moment of the toggle. A value cached at
 * startup goes stale as soon as the user flips any of those switches, and the reveal then "runs" in
 * 0 ms while still reporting success.
 */
private fun systemAnimatorDurationScale(context: android.content.Context): Float =
    try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    } catch (_: Exception) {
        1f
    }

/**
 * Root composition shell hosting persistent Desktop MiniPlayer, Navigation, Lyrics Sheet, Playlists, and Screens.
 *
 * Theme changes use Web View Transition parity ("wave-ripple"): the outgoing theme is mounted as a
 * still backdrop and the live shell -- which is never unmounted -- is clipped to a circle grown from
 * the SCREEN CENTRE over 1200 ms on an anticipatory overshoot curve. The reveal is driven entirely
 * from inside the draw block, so it costs no recomposition and no re-layout.
 *
 * The geometry, origin, easing and duration are frozen brand identity. See
 * [THEME_REVEAL_DURATION_MS] and the draw block on the live shell below.
 */
@Composable
fun SonaraAppRoot(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    searchViewModel: SearchViewModel,
    homeViewModel: HomeViewModel,
    lyricsViewModel: LyricsViewModel,
    playlistViewModel: PlaylistViewModel,
    settingsViewModel: SettingsViewModel,
    importViewModel: ImportViewModel? = null,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    openExpandedPlayerTrigger: Long = 0L,
    modifier: Modifier = Modifier
) {
    val importFallbackState = remember { kotlinx.coroutines.flow.MutableStateFlow(ImportUiState()) }
    val importState by (importViewModel?.uiState ?: importFallbackState).collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    val searchState by searchViewModel.uiState.collectAsState()
    val searchQuery by searchViewModel.query.collectAsState()
    val homeState by homeViewModel.uiState.collectAsState()
    val lyricsState by lyricsViewModel.uiState.collectAsState()
    val playlistState by playlistViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val audioOutputState by playerViewModel.audioOutputState.collectAsState()

    var currentDestination by remember { mutableStateOf<NavigationDestination>(NavigationDestination.Home) }
    var previousDestination by remember { mutableStateOf<NavigationDestination>(NavigationDestination.Home) }
    var isExpandedPlayerOpen by remember { mutableStateOf(false) }
    var isAudioOutputDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(openExpandedPlayerTrigger) {
        if (openExpandedPlayerTrigger > 0L) {
            isExpandedPlayerOpen = true
        }
    }

    // System back navigation routing:
    // 1. Settings routes back to previous destination (or Home fallback).
    // 2. Peer tabs (Search, Library, Playlists root) route back to Home instead of closing the app.
    // 3. Playlists detail (selectedPlaylist != null) is excluded here so UserPlaylistDetailScreen's
    //    own BackHandler can pop the playlist detail first.
    BackHandler(
        enabled = currentDestination != NavigationDestination.Home &&
            (currentDestination != NavigationDestination.Playlists || playlistState.selectedPlaylist == null)
    ) {
        if (currentDestination == NavigationDestination.Settings) {
            currentDestination = if (previousDestination != NavigationDestination.Settings) {
                previousDestination
            } else {
                NavigationDestination.Home
            }
        } else {
            currentDestination = NavigationDestination.Home
        }
    }

    val isCurrentTrackFavorite = remember(libraryState.likedSongs, playerState.trackId) {
        libraryState.likedSongs.any { it.id == playerState.trackId }
    }
    val effectivePlayerState = playerState.copy(isFavorite = isCurrentTrackFavorite)

    val downloadsMap by playerViewModel.downloads.collectAsState()
    val currentDownloadInfo = downloadsMap[playerState.trackId]
    val isCurrentTrackDownloaded = currentDownloadInfo?.status == com.example.sonara.domain.model.DownloadStatus.DOWNLOADED
    val isCurrentTrackDownloading = currentDownloadInfo?.status == com.example.sonara.domain.model.DownloadStatus.DOWNLOADING || currentDownloadInfo?.status == com.example.sonara.domain.model.DownloadStatus.QUEUED

    var activeDownloadTrack by remember { mutableStateOf<Track?>(null) }

    val context = LocalContext.current

    LaunchedEffect(playlistState.feedbackMessage) {
        playlistState.feedbackMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            playlistViewModel.clearFeedbackMessage()
        }
    }

    // The in-app preference is a hard opt-out. The *system* animator scale is deliberately NOT
    // cached here: it can flip mid-session (battery saver, accessibility "remove animations"), and
    // Compose's animation clock honours it live -- a stale "animations are on" read produces a
    // 0 ms "animation" that draws no frames at all. It is re-read at toggle time instead.
    val reduceMotionPreference = settingsState.preferences.reduceMotion

    val isSystemDark = isSystemInDarkTheme()
    val targetIsDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    var displayedIsDark by remember { mutableStateOf(targetIsDark) }
    var previousIsDark by remember { mutableStateOf(targetIsDark) }
    var isTransitioning by remember { mutableStateOf(false) }
    val rippleProgress = remember { Animatable(1f) }

    // Single, permanently mounted Home scroll state. The reveal backdrop gets its own seeded copy
    // so the live list is never re-created (and never re-measured from the top) by a theme change.
    val homeListState = rememberLazyListState()
    var backdropScroll by remember { mutableStateOf(0 to 0) }

    // Guards against a cancelled reveal (rapid double toggle) tearing down the reveal that replaced
    // it: the loser's finally block would otherwise clear isTransitioning out from under the winner.
    val revealEpoch = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    LaunchedEffect(themeMode, targetIsDark) {
        if (targetIsDark == displayedIsDark) return@LaunchedEffect
        val epoch = revealEpoch.incrementAndGet()

        val systemScale = systemAnimatorDurationScale(context)
        if (reduceMotionPreference || systemScale == 0f) {
            android.util.Log.d(
                THEME_MOTION_TAG,
                "reveal skipped (reduceMotion=$reduceMotionPreference, systemAnimatorScale=$systemScale)"
            )
            rippleProgress.snapTo(1f)
            previousIsDark = targetIsDark
            displayedIsDark = targetIsDark
            isTransitioning = false
            return@LaunchedEffect
        }

        // Close the circle BEFORE the incoming theme is adopted, otherwise the new theme lands
        // full-screen for one frame and the reveal is over before it begins.
        rippleProgress.snapTo(0f)
        previousIsDark = displayedIsDark
        backdropScroll = Pair(
            homeListState.firstVisibleItemIndex,
            homeListState.firstVisibleItemScrollOffset
        )
        displayedIsDark = targetIsDark
        isTransitioning = true
        android.util.Log.d(THEME_MOTION_TAG, "reveal start: dark $previousIsDark -> $targetIsDark")

        try {
            // Spend the expensive frames before the clock starts running. Mounting the
            // outgoing-theme backdrop is the heaviest frame of the whole toggle, and tween() is
            // wall-clock driven: it does not catch up, it jumps. Without this gate the first frame
            // that reaches the screen is already near the end of the curve, which reads as
            // "the animation never rendered at all".
            withFrameNanos { }
            withFrameNanos { }

            val startNanos = System.nanoTime()
            rippleProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = THEME_REVEAL_DURATION_MS,
                    // Immutable brand curve, ported 1:1 from the web player's
                    // cubic-bezier(0.68, -0.55, 0.265, 1.55). It deliberately dips below 0 (to
                    // -0.093 at 240ms) and overshoots past 1 (to +1.093 at 960ms). Animatable is
                    // unbounded here on purpose so both excursions survive.
                    easing = CubicBezierEasing(0.68f, -0.55f, 0.265f, 1.55f)
                )
            )
            android.util.Log.d(
                THEME_MOTION_TAG,
                "reveal ran ${(System.nanoTime() - startNanos) / 1_000_000}ms of an expected " +
                    "${THEME_REVEAL_DURATION_MS}ms (systemAnimatorScale=$systemScale)"
            )
        } finally {
            if (revealEpoch.get() == epoch) {
                isTransitioning = false
                previousIsDark = targetIsDark
            }
        }
    }

    // One argument list, two mount points: the live shell, and -- only while the reveal runs -- the
    // outgoing-theme backdrop beneath it. Sibling call sites, not the two arms of an if/else, so a
    // theme change no longer tears the live shell down and rebuilds it from scratch.
    val shellContent: @Composable (LazyListState) -> Unit = { listState ->
        AppScaffoldContent(
            currentDestination = currentDestination,
            onNavigate = { currentDestination = it },
            onOpenSettings = {
                previousDestination = currentDestination
                currentDestination = NavigationDestination.Settings
            },
            onBack = { currentDestination = previousDestination },
            themeMode = themeMode,
            onToggleTheme = onToggleTheme,
            playerState = effectivePlayerState,
            playerViewModel = playerViewModel,
            libraryState = libraryState,
            libraryViewModel = libraryViewModel,
            searchState = searchState,
            searchQuery = searchQuery,
            searchViewModel = searchViewModel,
            homeState = homeState,
            homeViewModel = homeViewModel,
            homeListState = listState,
            playlistState = playlistState,
            playlistViewModel = playlistViewModel,
            settingsState = settingsState,
            settingsViewModel = settingsViewModel,
            importViewModel = importViewModel,
            audioOutputState = audioOutputState,
            onPlayTrack = onPlayTrack,
            onOpenExpandedPlayer = { isExpandedPlayerOpen = true },
            onOpenAudioOutputSelector = {
                playerViewModel.refreshAudioOutputs()
                isAudioOutputDialogOpen = true
            }
        )
    }

    // Centralized reduced-motion gate for the whole navigation motion system (shell + Home +
    // Playlists all read LocalReduceMotion). Provided once here so both the live shell and the
    // theme-reveal backdrop inherit the same value. System animator-duration-scale == 0 is honoured
    // automatically by Compose's clock and needs no entry here (see SonaraMotion).
    CompositionLocalProvider(LocalReduceMotion provides reduceMotionPreference) {
    Box(modifier = modifier.fillMaxSize()) {
        // BACKDROP: the outgoing theme, held still beneath the growing circle. Mounted for the
        // duration of the reveal only, and only ever as a sibling of the live shell.
        if (isTransitioning) {
            val backdropListState = rememberLazyListState(
                initialFirstVisibleItemIndex = backdropScroll.first,
                initialFirstVisibleItemScrollOffset = backdropScroll.second
            )
            SonaraTheme(darkTheme = previousIsDark) {
                shellContent(backdropListState)
            }
        }

        // LIVE SHELL: one permanent call site. While the reveal runs it already wears the incoming
        // theme and is clipped to the circle growing out of the screen centre; at rest it draws
        // unclipped. isTransitioning and rippleProgress are read inside the draw block, so each
        // frame of the reveal re-runs the draw phase only -- no recomposition, no re-layout.
        SonaraTheme(darkTheme = displayedIsDark) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        // Applied unconditionally so the modifier chain never changes shape; the
                        // draw block itself decides whether to clip. Reading isTransitioning here
                        // instead of branching on it in composition keeps the entire reveal off the
                        // composition and layout phases.
                        if (!isTransitioning) {
                            drawContent()
                            return@drawWithContent
                        }

                        // FROZEN GEOMETRY -- centre origin, half-diagonal radius. Origin and radius
                        // are a matched pair: at |progress| == 1f the circle is tangent to all four
                        // corners simultaneously. Changing one without the other breaks coverage.
                        val maxRadius = hypot(size.width / 2f, size.height / 2f)

                        // USING ABSOLUTE VALUE!
                        // The easing curve (CubicBezierEasing(0.68f, -0.55f, 0.265f, 1.55f))
                        // dips into the negatives for the first ~300ms to create "anticipation".
                        // If clamped to 0, it stays invisible.
                        // If absolute, it appears, shrinks back, then blasts outward!
                        val currentRadius = abs(rippleProgress.value * maxRadius)

                        val centerOffset = Offset(size.width / 2f, size.height / 2f)

                        clipPath(
                            path = Path().apply {
                                addOval(
                                    Rect(
                                        center = centerOffset,
                                        radius = currentRadius
                                    )
                                )
                            }
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    }
            ) {
                shellContent(homeListState)
            }
        }

        // Modal Expanded Player Screen (Immersive Now Playing)
        SonaraTheme(darkTheme = displayedIsDark) {
            ExpandedPlayerScreen(
                isExpanded = isExpandedPlayerOpen,
                playerState = effectivePlayerState,
                lyricsState = lyricsState,
                onCollapse = { isExpandedPlayerOpen = false },
                onPlay = { playerViewModel.play() },
                onPause = { playerViewModel.pause() },
                onNext = { playerViewModel.skipToNext() },
                onPrevious = { playerViewModel.skipToPrevious() },
                peekNextTrack = { playerViewModel.peekNextTrack() },
                peekPreviousTrack = { playerViewModel.peekPreviousTrack() },
                onSeek = { fraction ->
                    val targetMs = (fraction * playerState.durationMs).toLong()
                    playerViewModel.seekTo(targetMs)
                },
                onToggleShuffle = { playerViewModel.toggleShuffle() },
                onToggleRepeat = { playerViewModel.toggleRepeatMode() },
                onToggleFavorite = {
                    val currentTrack = Track(
                        id = playerState.trackId,
                        title = playerState.trackTitle,
                        artist = playerState.artistName,
                        album = playerState.albumTitle,
                        artworkUrl = playerState.artworkUrl
                    )
                    libraryViewModel.toggleLike(currentTrack, isCurrentTrackFavorite)
                },
                onAddPlaylist = {
                    val currentTrack = Track(
                        id = playerState.trackId,
                        title = playerState.trackTitle,
                        artist = playerState.artistName,
                        album = playerState.albumTitle,
                        artworkUrl = playerState.artworkUrl
                    )
                    playlistViewModel.openAddToPlaylist(currentTrack)
                },
                onVolumeChange = { volume -> playerViewModel.setVolume(volume) },
                onToggleMute = { playerViewModel.toggleMute() },
                onToggleRomanization = { lyricsViewModel.toggleRomanization() },
                isDownloaded = isCurrentTrackDownloaded,
                isDownloading = isCurrentTrackDownloading,
                onToggleDownload = {
                    val currentTrack = Track(
                        id = playerState.trackId,
                        title = playerState.trackTitle,
                        artist = playerState.artistName,
                        album = playerState.albumTitle,
                        artworkUrl = playerState.artworkUrl
                    )
                    activeDownloadTrack = currentTrack
                    val downloadInfo = downloadsMap[currentTrack.id]
                    if (downloadInfo == null || (downloadInfo.status != com.example.sonara.domain.model.DownloadStatus.DOWNLOADED && downloadInfo.status != com.example.sonara.domain.model.DownloadStatus.DOWNLOADING)) {
                        playerViewModel.requestDownload(currentTrack)
                    }
                },
                audioOutputState = audioOutputState,
                onOpenAudioOutputSelector = {
                    playerViewModel.refreshAudioOutputs()
                    isAudioOutputDialogOpen = true
                }
            )

            // Centralized DownloadProgressDialog
            if (activeDownloadTrack != null) {
                val track = activeDownloadTrack!!
                val downloadInfo = downloadsMap[track.id]
                DownloadProgressDialog(
                    track = track,
                    downloadInfo = downloadInfo,
                    onCancel = {
                        playerViewModel.cancelDownload(track.id)
                        activeDownloadTrack = null
                    },
                    onRetry = {
                        playerViewModel.requestDownload(track)
                    },
                    onRemove = {
                        playerViewModel.removeDownload(track.id)
                        activeDownloadTrack = null
                    },
                    onPlayOffline = {
                        playerViewModel.playTrack(track)
                        activeDownloadTrack = null
                    },
                    onDismiss = {
                        activeDownloadTrack = null
                    }
                )
            }

            // Centralized AddToPlaylistSheet
            AddToPlaylistSheet(
                isOpen = playlistState.isAddToPlaylistSheetOpen,
                track = playlistState.pendingAddTrack,
                playlists = playlistState.playlists,
                selectedPlaylistId = playlistState.selectedPlaylistIdForAdd,
                onSelectPlaylist = { playlistViewModel.selectPlaylistForAdd(it) },
                onConfirmAdd = { playlistViewModel.addPendingTrackToSelectedPlaylist() },
                onCreateNewPlaylist = { playlistViewModel.openCreateDialog() },
                onDismiss = { playlistViewModel.dismissAddToPlaylist() }
            )

            // Centralized CreatePlaylistDialog
            CreatePlaylistDialog(
                isOpen = playlistState.isCreateDialogOpen,
                onDismiss = { playlistViewModel.dismissCreateDialog() },
                onConfirm = { name -> playlistViewModel.createPlaylist(name, andAddPendingTrack = playlistState.isAddToPlaylistSheetOpen) }
            )

            // Centralized RenamePlaylistDialog
            RenamePlaylistDialog(
                playlist = playlistState.playlistToRename,
                onDismiss = { playlistViewModel.dismissRenameDialog() },
                onConfirm = { newName -> playlistState.playlistToRename?.let { playlistViewModel.renamePlaylist(it.id, newName) } }
            )

            // Centralized DeletePlaylistConfirmationDialog
            DeletePlaylistConfirmationDialog(
                playlist = playlistState.playlistToDelete,
                onDismiss = { playlistViewModel.dismissDeleteDialog() },
                onConfirm = { playlistState.playlistToDelete?.let { playlistViewModel.confirmDeletePlaylist(it.id) } }
            )

            // Centralized RemoveSongConfirmationDialog
            RemoveSongConfirmationDialog(
                track = playlistState.trackToRemove?.second,
                onDismiss = { playlistViewModel.dismissRemoveTrackDialog() },
                onConfirm = { playlistViewModel.confirmRemoveTrack() }
            )

            // Centralized AudioOutputSelectorDialog (Connected to... output switcher)
            if (isAudioOutputDialogOpen) {
                AudioOutputSelectorDialog(
                    state = audioOutputState,
                    onSelectDevice = { device -> playerViewModel.selectAudioOutput(device) },
                    onDismiss = { isAudioOutputDialogOpen = false }
                )
            }
        }
    }
    } // CompositionLocalProvider(LocalReduceMotion)
}

@Composable
private fun AppScaffoldContent(
    currentDestination: NavigationDestination,
    onNavigate: (NavigationDestination) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    playerState: PlayerUiState,
    playerViewModel: PlayerViewModel,
    libraryState: com.example.sonara.feature.library.LibraryUiState,
    libraryViewModel: LibraryViewModel,
    searchState: com.example.sonara.feature.search.SearchUiState,
    searchQuery: String,
    searchViewModel: SearchViewModel,
    homeState: HomeUiState,
    homeViewModel: HomeViewModel,
    homeListState: LazyListState,
    playlistState: PlaylistUiState,
    playlistViewModel: PlaylistViewModel,
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    importViewModel: ImportViewModel? = null,
    audioOutputState: com.example.sonara.domain.model.AudioOutputState,
    onPlayTrack: (Track) -> Unit,
    onOpenExpandedPlayer: () -> Unit,
    onOpenAudioOutputSelector: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val importFallbackState = remember { kotlinx.coroutines.flow.MutableStateFlow(ImportUiState()) }
    val importState by (importViewModel?.uiState ?: importFallbackState).collectAsState()
    // Single centralized reduced-motion gate (see SonaraMotion / LocalReduceMotion). Read here in
    // composition and captured by the transitionSpec lambdas below, which are not @Composable and
    // so cannot read the CompositionLocal themselves.
    val reduceMotion = LocalReduceMotion.current

    // Resolve home view mode to suppress the global Top Bar when Home is showing a sub-screen.
    // The bar must only appear on the Home Feed surface; detail and catalog sub-screens own their
    // contextual header row and must NOT be double-barred.
    val homeViewMode = resolveHomeViewMode(homeState)

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isTablet = maxWidth >= 600.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (isTablet) {
                SonaraNavigationRail(
                    currentRoute = currentDestination.route,
                    onNavigate = onNavigate
                )
            }

            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(colors.background),
                containerColor = colors.background,
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                topBar = {
                    // Contextual top bar — only visible on surfaces that need a persistent global bar.
                    // Detail and catalog sub-screens (ArtistDetail, PlaylistDetail,
                    // CuratedPlaylistsCatalog, FeaturedArtistsCatalog) own their own contextual
                    // header row and receive an empty top bar here.
                    when {
                        currentDestination == NavigationDestination.Settings -> {
                            // Settings surface: Back arrow + "Settings" + ThemeToggle
                            SonaraTopBar(
                                currentTitle = "",
                                onToggleTheme = onToggleTheme,
                                isSettings = true,
                                onOpenSettings = onOpenSettings,
                                onBack = onBack
                            )
                        }
                        currentDestination == NavigationDestination.Home &&
                                homeViewMode is HomeViewMode.Feed -> {
                            // Home Feed surface: Sonara wordmark + Settings + ThemeToggle
                            SonaraTopBar(
                                currentTitle = "",
                                onToggleTheme = onToggleTheme,
                                isSettings = false,
                                onOpenSettings = onOpenSettings,
                                onBack = onBack
                            )
                        }
                        else -> {
                            // All other destinations (Search, Library, Playlists peers; Home
                            // sub-screens like ArtistDetail, PlaylistDetail, and catalog views):
                            // no global bar. Each screen owns its contextual header.
                        }
                    }
                },
                bottomBar = {
                    Column {
                        MiniPlayer(
                            state = playerState,
                            onPlay = { playerViewModel.play() },
                            onPause = { playerViewModel.pause() },
                            onNext = { playerViewModel.skipToNext() },
                            onPrevious = { playerViewModel.skipToPrevious() },
                            onSeek = { fraction ->
                                val targetMs = (fraction * playerState.durationMs).toLong()
                                playerViewModel.seekTo(targetMs)
                            },
                            onClickBody = onOpenExpandedPlayer,
                            audioOutputState = audioOutputState,
                            onOpenAudioOutputSelector = onOpenAudioOutputSelector
                        )
                        if (!isTablet) {
                            SonaraBottomNavBar(
                                currentRoute = currentDestination.route,
                                onNavigate = onNavigate
                            )
                        }
                    }
                }
            ) { innerPadding ->
                // When a global top bar is present it internally consumes the status bar inset via
                // WindowInsets.statusBars inside SonaraTopBar, and the Scaffold innerPadding.top
                // already accounts for it. When no top bar is rendered (all detail/catalog sub-
                // screens, Search, Library, Playlists) innerPadding.calculateTopPadding() == 0 and
                // content would bleed under the status bar. effectivePadding replaces the missing
                // inset for those cases so every screen stays safely below the notch.
                val hasGlobalTopBar = currentDestination == NavigationDestination.Settings ||
                    (currentDestination == NavigationDestination.Home &&
                        homeViewMode is HomeViewMode.Feed)
                val statusBarTop = WindowInsets.statusBars
                    .asPaddingValues()
                    .calculateTopPadding()
                val effectivePadding = if (hasGlobalTopBar) {
                    innerPadding
                } else {
                    PaddingValues(
                        top = statusBarTop,
                        bottom = innerPadding.calculateBottomPadding()
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    // FROZEN navigation motion — see docs/navigation-motion-freeze.md.
                    // Only this inner content region is animated; the Scaffold, MiniPlayer, bottom
                    // nav and rail live OUTSIDE it and never travel. The shell owns three cases:
                    // peer slide, Settings depth, and the Library→playlist depth-forward.
                    // Keyed on `currentDestination` — orthogonal to Home's `HomeViewMode` key and
                    // Playlists' `selectedPlaylist` key, so one navigation action yields exactly ONE
                    // transition (no stacked/double animation).
                    AnimatedContent(
                        targetState = currentDestination,
                        transitionSpec = {
                            when {
                                targetState == NavigationDestination.Settings ->
                                    SonaraMotion.depthForward(reduceMotion)
                                initialState == NavigationDestination.Settings ->
                                    SonaraMotion.depthBack(reduceMotion)
                                targetState == NavigationDestination.Playlists &&
                                    playlistState.selectedPlaylist != null &&
                                    initialState != NavigationDestination.Playlists ->
                                    SonaraMotion.depthForward(reduceMotion)
                                else -> {
                                    // Peer ↔ peer: restrained full-width horizontal page slide,
                                    // directed by tab order (Home→Search→Library→Playlists). Moving
                                    // DOWN the order is "forward" (current exits left, incoming from
                                    // the right); moving UP is the mirror. Both peers are always in
                                    // `items`, so indexOf is 0..3 here (Settings is handled above and
                                    // never reaches this branch). Reduced motion collapses to an
                                    // opacity-only fade inside peerSlide.
                                    val order = NavigationDestination.items
                                    val forward = order.indexOf(targetState) > order.indexOf(initialState)
                                    SonaraMotion.peerSlide(forward = forward, reduceMotion = reduceMotion)
                                }
                            }
                        },
                        label = "SonaraShellNavTransition",
                        // clipToBounds keeps the peer slide inside the content region: as one peer
                        // slides out and the next slides in, neither may draw past this Box onto the
                        // navigation rail (tablet) or the screen edge. Harmless for depth (its scale
                        // stays within bounds). The chrome (top bar, MiniPlayer, bottom nav, rail)
                        // lives OUTSIDE this Box and never moves.
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                    ) { destination ->
                    when (destination) {
                        NavigationDestination.Home -> {
                            HomeScreen(
                                state = homeState,
                                listState = homeListState,
                                contentPadding = effectivePadding,
                                onArtistClick = { artist -> homeViewModel.selectArtist(artist) },
                                onPlaylistClick = { playlist -> homeViewModel.selectPlaylist(playlist) },
                                onSeeAllPlaylists = { homeViewModel.viewAllPlaylists(true) },
                                onSeeAllArtists = { homeViewModel.viewAllArtists(true) },
                                onBackFromDetail = {
                                    if (homeState.selectedPlaylist != null) {
                                        homeViewModel.selectPlaylist(null)
                                    } else if (homeState.selectedArtist != null) {
                                        homeViewModel.selectArtist(null)
                                    } else if (homeState.isViewingAllPlaylists) {
                                        homeViewModel.viewAllPlaylists(false)
                                    } else if (homeState.isViewingAllArtists) {
                                        homeViewModel.viewAllArtists(false)
                                    }
                                },
                                onBackFromCatalog = { homeViewModel.viewAllPlaylists(false) },
                                onBackFromArtistsCatalog = { homeViewModel.viewAllArtists(false) },
                                onRefreshQuickPicks = { homeViewModel.refreshQuickPicks() },
                                onPlayTrack = onPlayTrack,
                                onToggleLike = { track, liked -> libraryViewModel.toggleLike(track, liked) },
                                isLiked = { id -> libraryState.likedSongs.any { it.id == id } }
                            )
                        }
                        NavigationDestination.Search -> {
                            SearchScreen(
                                modifier = Modifier.padding(effectivePadding),
                                state = searchState,
                                query = searchQuery,
                                onQueryChange = { searchViewModel.onQueryChange(it) },
                                onQuerySubmit = { searchViewModel.onQuerySubmit(it) },
                                onSuggestionSelected = { searchViewModel.onSuggestionSelected(it) },
                                onSelectMode = { searchViewModel.selectMode(it) },
                                onPlayTrack = onPlayTrack,
                                onToggleLike = { track, liked -> libraryViewModel.toggleLike(track, liked) },
                                isLiked = { id -> libraryState.likedSongs.any { it.id == id } },
                                onDeleteHistoryEntry = { searchViewModel.deleteHistoryEntry(it) },
                                onClearHistory = { searchViewModel.clearSearchHistory() }
                            )
                        }
                        NavigationDestination.Library -> {
                            LibraryScreen(
                                modifier = Modifier.padding(effectivePadding),
                                state = libraryState,
                                onSelectTab = { libraryViewModel.selectTab(it) },
                                onPlayTrack = onPlayTrack,
                                onToggleLike = { track, liked -> libraryViewModel.toggleLike(track, liked) }
                            )
                        }
                        NavigationDestination.Playlists -> {
                            // Playlist list ↔ detail is depth, owned INTERNALLY by this feature.
                            // contentKey = { it != null } makes the transition fire only on the
                            // presence/absence of a selection (the frozen "keyed on selectedPlaylist
                            // != null"), not on switching between two playlists. The target state is
                            // the playlist itself (not a Boolean) so the EXITING detail keeps
                            // rendering its own playlist during depth-back instead of dereferencing a
                            // now-null selection. Arriving from Library the selection is already set,
                            // so this first-composes straight to detail with NO internal transition —
                            // leaving the single shell depth-forward as the only visible motion.
                            AnimatedContent(
                                targetState = playlistState.selectedPlaylist,
                                contentKey = { it != null },
                                transitionSpec = {
                                    if (targetState != null) SonaraMotion.depthForward(reduceMotion)
                                    else SonaraMotion.depthBack(reduceMotion)
                                },
                                label = "PlaylistListDetailTransition",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(effectivePadding)
                            ) { selected ->
                                if (importState.step !is ImportStep.Idle && importViewModel != null) {
                                    ImportScreen(
                                        viewModel = importViewModel,
                                        onNavigateToPlaylist = { playlistId ->
                                            playlistViewModel.selectPlaylistById(playlistId)
                                        },
                                        onDismiss = {
                                            importViewModel.cancelImport()
                                        }
                                    )
                                } else if (selected != null) {
                                    UserPlaylistDetailScreen(
                                        playlist = selected,
                                        isReorderMode = playlistState.isReorderMode,
                                        reorderedTracks = playlistState.reorderedTracks,
                                        onBack = { playlistViewModel.selectPlaylist(null) },
                                        onPlayTrack = onPlayTrack,
                                        onToggleLike = { track, liked -> libraryViewModel.toggleLike(track, liked) },
                                        isLiked = { id -> libraryState.likedSongs.any { it.id == id } },
                                        onStartReorder = { playlistViewModel.startReorderMode() },
                                        onMoveTrack = { from, to -> playlistViewModel.moveTrackInReorder(from, to) },
                                        onCommitReorder = { playlistViewModel.commitReorder() },
                                        onCancelReorder = { playlistViewModel.cancelReorder() },
                                        onRename = { playlistViewModel.requestRename(it) },
                                        onDelete = { playlistViewModel.requestDelete(it) },
                                        onRequestRemoveTrack = { track ->
                                            playlistViewModel.requestRemoveTrack(selected.id, track)
                                        }
                                    )
                                } else {
                                    PlaylistsScreen(
                                        state = playlistState,
                                        onSelectPlaylist = { playlistViewModel.selectPlaylist(it) },
                                        onCreatePlaylist = { playlistViewModel.openCreateDialog() },
                                        onRenamePlaylist = { playlistViewModel.requestRename(it) },
                                        onDeletePlaylist = { playlistViewModel.requestDelete(it) },
                                        onImportPlaylist = { importViewModel?.startImport() }
                                    )
                                }
                            }
                        }
                        NavigationDestination.Settings -> {
                            SettingsScreen(
                                modifier = Modifier.padding(effectivePadding),
                                state = settingsState,
                                onToggleCategory = { settingsViewModel.toggleCategory(it) },
                                onOpenDialog = { settingsViewModel.openDialog(it) },
                                onDismissDialog = { settingsViewModel.dismissDialog() },
                                onSetThemeMode = { settingsViewModel.setThemeMode(it) },
                                onSetAppLanguage = { settingsViewModel.setAppLanguage(it) },
                                onSetShowRomanized = { settingsViewModel.setShowRomanized(it) },
                                onSetRestorePlaybackSession = { settingsViewModel.setRestorePlaybackSession(it) },
                                onSetDefaultSearchMode = { settingsViewModel.setDefaultSearchMode(it) },
                                onSetReduceMotion = { settingsViewModel.setReduceMotion(it) },
                                onSetEqualizerEnabled = { settingsViewModel.setEqualizerEnabled(it) },
                                onSetEqualizerBandGain = { index, gain -> settingsViewModel.setEqualizerBandGain(index, gain) },
                                onResetEqualizerGains = { settingsViewModel.resetEqualizerGains() },
                                onSetStopMusicOnTaskClear = { settingsViewModel.setStopMusicOnTaskClear(it) },
                                onRefreshBatteryOptimizationStatus = { settingsViewModel.refreshBatteryOptimizationStatus() },
                                onSetStreamingQuality = { settingsViewModel.setStreamingQuality(it) },
                                onSetDownloadQuality = { settingsViewModel.setDownloadQuality(it) },
                                onSetDownloadFileFormat = { settingsViewModel.setDownloadFileFormat(it) },
                                onSetDownloadOverWifiOnly = { settingsViewModel.setDownloadOverWifiOnly(it) },
                                onClearAllDownloads = { settingsViewModel.clearAllDownloads() },
                                onClearImageCache = { settingsViewModel.clearImageCache() },
                                onClearPlaybackHistory = { settingsViewModel.clearPlaybackHistory() },
                                onResetSettings = { settingsViewModel.resetSettings() },
                                onClearFeedbackMessage = { settingsViewModel.clearFeedbackMessage() }
                            )
                        }
                    }
                    } // AnimatedContent(SonaraShellNavTransition)
                }
            }
        }
    }
}
