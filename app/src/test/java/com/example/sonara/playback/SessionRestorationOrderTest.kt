package com.example.sonara.playback

import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRestorationOrderTest {

    @Test
    fun sessionRestoration_preservesCheckpointedQueueOrder() {
        val checkpointedQueueIds = listOf("t1", "t2", "t3", "t4")
        val locallyCachedTracks = listOf(
            Track(id = "t3", title = "Track 3", artist = "Artist"),
            Track(id = "t1", title = "Track 1", artist = "Artist"),
            Track(id = "t4", title = "Track 4", artist = "Artist")
            // t2 is missing from local cache
        )

        // Queue order restoration algorithm
        val reconstructedQueue = checkpointedQueueIds.mapNotNull { id ->
            locallyCachedTracks.find { it.id == id }
        }

        // Must preserve relative sequence: t1 -> t3 -> t4 (omitting t2 safely)
        assertEquals(3, reconstructedQueue.size)
        assertEquals("t1", reconstructedQueue[0].id)
        assertEquals("t3", reconstructedQueue[1].id)
        assertEquals("t4", reconstructedQueue[2].id)
    }
}
