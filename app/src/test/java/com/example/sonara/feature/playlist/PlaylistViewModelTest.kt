package com.example.sonara.feature.playlist

import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistOrigin
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.PlaylistRepository
import com.example.sonara.feature.playlist.PlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakePlaylistRepository : PlaylistRepository {
        val summaries = MutableStateFlow<List<PlaylistSummary>>(emptyList())
        val details = mutableMapOf<String, MutableStateFlow<PlaylistDetail?>>()

        override fun getUserPlaylists(): Flow<List<PlaylistSummary>> = summaries

        override fun getPlaylistDetail(playlistId: String): Flow<PlaylistDetail?> {
            return details.getOrPut(playlistId) { MutableStateFlow(null) }
        }

        override suspend fun createPlaylist(name: String): Result<String> {
            val id = UUID.randomUUID().toString()
            val summary = PlaylistSummary(id = id, name = name, size = 0, origin = PlaylistOrigin.USER)
            summaries.value = summaries.value + summary
            details[id] = MutableStateFlow(PlaylistDetail(id = id, name = name, tracks = emptyList()))
            return Result.success(id)
        }

        override suspend fun renamePlaylist(playlistId: String, newName: String): Result<Unit> {
            summaries.value = summaries.value.map { if (it.id == playlistId) it.copy(name = newName) else it }
            details[playlistId]?.value = details[playlistId]?.value?.copy(name = newName)
            return Result.success(Unit)
        }

        override suspend fun deletePlaylist(playlistId: String): Result<Unit> {
            summaries.value = summaries.value.filter { it.id != playlistId }
            details.remove(playlistId)
            return Result.success(Unit)
        }

        override suspend fun addTrackToPlaylist(playlistId: String, track: Track): Result<Unit> {
            val detailFlow = details[playlistId] ?: return Result.failure(Exception("Not found"))
            val cur = detailFlow.value ?: return Result.failure(Exception("Not found"))
            if (cur.tracks.none { it.id == track.id }) {
                val newTracks = cur.tracks + track
                detailFlow.value = cur.copy(tracks = newTracks)
                summaries.value = summaries.value.map { if (it.id == playlistId) it.copy(size = newTracks.size) else it }
            }
            return Result.success(Unit)
        }

        override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): Result<Unit> {
            val detailFlow = details[playlistId] ?: return Result.failure(Exception("Not found"))
            val cur = detailFlow.value ?: return Result.failure(Exception("Not found"))
            val newTracks = cur.tracks.filter { it.id != trackId }
            detailFlow.value = cur.copy(tracks = newTracks)
            summaries.value = summaries.value.map { if (it.id == playlistId) it.copy(size = newTracks.size) else it }
            return Result.success(Unit)
        }

        override suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<String>): Result<Unit> {
            val detailFlow = details[playlistId] ?: return Result.failure(Exception("Not found"))
            val cur = detailFlow.value ?: return Result.failure(Exception("Not found"))
            val reordered = orderedTrackIds.mapNotNull { id -> cur.tracks.find { it.id == id } }
            detailFlow.value = cur.copy(tracks = reordered)
            return Result.success(Unit)
        }

        override suspend fun isTrackInPlaylist(playlistId: String, trackId: String): Boolean {
            return details[playlistId]?.value?.tracks?.any { it.id == trackId } == true
        }

        override suspend fun importPlaylist(name: String, tracks: List<Track>): Result<com.example.sonara.domain.model.ImportPlaylistResult> {
            val id = UUID.randomUUID().toString()
            val summary = PlaylistSummary(id = id, name = name, size = tracks.size, origin = PlaylistOrigin.SPOTIFY)
            summaries.value = summaries.value + summary
            details[id] = MutableStateFlow(PlaylistDetail(id = id, name = name, tracks = tracks))
            return Result.success(
                com.example.sonara.domain.model.ImportPlaylistResult(
                    playlistId = id,
                    playlistName = name,
                    totalTracksAdded = tracks.size,
                    collapsedDuplicates = 0
                )
            )
        }
    }

    private lateinit var repository: FakePlaylistRepository
    private lateinit var viewModel: PlaylistViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = FakePlaylistRepository()
        viewModel = PlaylistViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun createPlaylist_updatesUiStateAndShowsFeedback() = runTest {
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.openCreateDialog()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isCreateDialogOpen)

        viewModel.createPlaylist("Chill Mix")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCreateDialogOpen)
        assertEquals(1, viewModel.uiState.value.playlists.size)
        assertEquals("Chill Mix", viewModel.uiState.value.playlists.first().name)
        assertEquals("Playlist created", viewModel.uiState.value.feedbackMessage)
        collectJob.cancel()
    }

    @Test
    fun addToPlaylist_flowFromExpandedPlayerWithNoPlaylists() = runTest {
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val track = Track(id = "track_1", title = "Midnight City", artist = "M83")
        viewModel.openAddToPlaylist(track)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isAddToPlaylistSheetOpen)
        assertEquals(track, viewModel.uiState.value.pendingAddTrack)

        // Case A: Create playlist directly from Add sheet
        viewModel.createPlaylist("Electronic", andAddPendingTrack = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAddToPlaylistSheetOpen)
        assertEquals("Playlist created and song added", viewModel.uiState.value.feedbackMessage)

        val createdPlaylist = viewModel.uiState.value.playlists.first()
        viewModel.selectPlaylist(createdPlaylist)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.selectedPlaylist?.tracks?.size)
        assertEquals("track_1", viewModel.uiState.value.selectedPlaylist?.tracks?.first()?.id)
        collectJob.cancel()
    }

    @Test
    fun renameAndDeletePlaylist_updatesStateCleanly() = runTest {
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.createPlaylist("Original")
        advanceUntilIdle()
        val summary = viewModel.uiState.value.playlists.first()

        // Rename
        viewModel.requestRename(summary)
        advanceUntilIdle()
        assertEquals(summary, viewModel.uiState.value.playlistToRename)

        viewModel.renamePlaylist(summary.id, "Renamed")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.playlistToRename)
        assertEquals("Renamed", viewModel.uiState.value.playlists.first().name)

        // Delete
        viewModel.selectPlaylist(viewModel.uiState.value.playlists.first())
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.selectedPlaylist)

        viewModel.requestDelete(viewModel.uiState.value.playlists.first())
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.playlistToDelete)

        viewModel.confirmDeletePlaylist(summary.id)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.playlistToDelete)
        assertNull(viewModel.uiState.value.selectedPlaylist)
        assertEquals(0, viewModel.uiState.value.playlists.size)
        collectJob.cancel()
    }

    @Test
    fun reorderingTracks_movesAndCommitsOrder() = runTest {
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.createPlaylist("Reorder Test")
        advanceUntilIdle()
        val summary = viewModel.uiState.value.playlists.first()

        val track1 = Track(id = "track_1", title = "First", artist = "A")
        val track2 = Track(id = "track_2", title = "Second", artist = "B")
        val track3 = Track(id = "track_3", title = "Third", artist = "C")

        repository.addTrackToPlaylist(summary.id, track1)
        repository.addTrackToPlaylist(summary.id, track2)
        repository.addTrackToPlaylist(summary.id, track3)

        viewModel.selectPlaylist(summary)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.selectedPlaylist?.tracks?.size)

        viewModel.startReorderMode()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReorderMode)

        // Move track at index 2 (Third) to index 0
        viewModel.moveTrackInReorder(2, 0)
        advanceUntilIdle()
        assertEquals("track_3", viewModel.uiState.value.reorderedTracks[0].id)

        viewModel.commitReorder()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isReorderMode)
        assertEquals("track_3", viewModel.uiState.value.selectedPlaylist?.tracks?.get(0)?.id)
        collectJob.cancel()
    }

    @Test
    fun deterministicOriginPartitioning_separatesUserAndSpotifyPlaylists() = runTest {
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // 1. Neither category exists
        val initialPlaylists = viewModel.uiState.value.playlists
        assertEquals(0, initialPlaylists.count { it.origin == PlaylistOrigin.USER })
        assertEquals(0, initialPlaylists.count { it.origin == PlaylistOrigin.SPOTIFY })

        // 2. USER playlist created
        viewModel.createPlaylist("Native Sonara Playlist")
        advanceUntilIdle()

        val afterUser = viewModel.uiState.value.playlists
        assertEquals(1, afterUser.count { it.origin == PlaylistOrigin.USER })
        assertEquals(0, afterUser.count { it.origin == PlaylistOrigin.SPOTIFY })
        assertEquals("Native Sonara Playlist", afterUser.first { it.origin == PlaylistOrigin.USER }.name)

        // 3. SPOTIFY playlist imported
        repository.importPlaylist("Spotify Roadtrip", listOf(Track(id = "s1", title = "S", artist = "A")))
        advanceUntilIdle()

        val afterBoth = viewModel.uiState.value.playlists
        val userGroup = afterBoth.filter { it.origin == PlaylistOrigin.USER }
        val spotifyGroup = afterBoth.filter { it.origin == PlaylistOrigin.SPOTIFY }

        assertEquals(1, userGroup.size)
        assertEquals(1, spotifyGroup.size)
        assertEquals("Native Sonara Playlist", userGroup.first().name)
        assertEquals("Spotify Roadtrip", spotifyGroup.first().name)

        // 4. SPOTIFY only (delete USER playlist)
        viewModel.confirmDeletePlaylist(userGroup.first().id)
        advanceUntilIdle()

        val afterUserDeleted = viewModel.uiState.value.playlists
        assertEquals(0, afterUserDeleted.count { it.origin == PlaylistOrigin.USER })
        assertEquals(1, afterUserDeleted.count { it.origin == PlaylistOrigin.SPOTIFY })

        collectJob.cancel()
    }
}
