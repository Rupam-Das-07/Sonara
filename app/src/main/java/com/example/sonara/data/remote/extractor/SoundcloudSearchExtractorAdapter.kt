package com.example.sonara.data.remote.extractor

import com.example.sonara.core.error.SonaraException
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.soundcloud.SoundcloudSourceIdentity
import com.example.sonara.domain.ports.SoundcloudSearchPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

/**
 * SoundCloud track search adapter backed by pinned NewPipe Extractor 0.26.5.
 *
 * Scope: Audio-only songs for Sonara's Songs search experience.
 * Translates SoundCloud search queries into domain [Track] models with "sc:" source
 * identities compatible with the SC-3 stream resolver and Media3 playback pipeline.
 */
class SoundcloudSearchExtractorAdapter(
    private val downloader: Downloader = NewPipeDownloader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val initialize: (Downloader) -> Result<Unit> = {
        NewPipeExtractorInitializer.initialize(it)
    },
    private val searchExtractorFactory: (query: String) -> SearchExtractor = { query ->
        val queryHandler = ServiceList.SoundCloud.searchQHFactory.fromQuery(
            query,
            listOf(SoundcloudSearchQueryHandlerFactory.TRACKS),
            ""
        )
        ServiceList.SoundCloud.getSearchExtractor(queryHandler)
    }
) : SoundcloudSearchPort {

    override suspend fun searchSongs(query: String, limit: Int): Result<List<Track>> = withContext(ioDispatcher) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank() || limit <= 0) {
            return@withContext Result.success(emptyList())
        }

        try {
            ensureActive()
            initialize(downloader).getOrElse { error ->
                return@withContext Result.failure(
                    SonaraException.ProviderUnavailableException(
                        "SoundCloud search extractor initialization failed",
                        error
                    )
                )
            }

            ensureActive()
            val extractor = try {
                searchExtractorFactory(cleanQuery)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withContext Result.failure(mapExtractorError(error))
            }

            ensureActive()
            try {
                extractor.fetchPage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (nothingFound: SearchExtractor.NothingFoundException) {
                // NewPipe Extractor throws NothingFoundException on zero matching results
                return@withContext Result.success(emptyList())
            } catch (error: Exception) {
                return@withContext Result.failure(mapExtractorError(error))
            }

            ensureActive()
            val page = try {
                extractor.initialPage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (nothingFound: SearchExtractor.NothingFoundException) {
                return@withContext Result.success(emptyList())
            } catch (error: Exception) {
                return@withContext Result.failure(mapExtractorError(error))
            }

            val items = page?.items.orEmpty()
            val tracks = mapStreamInfoItems(items, limit)
            Result.success(tracks)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SonaraException) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(
                SonaraException.ProviderUnavailableException(
                    "SoundCloud search failed: ${error.message ?: "Unknown error"}",
                    error
                )
            )
        }
    }

    private fun mapStreamInfoItems(items: List<*>, limit: Int): List<Track> {
        val tracks = mutableListOf<Track>()
        for (item in items) {
            if (tracks.size >= limit) break
            if (item !is StreamInfoItem) continue

            // Exclude subscription-gated or paid tracks (SoundCloud Go+)
            val availability = item.contentAvailability
            if (availability == ContentAvailability.MEMBERSHIP || availability == ContentAvailability.PAID) {
                continue
            }

            val title = item.name?.trim().orEmpty()
            val url = item.url?.trim().orEmpty()
            if (title.isBlank() || url.isBlank()) continue

            val uploader = item.uploaderName?.trim().orEmpty().ifBlank { "Unknown Artist" }
            val durationSec = item.duration
            val durationMs = if (durationSec > 0) durationSec * 1000L else 0L

            val thumbnails = runCatching { item.thumbnails }.getOrNull().orEmpty()
            val artworkUrl = thumbnails
                .filter { !it.url.isNullOrBlank() }
                .maxByOrNull { it.width * it.height }
                ?.url
                ?: thumbnails.firstOrNull()?.url

            val trackId = SoundcloudSourceIdentity.toSoundcloudTrackId(url)

            tracks.add(
                Track(
                    id = trackId,
                    title = title,
                    artist = uploader,
                    album = "", // Do not overwrite album with source brand
                    durationMs = durationMs,
                    artworkUrl = artworkUrl
                )
            )
        }
        return tracks
    }

    private fun mapExtractorError(error: Exception): SonaraException = when (error) {
        is SearchExtractor.NothingFoundException -> SonaraException.NotFoundException(
            "No SoundCloud tracks found",
            error
        )
        is IOException -> SonaraException.NetworkException(
            "SoundCloud search network request failed",
            error
        )
        is ParsingException -> SonaraException.ParsingException(
            "Failed to parse SoundCloud search response: ${error.message ?: "Malformed data"}",
            error
        )
        is ExtractionException -> SonaraException.ProviderUnavailableException(
            "SoundCloud search extraction error: ${error.message ?: "Provider error"}",
            error
        )
        is SonaraException -> error
        else -> SonaraException.ProviderUnavailableException(
            "SoundCloud search request failed: ${error.message ?: "Unexpected error"}",
            error
        )
    }
}
