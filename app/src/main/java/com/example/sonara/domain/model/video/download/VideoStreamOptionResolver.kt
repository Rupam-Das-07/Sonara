package com.example.sonara.domain.model.video.download

import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoPlaybackStream
import com.example.sonara.domain.model.video.VideoQuality
import com.example.sonara.domain.model.video.VideoStreamKind
import com.example.sonara.playback.client.VideoDecoderCapabilityChecker

/**
 * Resolves structured downloadable options from extracted video streams and determines
 * playback-quality-based preselection according to Sonara's download contract.
 */
object VideoStreamOptionResolver {

    private const val MAX_SUPPORTED_DOWNLOAD_HEIGHT = 1440

    /**
     * Resolves all compatible and distinct downloadable options from a [VideoPlaybackSource].
     *
     * Rules:
     * 1. Scope capped up to 1440p (2K). 2160p (4K) is explicitly excluded from this release.
     * 2. 30 FPS and 60 FPS are distinguished as separate options.
     * 3. VP9 video is paired with Opus audio in a WebM container (zero transcoding).
     * 4. AVC / H.264 video is paired with AAC audio in an MP4 container.
     * 5. Progressive streams containing both video and audio require no merge.
     * 6. Options that cannot be downloaded or remuxed safely are excluded or marked unsupported.
     */
    fun resolveDownloadOptions(
        source: VideoPlaybackSource,
        durationMs: Long? = source.durationMs,
        capabilityChecker: VideoDecoderCapabilityChecker? = null
    ): List<VideoDownloadOption> {
        val candidates = mutableListOf<VideoDownloadOption>()

        // 1. Process progressive streams (combined video + audio, zero remux needed)
        source.progressiveStreams.forEach { stream ->
            val height = stream.height ?: return@forEach
            if (height > MAX_SUPPORTED_DOWNLOAD_HEIGHT) return@forEach
            val width = stream.width ?: ((height * 16) / 9)
            val actualFps = stream.frameRate
            val fpsRaw = actualFps ?: 30
            val normalizedFps = if (fpsRaw >= 45) 60 else 30
            val quality = VideoQuality.of(height, normalizedFps)
            val bitrateKbps = stream.bitrateKbps
            val estBytes = estimateBytes(bitrateKbps, durationMs)

            candidates.add(
                VideoDownloadOption(
                    quality = quality,
                    width = width,
                    height = height,
                    frameRate = normalizedFps,
                    videoCodec = stream.videoCodec ?: "avc1",
                    audioCodec = stream.audioCodec ?: "mp4a",
                    container = VideoDownloadContainer.MP4,
                    streamType = VideoDownloadStreamType.PROGRESSIVE,
                    videoStreamUrl = stream.url,
                    audioStreamUrl = null,
                    videoItag = stream.formatId,
                    audioItag = null,
                    estimatedVideoSizeBytes = estBytes,
                    estimatedAudioSizeBytes = 0L,
                    estimatedTotalSizeBytes = estBytes,
                    videoBitrateKbps = bitrateKbps,
                    audioBitrateKbps = null,
                    isMergeRequired = false,
                    compatibility = VideoDownloadOptionCompatibility.COMPATIBLE,
                    compatibilityReason = null,
                    actualFps = actualFps,
                    audioLocale = stream.audioLocale,
                    audioTrackId = stream.audioTrackId
                )
            )
        }

        // 2. Separate audio-only streams by codec family
        val opusAudios = source.audioOnlyStreams.filter { audio ->
            val codec = audio.audioCodec?.lowercase().orEmpty()
            val container = audio.container?.lowercase().orEmpty()
            codec.contains("opus") || container.contains("webm")
        }.sortedByDescending { it.bitrateKbps ?: 0 }

        val aacAudios = source.audioOnlyStreams.filter { audio ->
            val codec = audio.audioCodec?.lowercase().orEmpty()
            val container = audio.container?.lowercase().orEmpty()
            codec.contains("aac") || codec.contains("mp4a") || container.contains("m4a") || container.contains("mp4")
        }.sortedByDescending { it.bitrateKbps ?: 0 }

        // 3. Process adaptive video-only streams
        source.videoOnlyStreams.forEach { stream ->
            val height = stream.height ?: return@forEach
            if (height > MAX_SUPPORTED_DOWNLOAD_HEIGHT) return@forEach
            val width = stream.width ?: ((height * 16) / 9)
            val actualFps = stream.frameRate
            val fpsRaw = actualFps ?: 30
            val normalizedFps = if (fpsRaw >= 45) 60 else 30
            val quality = VideoQuality.of(height, normalizedFps)
            val rawCodec = stream.videoCodec?.lowercase().orEmpty()
            val containerStr = stream.container?.lowercase().orEmpty()
            val isVp9 = rawCodec.contains("vp9") || rawCodec.contains("vp09") || containerStr.contains("webm")
            val isAvc = rawCodec.contains("avc") || rawCodec.contains("h264") || (containerStr.contains("mp4") && !rawCodec.contains("av01"))
            val isAv1 = rawCodec.contains("av01") || rawCodec.contains("av1")

            when {
                isVp9 -> {
                    val matchingOpus = opusAudios.firstOrNull()
                    if (matchingOpus != null) {
                        val videoBytes = estimateBytes(stream.bitrateKbps, durationMs)
                        val audioBytes = estimateBytes(matchingOpus.bitrateKbps, durationMs)
                        val totalBytes = combineBytes(videoBytes, audioBytes)

                        candidates.add(
                            VideoDownloadOption(
                                quality = quality,
                                width = width,
                                height = height,
                                frameRate = normalizedFps,
                                videoCodec = "VP9",
                                audioCodec = "Opus",
                                container = VideoDownloadContainer.WEBM,
                                streamType = VideoDownloadStreamType.ADAPTIVE,
                                videoStreamUrl = stream.url,
                                audioStreamUrl = matchingOpus.url,
                                videoItag = stream.formatId,
                                audioItag = matchingOpus.formatId,
                                estimatedVideoSizeBytes = videoBytes,
                                estimatedAudioSizeBytes = audioBytes,
                                estimatedTotalSizeBytes = totalBytes,
                                videoBitrateKbps = stream.bitrateKbps,
                                audioBitrateKbps = matchingOpus.bitrateKbps,
                                isMergeRequired = true,
                                compatibility = VideoDownloadOptionCompatibility.COMPATIBLE,
                                compatibilityReason = null,
                                actualFps = actualFps,
                                audioLocale = matchingOpus.audioLocale,
                                audioTrackId = matchingOpus.audioTrackId
                            )
                        )
                    } else {
                        candidates.add(
                            VideoDownloadOption(
                                quality = quality,
                                width = width,
                                height = height,
                                frameRate = normalizedFps,
                                videoCodec = "VP9",
                                audioCodec = null,
                                container = VideoDownloadContainer.WEBM,
                                streamType = VideoDownloadStreamType.ADAPTIVE,
                                videoStreamUrl = stream.url,
                                audioStreamUrl = null,
                                videoItag = stream.formatId,
                                audioItag = null,
                                estimatedVideoSizeBytes = estimateBytes(stream.bitrateKbps, durationMs),
                                estimatedAudioSizeBytes = null,
                                estimatedTotalSizeBytes = null,
                                videoBitrateKbps = stream.bitrateKbps,
                                audioBitrateKbps = null,
                                isMergeRequired = true,
                                compatibility = VideoDownloadOptionCompatibility.MISSING_AUDIO,
                                compatibilityReason = "No compatible Opus audio stream available for WebM remuxing",
                                actualFps = actualFps
                            )
                        )
                    }
                }
                isAvc -> {
                    val matchingAac = aacAudios.firstOrNull()
                    if (matchingAac != null) {
                        val videoBytes = estimateBytes(stream.bitrateKbps, durationMs)
                        val audioBytes = estimateBytes(matchingAac.bitrateKbps, durationMs)
                        val totalBytes = combineBytes(videoBytes, audioBytes)

                        candidates.add(
                            VideoDownloadOption(
                                quality = quality,
                                width = width,
                                height = height,
                                frameRate = normalizedFps,
                                videoCodec = "AVC/H.264",
                                audioCodec = "AAC",
                                container = VideoDownloadContainer.MP4,
                                streamType = VideoDownloadStreamType.ADAPTIVE,
                                videoStreamUrl = stream.url,
                                audioStreamUrl = matchingAac.url,
                                videoItag = stream.formatId,
                                audioItag = matchingAac.formatId,
                                estimatedVideoSizeBytes = videoBytes,
                                estimatedAudioSizeBytes = audioBytes,
                                estimatedTotalSizeBytes = totalBytes,
                                videoBitrateKbps = stream.bitrateKbps,
                                audioBitrateKbps = matchingAac.bitrateKbps,
                                isMergeRequired = true,
                                compatibility = VideoDownloadOptionCompatibility.COMPATIBLE,
                                compatibilityReason = null,
                                actualFps = actualFps,
                                audioLocale = matchingAac.audioLocale,
                                audioTrackId = matchingAac.audioTrackId
                            )
                        )
                    } else {
                        candidates.add(
                            VideoDownloadOption(
                                quality = quality,
                                width = width,
                                height = height,
                                frameRate = normalizedFps,
                                videoCodec = "AVC/H.264",
                                audioCodec = null,
                                container = VideoDownloadContainer.MP4,
                                streamType = VideoDownloadStreamType.ADAPTIVE,
                                videoStreamUrl = stream.url,
                                audioStreamUrl = null,
                                videoItag = stream.formatId,
                                audioItag = null,
                                estimatedVideoSizeBytes = estimateBytes(stream.bitrateKbps, durationMs),
                                estimatedAudioSizeBytes = null,
                                estimatedTotalSizeBytes = null,
                                videoBitrateKbps = stream.bitrateKbps,
                                audioBitrateKbps = null,
                                isMergeRequired = true,
                                compatibility = VideoDownloadOptionCompatibility.MISSING_AUDIO,
                                compatibilityReason = "No compatible AAC audio stream available for MP4 remuxing",
                                actualFps = actualFps
                            )
                        )
                    }
                }
                isAv1 -> {
                    // AV1 paired with Opus in MP4 is rejected by native Android MediaMuxer
                    candidates.add(
                        VideoDownloadOption(
                            quality = quality,
                            width = width,
                            height = height,
                            frameRate = normalizedFps,
                            videoCodec = "AV1",
                            audioCodec = null,
                            container = VideoDownloadContainer.MP4,
                            streamType = VideoDownloadStreamType.ADAPTIVE,
                            videoStreamUrl = stream.url,
                            audioStreamUrl = null,
                            videoItag = stream.formatId,
                            audioItag = null,
                            estimatedVideoSizeBytes = estimateBytes(stream.bitrateKbps, durationMs),
                            estimatedAudioSizeBytes = null,
                            estimatedTotalSizeBytes = null,
                            videoBitrateKbps = stream.bitrateKbps,
                            audioBitrateKbps = null,
                            isMergeRequired = true,
                            compatibility = VideoDownloadOptionCompatibility.UNSUPPORTED_CODEC,
                            compatibilityReason = "AV1 stream without compatible VP9 pairing is unsupported for native remuxing",
                            actualFps = actualFps
                        )
                    )
                }
                else -> {
                    // Unknown or unsupported codec
                    candidates.add(
                        VideoDownloadOption(
                            quality = quality,
                            width = width,
                            height = height,
                            frameRate = normalizedFps,
                            videoCodec = stream.videoCodec ?: "unknown",
                            audioCodec = null,
                            container = VideoDownloadContainer.WEBM,
                            streamType = VideoDownloadStreamType.ADAPTIVE,
                            videoStreamUrl = stream.url,
                            audioStreamUrl = null,
                            videoItag = stream.formatId,
                            audioItag = null,
                            estimatedVideoSizeBytes = null,
                            estimatedAudioSizeBytes = null,
                            estimatedTotalSizeBytes = null,
                            videoBitrateKbps = stream.bitrateKbps,
                            audioBitrateKbps = null,
                            isMergeRequired = true,
                            compatibility = VideoDownloadOptionCompatibility.UNSUPPORTED_CODEC,
                            compatibilityReason = "Unsupported video codec: ${stream.videoCodec}",
                            actualFps = actualFps
                        )
                    )
                }
            }
        }

        // 4. Deduplicate options by (height, frameRate)
        // For identical (height, frameRate), prioritize COMPATIBLE, then VP9/WebM at 1440p, then highest bitrate
        return candidates
            .groupBy { it.height to it.frameRate }
            .values
            .mapNotNull { group ->
                group.sortedWith(
                    compareByDescending<VideoDownloadOption> { it.compatibility == VideoDownloadOptionCompatibility.COMPATIBLE }
                        .thenByDescending { if (it.height == 1440) it.container == VideoDownloadContainer.WEBM else true }
                        .thenByDescending { it.videoBitrateKbps ?: 0 }
                ).firstOrNull()
            }
            .sortedWith(
                compareByDescending<VideoDownloadOption> { it.height }
                    .thenByDescending { it.frameRate }
            )
    }

