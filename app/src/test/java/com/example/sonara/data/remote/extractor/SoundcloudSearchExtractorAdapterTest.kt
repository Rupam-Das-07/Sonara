package com.example.sonara.data.remote.extractor

import com.example.sonara.core.error.SonaraException
import com.example.sonara.domain.model.soundcloud.SoundcloudSourceIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.MetaInfo
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

class SoundcloudSearchExtractorAdapterTest {

    private class NoOpDownloader : Downloader() {
        override fun execute(request: Request): Response =
            Response(200, "OK", emptyMap(), "", "")
    }

    private class FakeSearchExtractor(
        val fakeItems: List<InfoItem> = emptyList(),
        val onFetchError: Exception? = null
    ) : SearchExtractor(
        ServiceList.SoundCloud,
        SearchQueryHandler(
            "https://api-v2.soundcloud.com/search/tracks",
            "https://api-v2.soundcloud.com/search/tracks",
            "test",
            listOf("tracks"),
            ""
        )
    ) {
        override fun onFetchPage(downloader: Downloader) {
            if (onFetchError != null) throw onFetchError
        }
        override fun getSearchSuggestion(): String = ""
        override fun isCorrectedSearch(): Boolean = false
        override fun getMetaInfo(): List<MetaInfo> = emptyList()
        override fun getInitialPage(): ListExtractor.InfoItemsPage<InfoItem> {
            if (onFetchError != null) throw onFetchError
            return ListExtractor.InfoItemsPage(fakeItems, null, emptyList())
        }
        override fun getPage(page: Page?): ListExtractor.InfoItemsPage<InfoItem> = initialPage
    }

    private fun createStreamInfoItem(
        url: String = "https://soundcloud.com/artist/sample-track",
        name: String = "Sample Track",
        uploaderName: String = "Sample Artist",
        durationSec: Long = 215L,
        thumbnails: List<Image> = listOf(
            Image("https://example.com/art_small.jpg", 100, 100, Image.ResolutionLevel.LOW),
            Image("https://example.com/art_large.jpg", 500, 500, Image.ResolutionLevel.HIGH)
        ),
        availability: ContentAvailability = ContentAvailability.AVAILABLE
    ): StreamInfoItem {
        val item = StreamInfoItem(ServiceList.SoundCloud.serviceId, url, name, StreamType.AUDIO_STREAM)
        item.uploaderName = uploaderName
        item.duration = durationSec
        item.thumbnails = thumbnails
        item.contentAvailability = availability
        return item
    }

    @Before
    fun setUp() {
        if (runCatching { NewPipe.getDownloader() }.getOrNull() == null) {
            NewPipe.init(NoOpDownloader())
        }
    }

    // ─── 1. Query Validation & Blank Handling ─────────────────────────────────

    @Test
    fun `blank or whitespace queries return empty list immediately without calling extractor`() = runTest {
        var factoryCalled = false
        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = {
                factoryCalled = true
                FakeSearchExtractor()
            }
        )

        val emptyResult = adapter.searchSongs("")
        assertTrue(emptyResult.isSuccess)
        assertTrue(emptyResult.getOrNull()?.isEmpty() == true)

        val whitespaceResult = adapter.searchSongs("   \t  \n  ")
        assertTrue(whitespaceResult.isSuccess)
        assertTrue(whitespaceResult.getOrNull()?.isEmpty() == true)

