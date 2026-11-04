package com.example.sonara.data.remote.extractor

import com.example.sonara.core.error.SonaraException
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.soundcloud.SoundcloudAudioStream
import com.example.sonara.domain.model.soundcloud.SoundcloudDeliveryMethod
import com.example.sonara.domain.model.soundcloud.SoundcloudNormalizedInput
import com.example.sonara.domain.model.soundcloud.SoundcloudSourceIdentity
import com.example.sonara.domain.model.soundcloud.SoundcloudTrackExtraction
import com.example.sonara.domain.ports.SoundcloudTrackExtractionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.io.IOException
import java.util.regex.Pattern


/**
 * Isolated SoundCloud track-extraction adapter backed by NewPipe Extractor.
 *
 * Scope: Audio-only songs for Sonara's Songs category.
 * Translates track URLs or track identifiers into validated [Track] metadata
 * and ephemeral playback stream descriptors without touching YouTube video models.
 */
class SoundcloudTrackExtractor(
    private val downloader: Downloader = NewPipeDownloader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val initialize: (Downloader) -> Result<Unit> = {
        NewPipeExtractorInitializer.initialize(it)
    },
    private val extractorFactory: (SoundcloudNormalizedInput) -> StreamExtractor = { input ->
        when (input) {
            is SoundcloudNormalizedInput.Url -> ServiceList.SoundCloud.getStreamExtractor(input.url)
            is SoundcloudNormalizedInput.TrackId -> {
                val linkHandler = ServiceList.SoundCloud.streamLHFactory.fromId(input.id)
                ServiceList.SoundCloud.getStreamExtractor(linkHandler)
            }
        }
    }
) : SoundcloudTrackExtractionPort {

    override suspend fun extract(input: String): Result<SoundcloudTrackExtraction> = withContext(ioDispatcher) {
        try {
            ensureActive()
            val normalized = normalizeInput(input).getOrElse { return@withContext Result.failure(it) }

            initialize(downloader).getOrElse { error ->
                return@withContext Result.failure(
                    SonaraException.ProviderUnavailableException("SoundCloud extractor initialization failed", error)
                )
            }

            ensureActive()
            val extractor = try {
                extractorFactory(normalized)
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
            } catch (error: Exception) {
                return@withContext Result.failure(mapExtractorError(error))
            }

            ensureActive()
            val rawId = extractor.id?.trim().orEmpty()
            val title = extractor.name?.trim().orEmpty()
            if (rawId.isBlank() || title.isBlank()) {
                return@withContext Result.failure(
                    SonaraException.ParsingException("SoundCloud track metadata missing required title or ID")
                )
            }

            val uploader = extractor.uploaderName?.trim().orEmpty().ifBlank { "Unknown Artist" }
            val durationSec = extractor.length
            val durationMs = if (durationSec > 0) durationSec * 1000L else 0L

            val artworkUrl = extractor.thumbnails
                ?.filter { !it.url.isNullOrBlank() }
                ?.maxByOrNull { it.width * it.height }
                ?.url
                ?: extractor.thumbnails?.firstOrNull()?.url

            val canonicalUrl = extractor.url?.trim().orEmpty().ifBlank {
                when (normalized) {
                    is SoundcloudNormalizedInput.Url -> normalized.url
                    is SoundcloudNormalizedInput.TrackId -> "https://api.soundcloud.com/tracks/${normalized.id}"
                }
            }

            val rawAudioStreams = try {
                extractor.audioStreams
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withContext Result.failure(mapExtractorError(error))
            }

            val mappedStreams = mapAudioStreams(rawAudioStreams)
            if (mappedStreams.isEmpty()) {
                return@withContext Result.failure(
                    SonaraException.ProviderUnavailableException("No supported audio streams available for track")
                )
            }

            val selectedStream = selectPreferredStream(mappedStreams)
                ?: return@withContext Result.failure(
                    SonaraException.ProviderUnavailableException("Failed to select a preferred audio stream")
                )

            val track = Track(
                id = SoundcloudSourceIdentity.toSoundcloudTrackId(rawId),
                title = title,
                artist = uploader,
                album = "",
                durationMs = durationMs,
                artworkUrl = artworkUrl
            )

            Result.success(
                SoundcloudTrackExtraction(
                    track = track,
                    rawTrackId = rawId,
                    canonicalUrl = canonicalUrl,
                    selectedStream = selectedStream,
                    allStreams = mappedStreams
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SonaraException) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(SonaraException.ProviderUnavailableException("SoundCloud track extraction failed", error))
        }
    }

    private fun mapExtractorError(error: Exception): SonaraException = when (error) {
        is SoundCloudGoPlusContentException -> SonaraException.ProviderUnavailableException(
            "SoundCloud Go+ subscription required for this track",
            error
        )
        is GeographicRestrictionException -> SonaraException.ProviderUnavailableException(
            "Track is not available in your region",
            error
        )
        is ContentNotAvailableException -> SonaraException.NotFoundException(
            "SoundCloud content not available: ${error.message ?: "Content unavailable"}",
            error
        )
        is IOException -> SonaraException.NetworkException(
            "SoundCloud network request failed",
            error
        )
        is SonaraException -> error
        else -> SonaraException.ParsingException(
            "Failed to extract SoundCloud track: ${error.message ?: "Unknown extraction error"}",
            error
        )
    }

    companion object {
        private const val ON_SOUNDCLOUD_PATTERN = "^https?://on\\.soundcloud\\.com/[0-9a-zA-Z]+$"
        private val SOUNDCLOUD_URL_PATTERN = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?" +
                "soundcloud\\.com/[0-9a-zA-Z_-]+" +
                "/(?!(?:tracks|albums|sets|reposts|followers|following)/?$)[0-9a-zA-Z_-]+/?(?:[#?].*)?$" +
                "|" + ON_SOUNDCLOUD_PATTERN,
            Pattern.CASE_INSENSITIVE
        )

        fun normalizeInput(input: String): Result<SoundcloudNormalizedInput> {
            val trimmed = input.trim()
            if (trimmed.isBlank()) {
                return Result.failure(SonaraException.ParsingException("SoundCloud track input cannot be blank"))
            }

            val stripped = if (SoundcloudSourceIdentity.isSoundcloudTrackId(trimmed)) {
                SoundcloudSourceIdentity.extractRawSoundcloudId(trimmed)
            } else {
                trimmed
            }

            if (stripped.isBlank()) {
                return Result.failure(SonaraException.ParsingException("SoundCloud track input cannot be blank"))
            }

            if (stripped.all { it.isDigit() }) {
                return Result.success(SoundcloudNormalizedInput.TrackId(stripped))
            }

            val urlCandidate = if (!stripped.startsWith("http://", ignoreCase = true) &&
                !stripped.startsWith("https://", ignoreCase = true)
            ) {
                if (stripped.startsWith("soundcloud.com", ignoreCase = true) ||
                    stripped.startsWith("m.soundcloud.com", ignoreCase = true) ||
                    stripped.startsWith("www.soundcloud.com", ignoreCase = true) ||
                    stripped.startsWith("on.soundcloud.com", ignoreCase = true)
                ) {
                    "https://$stripped"
                } else {
                    return Result.failure(
                        SonaraException.ParsingException("Invalid or unsupported SoundCloud track input: $input")
                    )
                }
            } else {
                stripped
            }

            return if (SOUNDCLOUD_URL_PATTERN.matcher(urlCandidate).matches()) {
                Result.success(SoundcloudNormalizedInput.Url(urlCandidate))
            } else {
                Result.failure(
                    SonaraException.ParsingException("Invalid or unsupported SoundCloud track input: $input")
                )
            }
        }

        fun mapAudioStreams(streams: List<AudioStream>): List<SoundcloudAudioStream> {
            val result = mutableListOf<SoundcloudAudioStream>()
            for (raw in streams) {
                val contentUrl = raw.content?.trim()
                if (contentUrl.isNullOrBlank()) continue

                val deliveryMethod = when (raw.deliveryMethod) {
                    DeliveryMethod.PROGRESSIVE_HTTP -> SoundcloudDeliveryMethod.PROGRESSIVE_HTTP
                    DeliveryMethod.HLS -> SoundcloudDeliveryMethod.HLS
                    else -> continue
                }

                val format = when (raw.format) {
                    MediaFormat.MP3 -> SoundcloudAudioStream.FORMAT_MP3
                    MediaFormat.M4A -> SoundcloudAudioStream.FORMAT_AAC
                    MediaFormat.OPUS -> SoundcloudAudioStream.FORMAT_OPUS
                    else -> continue
                }

                val reportedBitrate = raw.averageBitrate.takeIf { it > 0 } ?: continue

                // Pinned NewPipe Extractor 0.26.5 only exposes:
                // - MP3: 128 kbps (Progressive or HLS)
                // - AAC: 160 kbps (HLS)
                // - Opus: 64 kbps (HLS)
                val isValidPresetBitrate = when (format) {
                    SoundcloudAudioStream.FORMAT_MP3 -> reportedBitrate == 128
                    SoundcloudAudioStream.FORMAT_AAC -> reportedBitrate == 160 && deliveryMethod == SoundcloudDeliveryMethod.HLS
                    SoundcloudAudioStream.FORMAT_OPUS -> reportedBitrate == 64 && deliveryMethod == SoundcloudDeliveryMethod.HLS
                    else -> false
                }

                if (!isValidPresetBitrate) continue

                val mimeType = SoundcloudAudioStream.resolveMimeType(deliveryMethod, format)

                result.add(
                    SoundcloudAudioStream(
                        id = raw.id.orEmpty().ifBlank { "${deliveryMethod.name.lowercase()}_${format.lowercase()}_${reportedBitrate}k" },
                        streamUrl = contentUrl,
                        deliveryMethod = deliveryMethod,
                        mediaFormat = format,
                        mimeType = mimeType,
                        bitrateKbps = reportedBitrate
                    )
                )
            }
            return result
        }

        fun selectPreferredStream(streams: List<SoundcloudAudioStream>): SoundcloudAudioStream? {
            if (streams.isEmpty()) return null
            // Deterministic preference hierarchy:
            // 1. AAC 160k HLS (highest quality standard audio)
            // 2. MP3 128k Progressive (direct HTTP stream)
            // 3. MP3 128k HLS (adaptive fallback)
            // 4. Opus 64k HLS (lowest bitrate fallback)
            return streams.sortedWith(
                compareByDescending<SoundcloudAudioStream> { stream ->
                    when {
                        stream.mediaFormat == SoundcloudAudioStream.FORMAT_AAC &&
                            stream.deliveryMethod == SoundcloudDeliveryMethod.HLS &&
                            stream.bitrateKbps >= 160 -> 40
                        stream.mediaFormat == SoundcloudAudioStream.FORMAT_MP3 &&
                            stream.deliveryMethod == SoundcloudDeliveryMethod.PROGRESSIVE_HTTP &&
                            stream.bitrateKbps >= 128 -> 30
                        stream.mediaFormat == SoundcloudAudioStream.FORMAT_MP3 &&
                            stream.deliveryMethod == SoundcloudDeliveryMethod.HLS &&
                            stream.bitrateKbps >= 128 -> 20
                        stream.mediaFormat == SoundcloudAudioStream.FORMAT_OPUS &&
                            stream.deliveryMethod == SoundcloudDeliveryMethod.HLS -> 10
                        else -> 0
                    }
                }.thenByDescending { it.bitrateKbps }
            ).firstOrNull()
        }
    }
}
