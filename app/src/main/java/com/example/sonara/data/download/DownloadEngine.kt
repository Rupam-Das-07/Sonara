package com.example.sonara.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.sonara.data.local.db.dao.DownloadDao
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.DownloadEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadFileFormat
import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Engine coordinating native background downloads for offline music playback.
 *
 * Storage architecture:
 * - Scoped app-private storage: `context.filesDir/sonara_downloads/`
 *   - Device path: `/data/user/0/com.example.sonara/files/sonara_downloads/`
 *   - Persistent across app restarts.
 *   - Protected from system automatic cache pruning.
 *   - No external storage permissions required.
 * - Atomic write protocol: `${trackId}.webm.download` -> `${trackId}.webm`.
 * - Deep filesystem <-> database reconciliation.
 */
class DownloadEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val streamResolver: StreamResolverPort,
    private val settingsRepository: SettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    data class ResolvedDownloadFormat(
        val extension: String,
        val mimeType: String
    )

    companion object {
        private const val TAG = "DownloadEngine"
        private const val BUFFER_SIZE = 16384
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        /**
         * Truthfully resolves download container extension and MIME type
         * from resolved StreamInfo metadata and user format preference without transcoding.
         */
        fun determineDownloadFormat(
            streamInfo: StreamInfo,
            preferredFormat: DownloadFileFormat = DownloadFileFormat.AUTO
        ): Result<ResolvedDownloadFormat> {
            val format = streamInfo.format.lowercase()
            val codec = streamInfo.codec.lowercase()
            val isWebmSource = format.contains("webm") || codec == "opus"
            val isMp4Source = format.contains("mp4") || format.contains("m4a") || codec == "aac"

            return when {
                isWebmSource -> {
                    when (preferredFormat) {
                        DownloadFileFormat.MP4, DownloadFileFormat.M4A -> {
                            Log.w(TAG, "Requested format $preferredFormat incompatible with native WebM/Opus source; preserving native WebM container")
                            Result.success(ResolvedDownloadFormat(extension = "webm", mimeType = "audio/webm"))
                        }
                        else -> {
                            Result.success(ResolvedDownloadFormat(extension = "webm", mimeType = "audio/webm"))
                        }
                    }
                }
                isMp4Source -> {
                    when (preferredFormat) {
                        DownloadFileFormat.MP4 -> {
                            Result.success(ResolvedDownloadFormat(extension = "mp4", mimeType = "audio/mp4"))
                        }
                        DownloadFileFormat.WEBM -> {
                            Log.w(TAG, "Requested format WEBM incompatible with native MP4/AAC source; preserving native M4A container")
                            Result.success(ResolvedDownloadFormat(extension = "m4a", mimeType = "audio/mp4"))
                        }
                        else -> {
                            Result.success(ResolvedDownloadFormat(extension = "m4a", mimeType = "audio/mp4"))
                        }
                    }
                }
                else -> {
                    Result.failure(
                        IllegalArgumentException(
                            "Unsupported audio stream format for download: format=${streamInfo.format}, codec=${streamInfo.codec}"
                        )
                    )
                }
            }
        }
        /**
         * Resolves the actual quality tier truthful to the physical stream
         * returned by AudioSourceResolver rather than assuming the requested preference.
         */
        fun resolveActualQuality(streamInfo: StreamInfo): AudioQuality {
            val tier = streamInfo.qualityTier.uppercase()
            val provider = streamInfo.provider.lowercase()
            val bitrate = streamInfo.bitrateKbps

            return when {
                tier == "VERY_HIGH" -> AudioQuality.VERY_HIGH
                tier == "HIGH" && (provider == "jiosaavn" || bitrate >= 250) -> AudioQuality.VERY_HIGH
                else -> AudioQuality.HIGH
            }
        }
    }

    private val mutex = Mutex()
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()
    val downloadsDir: File by lazy {
        File(context.filesDir, "sonara_downloads").apply {
            if (!exists()) mkdirs()
        }
    }

    init {
        scope.launch(ioDispatcher) {
            reconcileStorage()
        }
    }

    /**
     * Reconciles Room database download records with physical disk storage:
     * 1. Cleans up dangling `.download` temp files left from aborted app processes.
     * 2. Reconciles active records that died during app termination to CANCELLED.
     * 3. Verifies all DOWNLOADED records in Room still exist and are valid on disk;
     *    marks missing/invalid records as NOT_DOWNLOADED.
     * Note: Orphan disk files are not arbitrarily mapped without authoritative track metadata.
     */
    suspend fun reconcileStorage() = withContext(ioDispatcher) {
        try {
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            // 1. Delete all orphaned .download temporary files
            downloadsDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".download")) {
                    Log.d(TAG, "Cleaning orphaned temp file: ${file.name}")
                    cleanupFile(file)
                }
            }

            // 2. Reconcile active records that died during app termination
            val active = downloadDao.getActiveDownloads()
            active.forEach { entity ->
                if (!activeDownloadJobs.containsKey(entity.trackId)) {
                    downloadDao.markFailed(entity.trackId, "Cancelled by app shutdown", DownloadStatus.CANCELLED.name)
                }
            }

            // 3. Verify all DOWNLOADED records still physically exist and are valid on disk
            val downloaded = downloadDao.getDownloadedRecords()
            downloaded.forEach { entity ->
                val path = entity.localFilePath
                val file = if (path.isNotBlank()) File(path) else null
                val isValid = file != null && file.exists() && file.canRead() && file.length() > 0L
                if (!isValid) {
                    Log.w(TAG, "Downloaded file for ${entity.trackId} missing/invalid on disk ($path). Reconciling to NOT_DOWNLOADED.")
                    downloadDao.markFailed(entity.trackId, "File missing from disk", DownloadStatus.NOT_DOWNLOADED.name)
                }
            }

            Log.d(TAG, "Storage reconciliation completed successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Storage reconciliation encountered non-fatal error: ${e.message}")
        }
    }

    suspend fun enqueueDownload(track: Track, qualityOverride: AudioQuality? = null): Result<Unit> = mutex.withLock {
        withContext(ioDispatcher) {
            val trackId = track.id.trim()
            if (trackId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Track ID cannot be blank"))
            }

            val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")

            // 1. Check if already active
            if (activeDownloadJobs.containsKey(trackId)) {
                Log.d(TAG, "Track $trackId is already actively downloading")
                return@withContext Result.success(Unit)
            }

            // 2. Check if already downloaded and valid on disk
            val existing = downloadDao.getDownload(trackId)
            if (existing != null && existing.status == DownloadStatus.DOWNLOADED.name) {
                val file = File(existing.localFilePath)
                if (file.exists() && file.canRead() && file.length() > 0) {
                    Log.d(TAG, "Track $trackId is already verified at ${existing.localFilePath}")
                    return@withContext Result.success(Unit)
                } else {
                    Log.w(TAG, "Track $trackId marked DOWNLOADED but file missing on disk. Reconciling.")
                    downloadDao.markFailed(trackId, "File missing from disk", DownloadStatus.NOT_DOWNLOADED.name)
                }
            }

            // If target file physically exists on disk and is readable, restore Room record directly
            val candidateM4a = File(downloadsDir, "$sanitizedId.m4a")
            val candidateMp4 = File(downloadsDir, "$sanitizedId.mp4")
            val candidateWebm = File(downloadsDir, "$sanitizedId.webm")
            val validPhysical = when {
                candidateM4a.exists() && candidateM4a.canRead() && candidateM4a.length() > 1024 -> candidateM4a to "audio/mp4"
                candidateMp4.exists() && candidateMp4.canRead() && candidateMp4.length() > 1024 -> candidateMp4 to "audio/mp4"
                candidateWebm.exists() && candidateWebm.canRead() && candidateWebm.length() > 1024 -> candidateWebm to "audio/webm"
                else -> null
            }
            if (validPhysical != null) {
                val (file, mime) = validPhysical
                Log.d(TAG, "Physical file exists for $trackId (${file.length()} bytes, $mime). Reconciling to DOWNLOADED.")
                trackDao.insertTrack(TrackEntity.fromDomain(track))
                val reconciledEntity = DownloadEntity(
                    trackId = trackId,
                    localFilePath = file.absolutePath,
                    totalBytes = file.length(),
                    downloadedBytes = file.length(),
                    status = DownloadStatus.DOWNLOADED.name,
                    quality = qualityOverride?.name ?: AudioQuality.VERY_HIGH.name,
                    mimeType = mime,
                    createdAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis()
                )
                downloadDao.insertOrUpdate(reconciledEntity)
                return@withContext Result.success(Unit)
            }

            // 3. Cache track metadata in Room
            trackDao.insertTrack(TrackEntity.fromDomain(track))

            // 4. Determine quality and Wi-Fi policies
            val prefs = settingsRepository.getUserPreferences().firstOrNull()
            val quality = qualityOverride ?: prefs?.downloadQuality ?: AudioQuality.VERY_HIGH
            val formatPreference = prefs?.downloadFileFormat ?: DownloadFileFormat.AUTO

            val initialEntity = DownloadEntity(
                trackId = trackId,
                localFilePath = "",
                totalBytes = 0L,
                downloadedBytes = 0L,
                status = DownloadStatus.DOWNLOADING.name,
                quality = quality.name,
                mimeType = "",
                createdAt = System.currentTimeMillis()
            )
            downloadDao.insertOrUpdate(initialEntity)

            // 5. Launch background download job
            val job = scope.launch(ioDispatcher) {
                performDownload(track, quality, prefs?.downloadOverWifiOnly ?: false, formatPreference)
            }
            activeDownloadJobs[trackId] = job

            Result.success(Unit)
        }
    }

    private suspend fun performDownload(
        track: Track,
        quality: AudioQuality,
        wifiOnly: Boolean,
        preferredFormat: DownloadFileFormat = DownloadFileFormat.AUTO
    ) {
        val trackId = track.id
        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        var currentTempFile: File? = null

        try {
            // Check network restrictions
            if (wifiOnly && !isWifiConnected()) {
                val errorMsg = "Download blocked: Wi-Fi only mode enabled"
                Log.w(TAG, errorMsg)
                downloadDao.markFailed(trackId, errorMsg)
                activeDownloadJobs.remove(trackId)
                return
            }

            downloadDao.updateProgress(trackId, 0L, 0L, DownloadStatus.DOWNLOADING.name)

            // Resolve download stream URL with full track metadata for provider matching
            val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0
            val streamResult = streamResolver.resolveStream(
                trackId = trackId,
                quality = quality,
                title = track.title,
                artist = track.artist,
                durationSeconds = durationSec
            )
            if (streamResult.isFailure) {
                val errorMsg = streamResult.exceptionOrNull()?.message ?: "Stream resolution failed"
                Log.e(TAG, "Stream resolution failed for download $trackId: $errorMsg")
                downloadDao.markFailed(trackId, errorMsg)
                activeDownloadJobs.remove(trackId)
                return
            }

            val streamInfo = streamResult.getOrThrow()
            val formatResult = determineDownloadFormat(streamInfo, preferredFormat)
            if (formatResult.isFailure) {
                val errorMsg = formatResult.exceptionOrNull()?.message ?: "Unsupported format"
                Log.e(TAG, "Format determination failed for download $trackId: $errorMsg")
                downloadDao.markFailed(trackId, errorMsg)
                activeDownloadJobs.remove(trackId)
                return
            }

            val actualQuality = resolveActualQuality(streamInfo)
            val resolvedFormat = formatResult.getOrThrow()
            val targetFile = File(downloadsDir, "$sanitizedId.${resolvedFormat.extension}")
            val tempFile = File(downloadsDir, "$sanitizedId.${resolvedFormat.extension}.download")
            currentTempFile = tempFile

            // Update database with resolved localFilePath, mimeType, and actual quality
            downloadDao.updateFileInfo(trackId, targetFile.absolutePath, resolvedFormat.mimeType, actualQuality.name)

            val streamUrl = streamInfo.streamUrl
            Log.d(TAG, "Starting download for ${track.title} [${resolvedFormat.extension}, ${resolvedFormat.mimeType}, ${actualQuality.name}] from $streamUrl to ${tempFile.absolutePath}")

            // Download stream to temp file
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                connection = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "SonaraAndroid/1.0")
                    setRequestProperty("Accept", "*/*")
                }
                activeConnections[trackId] = connection

                if (!currentCoroutineContext().isActive || !activeDownloadJobs.containsKey(trackId)) {
                    throw CancellationException("Download cancelled before network connect")
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val errorMsg = "HTTP error $responseCode during download"
                    Log.e(TAG, errorMsg)
                    downloadDao.markFailed(trackId, errorMsg)
                    cleanupFile(tempFile)
                    return
                }

                val contentLength = connection.contentLengthLong
                inputStream = connection.inputStream
                outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead = 0
                var totalDownloaded = 0L
                var lastProgressUpdate = System.currentTimeMillis()

                while (currentCoroutineContext().isActive && activeDownloadJobs.containsKey(trackId) && inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalDownloaded += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 200 || (contentLength > 0 && totalDownloaded == contentLength)) {
                        downloadDao.updateProgress(trackId, totalDownloaded, contentLength, DownloadStatus.DOWNLOADING.name)
                        lastProgressUpdate = now
                    }
                }
                outputStream.flush()

                // Check cooperative cancellation before committing
                if (!currentCoroutineContext().isActive || !activeDownloadJobs.containsKey(trackId)) {
                    throw CancellationException("Download cancelled during transfer")
                }

                if (contentLength > 0 && totalDownloaded < contentLength) {
                    throw IllegalStateException("Incomplete download: received $totalDownloaded of $contentLength bytes")
                }

                // Verify temp file written successfully
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    val errorMsg = "Zero bytes written during download"
                    Log.e(TAG, errorMsg)
                    downloadDao.markFailed(trackId, errorMsg)
                    cleanupFile(tempFile)
                    return
                }

                // Pre-rename cancellation guard
                if (!currentCoroutineContext().isActive || !activeDownloadJobs.containsKey(trackId)) {
                    throw CancellationException("Download cancelled before atomic rename")
                }

                // Atomic rename to final target file
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempFile.renameTo(targetFile)
                if (renamed && targetFile.exists() && targetFile.canRead() && targetFile.length() > 0) {
                    // Post-rename cancellation guard: ensure target file is not retained if cancelled mid-commit
                    if (!currentCoroutineContext().isActive || !activeDownloadJobs.containsKey(trackId)) {
                        cleanupFile(targetFile)
                        throw CancellationException("Download cancelled during final commit")
                    }
                    downloadDao.updateProgress(trackId, targetFile.length(), targetFile.length(), DownloadStatus.DOWNLOADED.name)
                    downloadDao.markCompleted(trackId, System.currentTimeMillis(), DownloadStatus.DOWNLOADED.name)
                    Log.i(TAG, "Successfully verified and committed download: ${track.title} (${targetFile.length()} bytes, ${resolvedFormat.extension}, ${actualQuality.name})")
                } else {
                    val errorMsg = "Failed to commit downloaded file to final storage"
                    Log.e(TAG, errorMsg)
                    downloadDao.markFailed(trackId, errorMsg)
                    cleanupFile(tempFile)
                }

            } finally {
                activeConnections.remove(trackId)
                try { outputStream?.close() } catch (_: Exception) {}
                try { inputStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "Download cancelled for $trackId")
            currentTempFile?.let { cleanupFile(it) }
            downloadDao.markFailed(trackId, "Cancelled", DownloadStatus.CANCELLED.name)
        } catch (e: Exception) {
            Log.e(TAG, "Download error for $trackId: ${e.message}")
            currentTempFile?.let { cleanupFile(it) }
            downloadDao.markFailed(trackId, e.message ?: "Unknown download error")
        } finally {
            activeConnections.remove(trackId)
            activeDownloadJobs.remove(trackId)
        }
    }

    /**
     * Re-attempt a previously failed (or cancelled) download using the cached track metadata.
     *
     * This is a REAL retry: it reuses the same [enqueueDownload] path the in-app UI uses to
     * re-download a FAILED track (see PlayerViewModel.toggleDownload). Track identity is recovered
     * from the Room [TrackEntity] cache written at enqueue time, so no track object needs to be
     * threaded through the notification layer. Returns failure if no cached metadata exists.
     */
    suspend fun retryDownload(trackId: String): Result<Unit> = withContext(ioDispatcher) {
        val cached = trackDao.getTrackById(trackId)
            ?: return@withContext Result.failure(
                IllegalStateException("No cached track metadata to retry download: $trackId")
            )
        enqueueDownload(cached.toDomain())
    }

    suspend fun cancelDownload(trackId: String): Result<Unit> = withContext(ioDispatcher) {
        val conn = activeConnections.remove(trackId)
        try {
            conn?.disconnect()
        } catch (_: Exception) {}

        val job = activeDownloadJobs.remove(trackId)
        job?.cancel()

        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        downloadsDir.listFiles()?.filter {
            it.name.startsWith("$sanitizedId.") && it.name.endsWith(".download")
        }?.forEach { cleanupFile(it) }

        downloadDao.markFailed(trackId, "Cancelled", DownloadStatus.CANCELLED.name)
        Result.success(Unit)
    }

    suspend fun removeDownload(trackId: String): Result<Unit> = withContext(ioDispatcher) {
        cancelDownload(trackId)

        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        cleanupFile(File(downloadsDir, "$sanitizedId.webm"))
        cleanupFile(File(downloadsDir, "$sanitizedId.m4a"))
        cleanupFile(File(downloadsDir, "$sanitizedId.mp4"))

        val record = downloadDao.getDownload(trackId)
        if (record != null && record.localFilePath.isNotBlank()) {
            val file = File(record.localFilePath)
            cleanupFile(file)
        }

        downloadDao.delete(trackId)
        Result.success(Unit)
    }

    suspend fun removeAllDownloads(): Result<Unit> = withContext(ioDispatcher) {
        activeConnections.values.forEach {
            try {
                it.disconnect()
            } catch (_: Exception) {}
        }
        activeConnections.clear()

        activeDownloadJobs.values.forEach { it.cancel() }
        activeDownloadJobs.clear()

        downloadsDir.listFiles()?.forEach { cleanupFile(it) }

        downloadDao.deleteAll()
        Result.success(Unit)
    }

    suspend fun getDownloadedFileUri(trackId: String): String? = withContext(ioDispatcher) {
        val record = downloadDao.getDownload(trackId)
        if (record != null && record.status == DownloadStatus.DOWNLOADED.name && record.localFilePath.isNotBlank()) {
            val file = File(record.localFilePath)
            if (file.exists() && file.canRead() && file.length() > 0) {
                return@withContext file.absolutePath
            } else {
                // Reconcile missing file
                downloadDao.markFailed(trackId, "File missing from disk", DownloadStatus.NOT_DOWNLOADED.name)
            }
        }

        // Fallback disk check
        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val candidateM4a = File(downloadsDir, "$sanitizedId.m4a")
        val candidateMp4 = File(downloadsDir, "$sanitizedId.mp4")
        val candidateWebm = File(downloadsDir, "$sanitizedId.webm")
        val validPhysical = when {
            candidateM4a.exists() && candidateM4a.canRead() && candidateM4a.length() > 1024 -> candidateM4a to "audio/mp4"
            candidateMp4.exists() && candidateMp4.canRead() && candidateMp4.length() > 1024 -> candidateMp4 to "audio/mp4"
            candidateWebm.exists() && candidateWebm.canRead() && candidateWebm.length() > 1024 -> candidateWebm to "audio/webm"
            else -> null
        }
        if (validPhysical != null) {
            val (file, mime) = validPhysical
            downloadDao.updateProgress(trackId, file.length(), file.length(), DownloadStatus.DOWNLOADED.name)
            val physicalQuality = if (mime == "audio/mp4") AudioQuality.VERY_HIGH.name else AudioQuality.HIGH.name
            downloadDao.updateFileInfo(trackId, file.absolutePath, mime, physicalQuality)
            downloadDao.markCompleted(trackId, System.currentTimeMillis(), DownloadStatus.DOWNLOADED.name)
            return@withContext file.absolutePath
        }

        null
    }

    private fun cleanupFile(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete file ${file.absolutePath}: ${e.message}")
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
