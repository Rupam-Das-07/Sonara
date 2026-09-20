package com.example.sonara.playback

import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryQualificationTest {

    private class FakeHistoryRepository : HistoryRepository {
        val recordedHistory = mutableListOf<HistoryItem>()
        private val historyFlow = MutableStateFlow<List<HistoryItem>>(emptyList())

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> = historyFlow

        override suspend fun recordHistory(track: Track, completed: Boolean) {
            val item = HistoryItem(
                id = recordedHistory.size + 1L,
                track = track,
                listenedAt = System.currentTimeMillis(),
                completed = completed
            )
            recordedHistory.add(item)
            historyFlow.value = recordedHistory.toList()
        }

        override suspend fun clearHistory() {
            recordedHistory.clear()
            historyFlow.value = emptyList()
        }
    }

    @Test
    fun historyRepository_recordsTrackUponQualification() = runBlocking {
        val repo = FakeHistoryRepository()
        val track = Track(id = "track_1", title = "Acoustic Calm", artist = "Sonara")

        repo.recordHistory(track, completed = false)

        assertEquals(1, repo.recordedHistory.size)
        assertEquals("track_1", repo.recordedHistory[0].track.id)
        assertEquals("Acoustic Calm", repo.recordedHistory[0].track.title)
    }

    @Test
    fun historyRepository_clearsHistoryCorrectly() = runBlocking {
        val repo = FakeHistoryRepository()
        val track = Track(id = "track_1", title = "Acoustic Calm", artist = "Sonara")
        repo.recordHistory(track, completed = true)
        assertEquals(1, repo.recordedHistory.size)

        repo.clearHistory()
        assertEquals(0, repo.recordedHistory.size)
    }
}