        assertFalse("Extractor factory should not be called for blank query", factoryCalled)
    }

    @Test
    fun `zero or negative limit returns empty list immediately`() = runTest {
        var factoryCalled = false
        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = {
                factoryCalled = true
                FakeSearchExtractor()
            }
        )

        val resultZero = adapter.searchSongs("test", limit = 0)
        assertTrue(resultZero.isSuccess)
        assertTrue(resultZero.getOrNull()?.isEmpty() == true)

        val resultNegative = adapter.searchSongs("test", limit = -5)
        assertTrue(resultNegative.isSuccess)
        assertTrue(resultNegative.getOrNull()?.isEmpty() == true)

        assertFalse(factoryCalled)
    }

    // ─── 2. Valid Track Metadata Mapping ──────────────────────────────────────

    @Test
    fun `valid track metadata maps cleanly into domain Track with sc prefix`() = runTest {
        val item = createStreamInfoItem(
            url = "https://soundcloud.com/wearecc/open-minds-ep-21",
            name = "Open Minds, Ep 21",
            uploaderName = "Creative Commons",
            durationSec = 1500L,
            thumbnails = listOf(
                Image("https://example.com/art_small.jpg", 100, 100, Image.ResolutionLevel.LOW),
                Image("https://example.com/art_large.jpg", 500, 500, Image.ResolutionLevel.HIGH)
            ),
            availability = ContentAvailability.AVAILABLE
        )

        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = { FakeSearchExtractor(fakeItems = listOf(item)) }
        )

        val result = adapter.searchSongs("Open Minds")
        assertTrue(result.isSuccess)
        val tracks = result.getOrNull()
        assertNotNull(tracks)
        assertEquals(1, tracks?.size)

        val track = tracks?.first()!!
        assertEquals("sc:https://soundcloud.com/wearecc/open-minds-ep-21", track.id)
        assertTrue(SoundcloudSourceIdentity.isSoundcloudTrackId(track.id))
        assertEquals("Open Minds, Ep 21", track.title)
        assertEquals("Creative Commons", track.artist)
        assertEquals("", track.album) // Must not be mislabeled as "SoundCloud"
        assertEquals(1500000L, track.durationMs)
        assertEquals("https://example.com/art_large.jpg", track.artworkUrl)
    }

    @Test
    fun `missing optional artwork maps cleanly with null artworkUrl`() = runTest {
        val item = createStreamInfoItem(
            thumbnails = emptyList()
        )

        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = { FakeSearchExtractor(fakeItems = listOf(item)) }
        )

        val result = adapter.searchSongs("test")
        assertTrue(result.isSuccess)
        val track = result.getOrNull()?.first()!!
        assertNull(track.artworkUrl)
    }

    @Test
    fun `zero duration maps to 0L durationMs`() = runTest {
        val item = createStreamInfoItem(
            durationSec = 0L
        )

        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = { FakeSearchExtractor(fakeItems = listOf(item)) }
        )

        val result = adapter.searchSongs("test")
        assertTrue(result.isSuccess)
        val track = result.getOrNull()?.first()!!
        assertEquals(0L, track.durationMs)
    }

    // ─── 3. Content Availability & Gated Content Filtering ────────────────────

    @Test
    fun `subscription gated membership and paid tracks are excluded from results`() = runTest {
        val freeItem = createStreamInfoItem(
            url = "https://soundcloud.com/artist/free-song",
            name = "Free Song",
            availability = ContentAvailability.AVAILABLE
        )
        val membershipItem = createStreamInfoItem(
            url = "https://soundcloud.com/artist/go-plus-song",
            name = "Go Plus Song",
            availability = ContentAvailability.MEMBERSHIP
        )
        val paidItem = createStreamInfoItem(
            url = "https://soundcloud.com/artist/paid-song",
            name = "Paid Song",
            availability = ContentAvailability.PAID
        )

        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = {
                FakeSearchExtractor(fakeItems = listOf(freeItem, membershipItem, paidItem))
            }
        )

        val result = adapter.searchSongs("query")
        assertTrue(result.isSuccess)
        val tracks = result.getOrNull()
        assertNotNull(tracks)
        assertEquals(1, tracks?.size)
        assertEquals("Free Song", tracks?.first()?.title)
    }

    @Test
    fun `unknown content availability is preserved for stream resolution`() = runTest {
        val unknownItem = createStreamInfoItem(
            url = "https://soundcloud.com/artist/unknown-avail-song",
            name = "Unknown Availability Song",
            availability = ContentAvailability.UNKNOWN
        )

        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = { FakeSearchExtractor(fakeItems = listOf(unknownItem)) }
        )

        val result = adapter.searchSongs("query")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("Unknown Availability Song", result.getOrNull()?.first()?.title)
    }

    // ─── 4. Empty Result & NothingFoundException Translation ──────────────────

    @Test
    fun `NothingFoundException during fetchPage translates to successful empty list`() = runTest {
        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = {
                FakeSearchExtractor(
                    onFetchError = SearchExtractor.NothingFoundException("Nothing found")
                )
            }
        )

        val result = adapter.searchSongs("nonexistentquery123456")
        assertTrue(result.isSuccess)
        val tracks = result.getOrNull()
        assertNotNull(tracks)
        assertTrue(tracks?.isEmpty() == true)
    }

    // ─── 5. Error Mapping & Cancellation ──────────────────────────────────────

    @Test
    fun `network IOException maps to SonaraException NetworkException`() = runTest {
        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = {
                FakeSearchExtractor(onFetchError = IOException("Connection reset by peer"))
            }
        )

        val result = adapter.searchSongs("query")
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(
            "Expected SonaraException.NetworkException but got ${exception?.javaClass?.simpleName}",
            exception is SonaraException.NetworkException
        )
    }

    @Test
    fun `ParsingException maps to SonaraException ParsingException`() = runTest {
        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = {
                FakeSearchExtractor(onFetchError = ParsingException("Could not parse json response"))
            }
        )

        val result = adapter.searchSongs("query")
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(
            "Expected SonaraException.ParsingException but got ${exception?.javaClass?.simpleName}",
            exception is SonaraException.ParsingException
        )
    }

    @Test
    fun `initialization failure maps to SonaraException ProviderUnavailableException`() = runTest {
        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.failure(IllegalStateException("Downloader misconfigured")) },
            searchExtractorFactory = { FakeSearchExtractor() }
        )

        val result = adapter.searchSongs("query")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SonaraException.ProviderUnavailableException)
    }

    @Test(expected = CancellationException::class)
    fun `CancellationException is rethrown without being caught`() = runTest {
        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = {
                throw CancellationException("Search coroutine was cancelled")
            }
        )

        adapter.searchSongs("query")
    }

    // ─── 6. Limit Enforcement ─────────────────────────────────────────────────

    @Test
    fun `search results respect limit parameter strictly`() = runTest {
        val items = (1..10).map { i ->
            createStreamInfoItem(
                url = "https://soundcloud.com/artist/song-$i",
                name = "Song $i"
            )
        }

        val adapter = SoundcloudSearchExtractorAdapter(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            searchExtractorFactory = { FakeSearchExtractor(fakeItems = items) }
        )

        val result = adapter.searchSongs("query", limit = 4)
        assertTrue(result.isSuccess)
        assertEquals(4, result.getOrNull()?.size)
        assertEquals("Song 1", result.getOrNull()?.first()?.title)
        assertEquals("Song 4", result.getOrNull()?.last()?.title)
    }

    // ─── 7. Source Attribution & Identity Compatibility ──────────────────────

    @Test
    fun `SoundCloud track IDs are reliably identifiable for UI source attribution`() {
        val scTrackId1 = SoundcloudSourceIdentity.toSoundcloudTrackId("https://soundcloud.com/artist/song")
        val scTrackId2 = SoundcloudSourceIdentity.toSoundcloudTrackId("12345678")
        val legacyScId = "soundcloud:12345678"
        val ytTrackId = "dQw4w9WgXcQ"

        assertTrue(SoundcloudSourceIdentity.isSoundcloudTrackId(scTrackId1))
        assertTrue(SoundcloudSourceIdentity.isSoundcloudTrackId(scTrackId2))
        assertTrue(SoundcloudSourceIdentity.isSoundcloudTrackId(legacyScId))
        assertFalse(SoundcloudSourceIdentity.isSoundcloudTrackId(ytTrackId))
        assertFalse(SoundcloudSourceIdentity.isSoundcloudTrackId(""))
    }
}