    /**
     * Determines the initial download option to preselect based on point-in-time playback telemetry.
     */
    fun preselectOption(
        options: List<VideoDownloadOption>,
        snapshot: PlaybackQualitySnapshot?,
        defaultQuality: VideoQuality = VideoQuality.P720
    ): DownloadPreselectionResult {
        require(options.isNotEmpty()) { "Options list cannot be empty" }

        val compatible = options.filter { it.isSupported }
        val pool = if (compatible.isNotEmpty()) compatible else options

        // If telemetry is unavailable or comes from IFrame mode where resolution cannot be verified:
        if (snapshot == null || !snapshot.isActualTelemetry || snapshot.sourceRoute.equals("IFRAME", ignoreCase = true)) {
            val targetHeight = defaultQuality.requestedHeight ?: 720
            val best = pool.firstOrNull { it.height <= targetHeight } ?: pool.last()
            return DownloadPreselectionResult(
                selectedOption = best,
                reason = DownloadPreselectionReason.PLAYBACK_QUALITY_UNAVAILABLE,
                explanation = "Playback quality unavailable in IFrame mode; selected compatible ${formatOptLabel(best)}"
            )
        }

        val renderedHeight = snapshot.renderedHeight ?: (defaultQuality.requestedHeight ?: 720)
        val renderedFps = if ((snapshot.renderedFps ?: 0) >= 45) 60 else 30

        // 1. Exact match (height and FPS)
        val exactMatch = pool.firstOrNull { it.height == renderedHeight && it.frameRate == renderedFps }
        if (exactMatch != null) {
            return DownloadPreselectionResult(
                selectedOption = exactMatch,
                reason = DownloadPreselectionReason.EXACT_MATCH,
                explanation = "Matches current playback (${formatOptLabel(exactMatch)})"
            )
        }

        // 2. Same height, alternative FPS
        val sameHeightAltFps = pool.firstOrNull { it.height == renderedHeight }
        if (sameHeightAltFps != null) {
            return DownloadPreselectionResult(
                selectedOption = sameHeightAltFps,
                reason = DownloadPreselectionReason.FALLBACK_RESOLUTION_LOWER_FPS,
                explanation = "${renderedHeight}p · ${renderedFps} FPS unavailable for this video; fallback to ${formatOptLabel(sameHeightAltFps)}"
            )
        }

        // 3. Fallback resolution below requested, matching requested FPS
        val lowerSameFps = pool.filter { it.height < renderedHeight && it.frameRate == renderedFps }.maxByOrNull { it.height }
        if (lowerSameFps != null) {
            return DownloadPreselectionResult(
                selectedOption = lowerSameFps,
                reason = DownloadPreselectionReason.FALLBACK_RESOLUTION_SAME_FPS,
                explanation = "${renderedHeight}p unavailable for this video; fallback to ${formatOptLabel(lowerSameFps)}"
            )
        }

        // 4. Fallback resolution below requested, alternative FPS
        val lowerAnyFps = pool.filter { it.height < renderedHeight }.maxByOrNull { it.height }
        if (lowerAnyFps != null) {
            return DownloadPreselectionResult(
                selectedOption = lowerAnyFps,
                reason = DownloadPreselectionReason.FALLBACK_RESOLUTION_LOWER_FPS,
                explanation = "${renderedHeight}p unavailable for this video; fallback to ${formatOptLabel(lowerAnyFps)}"
            )
        }

        // 5. Requested height is lower than any available option (e.g. requested 240p, only 720p available)
        val closest = pool.minByOrNull { it.height } ?: pool.first()
        return DownloadPreselectionResult(
            selectedOption = closest,
            reason = DownloadPreselectionReason.FALLBACK_HIGHEST_AVAILABLE,
            explanation = "Selected closest available quality (${formatOptLabel(closest)})"
        )
    }

    private fun formatOptLabel(option: VideoDownloadOption): String =
        if (option.fpsLabel.isNotBlank()) "${option.height}p · ${option.fpsLabel}" else "${option.height}p"

    private fun estimateBytes(bitrateKbps: Int?, durationMs: Long?): Long? {
        if (bitrateKbps == null || durationMs == null || bitrateKbps <= 0 || durationMs <= 0) return null
        return ((bitrateKbps * 1000L) / 8L) * (durationMs / 1000L)
    }

    private fun combineBytes(a: Long?, b: Long?): Long? {
        if (a == null && b == null) return null
        return (a ?: 0L) + (b ?: 0L)
    }
}
