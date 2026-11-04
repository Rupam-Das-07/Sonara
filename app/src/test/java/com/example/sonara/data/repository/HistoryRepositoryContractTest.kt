package com.example.sonara.data.repository

import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

class HistoryRepositoryContractTest {

    @Test
    fun provenanceAwareMethodIsAbstractRatherThanAnImplicitLegacyFallback() {
        val method = HistoryRepository::class.java.getDeclaredMethod(
            "recordHistoryWithProvenance",
            Track::class.java,
            Boolean::class.javaPrimitiveType!!,
            PlaybackOrigin::class.java,
            String::class.java,
            Continuation::class.java
        )

        assertTrue(
            "provenance-aware recording must be implemented by each repository",
            Modifier.isAbstract(method.modifiers)
        )
        assertFalse("the interface must not provide a default legacy fallback", method.isDefault)
    }

    @Test
    fun provenanceAwareCallKeepsOriginAndSessionSeparateFromLegacyRecording() = runBlocking {
        val repository = RecordingRepository()
        val track = Track(id = "track", title = "Song", artist = "Artist")

        repository.recordHistoryWithProvenance(
            track = track,
            completed = true,
            sourceSection = PlaybackOrigin.SEARCH_SONGS,
            playbackSessionId = "session-1"
        )

        assertEquals(0, repository.legacyCalls)
        assertEquals(
            RecordingRepository.ProvenanceCall(
                track = track,
                completed = true,
                sourceSection = PlaybackOrigin.SEARCH_SONGS,
                playbackSessionId = "session-1"
            ),
            repository.provenanceCall
        )
    }

    private class RecordingRepository : HistoryRepository {
        data class ProvenanceCall(
            val track: Track,
            val completed: Boolean,
            val sourceSection: PlaybackOrigin,
            val playbackSessionId: String
        )

        var legacyCalls = 0
        var provenanceCall: ProvenanceCall? = null

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> = flowOf(emptyList())

        override suspend fun recordHistory(track: Track, completed: Boolean): Long {
            legacyCalls++
            return 1L
        }

        override suspend fun recordHistoryWithProvenance(
            track: Track,
            completed: Boolean,
            sourceSection: PlaybackOrigin,
            playbackSessionId: String
        ): Long {
            provenanceCall = ProvenanceCall(track, completed, sourceSection, playbackSessionId)
            return 2L
        }

        override suspend fun markHistoryCompleted(historyId: Long) = Unit

        override suspend fun clearHistory() = Unit
    }
}
