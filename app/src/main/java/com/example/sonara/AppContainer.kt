package com.example.sonara

import android.content.Context
import com.example.sonara.data.download.DownloadEngine
import com.example.sonara.data.local.db.SonaraDatabase
import com.example.sonara.data.local.preferences.UserPreferencesDataStore
import com.example.sonara.data.repository.DiscoveryRepositoryImpl
import com.example.sonara.data.repository.DownloadRepositoryImpl
import com.example.sonara.data.repository.HistoryRepositoryImpl
import com.example.sonara.data.repository.LibraryRepositoryImpl
import com.example.sonara.data.repository.LyricsRepositoryImpl
import com.example.sonara.data.repository.PlaylistRepositoryImpl
import com.example.sonara.data.repository.SearchHistoryRepositoryImpl
import com.example.sonara.data.repository.SearchRepositoryImpl
import com.example.sonara.data.repository.SettingsRepositoryImpl
import com.example.sonara.core.notification.DownloadNotifier
import com.example.sonara.data.stream.StreamResolverImpl
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.domain.repository.DownloadRepository
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.LibraryRepository
import com.example.sonara.domain.repository.LyricsRepository
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.data.repository.ImportRepositoryImpl
import com.example.sonara.domain.repository.ImportRepository
import com.example.sonara.domain.repository.PlaylistRepository
import com.example.sonara.domain.repository.SearchHistoryRepository
import com.example.sonara.domain.repository.SearchRepository
import com.example.sonara.domain.repository.SettingsRepository
import com.example.sonara.playback.checkpoint.PlaybackCheckpointEngine
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.controller.PlaybackQueueEngine

/**
 * Lean, explicit manual dependency container for Sonara Android.
 * Keeps dependency wiring minimal without heavy reflection or DI frameworks.
 */
class AppContainer(context: Context) {
    val database: SonaraDatabase by lazy { SonaraDatabase.getInstance(context) }
    val dataStore: UserPreferencesDataStore by lazy { UserPreferencesDataStore(context) }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepositoryImpl(
            likedSongsDao = database.likedSongsDao(),
            trackDao = database.trackDao()
        )
    }

    val historyRepository: HistoryRepository by lazy {
        HistoryRepositoryImpl(
            historyDao = database.historyDao(),
            trackDao = database.trackDao()
        )
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            preferencesDataStore = dataStore
        )
    }

    val searchHistoryRepository: SearchHistoryRepository by lazy {
        SearchHistoryRepositoryImpl(
            searchHistoryDao = database.searchHistoryDao()
        )
    }

    val searchRepository: SearchRepository by lazy {
        SearchRepositoryImpl(
            searchHistoryRepository = searchHistoryRepository
        )
    }

    val discoveryRepository: DiscoveryRepository by lazy {
        DiscoveryRepositoryImpl()
    }

    val lyricsRepository: LyricsRepository by lazy {
        LyricsRepositoryImpl()
    }

    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepositoryImpl(
            playlistDao = database.playlistDao(),
            trackDao = database.trackDao(),
            database = database
        )
    }

    val backendClient: SonaraBackendClient by lazy {
        SonaraBackendClient()
    }

    val importRepository: ImportRepository by lazy {
        ImportRepositoryImpl(
            contentResolver = context.applicationContext.contentResolver,
            backendClient = backendClient,
            playlistRepository = playlistRepository
        )
    }

    val streamResolver: StreamResolverPort by lazy {
        StreamResolverImpl()
    }

    val downloadEngine: DownloadEngine by lazy {
        DownloadEngine(
            context = context.applicationContext,
            downloadDao = database.downloadDao(),
            trackDao = database.trackDao(),
            streamResolver = streamResolver,
            settingsRepository = settingsRepository
        )
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepositoryImpl(
            downloadDao = database.downloadDao(),
            downloadEngine = downloadEngine
        )
    }

    /**
     * App-scoped observer that renders download progress/completion/failure notifications.
     * Read-only: it observes [downloadRepository] and never mutates download state. Started once
     * from [SonaraApp.onCreate]; a single collector for the whole process (no per-download work).
     */
    val downloadNotifier: DownloadNotifier by lazy {
        DownloadNotifier(
            context = context.applicationContext,
            downloadRepository = downloadRepository
        )
    }

    val mediaControllerClient: MediaControllerClient by lazy {
        MediaControllerClient(context)
    }

    val playerCheckpointEngine: PlaybackCheckpointEngine by lazy {
        PlaybackCheckpointEngine(
            client = mediaControllerClient,
            settingsRepository = settingsRepository
        )
    }

    val playbackHistoryCoordinator: com.example.sonara.playback.checkpoint.PlaybackHistoryCoordinator by lazy {
        com.example.sonara.playback.checkpoint.PlaybackHistoryCoordinator(
            client = mediaControllerClient,
            historyRepository = historyRepository
        )
    }

    val audioOutputRepository: com.example.sonara.domain.repository.AudioOutputRepository by lazy {
        com.example.sonara.data.repository.AudioOutputRepositoryImpl(
            context = context.applicationContext,
            mediaControllerClient = mediaControllerClient
        )
    }

    val playbackQueueEngine: PlaybackQueueEngine by lazy {
        PlaybackQueueEngine()
    }
}
