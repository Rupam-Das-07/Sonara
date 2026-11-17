package com.example.sonara.data.download.remux

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.sonara.domain.model.video.download.VideoDownloadContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Android platform implementation of [VideoMediaMuxer] using native [MediaExtractor] and [MediaMuxer].
 *
 * Implements:
 * - Constant bounded memory usage via reusable direct ByteBuffer.
 * - Presentation timestamp preservation and chronological interleaving.
 * - Strict non-decreasing per-track timestamps with audio priming preservation.
 * - Clean rollback and resource release on cancellation or failure.
 */
class AndroidNativeMuxer(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : VideoMediaMuxer {

    companion object {
        private const val TAG = "AndroidNativeMuxer"
        private const val INITIAL_BUFFER_CAPACITY = 1024 * 1024 // 1 MB
        private const val MAX_BUFFER_CAPACITY = 8 * 1024 * 1024 // 8 MB upper bound for massive 2K I-frames
    }

    override suspend fun remux(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        container: VideoDownloadContainer,
        onProgress: ((Float) -> Unit)?
    ): Result<File> = withContext(ioDispatcher) {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Video file missing or empty: ${videoFile.absolutePath}"))
        }
        if (audioFile != null && (!audioFile.exists() || audioFile.length() == 0L)) {
            return@withContext Result.failure(IllegalArgumentException("Audio file missing or empty: ${audioFile.absolutePath}"))
        }

        val tempOutputFile = File(outputFile.parentFile, "${outputFile.name}.tmp_${System.currentTimeMillis()}")
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            currentCoroutineContext().ensureActive()

            // 1. Setup Video Extractor
            videoExtractor = MediaExtractor().apply {
                setDataSource(videoFile.absolutePath)
            }
            val videoTrackIdx = findTrack(videoExtractor, isVideo = true)
                ?: return@withContext Result.failure(IllegalStateException("No video track found in ${videoFile.name}"))
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIdx)

            // 2. Setup Audio Extractor
            val (audioExt, audioTrackIdx, audioFormat) = if (audioFile != null) {
                val ext = MediaExtractor().apply {
                    setDataSource(audioFile.absolutePath)
                }
                audioExtractor = ext
                val idx = findTrack(ext, isVideo = false)
                    ?: return@withContext Result.failure(IllegalStateException("No audio track found in ${audioFile.name}"))
                Triple(ext, idx, ext.getTrackFormat(idx))
            } else {
                // Progressive stream: check if videoExtractor also has an audio track
                val idx = findTrack(videoExtractor, isVideo = false)
                if (idx != null) {
                    Triple(videoExtractor, idx, videoExtractor.getTrackFormat(idx))
                } else {
                    Triple(null, -1, null)
                }
            }

            // 3. Validate extracted track codecs against target container format
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (container == VideoDownloadContainer.WEBM && !videoMime.contains("vp9", ignoreCase = true) && !videoMime.contains("vp8", ignoreCase = true)) {
                return@withContext Result.failure(IllegalArgumentException("Video format $videoMime is incompatible with WebM container"))
            }
            if (container == VideoDownloadContainer.MP4 && videoMime.contains("vp9", ignoreCase = true)) {
                return@withContext Result.failure(IllegalArgumentException("VP9 video is incompatible with MP4 container"))
            }

            if (audioFormat != null) {
                val audioMime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
                if (container == VideoDownloadContainer.WEBM && (audioMime.contains("mp4a", ignoreCase = true) || audioMime.contains("aac", ignoreCase = true))) {
                    return@withContext Result.failure(IllegalArgumentException("AAC audio ($audioMime) cannot be muxed into WebM container"))
                }
                if (container == VideoDownloadContainer.MP4 && audioMime.contains("opus", ignoreCase = true)) {
                    return@withContext Result.failure(IllegalArgumentException("Opus audio ($audioMime) cannot be muxed into MP4 container"))
                }
            }

            // 4. Configure MediaMuxer format
            val muxerOutputFormat = when (container) {
                VideoDownloadContainer.WEBM -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                VideoDownloadContainer.MP4 -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(tempOutputFile.absolutePath, muxerOutputFormat)

            // 5. Register all tracks BEFORE starting muxer
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = if (audioFormat != null) {
                muxer.addTrack(audioFormat)
            } else {
                -1
            }

            muxer.start()
            muxerStarted = true

            // 5. Select tracks in extractors
            videoExtractor.selectTrack(videoTrackIdx)
            if (audioExt != null && audioTrackIdx >= 0 && audioExt !== videoExtractor) {
                audioExt.selectTrack(audioTrackIdx)
            } else if (audioTrackIdx >= 0 && audioExt === videoExtractor) {
                videoExtractor.selectTrack(audioTrackIdx)
            }

            // 6. Estimate total duration for progress
            val videoDurUs = getFormatDurationUs(videoFormat)
            val audioDurUs = if (audioFormat != null) getFormatDurationUs(audioFormat) else 0L
            val totalDurationUs = maxOf(videoDurUs, audioDurUs).takeIf { it > 0L } ?: 1L

            // 7. Bounded Reusable Buffer
            var buffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY)
            val bufferInfo = MediaCodec.BufferInfo()

            var lastVideoPts = 0L
            var lastAudioPts = 0L
            var isVideoEof = false
            var isAudioEof = audioExt == null || audioTrackIdx < 0

            var lastProgressEmit = 0L

            // 8. Interleaved Sample Pump
            while (!isVideoEof || !isAudioEof) {
                currentCoroutineContext().ensureActive()

                val vPts = if (!isVideoEof) videoExtractor.sampleTime else -1L
                val aPts = if (!isAudioEof && audioExt != null) audioExt.sampleTime else -1L

                if (vPts == -1L && !isVideoEof) isVideoEof = true
                if (aPts == -1L && !isAudioEof) isAudioEof = true

                if (isVideoEof && isAudioEof) break

                val writeVideo = when {
                    isVideoEof -> false
                    isAudioEof -> true
                    vPts <= aPts -> true
                    else -> false
                }

                if (writeVideo) {
                    val rawPts = videoExtractor.sampleTime
                    if (rawPts < 0L) {
                        isVideoEof = true
                    } else {
                        val sampleTrack = videoExtractor.sampleTrackIndex
                        // Ensure we are reading from the video track if shared extractor
                        if (sampleTrack == videoTrackIdx) {
                            val sampleSize = readSampleWithGrowth(videoExtractor, buffer) { newBuf -> buffer = newBuf }
                            if (sampleSize > 0) {
                                val writePts = maxOf(lastVideoPts, rawPts.coerceAtLeast(0L))
                                lastVideoPts = writePts
                                bufferInfo.offset = 0
                                bufferInfo.size = sampleSize
                                bufferInfo.presentationTimeUs = writePts
                                bufferInfo.flags = mapSampleFlags(videoExtractor.sampleFlags)
                                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                            }
                        }
                        videoExtractor.advance()
                    }
                } else {
                    val rawPts = audioExt!!.sampleTime
                    if (rawPts < 0L) {
                        isAudioEof = true
                    } else {
                        val sampleTrack = audioExt.sampleTrackIndex
                        if (sampleTrack == audioTrackIdx) {
                            val sampleSize = readSampleWithGrowth(audioExt, buffer) { newBuf -> buffer = newBuf }
                            if (sampleSize > 0) {
                                val writePts = maxOf(lastAudioPts, rawPts.coerceAtLeast(0L))
                                lastAudioPts = writePts
                                bufferInfo.offset = 0
                                bufferInfo.size = sampleSize
                                bufferInfo.presentationTimeUs = writePts
                                bufferInfo.flags = mapSampleFlags(audioExt.sampleFlags)
                                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                            }
                        }
                        audioExt.advance()
                    }
                }

                // Throttle progress emissions
                val currentPts = maxOf(lastVideoPts, lastAudioPts)
                val now = System.currentTimeMillis()
                if (now - lastProgressEmit > 250) {
                    lastProgressEmit = now
                    val progressFraction = (currentPts.toFloat() / totalDurationUs.toFloat()).coerceIn(0f, 0.99f)
                    onProgress?.invoke(progressFraction)
                }
            }

            currentCoroutineContext().ensureActive()

            // 9. Finalize Muxer
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            onProgress?.invoke(1.0f)

            // 10. Atomic Rename to final file
            if (outputFile.exists()) {
                outputFile.delete()
            }
            val renamed = tempOutputFile.renameTo(outputFile)
            if (!renamed || !outputFile.exists() || outputFile.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Failed to commit final muxed file: ${outputFile.name}"))
            }

            Log.i(TAG, "Successfully remuxed ${outputFile.name} (${outputFile.length()} bytes)")
            Result.success(outputFile)
        } catch (e: CancellationException) {
            Log.w(TAG, "Remuxing cancelled by coroutine: ${e.message}")
            if (tempOutputFile.exists()) tempOutputFile.delete()
            if (outputFile.exists()) outputFile.delete()
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Remuxing failed: ${t.message}", t)
            if (tempOutputFile.exists()) tempOutputFile.delete()
            Result.failure(t)
        } finally {
            try { videoExtractor?.release() } catch (_: Throwable) {}
            try { audioExtractor?.release() } catch (_: Throwable) {}
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Throwable) {}
        }
    }

    private fun findTrack(extractor: MediaExtractor, isVideo: Boolean): Int? {
        val count = extractor.trackCount
        val prefix = if (isVideo) "video/" else "audio/"
        for (i in 0 until count) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith(prefix, ignoreCase = true)) {
                return i
            }
        }
        return null
    }

    private fun getFormatDurationUs(format: MediaFormat): Long {
        return try {
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
        } catch (_: Throwable) {
            0L
        }
    }

    private fun mapSampleFlags(extractorFlags: Int): Int {
        var flags = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun readSampleWithGrowth(
        extractor: MediaExtractor,
        currentBuffer: ByteBuffer,
        onBufferGrown: (ByteBuffer) -> Unit
    ): Int {
        var buf = currentBuffer
        buf.clear()
        var bytesRead = extractor.readSampleData(buf, 0)
        // If buffer was too small (indicated by bytesRead <= 0 when sampleTime >= 0)
        if (bytesRead < 0 && extractor.sampleTime >= 0L && buf.capacity() < MAX_BUFFER_CAPACITY) {
            val newCapacity = minOf(buf.capacity() * 2, MAX_BUFFER_CAPACITY)
            Log.d(TAG, "Enlarging sample buffer to $newCapacity bytes")
            buf = ByteBuffer.allocateDirect(newCapacity)
            onBufferGrown(buf)
            bytesRead = extractor.readSampleData(buf, 0)
        }
        return bytesRead
    }
}
