package com.example.sonara.data.remote.extractor

import com.example.sonara.domain.model.video.ExternalHighResStreamDescriptor
import com.example.sonara.domain.model.video.HighResAudioRepresentation
import com.example.sonara.domain.model.video.HighResByteRange
import com.example.sonara.domain.model.video.HighResVideoRepresentation
import com.example.sonara.domain.model.video.VideoDeliveryMethod
import com.example.sonara.domain.model.video.VideoExtractionResult
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoPlaybackStream
import com.example.sonara.domain.model.video.VideoStreamDescriptor
import com.example.sonara.domain.model.video.VideoStreamKind
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

internal object NewPipeExtractionMapper {
    fun map(info: StreamInfo, videoId: String): Result<VideoExtractionResult> {
        if (!isPubliclyAvailable(info)) {
            return Result.failure(IllegalStateException("Extracted content is not publicly available"))
        }

        val streams = buildList {
            info.getVideoStreams().orEmpty().mapNotNullTo(this) { it.toDescriptor() }
            info.getVideoOnlyStreams().orEmpty().mapNotNullTo(this) { it.toDescriptor() }
            info.getAudioStreams().orEmpty().mapNotNullTo(this) { it.toDescriptor() }
        }
        if (streams.isEmpty()) {
            return Result.failure(IllegalStateException("Extractor returned no usable streams"))
        }

        val streamType = info.getStreamType()
        val isLive = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM
        val isUpcoming = isLive && info.getContentAvailability() == ContentAvailability.UPCOMING

        val durationMs = if (isLive) {
            null
        } else {
            info.getDuration().takeIf { it >= 0L }?.let { seconds ->
                seconds.takeIf { it <= Long.MAX_VALUE / 1_000L }?.times(1_000L)
            }
        }
        val viewCount = info.getViewCount().takeIf { it >= 0L }
        val thumbnails = info.getThumbnails().orEmpty()
            .map(Image::getUrl)
            .filter(String::isNotBlank)

        val validDashUrl = info.dashMpdUrl?.trim()?.takeIf {
            it.isNotEmpty() && (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
        }
        val validHlsUrl = info.hlsUrl?.trim()?.takeIf {
            it.isNotEmpty() && (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
        }

        return Result.success(
            VideoExtractionResult(
                videoId = videoId,
                title = info.getName().takeIf(String::isNotBlank),
                uploader = info.getUploaderName().takeIf(String::isNotBlank),
                durationMs = durationMs,
                thumbnails = thumbnails,
                streams = streams,
                viewCount = viewCount,
                dashUrl = validDashUrl,
                hlsUrl = validHlsUrl,
                isLive = isLive,
                isUpcoming = isUpcoming
            )
        )
    }

    fun mapPlaybackSource(
        info: StreamInfo,
        videoId: String,
        externalHighRes: ExternalHighResStreamDescriptor? = null
    ): Result<VideoPlaybackSource> {
        val metadata = map(info, videoId).getOrElse { return Result.failure(it) }
        val candidates = playbackCandidates(info, externalHighRes)
        val candidate = candidates.firstOrNull()
            ?: return Result.failure(IllegalStateException("Extractor returned no playable stream URL"))
        val progressiveFallback = candidates
            .firstOrNull { it.deliveryMethod == VideoDeliveryMethod.PROGRESSIVE_HTTP && it.url != candidate.url }
            ?.url

        val validDashUrl = info.dashMpdUrl?.trim()?.takeIf {
            it.isNotEmpty() && (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
        } ?: candidate.url.takeIf { candidate.deliveryMethod == VideoDeliveryMethod.DASH }
        val validHlsUrl = info.hlsUrl?.trim()?.takeIf {
            it.isNotEmpty() && (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
        } ?: candidates.firstOrNull { it.deliveryMethod == VideoDeliveryMethod.HLS }?.url

        val videoOnly = info.getVideoOnlyStreams().orEmpty().mapNotNull { it.toPlaybackStream() }.toMutableList()
        if (externalHighRes != null && isExternalDescriptorValid(externalHighRes)) {
            val v = externalHighRes.video
            videoOnly.add(
                0,
                VideoPlaybackStream(
                    url = v.streamProxyUrl,
                    kind = VideoStreamKind.VIDEO_ONLY,
                    deliveryMethod = VideoDeliveryMethod.DASH,
                    container = "mp4",
                    mimeType = v.mimeType,
                    formatId = v.itag,
                    resolution = "${v.width}x${v.height}",
                    width = v.width,
                    height = v.height,
                    frameRate = v.fps,
                    videoCodec = v.codec,
                    bitrateKbps = (v.bitrate / 1000L).toInt()
                )
            )
        }
        val sortedVideoOnly = videoOnly.sortedWith(
            compareByDescending<VideoPlaybackStream> { it.height ?: 0 }
                .thenByDescending { it.bitrateKbps ?: 0 }
                .thenByDescending { it.frameRate ?: 0 }
        )
        val audioOnly = info.getAudioStreams().orEmpty().mapNotNull { it.toPlaybackStream() }
            .sortedWith(
                compareByDescending<VideoPlaybackStream> { it.bitrateKbps ?: 0 }
            )
        val progressive = info.getVideoStreams().orEmpty().mapNotNull { it.toPlaybackStream() }
            .sortedWith(
                compareByDescending<VideoPlaybackStream> { it.height ?: 0 }
                    .thenByDescending { it.bitrateKbps ?: 0 }
            )

        return Result.success(
            VideoPlaybackSource(
                videoId = videoId,
                streamUrl = candidate.url,
                kind = candidate.kind,
                deliveryMethod = candidate.deliveryMethod,
                progressiveFallbackUrl = progressiveFallback,
                durationMs = metadata.durationMs,
                title = metadata.title,
                uploader = metadata.uploader,
                viewCount = metadata.viewCount,
                dashUrl = validDashUrl,
                hlsUrl = validHlsUrl,
                videoOnlyStreams = sortedVideoOnly,
                audioOnlyStreams = audioOnly,
                isLive = metadata.isLive,
                isUpcoming = metadata.isUpcoming,
                progressiveStreams = progressive
            )
        )
    }

    private fun VideoStream.toDescriptor(): VideoStreamDescriptor? {
        if (!isUrl() || getContent().isNullOrBlank()) return null
        return VideoStreamDescriptor(
            kind = if (isVideoOnly()) VideoStreamKind.VIDEO_ONLY else VideoStreamKind.COMBINED,
            deliveryMethod = getDeliveryMethod().toSonaraDeliveryMethod(),
            container = getFormat()?.getSuffix(),
            mimeType = getFormat()?.getMimeType(),
            formatId = getItag().takeIf { it >= 0 } ?: getFormatId().takeIf { it >= 0 },
            resolution = getResolution().takeIf(String::isNotBlank),
            width = width.takeIf { it > 0 },
            height = height.takeIf { it > 0 },
            frameRate = fps.takeIf { it > 0 },
            videoCodec = getCodec()?.takeIf(String::isNotBlank),
            bitrateKbps = getBitrate().toKbps()
        )
    }

    private fun AudioStream.toDescriptor(): VideoStreamDescriptor? {
        if (!isUrl() || getContent().isNullOrBlank()) return null
        return VideoStreamDescriptor(
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = getDeliveryMethod().toSonaraDeliveryMethod(),
            container = getFormat()?.getSuffix(),
            mimeType = getFormat()?.getMimeType(),
            formatId = getItag().takeIf { it >= 0 } ?: getFormatId().takeIf { it >= 0 },
            audioCodec = getCodec()?.takeIf(String::isNotBlank),
            bitrateKbps = (getAverageBitrate().takeIf { it > 0 } ?: getBitrate()).toKbps(),
            audioTrackId = getAudioTrackId()?.takeIf(String::isNotBlank),
            audioTrackName = getAudioTrackName()?.takeIf(String::isNotBlank),
            audioLocale = getAudioLocale()?.language?.takeIf(String::isNotBlank),
            audioTrackType = getAudioTrackType()?.name
        )
    }

    private fun VideoStream.toPlaybackStream(): VideoPlaybackStream? {
        val url = getContent()?.trim()?.takeIf {
            isUrl() && it.isNotBlank() && (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
        } ?: return null
        return VideoPlaybackStream(
            url = url,
            kind = if (isVideoOnly()) VideoStreamKind.VIDEO_ONLY else VideoStreamKind.COMBINED,
            deliveryMethod = getDeliveryMethod().toSonaraDeliveryMethod(),
            container = getFormat()?.getSuffix(),
            mimeType = getFormat()?.getMimeType(),
            formatId = getItag().takeIf { it >= 0 } ?: getFormatId().takeIf { it >= 0 },
            resolution = getResolution().takeIf(String::isNotBlank),
            width = width.takeIf { it > 0 },
            height = height.takeIf { it > 0 },
            frameRate = fps.takeIf { it > 0 },
            videoCodec = getCodec()?.takeIf(String::isNotBlank),
            bitrateKbps = getBitrate().toKbps()
        )
    }

    private fun AudioStream.toPlaybackStream(): VideoPlaybackStream? {
        val url = getContent()?.trim()?.takeIf {
            isUrl() && it.isNotBlank() && (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
        } ?: return null
        return VideoPlaybackStream(
            url = url,
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = getDeliveryMethod().toSonaraDeliveryMethod(),
            container = getFormat()?.getSuffix(),
            mimeType = getFormat()?.getMimeType(),
            formatId = getItag().takeIf { it >= 0 } ?: getFormatId().takeIf { it >= 0 },
            audioCodec = getCodec()?.takeIf(String::isNotBlank),
            bitrateKbps = (getAverageBitrate().takeIf { it > 0 } ?: getBitrate()).toKbps(),
            audioTrackId = getAudioTrackId()?.takeIf(String::isNotBlank),
            audioTrackName = getAudioTrackName()?.takeIf(String::isNotBlank),
            audioLocale = getAudioLocale()?.language?.takeIf(String::isNotBlank),
            audioTrackType = getAudioTrackType()?.name
        )
    }

    private data class PlaybackCandidate(
        val url: String,
        val kind: VideoStreamKind,
        val deliveryMethod: VideoDeliveryMethod
    )

    private fun playbackCandidates(
        info: StreamInfo,
        externalHighRes: ExternalHighResStreamDescriptor? = null
    ): List<PlaybackCandidate> {
        val candidates = mutableListOf<PlaybackCandidate>()

        val dashUrl = info.dashMpdUrl?.trim()
        if (!dashUrl.isNullOrBlank() && (dashUrl.startsWith("http://", ignoreCase = true) || dashUrl.startsWith("https://", ignoreCase = true))) {
            candidates.add(
                PlaybackCandidate(
                    url = dashUrl,
                    kind = VideoStreamKind.COMBINED,
                    deliveryMethod = VideoDeliveryMethod.DASH
                )
            )
        }

        val generatedDash = generateDynamicDashMpd(
            videoStreams = info.getVideoOnlyStreams().orEmpty(),
            audioStreams = info.getAudioStreams().orEmpty(),
            durationSeconds = info.duration.coerceAtLeast(0L),
            externalHighRes = externalHighRes
        )
        if (generatedDash != null && candidates.none { it.url == generatedDash }) {
            candidates.add(
                PlaybackCandidate(
                    url = generatedDash,
                    kind = VideoStreamKind.COMBINED,
                    deliveryMethod = VideoDeliveryMethod.DASH
                )
            )
        }

        val hlsUrl = info.hlsUrl?.trim()
        if (!hlsUrl.isNullOrBlank() && (hlsUrl.startsWith("http://", ignoreCase = true) || hlsUrl.startsWith("https://", ignoreCase = true))) {
            candidates.add(
                PlaybackCandidate(
                    url = hlsUrl,
                    kind = VideoStreamKind.COMBINED,
                    deliveryMethod = VideoDeliveryMethod.HLS
                )
            )
        }

        val combined = info.getVideoStreams().orEmpty()
            .filter { it.isUrl() && !it.getContent().isNullOrBlank() }
            .sortedWith(
                compareByDescending<VideoStream> { it.height }
                    .thenByDescending { it.bitrate }
            )
            .mapNotNull { it.toPlaybackCandidate(VideoStreamKind.COMBINED) }

        candidates.addAll(combined)

        return candidates
    }

    private fun VideoStream.hasValidDashRanges(): Boolean =
        getInitStart() >= 0 && getInitEnd() > getInitStart() && getIndexStart() >= 0 && getIndexEnd() > getIndexStart() &&
            isUrl() && !getContent().isNullOrBlank() && height > 0 && getBitrate() > 0

    private fun AudioStream.hasValidDashRanges(): Boolean =
        getInitStart() >= 0 && getInitEnd() > getInitStart() && getIndexStart() >= 0 && getIndexEnd() > getIndexStart() &&
            isUrl() && !getContent().isNullOrBlank() && (getAverageBitrate() > 0 || getBitrate() > 0)

    private fun xmlEscape(str: String): String = str
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    internal fun isExternalDescriptorValid(
        descriptor: ExternalHighResStreamDescriptor?,
        nowSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        if (descriptor == null) return false
        if (descriptor.sessionId.isBlank()) return false
        if (descriptor.expiresAt <= nowSeconds) return false

        val v = descriptor.video
        if (v.width != 7680 || v.height != 4320) return false
        if (v.fps !in 1..240) return false
        if (v.bitrate <= 0L || v.bitrate > 1_000_000_000L) return false
        if (v.duration <= 0.0 || !v.duration.isFinite()) return false
        if (v.codec.isBlank()) return false
        if (v.mimeType.isBlank() || !v.mimeType.startsWith("video/")) return false
        if (v.streamProxyUrl.isBlank() || !v.streamProxyUrl.contains("sessionId=${descriptor.sessionId}")) return false
        if (v.initRange.start < 0L || v.initRange.end <= v.initRange.start) return false
        if (v.indexRange.start <= v.initRange.end || v.indexRange.end <= v.indexRange.start) return false
        if (v.initRange.size > HighResByteRange.MAX_REASONABLE_INIT_SIZE) return false

        val a = descriptor.audio
        if (a.bitrate <= 0L || a.bitrate > 10_000_000L) return false
        if (a.duration <= 0.0 || !a.duration.isFinite()) return false
        if (a.codec.isBlank()) return false
        if (a.mimeType.isBlank() || !a.mimeType.startsWith("audio/")) return false
        if (a.streamProxyUrl.isBlank() || !a.streamProxyUrl.contains("sessionId=${descriptor.sessionId}")) return false
        if (a.initRange.start < 0L || a.initRange.end <= a.initRange.start) return false
        if (a.indexRange.start <= a.initRange.end || a.indexRange.end <= a.indexRange.start) return false
        if (a.initRange.size > HighResByteRange.MAX_REASONABLE_INIT_SIZE) return false

        // Upstream direct URLs (googlevideo.com / youtube.com) are strictly forbidden
        if (v.streamProxyUrl.contains("googlevideo.com") || v.streamProxyUrl.contains("youtube.com")) return false
        if (a.streamProxyUrl.contains("googlevideo.com") || a.streamProxyUrl.contains("youtube.com")) return false

        // Duration drift between audio and video must not exceed tolerance
        val maxDrift = maxOf(15.0, 0.15 * v.duration)
        if (kotlin.math.abs(v.duration - a.duration) > maxDrift) return false

        return true
    }

    internal fun cleanMimeType(rawMime: String): String =
        rawMime.substringBefore(';').trim().lowercase()

    private fun appendExternalVideoRepresentation(
        sb: StringBuilder,
        v: HighResVideoRepresentation,
        existingRepresentationIds: Set<String> = emptySet(),
        forceExternalId: Boolean = false
    ) {
        val baseItag = v.itag.takeIf { it > 0 } ?: 571
        val baseItagStr = baseItag.toString()
        val hasCollision = forceExternalId || existingRepresentationIds.contains(baseItagStr)
        val repId = if (hasCollision) "${baseItag}_ext" else baseItagStr

        val cleanMime = cleanMimeType(v.mimeType)
        val mimeAttr = if (cleanMime.isNotBlank()) "mimeType=\"${xmlEscape(cleanMime)}\" " else ""
        val codecAttr = if (v.codec.isNotBlank()) "codecs=\"${xmlEscape(v.codec)}\" " else ""
        val fpsAttr = if (v.fps > 0) "frameRate=\"${v.fps}\" " else ""
        sb.append("      <Representation id=\"$repId\" bandwidth=\"${v.bitrate}\" ${mimeAttr}${codecAttr}width=\"${v.width}\" height=\"${v.height}\" ${fpsAttr}>\n")
        sb.append("        <BaseURL>${xmlEscape(v.streamProxyUrl.trim())}</BaseURL>\n")
        sb.append("        <SegmentBase indexRange=\"${v.indexRange.start}-${v.indexRange.end}\">\n")
        sb.append("          <Initialization range=\"${v.initRange.start}-${v.initRange.end}\"/>\n")
        sb.append("        </SegmentBase>\n")
        sb.append("      </Representation>\n")
    }

    private fun appendExternalAudioRepresentation(
        sb: StringBuilder,
        a: HighResAudioRepresentation,
        existingStreams: List<AudioStream> = emptyList(),
        existingRepresentationIds: Set<String> = emptySet(),
        forceExternalId: Boolean = false
    ) {
        val baseItag = a.itag.takeIf { it > 0 } ?: 140
        val baseItagStr = baseItag.toString()
        val hasCollision = forceExternalId ||
            existingRepresentationIds.contains(baseItagStr) ||
            existingStreams.any { (it.getItag().takeIf { id -> id > 0 } ?: it.getFormatId()) == baseItag }
        val itagStr = if (hasCollision) {
            var candidate = "${baseItag}_ext"
            var counter = 2
            while (existingRepresentationIds.contains(candidate)) {
                candidate = "${baseItag}_ext$counter"
                counter++
            }
            candidate
        } else {
            baseItagStr
        }
        val cleanMime = cleanMimeType(a.mimeType)
        val mimeAttr = if (cleanMime.isNotBlank()) "mimeType=\"${xmlEscape(cleanMime)}\" " else ""
        val codecAttr = if (a.codec.isNotBlank()) "codecs=\"${xmlEscape(a.codec)}\" " else ""
        sb.append("      <Representation id=\"$itagStr\" bandwidth=\"${a.bitrate}\" ${mimeAttr}$codecAttr>\n")
        sb.append("        <AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\"/>\n")
        sb.append("        <BaseURL>${xmlEscape(a.streamProxyUrl.trim())}</BaseURL>\n")
        sb.append("        <SegmentBase indexRange=\"${a.indexRange.start}-${a.indexRange.end}\">\n")
        sb.append("          <Initialization range=\"${a.initRange.start}-${a.initRange.end}\"/>\n")
        sb.append("        </SegmentBase>\n")
        sb.append("      </Representation>\n")
    }

    internal fun generateDynamicDashMpd(
        videoStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        durationSeconds: Long,
        externalHighRes: ExternalHighResStreamDescriptor? = null
    ): String? {
        val eligibleVideos = videoStreams.filter { it.hasValidDashRanges() }
        val eligibleAudios = audioStreams.filter { it.hasValidDashRanges() }
        val validExternal = if (isExternalDescriptorValid(externalHighRes)) externalHighRes else null

        if (eligibleVideos.isEmpty() || eligibleAudios.isEmpty()) return null

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
        sb.append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" ")
        val effectiveDuration = if (durationSeconds > 0) {
            durationSeconds
        } else if (validExternal != null && validExternal.video.duration > 0.0) {
            validExternal.video.duration.toLong()
        } else {
            0L
        }
        if (effectiveDuration > 0) {
            sb.append("mediaPresentationDuration=\"PT${effectiveDuration}S\" ")
        }
        sb.append("minBufferTime=\"PT1.5S\">\n")
        sb.append("  <Period>\n")

        var adaptationId = 0

        val videosByMime = eligibleVideos.groupBy {
            it.getFormat()?.mimeType?.takeIf(String::isNotBlank)
                ?: if (it.getFormat()?.suffix == "webm") "video/webm" else "video/mp4"
        }
        var externalVideoAppended = false

        for ((mimeType, streams) in videosByMime) {
            sb.append("    <AdaptationSet id=\"${adaptationId++}\" contentType=\"video\" mimeType=\"${xmlEscape(mimeType)}\" subsegmentAlignment=\"true\">\n")
            sb.append("      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"main\"/>\n")
            for (v in streams) {
                val itag = v.getItag().takeIf { it > 0 } ?: v.getFormatId().takeIf { it > 0 } ?: (1000 + adaptationId)
                val codecAttr = v.getCodec()?.takeIf(String::isNotBlank)?.let { "codecs=\"${xmlEscape(it)}\" " }.orEmpty()
                val fpsAttr = if (v.fps > 0) "frameRate=\"${v.fps}\" " else ""
                sb.append("      <Representation id=\"$itag\" bandwidth=\"${v.getBitrate()}\" ${codecAttr}width=\"${v.width}\" height=\"${v.height}\" ${fpsAttr}>\n")
                sb.append("        <BaseURL>${xmlEscape(v.getContent().orEmpty().trim())}</BaseURL>\n")
                sb.append("        <SegmentBase indexRange=\"${v.getIndexStart()}-${v.getIndexEnd()}\">\n")
                sb.append("          <Initialization range=\"${v.getInitStart()}-${v.getInitEnd()}\"/>\n")
                sb.append("        </SegmentBase>\n")
                sb.append("      </Representation>\n")
            }
            if (validExternal != null && !externalVideoAppended && cleanMimeType(mimeType).equals(cleanMimeType(validExternal.video.mimeType), ignoreCase = true)) {
                appendExternalVideoRepresentation(sb, validExternal.video)
                externalVideoAppended = true
            }
            sb.append("    </AdaptationSet>\n")
        }

        if (validExternal != null && !externalVideoAppended) {
            val cleanMime = cleanMimeType(validExternal.video.mimeType)
            sb.append("    <AdaptationSet id=\"${adaptationId++}\" contentType=\"video\" mimeType=\"${xmlEscape(cleanMime)}\" subsegmentAlignment=\"true\">\n")
            sb.append("      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"main\"/>\n")
            appendExternalVideoRepresentation(sb, validExternal.video)
            sb.append("    </AdaptationSet>\n")
            externalVideoAppended = true
        }

        val usedAudioRepIds = mutableSetOf<String>()

        fun AudioStream.trackIdentifier(): String {
            val trackId = audioTrackId?.trim()?.takeIf(String::isNotBlank)
            if (trackId != null) return trackId
            val localeLang = audioLocale?.language?.trim()?.takeIf(String::isNotBlank)
            if (localeLang != null) return localeLang
            val trackName = audioTrackName?.trim()?.takeIf(String::isNotBlank)
            if (trackName != null) return trackName
            return "default"
        }

        val audiosByMime = eligibleAudios.groupBy {
            val rawMime = it.getFormat()?.mimeType?.takeIf(String::isNotBlank)
            when {
                rawMime?.startsWith("audio/") == true -> cleanMimeType(rawMime)
                rawMime?.equals("video/webm", ignoreCase = true) == true ||
                    it.getFormat()?.suffix == "webm" ||
                    it.getFormat() == MediaFormat.WEBMA ||
                    it.getFormat() == MediaFormat.WEBM -> "audio/webm"
                else -> "audio/mp4"
            }
        }
        var externalAudioAppended = false

        for ((mimeType, mimeStreams) in audiosByMime) {
            val tracksByIdentifier = mimeStreams.groupBy { it.trackIdentifier() }
            val isMultilingual = tracksByIdentifier.size > 1
            val hasExplicitOriginal = mimeStreams.any { it.audioTrackType == AudioTrackType.ORIGINAL }

            // Sort so that the ORIGINAL audio track is emitted FIRST
            val sortedTracks = tracksByIdentifier.entries.sortedWith(
                compareByDescending<Map.Entry<String, List<AudioStream>>> { entry ->
                    entry.value.any { it.audioTrackType == AudioTrackType.ORIGINAL }
                }.thenBy { entry ->
                    entry.key
                }
            )

            for ((trackIndex, trackEntry) in sortedTracks.withIndex()) {
                val streams = trackEntry.value
                val isOriginal = streams.any { it.audioTrackType == AudioTrackType.ORIGINAL } ||
                    (!hasExplicitOriginal && trackIndex == 0)

                val roleValue = if (isOriginal) "main" else "alternate"

                val lang = streams.firstNotNullOfOrNull { it.audioLocale?.language?.trim()?.takeIf(String::isNotBlank) }
                    ?: streams.firstNotNullOfOrNull { it.audioLocale?.toLanguageTag()?.trim()?.takeIf(String::isNotBlank) }
                val langAttr = if (!lang.isNullOrBlank()) "lang=\"${xmlEscape(lang)}\" " else ""

                sb.append("    <AdaptationSet id=\"${adaptationId++}\" contentType=\"audio\" mimeType=\"${xmlEscape(mimeType)}\" ${langAttr}subsegmentAlignment=\"true\">\n")
                sb.append("      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"$roleValue\"/>\n")

                val trackName = streams.firstNotNullOfOrNull { it.audioTrackName?.trim()?.takeIf(String::isNotBlank) }
                val label = when {
                    !trackName.isNullOrBlank() && isOriginal && isMultilingual -> "$trackName (original)"
                    !trackName.isNullOrBlank() -> trackName
                    isOriginal && isMultilingual -> "Original"
                    else -> null
                }
                if (label != null) {
                    sb.append("      <Label>${xmlEscape(label)}</Label>\n")
                }

                for (a in streams) {
                    val itag = a.getItag().takeIf { it > 0 } ?: a.getFormatId().takeIf { it > 0 } ?: (2000 + adaptationId)
                    val rate = a.getBitrate().takeIf { it > 0 }
                        ?: a.getAverageBitrate().takeIf { it > 0 }?.let { it * 1000 }
                        ?: a.getAverageBitrate().takeIf { it > 0 }
                        ?: 128000

                    val rawId = if (isMultilingual) {
                        val cleanSuffix = lang ?: trackEntry.key.filter { it.isLetterOrDigit() }.takeIf(String::isNotBlank) ?: "trk"
                        "${itag}_${cleanSuffix}"
                    } else {
                        itag.toString()
                    }

                    var uniqueId = rawId
                    var collisionCounter = 2
                    while (usedAudioRepIds.contains(uniqueId)) {
                        uniqueId = "${rawId}_$collisionCounter"
                        collisionCounter++
                    }
                    usedAudioRepIds.add(uniqueId)

                    val codecAttr = a.getCodec()?.takeIf(String::isNotBlank)?.let { "codecs=\"${xmlEscape(it)}\" " }.orEmpty()
                    sb.append("      <Representation id=\"$uniqueId\" bandwidth=\"$rate\" $codecAttr>\n")
                    sb.append("        <AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\"/>\n")
                    sb.append("        <BaseURL>${xmlEscape(a.getContent().orEmpty().trim())}</BaseURL>\n")
                    sb.append("        <SegmentBase indexRange=\"${a.getIndexStart()}-${a.getIndexEnd()}\">\n")
                    sb.append("          <Initialization range=\"${a.getInitStart()}-${a.getInitEnd()}\"/>\n")
                    sb.append("        </SegmentBase>\n")
                    sb.append("      </Representation>\n")
                }

                if (validExternal != null && !externalAudioAppended && cleanMimeType(mimeType).equals(cleanMimeType(validExternal.audio.mimeType), ignoreCase = true) && isOriginal) {
                    appendExternalAudioRepresentation(sb, validExternal.audio, streams, usedAudioRepIds)
                    externalAudioAppended = true
                }
                sb.append("    </AdaptationSet>\n")
            }
        }

        if (validExternal != null && !externalAudioAppended) {
            val cleanMime = cleanMimeType(validExternal.audio.mimeType)
            sb.append("    <AdaptationSet id=\"${adaptationId++}\" contentType=\"audio\" mimeType=\"${xmlEscape(cleanMime)}\" subsegmentAlignment=\"true\">\n")
            sb.append("      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"main\"/>\n")
            appendExternalAudioRepresentation(sb, validExternal.audio, emptyList(), usedAudioRepIds)
            sb.append("    </AdaptationSet>\n")
            externalAudioAppended = true
        }

        sb.append("  </Period>\n")
        sb.append("</MPD>\n")

        val xmlString = sb.toString()
        val base64 = java.util.Base64.getEncoder().encodeToString(xmlString.toByteArray(Charsets.UTF_8))
        return "data:application/dash+xml;charset=utf-8;base64,$base64"
    }

    /**
     * Injects the external 8K representation into an existing Base64-encoded DASH MPD data URI.
     * Preserves all original representations, inserts the external video representation before </AdaptationSet>,
     * and inserts the external audio representation before the audio </AdaptationSet>.
     * Returns null if the descriptor is invalid, expired, or the manifest cannot be updated cleanly.
     */
    internal fun injectExternalIntoDashMpd(
        dashMpdDataUri: String,
        externalHighRes: ExternalHighResStreamDescriptor?
    ): String? {
        if (!isExternalDescriptorValid(externalHighRes)) return null
        val descriptor = externalHighRes!!

        val prefix = "data:application/dash+xml;charset=utf-8;base64,"
        if (!dashMpdDataUri.startsWith(prefix, ignoreCase = true)) return null

        val base64Content = dashMpdDataUri.substring(prefix.length)
        val xmlString = try {
            String(java.util.Base64.getDecoder().decode(base64Content), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }

        val existingRepIds = Regex("""<Representation\b[^>]*\bid="([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(xmlString)
            .map { it.groupValues[1] }
            .toSet()

        val videoItagStr = descriptor.video.itag.toString()
        if (existingRepIds.contains(videoItagStr) || xmlString.contains("id=\"$videoItagStr\"")) {
            return dashMpdDataUri // Already contains representation (idempotent)
        }

        val cleanVideoMime = cleanMimeType(descriptor.video.mimeType)
        val cleanAudioMime = cleanMimeType(descriptor.audio.mimeType)

        val vSb = StringBuilder()
        appendExternalVideoRepresentation(
            sb = vSb,
            v = descriptor.video,
            existingRepresentationIds = existingRepIds
        )
        val videoRepXml = vSb.toString()

        val aSb = StringBuilder()
        appendExternalAudioRepresentation(
            sb = aSb,
            a = descriptor.audio,
            existingRepresentationIds = existingRepIds,
            forceExternalId = true // External audio is always disambiguated (e.g. 140_ext)
        )
        val audioRepXml = aSb.toString()

        // 1. Inject Video representation into compatible video AdaptationSet (matching container MIME)
        val videoAdaptationPattern = Regex(
            """<AdaptationSet\b(?=[^>]*\bcontentType="video")(?=[^>]*\bmimeType="${Regex.escape(cleanVideoMime)}")[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val videoMatch = videoAdaptationPattern.find(xmlString)

        val withVideo = if (videoMatch != null) {
            val videoAdaptationIndex = videoMatch.range.first
            val videoClosingIndex = xmlString.indexOf("</AdaptationSet>", videoAdaptationIndex)
            if (videoClosingIndex == -1) return null
            xmlString.substring(0, videoClosingIndex) + videoRepXml + xmlString.substring(videoClosingIndex)
        } else {
            // Dedicated AdaptationSet for external video when no compatible container exists (e.g. video/mp4 with only video/webm present)
            val periodClosingIndex = xmlString.indexOf("</Period>")
            if (periodClosingIndex == -1) return null
            val nextAdaptationId = (Regex("""<AdaptationSet\b[^>]*\bid="(\d+)"""", RegexOption.IGNORE_CASE)
                .findAll(xmlString)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull() ?: 10) + 1
            val newVideoSet = "    <AdaptationSet id=\"$nextAdaptationId\" contentType=\"video\" mimeType=\"${xmlEscape(cleanVideoMime)}\" subsegmentAlignment=\"true\">\n" +
                "      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"main\"/>\n" +
                videoRepXml +
                "    </AdaptationSet>\n"
            xmlString.substring(0, periodClosingIndex) + newVideoSet + xmlString.substring(periodClosingIndex)
        }

        // 2. Inject Audio representation into compatible audio AdaptationSet (matching container MIME)
        val audioAdaptationPattern = Regex(
            """<AdaptationSet\b(?=[^>]*\bcontentType="audio")(?=[^>]*\bmimeType="${Regex.escape(cleanAudioMime)}")[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val audioMatch = audioAdaptationPattern.find(withVideo)

        val finalXml = if (audioMatch != null) {
            val audioAdaptationIndex = audioMatch.range.first
            val audioClosingIndex = withVideo.indexOf("</AdaptationSet>", audioAdaptationIndex)
            if (audioClosingIndex == -1) return null
            withVideo.substring(0, audioClosingIndex) + audioRepXml + withVideo.substring(audioClosingIndex)
        } else {
            // Dedicated AdaptationSet for external audio when no compatible container exists (e.g. audio/mp4 with only audio/webm present)
            val periodClosingIndex = withVideo.indexOf("</Period>")
            if (periodClosingIndex == -1) return null
            val nextAdaptationId = (Regex("""<AdaptationSet\b[^>]*\bid="(\d+)"""", RegexOption.IGNORE_CASE)
                .findAll(withVideo)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull() ?: 20) + 1
            val newAudioSet = "    <AdaptationSet id=\"$nextAdaptationId\" contentType=\"audio\" mimeType=\"${xmlEscape(cleanAudioMime)}\" subsegmentAlignment=\"true\">\n" +
                "      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"main\"/>\n" +
                audioRepXml +
                "    </AdaptationSet>\n"
            withVideo.substring(0, periodClosingIndex) + newAudioSet + withVideo.substring(periodClosingIndex)
        }

        val newBase64 = java.util.Base64.getEncoder().encodeToString(finalXml.toByteArray(Charsets.UTF_8))
        return "$prefix$newBase64"
    }

    private fun VideoStream.toPlaybackCandidate(kind: VideoStreamKind): PlaybackCandidate? =
        getContent()?.trim()?.takeIf {
            isUrl() && it.isNotBlank() && (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
        }?.let { url ->
            PlaybackCandidate(url, kind, getDeliveryMethod().toSonaraDeliveryMethod())
        }

    internal fun isPubliclyAvailable(info: StreamInfo): Boolean {
        // 1. Explicit provider-level paywall, membership, or upcoming premiere restrictions
        when (info.getContentAvailability()) {
            ContentAvailability.MEMBERSHIP,
            ContentAvailability.PAID,
            ContentAvailability.UPCOMING -> return false
            ContentAvailability.AVAILABLE -> Unit
            ContentAvailability.UNKNOWN -> Unit
        }

        // 2. Privacy check: reject private or internal streams
        if (info.getPrivacy() == org.schabi.newpipe.extractor.stream.StreamExtractor.Privacy.PRIVATE ||
            info.getPrivacy() == org.schabi.newpipe.extractor.stream.StreamExtractor.Privacy.INTERNAL
        ) {
            return false
        }

        // 3. Age-restricted content requires authentication and is not publicly embeddable/playable
        if (info.getAgeLimit() > 0) return false

        // 4. Require at least one stream to exist; an UNKNOWN state without streams fails closed
        val hasStreams = !info.getVideoStreams().isNullOrEmpty() ||
            !info.getVideoOnlyStreams().isNullOrEmpty() ||
            !info.getAudioStreams().isNullOrEmpty() ||
            !info.dashMpdUrl.isNullOrBlank() ||
            !info.hlsUrl.isNullOrBlank()
        if (!hasStreams) return false

        return true
    }

    private fun DeliveryMethod.toSonaraDeliveryMethod(): VideoDeliveryMethod = when (this) {
        DeliveryMethod.PROGRESSIVE_HTTP -> VideoDeliveryMethod.PROGRESSIVE_HTTP
        DeliveryMethod.DASH -> VideoDeliveryMethod.DASH
        DeliveryMethod.HLS -> VideoDeliveryMethod.HLS
        else -> VideoDeliveryMethod.OTHER
    }

    private fun Int.toKbps(): Int? = takeIf { it > 0 }?.let { (it / 1_000).coerceAtLeast(1) }
}
