package com.example.sonara.data.remote.extractor

import com.example.sonara.core.error.SonaraException
import com.example.sonara.domain.model.video.ExternalHighResStreamDescriptor
import com.example.sonara.domain.model.video.VideoExtractionResult
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.domain.model.video.evaluateVideoEmbeddingEligibility
import com.example.sonara.domain.ports.VideoExtractionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.URI

/** Isolated NewPipe extraction boundary; it does not own or start playback. */
class NewPipeVideoExtractor(
    private val downloader: Downloader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val initialize: (Downloader) -> Result<Unit> = {
        NewPipeExtractorInitializer.initialize(it)
    },
    private val loadInfo: (String) -> StreamInfo = StreamInfo::getInfo
) : VideoExtractionPort {
    override suspend fun extract(input: String, status: VideoStatus): Result<VideoExtractionResult> =
        loadEligibleInfo(input, status).fold(
            onSuccess = { info -> mapResult { NewPipeExtractionMapper.map(info, canonicalVideoId(input)!!) } },
            onFailure = { Result.failure(it) }
        )

    override suspend fun extractPlaybackSource(
        input: String,
        status: VideoStatus
    ): Result<VideoPlaybackSource> = extractPlaybackSource(input, status, null)

    override suspend fun extractPlaybackSource(
        input: String,
        status: VideoStatus,
        externalHighRes: ExternalHighResStreamDescriptor?
    ): Result<VideoPlaybackSource> = loadEligibleInfo(input, status).fold(
        onSuccess = { info -> mapResult { NewPipeExtractionMapper.mapPlaybackSource(info, canonicalVideoId(input)!!, externalHighRes) } },
        onFailure = { Result.failure(it) }
    )

    private suspend fun loadEligibleInfo(input: String, status: VideoStatus): Result<StreamInfo> =
        withContext(ioDispatcher) {
            try {
                ensureActive()
                val videoId = canonicalVideoId(input)
                    ?: return@withContext Result.failure(
                        SonaraException.ParsingException("Invalid video identifier")
                    )
                if (status.videoId != videoId) {
                    return@withContext Result.failure(
                        SonaraException.ParsingException("Video status does not match requested content")
                    )
                }
                if (!evaluateVideoEmbeddingEligibility(status).isAllowed) {
                    return@withContext Result.failure(
                        SonaraException.ProviderUnavailableException("Video is not eligible for extraction")
                    )
                }
                initialize(downloader).getOrElse { error ->
                    return@withContext Result.failure(
                        SonaraException.ProviderUnavailableException("Extractor initialization failed", error)
                    )
                }
                ensureActive()
                val info = try {
                    loadInfo(canonicalUrl(videoId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@withContext Result.failure(mapExtractorFailure(error))
                }
                ensureActive()
                Result.success(info)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SonaraException) {
                Result.failure(error)
            } catch (error: Exception) {
                Result.failure(SonaraException.ProviderUnavailableException("Video extraction failed", error))
            }
        }

    private fun <T> mapResult(mapper: () -> Result<T>): Result<T> = mapper().fold(
        onSuccess = { Result.success(it) },
        onFailure = { error ->
            Result.failure(
                SonaraException.ProviderUnavailableException(
                    error.message ?: "Extracted stream data was unusable",
                    error
                )
            )
        }
    )

    private fun canonicalVideoId(input: String): String? {
        val value = input.trim()
        if (value.length == YOUTUBE_ID_LENGTH && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            return value
        }

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "youtube.com" && host != "www.youtube.com" &&
            host != "m.youtube.com" && host != "youtu.be"
        ) return null

        val candidate = when {
            host == "youtu.be" -> uri.path.orEmpty().trim('/').substringBefore('/')
            uri.path == "/watch" -> uri.query.orEmpty().split('&')
                .firstOrNull { it.startsWith("v=") }
                ?.substringAfter('=')
            uri.path.orEmpty().startsWith("/shorts/") -> uri.path.substringAfter("/shorts/").substringBefore('/')
            uri.path.orEmpty().startsWith("/embed/") -> uri.path.substringAfter("/embed/").substringBefore('/')
            else -> null
        }
        return candidate?.takeIf {
            it.length == YOUTUBE_ID_LENGTH && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' }
        }
    }

    private fun canonicalUrl(videoId: String): String =
        "https://www.youtube.com/watch?v=$videoId"

    private fun mapExtractorFailure(error: Exception): SonaraException = when (error) {
        is java.io.IOException -> SonaraException.NetworkException("Extractor network request failed", error)
        else -> SonaraException.ProviderUnavailableException("Extractor rejected the content", error)
    }

    private companion object {
        const val YOUTUBE_ID_LENGTH = 11
    }
}
