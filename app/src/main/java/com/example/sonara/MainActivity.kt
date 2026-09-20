package com.example.sonara

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.home.HomeViewModel
import com.example.sonara.feature.library.LibraryViewModel
import com.example.sonara.feature.lyrics.LyricsViewModel
import com.example.sonara.feature.player.PlayerViewModel
import com.example.sonara.feature.import.ImportViewModel
import com.example.sonara.feature.playlist.PlaylistViewModel
import com.example.sonara.feature.search.SearchViewModel
import com.example.sonara.feature.settings.SettingsViewModel
import com.example.sonara.feature.shell.SonaraAppRoot
import com.example.sonara.playback.service.SonaraPlaybackService
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer
    private lateinit var searchViewModel: SearchViewModel
    private lateinit var playerViewModel: PlayerViewModel
    private lateinit var lyricsViewModel: LyricsViewModel

    private var expandPlayerTrigger by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleNotificationIntent(intent)

        appContainer = (application as SonaraApp).container

        // Eagerly initialize Activity-scoped ViewModels to prevent race conditions during session restore
        playerViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlayerViewModel(
                    client = appContainer.mediaControllerClient,
                    discoveryRepository = appContainer.discoveryRepository,
                    streamResolverPort = appContainer.streamResolver,
                    downloadRepository = appContainer.downloadRepository,
                    settingsRepository = appContainer.settingsRepository,
                    audioOutputRepository = appContainer.audioOutputRepository,
                    historyRepository = appContainer.historyRepository,
                    queueEngine = appContainer.playbackQueueEngine
                ) as T
            }
        })[PlayerViewModel::class.java]

        searchViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SearchViewModel(
                    searchRepository = appContainer.searchRepository,
                    libraryRepository = appContainer.libraryRepository,
                    settingsRepository = appContainer.settingsRepository,
                    searchHistoryRepository = appContainer.searchHistoryRepository
                ) as T
            }
        })[SearchViewModel::class.java]

        lyricsViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LyricsViewModel(
                    lyricsRepository = appContainer.lyricsRepository,
                    mediaControllerClient = appContainer.mediaControllerClient,
                    settingsRepository = appContainer.settingsRepository
                ) as T
            }
        })[LyricsViewModel::class.java]

        setContent {
            val userPreferences by appContainer.settingsRepository.getUserPreferences()
                .collectAsState(initial = com.example.sonara.domain.model.UserPreferences())
            val scope = rememberCoroutineScope()
            val isSystemDark = isSystemInDarkTheme()

            // Dynamic locale management: updates UI without disrupting ExoPlayer audio stream.
            //
            // SYSTEM_DEFAULT: pass baseConfiguration/baseContext through unchanged — Android's
            //   own configuration pipeline is authoritative, no synthetic override needed.
            //   Using remember(appLanguage) + Resources.getSystem() was broken: it snapshotted
            //   the OS locale once and never refreshed when onConfigurationChanged fired.
            // Specific language: synthesise a localizedConfiguration + localizedContext so that
            //   stringResource() calls resolve against the chosen locale in-process.
            val appLanguage = userPreferences.appLanguage
            val baseConfiguration = LocalConfiguration.current
            val baseContext = LocalContext.current

            val isSystemDefault = appLanguage == AppLanguage.SYSTEM_DEFAULT
            val overrideLocale = remember(appLanguage) {
                if (isSystemDefault) null else Locale.forLanguageTag(appLanguage.bcp47Tag)
            }
            val localizedConfiguration = remember(overrideLocale, baseConfiguration) {
                if (overrideLocale == null) {
                    baseConfiguration
                } else {
                    Configuration(baseConfiguration).apply {
                        setLocale(overrideLocale)
                        setLayoutDirection(overrideLocale)
                    }
                }
            }
            val localizedContext = remember(overrideLocale, baseContext) {
                if (overrideLocale == null) baseContext
                else baseContext.createConfigurationContext(localizedConfiguration)
            }

            // Sync with Android 13+ per-app language manager.
            // Guard uses language subtag comparison (not LocaleList.equals) to prevent
            // an infinite recreation loop caused by Android normalising "bn" → "bn-IN"
            // or "ur" → "ur-PK", which makes LocaleList.equals() return false even when
            // the locale is already correctly applied.
            LaunchedEffect(appLanguage) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        val localeManager = getSystemService(LocaleManager::class.java)
                        if (localeManager != null) {
                            val targetLocaleList = if (appLanguage == AppLanguage.SYSTEM_DEFAULT) {
                                LocaleList.getEmptyLocaleList()
                            } else {
                                LocaleList.forLanguageTags(appLanguage.bcp47Tag)
                            }
                            // Compare by language subtag to survive Android's regional
                            // normalisation (e.g. "bn" stored as "bn-IN" or "bn-BD").
                            val current = localeManager.applicationLocales
                            val alreadyApplied = if (appLanguage == AppLanguage.SYSTEM_DEFAULT) {
                                current.isEmpty
                            } else {
                                val targetLang = Locale.forLanguageTag(appLanguage.bcp47Tag).language
                                !current.isEmpty && current[0]?.language == targetLang
                            }
                            if (!alreadyApplied) {
                                localeManager.applicationLocales = targetLocaleList
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            val playerViewModel = this@MainActivity.playerViewModel

            val libraryViewModel: LibraryViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return LibraryViewModel(
                            libraryRepository = appContainer.libraryRepository,
                            historyRepository = appContainer.historyRepository
                        ) as T
                    }
                }
            )

            val searchViewModel = this@MainActivity.searchViewModel

            val homeViewModel: HomeViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return HomeViewModel(
                            discoveryRepository = appContainer.discoveryRepository,
                            historyRepository = appContainer.historyRepository,
                            libraryRepository = appContainer.libraryRepository,
                            settingsRepository = appContainer.settingsRepository
                        ) as T
                    }
                }
            )

            val lyricsViewModel = this@MainActivity.lyricsViewModel

            val playlistViewModel: PlaylistViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return PlaylistViewModel(
                            playlistRepository = appContainer.playlistRepository
                        ) as T
                    }
                }
            )

            val settingsViewModel: SettingsViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SettingsViewModel(
                            settingsRepository = appContainer.settingsRepository,
                            historyRepository = appContainer.historyRepository,
                            streamResolver = appContainer.streamResolver,
                            downloadRepository = appContainer.downloadRepository,
                            appContext = applicationContext
                        ) as T
                    }
                }
            )

            val importViewModel: ImportViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return ImportViewModel(
                            importRepository = appContainer.importRepository
                        ) as T
                    }
                }
            )

            CompositionLocalProvider(
                LocalConfiguration provides localizedConfiguration,
                LocalContext provides localizedContext,
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity
            ) {
                SonaraAppRoot(
                    playerViewModel = playerViewModel,
                    libraryViewModel = libraryViewModel,
                    searchViewModel = searchViewModel,
                    homeViewModel = homeViewModel,
                    lyricsViewModel = lyricsViewModel,
                    playlistViewModel = playlistViewModel,
                    settingsViewModel = settingsViewModel,
                    importViewModel = importViewModel,
                    themeMode = userPreferences.themeMode,
                    openExpandedPlayerTrigger = expandPlayerTrigger,
                    onToggleTheme = {
                        val nextMode = when (userPreferences.themeMode) {
                            ThemeMode.SYSTEM -> if (isSystemDark) ThemeMode.LIGHT else ThemeMode.DARK
                            ThemeMode.DARK -> ThemeMode.LIGHT
                            ThemeMode.LIGHT -> ThemeMode.DARK
                        }
                        scope.launch {
                            appContainer.settingsRepository.setThemeMode(nextMode)
                        }
                    },
                    onPlayTrack = { track ->
                        handlePlayTrack(track)
                    }
                )
            }
        }

        // Start playback history coordinator and session checkpoint engine
        appContainer.playbackHistoryCoordinator.start()
        appContainer.playerCheckpointEngine.start()

        // Restore playback session snapshot if available and enabled
        restorePlaybackSession()

        // Request Bluetooth permission on Android 12+ for dynamic output device naming
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btPerm = android.Manifest.permission.BLUETOOTH_CONNECT
            if (checkSelfPermission(btPerm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(btPerm), 1001)
            }
        }

        // Request POST_NOTIFICATIONS permission on Android 13+ (API 33).
        // The manifest declaration exists; the runtime grant is required to post any
        // non-media notification (downloads, alerts). Denied permission is handled
        // gracefully: DownloadNotifier checks areNotificationsEnabled() and skips
        // posting silently. Playback and MediaSession are completely unaffected.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifPerm = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(notifPerm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(notifPerm), 1002)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val hasAction = intent?.action == SonaraPlaybackService.ACTION_OPEN_EXPANDED_PLAYER ||
            intent?.getBooleanExtra(SonaraPlaybackService.EXTRA_OPEN_EXPANDED_PLAYER, false) == true
        if (hasAction) {
            expandPlayerTrigger = System.currentTimeMillis()
            intent?.action = null
            intent?.removeExtra(SonaraPlaybackService.EXTRA_OPEN_EXPANDED_PLAYER)
        }
    }

    override fun onResume() {
        super.onResume()
        appContainer.audioOutputRepository.refreshDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        appContainer.playbackHistoryCoordinator.stop()
        appContainer.playerCheckpointEngine.stop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            appContainer.audioOutputRepository.refreshDevices()
        }
    }

    private fun handlePlayTrack(track: Track) {
        lifecycleScope.launch {
            if (!this@MainActivity::searchViewModel.isInitialized || !this@MainActivity::playerViewModel.isInitialized) {
                return@launch
            }
            // 1. Cache track metadata locally for offline library/history
            searchViewModel.cacheTrackMetadata(track)

            // 2. Record immediately into history repository as most recent playback
            appContainer.historyRepository.recordHistory(track, completed = false)

            // 3. Delegate to PlayerViewModel (coordinates queue, stream resolution, and backstack)
            playerViewModel.playTrack(track)
        }
    }

    private fun restorePlaybackSession() {
        lifecycleScope.launch {
            val prefs = appContainer.settingsRepository.getUserPreferences().firstOrNull() ?: return@launch
            if (!prefs.restorePlaybackSession) return@launch

            val snapshot = appContainer.settingsRepository.getPlaybackSession()
            val trackId = snapshot?.lastTrackId?.takeIf { it.isNotBlank() }
            if (trackId != null && this@MainActivity::playerViewModel.isInitialized && playerViewModel.uiState.value.trackId.isEmpty()) {
                val track = appContainer.libraryRepository.getTrack(trackId)
                    ?: appContainer.historyRepository.getRecentHistory(10).firstOrNull()
                        ?.firstOrNull { it.track.id == trackId }?.track
                if (track != null) {
                    val queueTracks = if (snapshot.queueTrackIds.isNotEmpty()) {
                        appContainer.libraryRepository.getTracks(snapshot.queueTrackIds)
                    } else {
                        emptyList()
                    }
                    playerViewModel.restoreTrack(
                        track = track,
                        initialPositionMs = snapshot.lastPositionMs,
                        contextQueue = queueTracks
                    )
                }
            }
        }
    }
}
