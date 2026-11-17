package com.example.sonara.data.download

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.sonara.data.download.remux.AndroidNativeMuxer
import com.example.sonara.data.download.remux.VideoMediaMuxer
import com.example.sonara.data.local.db.dao.DownloadDao
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.DownloadEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoPlaybackStream
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.domain.model.video.download.ActiveDownloadProgress
import com.example.sonara.domain.model.video.download.DownloadStage
import com.example.sonara.domain.model.video.download.VideoDownloadContainer
import com.example.sonara.domain.model.video.download.VideoDownloadOption
import com.example.sonara.domain.ports.VideoExtractionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Exceptions thrown during stream downloads.
 */
open class StreamDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ExpiredUrlException(val statusCode: Int, message: String) : StreamDownloadException(message)
class UrlExpiredException(message: String, cause: Throwable? = null) : StreamDownloadException(message, cause)
class StreamUnavailableException(message: String, cause: Throwable? = null) : StreamDownloadException(message, cause)

internal data class ContentRangeHeader(
    val start: Long,
    val end: Long,
    val total: Long?
)

internal data class StreamDownloadResult(
    val etag: String?,
    val bytesWritten: Long
)

/**
 * Engine coordinating high-performance, background video downloads up to 1440p / 60 FPS
 * with native zero-transcode remuxing, HTTP Range resumption, and real-time speed/ETA metrics.
 */
class VideoDownloadEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val videoExtractor: VideoExtractionPort? = null,
    private val mediaMuxer: VideoMediaMuxer = AndroidNativeMuxer(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "VideoDownloadEngine"
        private const val BUFFER_SIZE = 32768 // 32 KB chunk buffer
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MIN_REQUIRED_DISK_BYTES = 100 * 1024 * 1024L // 100 MB baseline
        const val MAX_URL_REFRESH_ATTEMPTS = 2

        internal fun parseContentRange(header: String?): ContentRangeHeader? {
            if (header == null) return null
            val regex = Regex("""bytes\s+(\d+)-(\d+)/(?:(\d+)|\*)""", RegexOption.IGNORE_CASE)
            val match = regex.find(header.trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].takeIf { it.isNotEmpty() }?.toLongOrNull()
            return ContentRangeHeader(start, end, total)
        }

        internal fun parseTotalFromContentRange(header: String?): Long? {
            if (header == null) return null
            val regex416 = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
            val match416 = regex416.find(header.trim())
            if (match416 != null) {
                return match416.groupValues[1].toLongOrNull()
            }
            val standard = parseContentRange(header)
            return standard?.total
        }

        internal fun isSameVideoCodec(actualCodec: String?, expectedCodec: String?): Boolean {
            if (actualCodec == null || expectedCodec == null) return false
            val a = actualCodec.lowercase()
            val e = expectedCodec.lowercase()
            if (e.contains("vp9") || e.contains("vp09")) {
                return a.contains("vp9") || a.contains("vp09")
            }
            if (e.contains("avc") || e.contains("h264")) {
                return a.contains("avc") || a.contains("h264")
            }
            if (e.contains("av1") || e.contains("av01")) {
                return a.contains("av1") || a.contains("av01")
            }
            return a == e
        }

        internal fun isMatchingFps(streamFps: Int?, option: VideoDownloadOption): Boolean {
            if (option.actualFps != null && streamFps != null) {
                return streamFps == option.actualFps
            }
            val normalized = if ((streamFps ?: 30) >= 45) 60 else 30
            return normalized == option.frameRate
        }

        internal fun matchProgressiveStream(
            source: VideoPlaybackSource,
            option: VideoDownloadOption
        ): Pair<String?, Boolean> {
            if (option.videoItag != null) {
                val exactItag = source.progressiveStreams.firstOrNull { it.formatId == option.videoItag }
                if (exactItag != null) return exactItag.url to true
            }
            val matchResCodec = source.progressiveStreams.firstOrNull { stream ->
                stream.height == option.height && isSameVideoCodec(stream.videoCodec, option.videoCodec)
            }
            if (matchResCodec != null) return matchResCodec.url to false
            val fallback = source.progressiveStreams.firstOrNull { it.height == option.height }
            if (fallback != null) return fallback.url to false
            return null to false
        }

        internal fun matchAdaptiveVideoStream(
            source: VideoPlaybackSource,
            option: VideoDownloadOption
        ): Pair<String?, Boolean> {
            if (option.videoItag != null) {
                val exactItag = source.videoOnlyStreams.firstOrNull { it.formatId == option.videoItag }
                if (exactItag != null) return exactItag.url to true
            }
            val matchFull = source.videoOnlyStreams.firstOrNull { stream ->
                stream.height == option.height &&
                    isMatchingFps(stream.frameRate, option) &&
                    isSameVideoCodec(stream.videoCodec, option.videoCodec)
            }
            if (matchFull != null) return matchFull.url to false
            val matchResCodec = source.videoOnlyStreams.firstOrNull { stream ->
                stream.height == option.height && isSameVideoCodec(stream.videoCodec, option.videoCodec)
            }
            if (matchResCodec != null) return matchResCodec.url to false
            val matchHeight = source.videoOnlyStreams.firstOrNull { it.height == option.height }
            if (matchHeight != null) return matchHeight.url to false
            return null to false
        }

        internal fun isMatchingLocale(localeA: String?, localeB: String?): Boolean {
            if (localeA.isNullOrBlank() || localeB.isNullOrBlank()) return false
            val cleanA = localeA.trim().lowercase()
            val cleanB = localeB.trim().lowercase()
            if (cleanA == cleanB) return true
            val langA = cleanA.split('-', '_')[0]
            val langB = cleanB.split('-', '_')[0]
            return langA.isNotBlank() && langA == langB
        }

        internal fun isAudioCodecCompatible(audioCodec: String?, container: VideoDownloadContainer): Boolean {
            if (audioCodec.isNullOrBlank()) return false
            val c = audioCodec.trim().lowercase()
            return when (container) {
                VideoDownloadContainer.WEBM -> c.contains("opus") || c.contains("vorbis")
                VideoDownloadContainer.MP4 -> c.contains("aac") || c.contains("mp4a")
            }
        }

        internal fun isAudioStreamCompatible(stream: VideoPlaybackStream, container: VideoDownloadContainer): Boolean {
            val codec = stream.audioCodec?.trim()?.lowercase().orEmpty()
            val cont = stream.container?.trim()?.lowercase().orEmpty()
            val mime = stream.mimeType?.trim()?.lowercase().orEmpty()

            return when (container) {
                VideoDownloadContainer.WEBM -> {
                    if (codec.isNotBlank()) {
                        // Fail-closed: explicit codec metadata must strictly be Opus or Vorbis
                        codec.contains("opus") || codec.contains("vorbis")
                    } else {
                        // Fallback only when explicit codec metadata is absent or blank
                        cont.contains("webm") || mime.contains("webm") || mime.contains("opus")
                    }
                }
                VideoDownloadContainer.MP4 -> {
                    if (codec.isNotBlank()) {
                        // Fail-closed: explicit codec metadata must strictly be AAC or MP4A
                        codec.contains("aac") || codec.contains("mp4a")
                    } else {
                        // Fallback only when explicit codec metadata is absent or blank
                        cont.contains("m4a") || cont.contains("mp4") || mime.contains("mp4") || mime.contains("aac")
                    }
                }
            }
        }

        internal fun matchAdaptiveAudioStream(
            source: VideoPlaybackSource,
            option: VideoDownloadOption
        ): Pair<String?, Boolean> {
            // Strict exact format ID match: only valid if the stream is also compatible with container
            if (option.audioItag != null) {
                val exactItag = source.audioOnlyStreams.firstOrNull { it.formatId == option.audioItag }
                if (exactItag != null && isAudioStreamCompatible(exactItag, option.container)) {
                    return exactItag.url to true
                }
            }

            // Filter strictly by required container/codec family (Opus/WebM for WebM, AAC/MP4 for MP4)
            val matchingAudios = source.audioOnlyStreams.filter { audio ->
                isAudioStreamCompatible(audio, option.container)
            }

            // If no stream exists in the required codec family, do NOT fall back across codec families!
            if (matchingAudios.isEmpty()) {
                return null to false
            }

            // 1. If audioTrackId is specified, try to match identical track ID within codec family
            if (option.audioTrackId != null) {
                val matchTrack = matchingAudios.firstOrNull { it.audioTrackId == option.audioTrackId }
                if (matchTrack != null) return matchTrack.url to false
            }

            // 2. If audioLocale is specified, match same language/locale within codec family
            // Crucial: do not pick a different language even if another language has higher bitrate!
            if (option.audioLocale != null) {
                val matchLocale = matchingAudios
                    .filter { isMatchingLocale(it.audioLocale, option.audioLocale) }
                    .maxByOrNull { it.bitrateKbps ?: 0 }
                if (matchLocale != null) return matchLocale.url to false
            }

            // 3. Fallback within codec family: highest bitrate stream
            val bestAudio = matchingAudios.maxByOrNull { it.bitrateKbps ?: 0 }
            if (bestAudio != null) {
                return bestAudio.url to false
            }

            // No compatible audio stream found within target container's codec family
            return null to false
        }
    }

    private val downloadsDir = File(context.filesDir, "sonara_downloads").apply {
        if (!exists()) mkdirs()
    }

    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val activeConnections = ConcurrentHashMap<String, MutableList<HttpURLConnection>>()
    private val _activeProgressMap = MutableStateFlow<Map<String, ActiveDownloadProgress>>(emptyMap())
    val activeProgressMap = _activeProgressMap.asStateFlow()

    private val dbWriteMutex = Mutex()

    fun observeActiveProgress(trackId: String): Flow<ActiveDownloadProgress?> =
        activeProgressMap.map { it[trackId] }.distinctUntilChanged()

    fun observeAllActiveProgress(): Flow<Map<String, ActiveDownloadProgress>> =
        activeProgressMap

    /**
     * Enqueues a video download with structured option and playback quality parameters.
     */
    suspend fun enqueueDownload(
        track: Track,
        option: VideoDownloadOption
    ): Result<Unit> = withContext(ioDispatcher) {
        val trackId = track.id
        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")

        // 1. Check if already actively downloading
        if (activeDownloadJobs.containsKey(trackId)) {
            Log.d(TAG, "Video $trackId is already downloading")
            return@withContext Result.success(Unit)
        }

        // 2. Check if already downloaded and verified on disk
        val existing = downloadDao.getDownload(trackId)
        if (existing != null && existing.status == DownloadStatus.DOWNLOADED.name) {
            val file = File(existing.localFilePath)
            if (file.exists() && file.canRead() && file.length() > 0) {
                Log.d(TAG, "Video $trackId already exists and verified on disk: ${existing.localFilePath}")
                return@withContext Result.success(Unit)
            }
        }

        // 3. Cache parent track in Room
        trackDao.insertTrack(TrackEntity.fromDomain(track))

        // 4. Insert initial queued record in downloads table
        val initialEntity = DownloadEntity(
            trackId = trackId,
            localFilePath = "",
            totalBytes = option.estimatedTotalSizeBytes ?: 0L,
            downloadedBytes = 0L,
            status = DownloadStatus.DOWNLOADING.name,
            quality = option.quality.name,
            mimeType = option.container.mimeType,
            createdAt = System.currentTimeMillis(),
            mediaType = "VIDEO",
            resolutionHeight = option.height,
            frameRate = option.frameRate,
            videoCodec = option.videoCodec,
            audioCodec = option.audioCodec,
            container = option.container.extension
        )
        downloadDao.insertOrUpdate(initialEntity)

        // 5. Update live state flow
        updateLiveProgress(
            ActiveDownloadProgress(
                trackId = trackId,
                stage = DownloadStage.QUEUED,
                downloadedBytes = 0L,
                totalBytes = option.estimatedTotalSizeBytes ?: 0L,
                progressFraction = 0f,
                stageDescription = "Queued for download"
            )
        )

        // 6. Launch background execution job
        val job = scope.launch(ioDispatcher) {
            performDownload(track, option)
        }
        activeDownloadJobs[trackId] = job

        Result.success(Unit)
    }

    /**
     * Cancels an in-flight video download, aborting network transfers and removing temp files.
     */
    suspend fun cancelDownload(trackId: String): Result<Unit> = withContext(ioDispatcher) {
        val job = activeDownloadJobs.remove(trackId)
        val connections = activeConnections.remove(trackId)

        connections?.forEach { conn ->
            try { conn.disconnect() } catch (_: Throwable) {}
        }
        job?.cancel()

        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        // Cleanup temp files
        downloadsDir.listFiles { file -> file.name.startsWith(sanitizedId) && (file.name.contains(".part") || file.name.contains(".tmp")) }
            ?.forEach { cleanupFile(it) }

        downloadDao.markFailed(trackId, "Download cancelled by user", DownloadStatus.CANCELLED.name)

        _activeProgressMap.value = _activeProgressMap.value.toMutableMap().apply {
            put(
                trackId,
                ActiveDownloadProgress(
                    trackId = trackId,
                    stage = DownloadStage.CANCELLED,
                    stageDescription = "Cancelled"
                )
            )
        }

        Result.success(Unit)
    }

    /**
     * Removes a completed or failed download and its physical file.
     */
    suspend fun removeDownload(trackId: String): Result<Unit> = withContext(ioDispatcher) {
        cancelDownload(trackId)

        val record = downloadDao.getDownload(trackId)
        if (record != null && record.localFilePath.isNotBlank()) {
            val file = File(record.localFilePath)
            cleanupFile(file)
        }

        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        downloadsDir.listFiles { file -> file.name.startsWith(sanitizedId) }
            ?.forEach { cleanupFile(it) }

        downloadDao.delete(trackId)

        _activeProgressMap.value = _activeProgressMap.value.toMutableMap().apply {
            remove(trackId)
        }

        Result.success(Unit)
    }

    suspend fun removeAllDownloads(): Result<Unit> = withContext(ioDispatcher) {
        activeDownloadJobs.keys.toList().forEach { cancelDownload(it) }
        downloadsDir.listFiles()?.forEach { cleanupFile(it) }
        downloadDao.deleteAll()
        _activeProgressMap.value = emptyMap()
        Result.success(Unit)
    }

    suspend fun getDownloadedFileUri(trackId: String): String? = withContext(ioDispatcher) {
        val record = downloadDao.getDownload(trackId)
        if (record != null && record.status == DownloadStatus.DOWNLOADED.name && record.localFilePath.isNotBlank()) {
            val file = File(record.localFilePath)
            if (file.exists() && file.canRead() && file.length() > 0) {
                return@withContext file.absolutePath
            } else {
                downloadDao.markFailed(trackId, "File missing from disk", DownloadStatus.NOT_DOWNLOADED.name)
            }
        }
        null
    }

    private suspend fun performDownload(
        track: Track,
        option: VideoDownloadOption
    ) {
        val trackId = track.id
        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val progressTracker = DownloadProgressTracker()

        val videoPartFile = File(downloadsDir, "${sanitizedId}_video_${option.height}p.part")
        val audioPartFile = File(downloadsDir, "${sanitizedId}_audio.part")
        val finalTargetFile = File(downloadsDir, "${sanitizedId}_${option.height}p.${option.container.extension}")

        var videoCompleted = false
        var audioCompleted = !option.isMergeRequired

        try {
            // 1. Validate representations and codec compatibility BEFORE starting transfers or checking disk
            if (option.isMergeRequired) {
                if (option.audioStreamUrl.isNullOrBlank()) {
                    throw StreamUnavailableException(
                        "No compatible audio stream available for ${option.container.name} (${option.videoCodec}) on track $trackId"
                    )
                }
                if (!isAudioCodecCompatible(option.audioCodec, option.container)) {
                    throw StreamUnavailableException(
                        "Audio codec '${option.audioCodec}' is incompatible with ${option.container.name} container for track $trackId"
                    )
                }
            }

            // 2. Verify available storage
            val requiredBytes = (option.estimatedTotalSizeBytes?.times(2.5)?.toLong()) ?: MIN_REQUIRED_DISK_BYTES
            if (!hasSufficientDiskSpace(requiredBytes)) {
                val error = "Insufficient storage space for download (requires at least ${DownloadProgressTracker.formatBytes(requiredBytes)})"
                Log.e(TAG, error)
                downloadDao.markFailed(trackId, error)
                updateLiveProgress(
                    ActiveDownloadProgress(
                        trackId = trackId,
                        stage = DownloadStage.FAILED,
                        errorMessage = error,
                        stageDescription = "Insufficient storage space"
                    )
                )
                activeDownloadJobs.remove(trackId)
                return
            }

            // 3. Prepare stream URLs (refresh if needed)
            var currentVideoUrl = option.videoStreamUrl
            var currentAudioUrl = option.audioStreamUrl
            var videoEtag: String? = null
            var audioEtag: String? = null

            // 4. Concurrent or single stream transfer
            val videoBytesDownloaded = AtomicLong(if (videoPartFile.exists()) videoPartFile.length() else 0L)
            val audioBytesDownloaded = AtomicLong(if (option.isMergeRequired && audioPartFile.exists()) audioPartFile.length() else 0L)
            val videoTotalBytes = AtomicLong(0L)
            val audioTotalBytes = AtomicLong(0L)

            val connectionsList = mutableListOf<HttpURLConnection>()
            activeConnections[trackId] = connectionsList

            updateLiveProgress(
                ActiveDownloadProgress(
                    trackId = trackId,
                    stage = if (option.isMergeRequired) DownloadStage.DOWNLOADING else DownloadStage.DOWNLOADING_VIDEO,
                    downloadedBytes = videoBytesDownloaded.get() + audioBytesDownloaded.get(),
                    totalBytes = option.estimatedTotalSizeBytes ?: 0L,
                    stageDescription = if (option.isMergeRequired) "Downloading video & audio (${option.height}p · ${option.fpsLabel})" else "Downloading video (${option.height}p · ${option.fpsLabel})"
                )
            )

            var lastDbPersist = 0L

            fun reportProgress(isFinal: Boolean = false) {
                val downloaded = videoBytesDownloaded.get() + audioBytesDownloaded.get()
                val total = (videoTotalBytes.get() + audioTotalBytes.get()).takeIf { it > 0 } ?: (option.estimatedTotalSizeBytes ?: 0L)
                val metrics = progressTracker.update(downloaded)
                val etaSeconds = DownloadProgressTracker.calculateEtaSeconds(downloaded, total, metrics.bytesPerSecond)
                val fraction = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null

                updateLiveProgress(
                    ActiveDownloadProgress(
                        trackId = trackId,
                        stage = DownloadStage.DOWNLOADING,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        progressFraction = fraction,
                        bytesPerSecond = metrics.bytesPerSecond,
                        etaSeconds = etaSeconds,
                        speedFormatted = metrics.speedFormatted,
                        etaFormatted = DownloadProgressTracker.formatEta(etaSeconds),
                        stageDescription = if (option.isMergeRequired) "Downloading video & audio" else "Downloading video"
                    )
                )

                val now = System.currentTimeMillis()
                if (isFinal || now - lastDbPersist > 500) {
                    lastDbPersist = now
                    scope.launch(ioDispatcher) {
                        dbWriteMutex.withLock {
                            downloadDao.updateProgress(trackId, downloaded, total, DownloadStatus.DOWNLOADING.name)
                        }
                    }
                }
            }

            var refreshAttempts = 0
            val maxRefreshAttempts = MAX_URL_REFRESH_ATTEMPTS

            while (!videoCompleted || !audioCompleted) {
                currentCoroutineContext().ensureActive()
                try {
                    coroutineScope {
                        val videoDeferred: Deferred<Unit>? = if (!videoCompleted) {
                            async(ioDispatcher) {
                                val result = downloadStreamWithRange(
                                    url = currentVideoUrl,
                                    targetFile = videoPartFile,
                                    connectionsList = connectionsList,
                                    expectedEtag = videoEtag,
                                    onResetBytes = {
                                        videoBytesDownloaded.set(0L)
                                    },
                                    onBytesRead = { bytesAdded, totalLen ->
                                        videoBytesDownloaded.addAndGet(bytesAdded)
                                        if (totalLen > 0) videoTotalBytes.set(totalLen)
                                        reportProgress()
                                    }
                                )
                                videoEtag = result.etag
                                videoCompleted = true
                            }
                        } else null

                        val audioDeferred: Deferred<Unit>? = if (!audioCompleted && currentAudioUrl != null) {
                            async(ioDispatcher) {
                                val result = downloadStreamWithRange(
                                    url = currentAudioUrl!!,
                                    targetFile = audioPartFile,
                                    connectionsList = connectionsList,
                                    expectedEtag = audioEtag,
                                    onResetBytes = {
                                        audioBytesDownloaded.set(0L)
                                    },
                                    onBytesRead = { bytesAdded, totalLen ->
                                        audioBytesDownloaded.addAndGet(bytesAdded)
                                        if (totalLen > 0) audioTotalBytes.set(totalLen)
                                        reportProgress()
                                    }
                                )
                                audioEtag = result.etag
                                audioCompleted = true
                            }
                        } else null

                        videoDeferred?.await()
                        audioDeferred?.await()
                    }
                } catch (e: ExpiredUrlException) {
                    if (refreshAttempts >= maxRefreshAttempts) {
                        throw UrlExpiredException(
                            "Stream URL expired (${e.statusCode}) and exceeded max refresh attempts ($maxRefreshAttempts)",
                            e
                        )
                    }
                    val extractor = videoExtractor
                        ?: throw StreamUnavailableException(
                            "Stream URL expired (${e.statusCode}) but no VideoExtractionPort provided to refresh",
                            e
                        )

                    refreshAttempts++
                    Log.i(
                        TAG,
                        "Caught ExpiredUrlException (${e.statusCode}). Refreshing stream URLs for track ${track.id} (attempt $refreshAttempts/$maxRefreshAttempts)..."
                    )

                    updateLiveProgress(
                        ActiveDownloadProgress(
                            trackId = trackId,
                            stage = DownloadStage.EXTRACTING_STREAMS,
                            downloadedBytes = videoBytesDownloaded.get() + audioBytesDownloaded.get(),
                            totalBytes = (videoTotalBytes.get() + audioTotalBytes.get()).takeIf { it > 0 } ?: (option.estimatedTotalSizeBytes ?: 0L),
                            progressFraction = null,
                            stageDescription = "Refreshing stream URLs..."
                        )
                    )

                    val eligibleStatus = VideoStatus(
                        videoId = track.id,
                        madeForKidsStatus = MadeForKidsStatus.NOT_MADE_FOR_KIDS,
                        embeddabilityStatus = EmbeddabilityStatus.EMBEDDABLE
                    )
                    val freshSourceResult = extractor.extractPlaybackSource(track.id, eligibleStatus)
                    val freshSource = freshSourceResult.getOrNull()
                        ?: throw StreamUnavailableException(
                            "Failed to refresh stream source for track ${track.id}: ${freshSourceResult.exceptionOrNull()?.message}",
                            freshSourceResult.exceptionOrNull()
                        )

                    if (!videoCompleted) {
                        val (freshVideoUrl, representationMatches) = if (!option.isMergeRequired) {
                            matchProgressiveStream(freshSource, option)
                        } else {
                            matchAdaptiveVideoStream(freshSource, option)
                        }
                        if (freshVideoUrl == null) {
                            throw StreamUnavailableException("Could not find matching video stream for track ${track.id} after refresh")
                        }
                        currentVideoUrl = freshVideoUrl
                        if (!representationMatches) {
                            Log.w(TAG, "Refreshed video representation differed; truncating video part file to 0")
                            if (videoPartFile.exists()) {
                                RandomAccessFile(videoPartFile, "rw").use { it.setLength(0L) }
                            }
                            videoBytesDownloaded.set(0L)
                            videoEtag = null
                        }
                    }

                    if (!audioCompleted) {
                        val (freshAudioUrl, representationMatches) = matchAdaptiveAudioStream(freshSource, option)
                        if (freshAudioUrl == null) {
                            throw StreamUnavailableException("Could not find matching audio stream for track ${track.id} after refresh")
                        }
                        currentAudioUrl = freshAudioUrl
                        if (!representationMatches) {
                            Log.w(TAG, "Refreshed audio representation differed; truncating audio part file to 0")
                            if (audioPartFile.exists()) {
                                RandomAccessFile(audioPartFile, "rw").use { it.setLength(0L) }
                            }
                            audioBytesDownloaded.set(0L)
                            audioEtag = null
                        }
                    }
                }
            }

            reportProgress(isFinal = true)

            // Validate completed source stream files
            if (!videoPartFile.exists() || videoPartFile.length() == 0L) {
                throw IllegalStateException("Video stream file empty or missing after transfer")
            }
            if (option.isMergeRequired && (!audioPartFile.exists() || audioPartFile.length() == 0L)) {
                throw IllegalStateException("Audio stream file empty or missing after transfer")
            }

            // 4. Remuxing Stage
            if (option.isMergeRequired) {
                updateLiveProgress(
                    ActiveDownloadProgress(
                        trackId = trackId,
                        stage = DownloadStage.REMUXING,
                        downloadedBytes = videoBytesDownloaded.get() + audioBytesDownloaded.get(),
                        totalBytes = videoTotalBytes.get() + audioTotalBytes.get(),
                        progressFraction = null, // Indeterminate progress for remuxing
                        stageDescription = "Remuxing into ${option.container.name} (${option.videoCodec} + ${option.audioCodec})..."
                    )
                )

                val remuxResult = mediaMuxer.remux(
                    videoFile = videoPartFile,
                    audioFile = audioPartFile,
                    outputFile = finalTargetFile,
                    container = option.container,
                    onProgress = { remuxFraction ->
                        updateLiveProgress(
                            ActiveDownloadProgress(
                                trackId = trackId,
                                stage = DownloadStage.REMUXING,
                                downloadedBytes = videoBytesDownloaded.get() + audioBytesDownloaded.get(),
                                totalBytes = videoTotalBytes.get() + audioTotalBytes.get(),
                                progressFraction = remuxFraction,
                                stageDescription = "Remuxing into ${option.container.name} (${(remuxFraction * 100).toInt()}%)"
                            )
                        )
                    }
                )

                if (remuxResult.isFailure) {
                    throw remuxResult.exceptionOrNull() ?: IllegalStateException("Remuxing failed")
                }
            } else {
                // Progressive stream: atomic rename directly to final container
                if (finalTargetFile.exists()) finalTargetFile.delete()
                val renamed = videoPartFile.renameTo(finalTargetFile)
                if (!renamed || !finalTargetFile.exists() || finalTargetFile.length() == 0L) {
                    throw IllegalStateException("Failed to commit final progressive file")
                }
            }

            // 5. Validation Stage
            updateLiveProgress(
                ActiveDownloadProgress(
                    trackId = trackId,
                    stage = DownloadStage.VALIDATING,
                    downloadedBytes = finalTargetFile.length(),
                    totalBytes = finalTargetFile.length(),
                    progressFraction = 1.0f,
                    stageDescription = "Validating final file..."
                )
            )

            if (!finalTargetFile.exists() || finalTargetFile.length() == 0L) {
                throw IllegalStateException("Zero-byte final file output")
            }

            // Cleanup temp files safely
            cleanupFile(videoPartFile)
            cleanupFile(audioPartFile)

            // 6. Final Database Commit
            dbWriteMutex.withLock {
                downloadDao.updateVideoFileInfo(
                    trackId = trackId,
                    localFilePath = finalTargetFile.absolutePath,
                    mimeType = option.container.mimeType,
                    quality = option.quality.name,
                    mediaType = "VIDEO",
                    resolutionHeight = option.height,
                    frameRate = option.frameRate,
                    videoCodec = option.videoCodec,
                    audioCodec = option.audioCodec,
                    container = option.container.extension
                )
                downloadDao.updateProgress(
                    trackId = trackId,
                    downloadedBytes = finalTargetFile.length(),
                    totalBytes = finalTargetFile.length(),
                    status = DownloadStatus.DOWNLOADED.name
                )
                downloadDao.markCompleted(trackId, System.currentTimeMillis())
            }

            updateLiveProgress(
                ActiveDownloadProgress(
                    trackId = trackId,
                    stage = DownloadStage.COMPLETED,
                    downloadedBytes = finalTargetFile.length(),
                    totalBytes = finalTargetFile.length(),
                    progressFraction = 1.0f,
                    stageDescription = "Download completed (${DownloadProgressTracker.formatBytes(finalTargetFile.length())})"
                )
            )

            Log.i(TAG, "Successfully completed video download for ${track.title} [${option.displayLabel}] -> ${finalTargetFile.absolutePath}")
        } catch (e: CancellationException) {
            Log.w(TAG, "Video download cancelled for $trackId")
            cleanupFile(videoPartFile)
            cleanupFile(audioPartFile)
            if (finalTargetFile.exists() && finalTargetFile.length() == 0L) cleanupFile(finalTargetFile)
            downloadDao.markFailed(trackId, "Download cancelled", DownloadStatus.CANCELLED.name)
            updateLiveProgress(
                ActiveDownloadProgress(
                    trackId = trackId,
                    stage = DownloadStage.CANCELLED,
                    stageDescription = "Download cancelled"
                )
            )
        } catch (t: Throwable) {
            val errorMsg = t.message ?: "Download failed"
            Log.e(TAG, "Video download failed for $trackId: $errorMsg", t)
            if (!videoCompleted) cleanupFile(videoPartFile)
            if (!audioCompleted) cleanupFile(audioPartFile)
            if (finalTargetFile.exists() && finalTargetFile.length() == 0L) cleanupFile(finalTargetFile)
            downloadDao.markFailed(trackId, errorMsg)
            updateLiveProgress(
                ActiveDownloadProgress(
                    trackId = trackId,
                    stage = DownloadStage.FAILED,
                    errorMessage = errorMsg,
                    stageDescription = "Failed: $errorMsg"
                )
            )
        } finally {
            activeDownloadJobs.remove(trackId)
            activeConnections.remove(trackId)
        }
    }

    private suspend fun downloadStreamWithRange(
        url: String,
        targetFile: File,
        connectionsList: MutableList<HttpURLConnection>,
        expectedEtag: String? = null,
        forceFromZero: Boolean = false,
        onResetBytes: () -> Unit,
        onBytesRead: (bytesAdded: Long, totalLength: Long) -> Unit
    ): StreamDownloadResult = withContext(ioDispatcher) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var raf: RandomAccessFile? = null

        try {
            val existingLength = if (!forceFromZero && targetFile.exists()) targetFile.length() else 0L

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SonaraAndroid/1.0")
                setRequestProperty("Accept", "*/*")
                if (existingLength > 0L) {
                    setRequestProperty("Range", "bytes=$existingLength-")
                    if (expectedEtag != null) {
                        setRequestProperty("If-Range", expectedEtag)
                    }
                }
            }
            connection = conn
            synchronized(connectionsList) { connectionsList.add(conn) }

            currentCoroutineContext().ensureActive()

            val responseCode = conn.responseCode

            if (responseCode == 403 || responseCode == 410) {
                throw ExpiredUrlException(responseCode, "Stream URL expired with HTTP $responseCode from $url")
            }

            if (responseCode == 416) {
                // Range Not Satisfiable: check if file is already complete
                val contentRange = conn.getHeaderField("Content-Range")
                val totalLengthFrom416 = parseTotalFromContentRange(contentRange)
                if (totalLengthFrom416 != null && totalLengthFrom416 > 0L && existingLength == totalLengthFrom416) {
                    Log.i(TAG, "HTTP 416 received and existing file matches total size ($existingLength bytes). Component is already complete.")
                    return@withContext StreamDownloadResult(
                        etag = expectedEtag ?: conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified"),
                        bytesWritten = existingLength
                    )
                }
                // Incomplete or mismatch: clean up and restart from byte 0
                Log.w(TAG, "HTTP 416 with non-matching size (existing=$existingLength, serverTotal=$totalLengthFrom416). Restarting from byte 0.")
                conn.disconnect()
                synchronized(connectionsList) { connectionsList.remove(conn) }
                if (targetFile.exists()) {
                    RandomAccessFile(targetFile, "rw").use { it.setLength(0L) }
                }
                onResetBytes()
                return@withContext downloadStreamWithRange(
                    url = url,
                    targetFile = targetFile,
                    connectionsList = connectionsList,
                    expectedEtag = null,
                    forceFromZero = true,
                    onResetBytes = onResetBytes,
                    onBytesRead = onBytesRead
                )
            }

            val isPartial = responseCode == 206
            val isOk = responseCode == 200

            if (!isPartial && !isOk) {
                throw IllegalStateException("HTTP error $responseCode while downloading stream from $url")
            }

            val serverEtag = conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified")

            // If 206 was returned, validate Content-Range and ETag
            if (isPartial) {
                val contentRangeHeader = conn.getHeaderField("Content-Range")
                val parsedRange = parseContentRange(contentRangeHeader)
                val etagMatches = expectedEtag == null || serverEtag == null || expectedEtag == serverEtag

                if (parsedRange == null || parsedRange.start != existingLength || !etagMatches) {
                    Log.w(
                        TAG,
                        "Invalid Range resume (parsedRange=$parsedRange, expectedStart=$existingLength, etagMatches=$etagMatches). Restarting from byte 0."
                    )
                    conn.disconnect()
                    synchronized(connectionsList) { connectionsList.remove(conn) }
                    if (targetFile.exists()) {
                        RandomAccessFile(targetFile, "rw").use { it.setLength(0L) }
                    }
                    onResetBytes()
                    return@withContext downloadStreamWithRange(
                        url = url,
                        targetFile = targetFile,
                        connectionsList = connectionsList,
                        expectedEtag = null,
                        forceFromZero = true,
                        onResetBytes = onResetBytes,
                        onBytesRead = onBytesRead
                    )
                }
            }

            val serverContentLength = conn.contentLengthLong
            val totalLength = when {
                isPartial -> {
                    val parsed = parseContentRange(conn.getHeaderField("Content-Range"))
                    if (parsed?.total != null && parsed.total > 0L) parsed.total
                    else if (serverContentLength > 0) existingLength + serverContentLength
                    else -1L
                }
                isOk && serverContentLength > 0 -> serverContentLength
                else -> -1L
            }

            raf = RandomAccessFile(targetFile, "rw")
            if (isPartial) {
                raf.seek(existingLength)
            } else {
                if (existingLength > 0L) {
                    // Server returned 200 when Range was requested; reset byte tracking and truncate
                    onResetBytes()
                }
                raf.setLength(0L)
                raf.seek(0L)
            }

            inputStream = conn.inputStream
            val buffer = ByteArray(BUFFER_SIZE)
            var read = 0

            while (currentCoroutineContext().isActive && inputStream.read(buffer).also { read = it } != -1) {
                raf.write(buffer, 0, read)
                onBytesRead(read.toLong(), totalLength)
            }

            currentCoroutineContext().ensureActive()

            StreamDownloadResult(
                etag = serverEtag ?: expectedEtag,
                bytesWritten = targetFile.length()
            )
        } finally {
            try { inputStream?.close() } catch (_: Throwable) {}
            try { raf?.close() } catch (_: Throwable) {}
            try { connection?.disconnect() } catch (_: Throwable) {}
            if (connection != null) {
                synchronized(connectionsList) { connectionsList.remove(connection) }
            }
        }
    }

    private fun updateLiveProgress(progress: ActiveDownloadProgress) {
        _activeProgressMap.value = _activeProgressMap.value.toMutableMap().apply {
            put(progress.trackId, progress)
        }
    }

    private fun hasSufficientDiskSpace(requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(downloadsDir.path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            if (available > 0L) {
                available >= requiredBytes
            } else {
                val free = downloadsDir.freeSpace
                if (free > 0L) free >= requiredBytes else true
            }
        } catch (_: Throwable) {
            true // If stat fails, do not block download arbitrarily
        }
    }

    private fun cleanupFile(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (_: Throwable) {}
    }
}
