package com.example.sonara.data.repository

import com.example.sonara.data.local.db.dao.PlaylistDao
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.PlaylistEntity
import com.example.sonara.data.local.db.entity.PlaylistTrackCrossRefEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.PlaylistOrigin
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistRepositoryTest {

    private class FakePlaylistDao : PlaylistDao {
        val playlists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
        val crossRefs = MutableStateFlow<List<PlaylistTrackCrossRefEntity>>(emptyList())
        val tracksMap = mutableMapOf<String, TrackEntity>()

        override fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlists

        override fun getPlaylistById(playlistId: String): Flow<PlaylistEntity?> {
            return MutableStateFlow(playlists.value.find { it.id == playlistId })
        }

        override suspend fun getPlaylistByIdSync(playlistId: String): PlaylistEntity? {
            return playlists.value.find { it.id == playlistId }
        }

        override fun getPlaylistTracks(playlistId: String): Flow<List<TrackEntity>> {
            val orderedIds = crossRefs.value
                .filter { it.playlistId == playlistId }
                .sortedBy { it.position }
                .map { it.trackId }
            return MutableStateFlow(orderedIds.mapNotNull { tracksMap[it] })
        }

        override suspend fun getPlaylistTracksSync(playlistId: String): List<TrackEntity> {
            val orderedIds = crossRefs.value
                .filter { it.playlistId == playlistId }
                .sortedBy { it.position }
                .map { it.trackId }
            return orderedIds.mapNotNull { tracksMap[it] }
        }

        override suspend fun insertPlaylist(playlist: PlaylistEntity) {
            val list = playlists.value.toMutableList()
            list.removeAll { it.id == playlist.id }
            list.add(0, playlist)
            playlists.value = list
        }

        override suspend fun updatePlaylistName(playlistId: String, newName: String, updatedAt: Long) {
            val list = playlists.value.toMutableList()
            val index = list.indexOfFirst { it.id == playlistId }
            if (index != -1) {
                list[index] = list[index].copy(name = newName, updatedAt = updatedAt)
                playlists.value = list
            }
        }

        override suspend fun updatePlaylistTimestamp(playlistId: String, updatedAt: Long) {
            val list = playlists.value.toMutableList()
            val index = list.indexOfFirst { it.id == playlistId }
            if (index != -1) {
                list[index] = list[index].copy(updatedAt = updatedAt)
                playlists.value = list
            }
        }

        override suspend fun deletePlaylist(playlistId: String) {
            playlists.value = playlists.value.filter { it.id != playlistId }
            crossRefs.value = crossRefs.value.filter { it.playlistId != playlistId }
        }

        override suspend fun insertPlaylistTrack(crossRef: PlaylistTrackCrossRefEntity): Long {
            val list = crossRefs.value.toMutableList()
            if (list.none { it.playlistId == crossRef.playlistId && it.trackId == crossRef.trackId }) {
                list.add(crossRef)
                crossRefs.value = list
                return 1L
            }
            return -1L
        }

        override suspend fun insertPlaylistTracks(crossRefs: List<PlaylistTrackCrossRefEntity>): List<Long> {
            val list = this.crossRefs.value.toMutableList()
            val result = mutableListOf<Long>()
            for (cr in crossRefs) {
                if (list.none { it.playlistId == cr.playlistId && it.trackId == cr.trackId }) {
                    list.add(cr)
                    result.add(1L)
                } else {
                    result.add(-1L)
                }
            }
            this.crossRefs.value = list
            return result
        }

        override suspend fun deletePlaylistTrack(playlistId: String, trackId: String) {
            crossRefs.value = crossRefs.value.filter { !(it.playlistId == playlistId && it.trackId == trackId) }
        }

        override suspend fun getMaxPosition(playlistId: String): Int? {
            return crossRefs.value.filter { it.playlistId == playlistId }.maxOfOrNull { it.position }
        }

        override suspend fun isTrackInPlaylist(playlistId: String, trackId: String): Boolean {
            return crossRefs.value.any { it.playlistId == playlistId && it.trackId == trackId }
        }

        override suspend fun getTrackCount(playlistId: String): Int {
            return crossRefs.value.count { it.playlistId == playlistId }
        }

        override suspend fun getFirstTrackArtwork(playlistId: String): String? {
            val firstId = crossRefs.value
                .filter { it.playlistId == playlistId }
                .minByOrNull { it.position }
                ?.trackId ?: return null
            return tracksMap[firstId]?.artworkUrl
        }

        override suspend fun updateTrackPosition(playlistId: String, trackId: String, newPosition: Int) {
            val list = crossRefs.value.toMutableList()
            val index = list.indexOfFirst { it.playlistId == playlistId && it.trackId == trackId }
            if (index != -1) {
                list[index] = list[index].copy(position = newPosition)
                crossRefs.value = list
            }
        }

        override suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<String>) {
            val list = crossRefs.value.toMutableList()
            orderedTrackIds.forEachIndexed { pos, trackId ->
                val index = list.indexOfFirst { it.playlistId == playlistId && it.trackId == trackId }
                if (index != -1) {
                    list[index] = list[index].copy(position = pos)
                }
            }
            crossRefs.value = list
        }
    }

    private class FakeTrackDao(private val fakePlaylistDao: FakePlaylistDao) : TrackDao {
        override suspend fun insertTrack(track: TrackEntity) {
            fakePlaylistDao.tracksMap[track.id] = track
        }
        override suspend fun insertTracks(tracks: List<TrackEntity>) {
            tracks.forEach { fakePlaylistDao.tracksMap[it.id] = it }
        }
        override suspend fun getTrackById(trackId: String): TrackEntity? = fakePlaylistDao.tracksMap[trackId]
        override suspend fun getTracksByIds(trackIds: List<String>): List<TrackEntity> = trackIds.mapNotNull { fakePlaylistDao.tracksMap[it] }
    }

    private lateinit var fakePlaylistDao: FakePlaylistDao
    private lateinit var fakeTrackDao: FakeTrackDao
    private lateinit var repository: PlaylistRepositoryImpl

    @Before
    fun setup() {
        fakePlaylistDao = FakePlaylistDao()
        fakeTrackDao = FakeTrackDao(fakePlaylistDao)
        repository = PlaylistRepositoryImpl(fakePlaylistDao, fakeTrackDao)
    }

    @Test
    fun createPlaylist_validName_succeedsAndPersists() = runTest {
        val result = repository.createPlaylist("Acoustic Vibes")
        assertTrue(result.isSuccess)
        val id = result.getOrThrow()

        val playlists = repository.getUserPlaylists().first()
        assertEquals(1, playlists.size)
        assertEquals("Acoustic Vibes", playlists.first().name)
        assertEquals(id, playlists.first().id)
    }

    @Test
    fun createPlaylist_invalidName_fails() = runTest {
        val emptyResult = repository.createPlaylist("")
        assertTrue(emptyResult.isFailure)

        val whitespaceResult = repository.createPlaylist("   ")
        assertTrue(whitespaceResult.isFailure)

        val tooLongResult = repository.createPlaylist("A".repeat(61))
        assertTrue(tooLongResult.isFailure)
    }

    @Test
    fun renamePlaylist_validName_updatesName() = runTest {
        val id = repository.createPlaylist("Old Name").getOrThrow()
        val renameResult = repository.renamePlaylist(id, "New Name")
        assertTrue(renameResult.isSuccess)

        val detail = repository.getPlaylistDetail(id).first()
        assertNotNull(detail)
        assertEquals("New Name", detail?.name)
    }

    @Test
    fun deletePlaylist_removesPlaylistAndAssociations() = runTest {
        val id = repository.createPlaylist("To Delete").getOrThrow()
        val track = Track(id = "track_1", title = "Song", artist = "Artist")
        repository.addTrackToPlaylist(id, track)

        assertEquals(1, repository.getUserPlaylists().first().size)

        val deleteResult = repository.deletePlaylist(id)
        assertTrue(deleteResult.isSuccess)

        assertEquals(0, repository.getUserPlaylists().first().size)
        assertNull(repository.getPlaylistDetail(id).first())
    }

    @Test
    fun addTrack_appendsTrackAndPreventsDuplicates() = runTest {
        val id = repository.createPlaylist("Favorites").getOrThrow()
        val track1 = Track(id = "track_1", title = "First Song", artist = "Artist")
        val track2 = Track(id = "track_2", title = "Second Song", artist = "Artist")

        repository.addTrackToPlaylist(id, track1)
        repository.addTrackToPlaylist(id, track2)
        // Duplicate addition attempt
        repository.addTrackToPlaylist(id, track1)

        val detail = repository.getPlaylistDetail(id).first()
        assertNotNull(detail)
        assertEquals(2, detail?.tracks?.size)
        assertEquals("track_1", detail?.tracks?.get(0)?.id)
        assertEquals("track_2", detail?.tracks?.get(1)?.id)
    }

    @Test
    fun removeTrack_removesTrackAndPreservesOthers() = runTest {
        val id = repository.createPlaylist("Mixed").getOrThrow()
        val track1 = Track(id = "track_1", title = "One", artist = "A")
        val track2 = Track(id = "track_2", title = "Two", artist = "B")
        repository.addTrackToPlaylist(id, track1)
        repository.addTrackToPlaylist(id, track2)

        repository.removeTrackFromPlaylist(id, "track_1")

        val detail = repository.getPlaylistDetail(id).first()
        assertEquals(1, detail?.tracks?.size)
        assertEquals("track_2", detail?.tracks?.first()?.id)
    }

    @Test
    fun reorderTracks_updatesOrderCorrectly() = runTest {
        val id = repository.createPlaylist("Order Test").getOrThrow()
        val track1 = Track(id = "track_1", title = "One", artist = "A")
        val track2 = Track(id = "track_2", title = "Two", artist = "B")
        val track3 = Track(id = "track_3", title = "Three", artist = "C")
        repository.addTrackToPlaylist(id, track1)
        repository.addTrackToPlaylist(id, track2)
        repository.addTrackToPlaylist(id, track3)

        val reorderResult = repository.reorderTracks(id, listOf("track_3", "track_1", "track_2"))
        assertTrue(reorderResult.isSuccess)

        val detail = repository.getPlaylistDetail(id).first()
        assertEquals(3, detail?.tracks?.size)
        assertEquals("track_3", detail?.tracks?.get(0)?.id)
        assertEquals("track_1", detail?.tracks?.get(1)?.id)
        assertEquals("track_2", detail?.tracks?.get(2)?.id)
    }

    @Test
    fun importPlaylist_validTracksWithDuplicates_collapsesDuplicatesAndPreservesOrder() = runTest {
        val tracks = listOf(
            Track(id = "vid_1", title = "Song A", artist = "Artist 1"),
            Track(id = "vid_2", title = "Song B", artist = "Artist 2"),
            Track(id = "vid_1", title = "Song A (Duplicate)", artist = "Artist 1"), // Duplicate videoId
            Track(id = "vid_3", title = "Song C", artist = "Artist 3"),
            Track(id = "vid_2", title = "Song B (Duplicate)", artist = "Artist 2")  // Duplicate videoId
        )

        val result = repository.importPlaylist("My Imported Mix", tracks)
        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals("My Imported Mix", summary.playlistName)
        assertEquals(3, summary.totalTracksAdded)
        assertEquals(2, summary.collapsedDuplicates)

        val detail = repository.getPlaylistDetail(summary.playlistId).first()
        assertNotNull(detail)
        assertEquals(3, detail?.tracks?.size)
        // Preserves first-seen order: vid_1, vid_2, vid_3
        assertEquals("vid_1", detail?.tracks?.get(0)?.id)
        assertEquals("vid_2", detail?.tracks?.get(1)?.id)
        assertEquals("vid_3", detail?.tracks?.get(2)?.id)
    }

    @Test
    fun importPlaylist_invalidName_fails() = runTest {
        val emptyResult = repository.importPlaylist("", listOf(Track(id = "t1", title = "T", artist = "A")))
        assertTrue(emptyResult.isFailure)

        val blankResult = repository.importPlaylist("   ", listOf(Track(id = "t1", title = "T", artist = "A")))
        assertTrue(blankResult.isFailure)

        val tooLongResult = repository.importPlaylist("A".repeat(61), listOf(Track(id = "t1", title = "T", artist = "A")))
        assertTrue(tooLongResult.isFailure)
    }

    @Test
    fun createPlaylist_persistsOriginUser() = runTest {
        val id = repository.createPlaylist("Acoustic Vibes").getOrThrow()
        val entity = fakePlaylistDao.playlists.value.find { it.id == id }
        assertNotNull("PlaylistEntity must be inserted", entity)
        assertEquals("USER", entity?.origin)

        val summaries = repository.getUserPlaylists().first()
        val summary = summaries.find { it.id == id }
        assertNotNull("PlaylistSummary must exist", summary)
        assertEquals(PlaylistOrigin.USER, summary?.origin)
    }

    @Test
    fun importPlaylist_persistsOriginSpotify() = runTest {
        val tracks = listOf(Track(id = "t_spotify", title = "Imported Track", artist = "Artist"))
        val result = repository.importPlaylist("Imported Mix", tracks).getOrThrow()
        val entity = fakePlaylistDao.playlists.value.find { it.id == result.playlistId }
        assertNotNull("PlaylistEntity must be inserted", entity)
        assertEquals("SPOTIFY", entity?.origin)

        val summaries = repository.getUserPlaylists().first()
        val summary = summaries.find { it.id == result.playlistId }
        assertNotNull("PlaylistSummary must exist", summary)
        assertEquals(PlaylistOrigin.SPOTIFY, summary?.origin)
    }

    @Test
    fun getUserPlaylists_mapsOriginDeterministic() = runTest {
        // Seed both a USER entity and a SPOTIFY entity directly
        fakePlaylistDao.insertPlaylist(PlaylistEntity(id = "p_user", name = "Native", origin = "USER"))
        fakePlaylistDao.insertPlaylist(PlaylistEntity(id = "p_spotify", name = "Imported", origin = "SPOTIFY"))

        val summaries = repository.getUserPlaylists().first()
        val userSummary = summaries.find { it.id == "p_user" }
        val spotifySummary = summaries.find { it.id == "p_spotify" }

        assertEquals(PlaylistOrigin.USER, userSummary?.origin)
        assertEquals(PlaylistOrigin.SPOTIFY, spotifySummary?.origin)
    }

    @Test
    fun playlistOrigin_unknownOrNull_safelyResolvesToUser() {
        assertEquals(PlaylistOrigin.USER, PlaylistOrigin.fromString(null))
        assertEquals(PlaylistOrigin.USER, PlaylistOrigin.fromString(""))
        assertEquals(PlaylistOrigin.USER, PlaylistOrigin.fromString("UNKNOWN"))
        assertEquals(PlaylistOrigin.USER, PlaylistOrigin.fromString("user"))
        assertEquals(PlaylistOrigin.USER, PlaylistOrigin.fromString("USER"))
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromString("spotify"))
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromString("SPOTIFY"))
    }
}
