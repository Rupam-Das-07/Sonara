package com.example.sonara.data.playlist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Bounded copy + sampled decode for playlist covers.
 * No persistent URI grant, broad storage permissions, or heavy image dependencies required.
 */
class AndroidPlaylistImageStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : PlaylistImageStore {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "playlist_covers")

    private fun ownedFile(nameOrPath: String?): File? {
        if (nameOrPath.isNullOrBlank()) return null
        val fileName = File(nameOrPath).name
        return fileName.takeIf { FILE_NAME.matches(it) }?.let { File(directory, it) }
    }

    override suspend fun resolve(fileNameOrPath: String?): String? = withContext(io) {
        ownedFile(fileNameOrPath)?.takeIf { it.isFile && it.canRead() && it.length() > 0L }?.absolutePath
    }

    override suspend fun delete(fileNameOrPath: String?) = withContext(io) {
        ownedFile(fileNameOrPath)?.delete()
        Unit
    }

    override suspend fun importImage(sourceUri: String): String = withContext(io) {
        val uri = Uri.parse(sourceUri)
        val scheme = uri?.scheme ?: if (sourceUri.startsWith("content://")) "content" else null
        require(scheme == "content") { "Select an image with the system picker" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create playlist image directory")

        val id = UUID.randomUUID().toString()
        val input = File(directory, "cover_$id.input")
        val pending = File(directory, "cover_$id.tmp")
        val output = File(directory, "cover_$id.jpg")

        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var scaled: Bitmap? = null

        try {
            // 1. Bounded stream copy (hard 20MB limit)
            resolver.openInputStream(requireNotNull(uri))?.use { sourceStream ->
                input.outputStream().use { target ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_INPUT_BYTES) throw IOException("Image exceeds 20 MB")
                        target.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("Image is no longer accessible")

            // 2. Validate dimensions (bounds-first decode)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > 200_000_000L) {
                throw IOException("Unsupported image dimensions")
            }

            // 3. Subsampling to prevent excessive memory use
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) sample *= 2
            decoded = BitmapFactory.decodeFile(input.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw IOException("Cannot decode image")

            val bitmap = requireNotNull(decoded)

            // 4. Correct EXIF orientation
            val orientation = runCatching {
                ExifInterface(input.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }

            oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val upright = requireNotNull(oriented)

            // 5. Downscale preserving aspect ratio (max edge 1024px)
            val ratio = minOf(1f, MAX_EDGE.toFloat() / maxOf(upright.width, upright.height))
            scaled = Bitmap.createScaledBitmap(
                upright,
                (upright.width * ratio).roundToInt().coerceAtLeast(1),
                (upright.height * ratio).roundToInt().coerceAtLeast(1),
                true
            )

            // 6. Compress and save atomically
            pending.outputStream().use {
                if (!requireNotNull(scaled).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) {
                    throw IOException("Cannot save image")
                }
            }

            coroutineContext.ensureActive()
            if (!pending.renameTo(output)) throw IOException("Cannot finish image copy")
            output.name
        } finally {
            listOfNotNull(scaled, oriented, decoded).distinct().forEach { it.recycle() }
            input.delete()
            pending.delete()
        }
    }

    companion object {
        const val MAX_EDGE = 1024
        const val MAX_INPUT_BYTES = 20L * 1024 * 1024
        const val JPEG_QUALITY = 85
        private val FILE_NAME = Regex("cover_[0-9a-f-]{36}\\.(jpg|jpeg|png)")
    }
}
