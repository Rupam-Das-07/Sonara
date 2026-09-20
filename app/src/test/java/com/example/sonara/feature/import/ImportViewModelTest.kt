package com.example.sonara.feature.import

import android.net.Uri
import com.example.sonara.domain.model.ImportMatchItem
import com.example.sonara.domain.model.ImportMatchStatus
import com.example.sonara.domain.model.ImportMatchTier
import com.example.sonara.domain.model.ImportPlaylistResult
import com.example.sonara.domain.model.ImportedTrack
import com.example.sonara.domain.model.ParseSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.ImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeImportRepository : ImportRepository {
        var parseResult: Result<ParseSummary>? = null
        var matchChunkHandler: ((String, Int, List<ImportedTrack>) -> Result<List<ImportMatchItem>>)? = null
        val persistCalls = mutableListOf<Pair<String, List<Track>>>()
        var persistResult: Result<ImportPlaylistResult>? = null
        var parseCallCount = 0
        var matchCallCount = 0

        override suspend fun parseExportFile(uri: Uri, fallbackName: String): Result<ParseSummary> {
            parseCallCount++
            return parseResult ?: Result.failure(IllegalStateException("No parseResult configured"))
        }

        override suspend fun matchTracksChunk(
            importId: String,
            chunkIndex: Int,
            tracks: List<ImportedTrack>
        ): Result<List<ImportMatchItem>> {
            matchCallCount++
            return matchChunkHandler?.invoke(importId, chunkIndex, tracks)
                ?: Result.success(emptyList())
        }

        override suspend fun persistPlaylist(name: String, tracks: List<Track>): Result<ImportPlaylistResult> {
            persistCalls.add(name to tracks)
            return persistResult ?: Result.success(
                ImportPlaylistResult(
                    playlistId = "pl_test_123",
                    playlistName = name,
                    totalTracksAdded = tracks.size,
                    collapsedDuplicates = 0
                )
            )
        }
    }

    private lateinit var fakeRepository: FakeImportRepository
    private lateinit var viewModel: ImportViewModel
    private val testUri: Uri = android.net.TestUri()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeImportRepository()
        viewModel = ImportViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isIdle() {
        assertTrue(viewModel.uiState.value.step is ImportStep.Idle)
    }

    @Test
    fun startImport_transitionsToAwaitingFile() {
        viewModel.startImport()
        assertTrue(viewModel.uiState.value.step is ImportStep.AwaitingFile)
    }

    /**
     * Regression guard for the "import screen auto-launches the SAF picker" bug.
     *
     * Entering the import flow must produce a PASSIVE landing state ([ImportStep.AwaitingFile]):
     * showing the Import landing screen must not, by itself, begin any file work. The SAF picker
     * and everything downstream (parse -> match -> persist) may only be driven by an explicit user
     * action (tapping Import, which calls [ImportViewModel.onFileSelected]).
     *
     * The original defect was a `LaunchedEffect(state.step)` in ImportScreen that launched the file
     * picker whenever the step was AwaitingFile; because startImport() sets that step to make the
     * landing screen appear, the picker opened on its own. This test locks the state contract the
     * fix relies on: reaching AwaitingFile triggers zero repository/side-effect work.
     */
    @Test
    fun startImport_entersAwaitingFile_asPassiveLandingState_withNoFileProcessing() = runTest {
        viewModel.startImport()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.step is ImportStep.AwaitingFile)
        assertEquals(0, fakeRepository.parseCallCount)
        assertEquals(0, fakeRepository.matchCallCount)
        assertEquals(0, fakeRepository.persistCalls.size)
    }

    @Test
    fun onFileSelected_parseFailure_transitionsToParseFailed() = runTest {
        fakeRepository.parseResult = Result.failure(IllegalArgumentException("Unsupported format"))

        viewModel.onFileSelected(testUri, "Test Playlist")
        advanceUntilIdle()

        val step = viewModel.uiState.value.step
        assertTrue(step is ImportStep.ParseFailed)
        assertEquals("Unsupported format", (step as ImportStep.ParseFailed).error)
        // Zero DB persistence calls
        assertEquals(0, fakeRepository.persistCalls.size)
    }

    @Test
    fun onFileSelected_successfulParseAndMatching_populatesReviewBucketsAndPreservesSourceOrder() = runTest {
        val importedTracks = listOf(
            ImportedTrack(sourceOrder = 0, title = "Song A", artist = "Artist A"),
            ImportedTrack(sourceOrder = 1, title = "Song B", artist = "Artist B"),
            ImportedTrack(sourceOrder = 2, title = "Song C", artist = "Artist C", isLocalFile = true)
        )
        fakeRepository.parseResult = Result.success(
            ParseSummary(
                playlistName = "Exported Favorites",
                totalSourceRows = 3,
                parsedRows = 2,
                skippedRows = 1,
                localFilesCount = 1,
                episodesCount = 0,
                invalidRowsCount = 0,
                tracks = importedTracks
            )
        )

        val resolvedA = Track(id = "yt_a", title = "Song A", artist = "Artist A")
        val resolvedB = Track(id = "yt_b", title = "Song B", artist = "Artist B")
        val altB = Track(id = "yt_b_alt", title = "Song B (Live)", artist = "Artist B")

        fakeRepository.matchChunkHandler = { _, _, _ ->
            Result.success(
                listOf(
                    ImportMatchItem(
                        sourceOrder = 0,
                        status = ImportMatchStatus.MATCHED,
                        tier = ImportMatchTier.CONFIDENT,
                        confidence = 0.95,
                        resolvedTrack = resolvedA
                    ),
                    ImportMatchItem(
                        sourceOrder = 1,
                        status = ImportMatchStatus.AMBIGUOUS,
                        tier = ImportMatchTier.REVIEW,
                        confidence = 0.65,
                        resolvedTrack = resolvedB,
                        alternatives = listOf(altB)
                    ),
                    ImportMatchItem(
                        sourceOrder = 2,
                        status = ImportMatchStatus.SKIPPED,
                        tier = ImportMatchTier.NONE,
                        confidence = 0.0,
                        resolvedTrack = null,
                        reason = "Local audio file"
                    )
                )
            )
        }

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        // Zero DB persistence calls during matching
        assertEquals(0, fakeRepository.persistCalls.size)

        val step = viewModel.uiState.value.step
        assertTrue(step is ImportStep.Review)
        val review = step as ImportStep.Review
        assertEquals("Exported Favorites", review.playlistName)

        // Verify confident bucket
        assertEquals(1, review.confidentItems.size)
        assertEquals("yt_a", review.confidentItems[0].selectedTrack.id)
        assertTrue(review.confidentItems[0].isSelected)

        // Verify review bucket
        assertEquals(1, review.reviewItems.size)
        assertEquals("yt_b", review.reviewItems[0].selectedTrack.id)
        assertTrue(review.reviewItems[0].isSelected)
        assertEquals(1, review.reviewItems[0].alternatives.size)
        assertEquals("yt_b_alt", review.reviewItems[0].alternatives[0].id)

        // Verify skipped bucket
        assertEquals(1, review.skippedItems.size)
        assertEquals(2, review.skippedItems[0].sourceOrder)
    }

    @Test
    fun selectAlternative_swapsSelectedTrackWithChosenAlternative() = runTest {
        val track = ImportedTrack(sourceOrder = 0, title = "Song B", artist = "Artist B")
        fakeRepository.parseResult = Result.success(
            ParseSummary("Mix", 1, 1, 0, 0, 0, 0, listOf(track))
        )
        val initial = Track(id = "yt_orig", title = "Song B", artist = "Artist B")
        val alt = Track(id = "yt_alt", title = "Song B (Live)", artist = "Artist B")

        fakeRepository.matchChunkHandler = { _, _, _ ->
            Result.success(
                listOf(
                    ImportMatchItem(
                        sourceOrder = 0,
                        status = ImportMatchStatus.AMBIGUOUS,
                        tier = ImportMatchTier.REVIEW,
                        confidence = 0.65,
                        resolvedTrack = initial,
                        alternatives = listOf(alt)
                    )
                )
            )
        }

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        // Swap to alt
        viewModel.selectAlternative(sourceOrder = 0, alternative = alt)

        val step = viewModel.uiState.value.step as ImportStep.Review
        val item = step.reviewItems[0]
        assertEquals("yt_alt", item.selectedTrack.id)
        assertEquals(1, item.alternatives.size)
        assertEquals("yt_orig", item.alternatives[0].id)
    }

    @Test
    fun toggleItemSelection_togglesIsSelectedInBucket() = runTest {
        val track = ImportedTrack(sourceOrder = 0, title = "Song A", artist = "Artist A")
        fakeRepository.parseResult = Result.success(
            ParseSummary("Mix", 1, 1, 0, 0, 0, 0, listOf(track))
        )
        val resolved = Track(id = "yt_a", title = "Song A", artist = "Artist A")
        fakeRepository.matchChunkHandler = { _, _, _ ->
            Result.success(
                listOf(
                    ImportMatchItem(
                        sourceOrder = 0,
                        status = ImportMatchStatus.MATCHED,
                        tier = ImportMatchTier.CONFIDENT,
                        confidence = 0.95,
                        resolvedTrack = resolved
                    )
                )
            )
        }

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        val review1 = viewModel.uiState.value.step as ImportStep.Review
        assertTrue(review1.confidentItems[0].isSelected)

        // Toggle off
        viewModel.toggleItemSelection(sourceOrder = 0)
        val review2 = viewModel.uiState.value.step as ImportStep.Review
        assertFalse(review2.confidentItems[0].isSelected)

        // Toggle back on
        viewModel.toggleItemSelection(sourceOrder = 0)
        val review3 = viewModel.uiState.value.step as ImportStep.Review
        assertTrue(review3.confidentItems[0].isSelected)
    }

    @Test
    fun confirmImport_onlyPersistsSelectedTracks_preservesOrderingAndTransitionsToDone() = runTest {
        val tracks = listOf(
            ImportedTrack(sourceOrder = 0, title = "Track 0", artist = "Artist"),
            ImportedTrack(sourceOrder = 1, title = "Track 1", artist = "Artist")
        )
        fakeRepository.parseResult = Result.success(
            ParseSummary("My Playlist", 2, 2, 0, 0, 0, 0, tracks)
        )
        val t0 = Track(id = "id_0", title = "Track 0", artist = "Artist")
        val t1 = Track(id = "id_1", title = "Track 1", artist = "Artist")

        fakeRepository.matchChunkHandler = { _, _, _ ->
            Result.success(
                listOf(
                    ImportMatchItem(0, ImportMatchStatus.MATCHED, ImportMatchTier.CONFIDENT, 0.9, t0),
                    ImportMatchItem(1, ImportMatchStatus.MATCHED, ImportMatchTier.CONFIDENT, 0.9, t1)
                )
            )
        }

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        // Unselect track 0
        viewModel.toggleItemSelection(0)

        // Confirm import
        viewModel.confirmImport()
        advanceUntilIdle()

        // Now persist should have been called exactly once with Track 1
        assertEquals(1, fakeRepository.persistCalls.size)
        val (persistedName, persistedTracks) = fakeRepository.persistCalls[0]
        assertEquals("My Playlist", persistedName)
        assertEquals(1, persistedTracks.size)
        assertEquals("id_1", persistedTracks[0].id)

        val step = viewModel.uiState.value.step
        assertTrue(step is ImportStep.Done)
        assertEquals("pl_test_123", (step as ImportStep.Done).result.playlistId)
    }

    @Test
    fun confirmImport_emptySelection_transitionsToPersistFailedWithoutWritingDb() = runTest {
        val tracks = listOf(ImportedTrack(0, "Track", "Artist"))
        fakeRepository.parseResult = Result.success(ParseSummary("Mix", 1, 1, 0, 0, 0, 0, tracks))
        val t0 = Track(id = "id_0", title = "Track", artist = "Artist")
        fakeRepository.matchChunkHandler = { _, _, _ ->
            Result.success(listOf(ImportMatchItem(0, ImportMatchStatus.MATCHED, ImportMatchTier.CONFIDENT, 0.9, t0)))
        }

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        // Uncheck the only track
        viewModel.toggleItemSelection(0)

        viewModel.confirmImport()
        advanceUntilIdle()

        assertEquals(0, fakeRepository.persistCalls.size)
        val step = viewModel.uiState.value.step
        assertTrue(step is ImportStep.PersistFailed)
    }

    @Test
    fun matchingChunkFailure_setsMatchFailed_retryResumesFromFailedChunk() = runTest {
        // 30 tracks -> chunk 0 (25 tracks), chunk 1 (5 tracks)
        val tracks = (0 until 30).map {
            ImportedTrack(sourceOrder = it, title = "Song $it", artist = "Artist")
        }
        fakeRepository.parseResult = Result.success(ParseSummary("Big Mix", 30, 30, 0, 0, 0, 0, tracks))

        var chunk0Attempts = 0
        var chunk1Attempts = 0
        fakeRepository.matchChunkHandler = { _, chunkIndex, chunkTracks ->
            if (chunkIndex == 0) {
                chunk0Attempts++
                Result.success(chunkTracks.map {
                    ImportMatchItem(it.sourceOrder, ImportMatchStatus.MATCHED, ImportMatchTier.CONFIDENT, 0.9, Track(it.title, it.title, it.artist))
                })
            } else {
                chunk1Attempts++
                if (chunk1Attempts == 1) {
                    Result.failure(RuntimeException("Network timeout"))
                } else {
                    Result.success(chunkTracks.map {
                        ImportMatchItem(it.sourceOrder, ImportMatchStatus.MATCHED, ImportMatchTier.CONFIDENT, 0.9, Track(it.title, it.title, it.artist))
                    })
                }
            }
        }

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        // First attempt should fail on chunk 1
        val failedStep = viewModel.uiState.value.step
        assertTrue(failedStep is ImportStep.MatchFailed)
        assertEquals(1, chunk0Attempts)
        assertEquals(1, chunk1Attempts)

        // Retry matching
        viewModel.retryMatching()
        advanceUntilIdle()

        // Chunk 0 should NOT be re-fetched (only chunk 1 re-attempted)
        assertEquals(1, chunk0Attempts)
        assertEquals(2, chunk1Attempts)

        // Now should be in Review state with all 30 tracks
        val reviewStep = viewModel.uiState.value.step
        assertTrue(reviewStep is ImportStep.Review)
        assertEquals(30, (reviewStep as ImportStep.Review).confidentItems.size)
    }
}
